package com.ase.ism.wcdad;

import java.util.concurrent.TimeoutException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.Base64;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import io.javalin.Javalin;
import io.javalin.http.UploadedFile;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;
import io.javalin.websocket.WsContext;

public class HelloWorld {
  private static final String EXCHANGE_NAME_C03_JOBS = "image_processing_exchange";
  private static final String ROUTING_KEY_C03_JOBS = "image.job";

  private static final String JOB_NOTIFICATION_EXCHANGE_NAME = "job_updates_exchange";
  private static final String JOB_NOTIFICATION_QUEUE_NAME = "c01_job_updates_queue";
  private static final String JOB_NOTIFICATION_ROUTING_KEY = "job.update.#";

  private static final int CHUNK_SIZE_BYTES = 1024 * 512;
  private static final ObjectMapper objectMapper = new ObjectMapper();

  private static Connection rabbitConnection;
  private static Channel rabbitPublishChannel;
  private static Channel rabbitConsumeChannel;
  private static final Map<String, WsContext> webSocketSessions = new ConcurrentHashMap<>();

  static class ChunkMessage {
    public String jobId;
    public int chunkId;
    public int totalChunks;
    public boolean firstChunk;
    public String metadataJson;
    public String chunkDataB64;

    public ChunkMessage() {
    }

    public ChunkMessage(String jobId, int chunkId, int totalChunks, boolean firstChunk, String metadataJson,
        String chunkDataB64) {
      this.jobId = jobId;
      this.chunkId = chunkId;
      this.totalChunks = totalChunks;
      this.firstChunk = firstChunk;
      this.metadataJson = metadataJson;
      this.chunkDataB64 = chunkDataB64;
    }
  }

  private static void initRabbitMQ() throws IOException, TimeoutException {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(System.getenv().getOrDefault("RABBITMQ_HOST", "c02"));
    factory.setPort(Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672")));
    factory.setUsername(System.getenv().getOrDefault("RABBITMQ_DEFAULT_USER", "user"));
    factory.setPassword(System.getenv().getOrDefault("RABBITMQ_DEFAULT_PASS", "password"));

    rabbitConnection = factory.newConnection();

    rabbitPublishChannel = rabbitConnection.createChannel();
    rabbitPublishChannel.exchangeDeclare(EXCHANGE_NAME_C03_JOBS, "topic", true);
    System.out
        .println("RabbitMQ channel for publishing image jobs initialized. Exchange: " + EXCHANGE_NAME_C03_JOBS);

    rabbitConsumeChannel = rabbitConnection.createChannel();
    rabbitConsumeChannel.exchangeDeclare(JOB_NOTIFICATION_EXCHANGE_NAME, "topic", true);
    rabbitConsumeChannel.queueDeclare(JOB_NOTIFICATION_QUEUE_NAME, true, false, false, null);
    rabbitConsumeChannel.queueBind(JOB_NOTIFICATION_QUEUE_NAME, JOB_NOTIFICATION_EXCHANGE_NAME,
        JOB_NOTIFICATION_ROUTING_KEY);
    System.out.println("RabbitMQ channel for consuming job notifications initialized. Exchange: "
        + JOB_NOTIFICATION_EXCHANGE_NAME + ", Queue: " + JOB_NOTIFICATION_QUEUE_NAME);

    startJobNotificationConsumer();
  }

  private static void startJobNotificationConsumer() {
    try {
      DeliverCallback deliverCallback = (consumerTag, delivery) -> {
        String message = new String(delivery.getBody(), StandardCharsets.UTF_8);
        System.out.println(" [C01] Received job notification: " + message);
        try {
          Map<String, Object> notification = objectMapper.readValue(message, new TypeReference<Map<String, Object>>() {
          });
          String jobId = (String) notification.get("jobId");
          String status = (String) notification.get("status");
          String pictureId = (String) notification.get("pictureId");
          String errorMessage = (String) notification.get("errorMessage");

          if (jobId != null && status != null) {
            WsContext wsCtx = webSocketSessions.get(jobId);
            if (wsCtx != null && wsCtx.session.isOpen()) {
              Map<String, Object> wsMessageMap = new HashMap<>();
              wsMessageMap.put("jobId", jobId);
              wsMessageMap.put("status", status);
              if (pictureId != null) {
                String downloadLink = "/api/pictures/" + pictureId;

                wsMessageMap.put("downloadLink", downloadLink);
              }
              if (errorMessage != null) {
                wsMessageMap.put("errorMessage", errorMessage);
              }
              wsCtx.send(wsMessageMap);
              System.out.println(" [C01] Sent WebSocket update to client for jobId: " + jobId + ", Status: " + status);
            } else {
              System.out.println(" [C01] No active WebSocket session for jobId: " + jobId + " or session is closed.");
            }
          } else {
            System.err.println(" [C01] Invalid job notification message: missing jobId or status. Message: " + message);
          }
        } catch (Exception e) {
          System.err.println(" [C01] Error processing job notification message: " + e.getMessage());
          e.printStackTrace();
        } finally {
          rabbitConsumeChannel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
        }
      };
      rabbitConsumeChannel.basicConsume(JOB_NOTIFICATION_QUEUE_NAME, false, deliverCallback, consumerTag -> {
      });
      System.out.println(
          " [C01] Job notification consumer started. Waiting for messages on queue: " + JOB_NOTIFICATION_QUEUE_NAME);
    } catch (IOException e) {
      System.err.println(" [C01] Failed to start job notification consumer: " + e.getMessage());
      e.printStackTrace();
    }
  }

  public static void main(String[] args) {
    try {
      initRabbitMQ();
    } catch (IOException | TimeoutException e) {
      System.err.println("Failed to initialize RabbitMQ: " + e.getMessage());
      e.printStackTrace();
    }

    Javalin app = Javalin.create(config -> {
      config.staticFiles.add(staticFiles -> {
        staticFiles.hostedPath = "/";
        staticFiles.directory = "/static";
        staticFiles.location = Location.CLASSPATH;

      });
      config.spaRoot.addFile("/", "/static/index.html", Location.CLASSPATH);

      config.jsonMapper(new JavalinJackson());

    }).start(7000);

    app.ws("/api/testws", ws -> {
      ws.onConnect(ctx -> {
        ctx.session.setIdleTimeout(java.time.Duration.ofMinutes(15)); // Pass Duration object
        System.out.println("[C01 TestWS] Test WebSocket connected! Context hash: " + ctx.hashCode()
            + ". Idle timeout set to 15 mins.");
      });
      ws.onMessage(ctx -> {
        System.out.println("[C01 TestWS] Test WebSocket received message: " + ctx.message());
        ctx.send("Server received: " + ctx.message());
      });
      ws.onClose(ctx -> System.out.println("[C01 TestWS] Test WebSocket disconnected. Status: " + ctx.status()));
      ws.onError(ctx -> System.err.println("[C01 TestWS] Test WebSocket error: " + ctx.error()));
    });

    app.post("/api/newJob", ctx -> {
      try {
        System.out.println("[C01 /api/newJob] Received request to /api/newJob.");
        System.out.println("[C01 /api/newJob] Request Content-Type: " + ctx.contentType());
        System.out.println("[C01 /api/newJob] Request Form Params: " + ctx.formParamMap());

        UploadedFile file = ctx.uploadedFile("file");
        String userProvidedFilename = ctx.formParam("filename");
        String operation = ctx.formParam("operation");
        String mode = ctx.formParam("mode");
        String key = ctx.formParam("key");
        String keySizeParam = ctx.formParam("keySize");
        String iv = ctx.formParam("iv");

        if (file == null) {
          System.err.println(
              "[C01 /api/newJob] Error: ctx.uploadedFile(\"file\") returned null. This usually means the frontend did not send a file part with the name 'file' in the multipart/form-data request, or the Content-Type is not 'multipart/form-data'.");
          ctx.status(400).result("File upload is missing.");
          return;
        }

        String originalFileName = (userProvidedFilename != null && !userProvidedFilename.trim().isEmpty())
            ? userProvidedFilename
            : file.filename();
        long originalFileSize = file.size();

        String jobId = UUID.randomUUID().toString();

        System.out.println("[C01 /api/newJob] Job ID: " + jobId + ", Original Filename: '" + originalFileName +
            "', Uploaded Size: " + originalFileSize + " bytes, Content-Type: " + file.contentType());

        if (originalFileSize == 0) {
          System.err.println("[C01 /api/newJob] Warning: Uploaded file '" + originalFileName + "' is empty (0 bytes).");
        }

        byte[] fileBytes = file.content().readAllBytes();
        int totalChunks = (int) Math.ceil((double) fileBytes.length / CHUNK_SIZE_BYTES);
        if (totalChunks == 0 && fileBytes.length > 0) {
          totalChunks = 1;
        }
        if (fileBytes.length == 0) {
          totalChunks = 1;
        }

        System.out.println("[C01 /api/newJob] Total chunks required: " + totalChunks);

        for (int chunkId = 0; chunkId < totalChunks; chunkId++) {
          boolean isFirstChunk = (chunkId == 0);

          String metadataJsonString = null;
          if (isFirstChunk) {
            Map<String, Object> jobMetadata = new HashMap<>();
            jobMetadata.put("fileName", originalFileName);
            jobMetadata.put("originalFileSize", originalFileSize);
            jobMetadata.put("operation",
                (operation != null && !operation.isEmpty()) ? operation.toUpperCase() : "ENCRYPT");
            jobMetadata.put("mode", (mode != null && !mode.isEmpty()) ? mode.toUpperCase() : "ECB");
            jobMetadata.put("key", (key != null) ? key : "");
            try {
              jobMetadata.put("keySize", keySizeParam != null ? Integer.parseInt(keySizeParam) : 128);
            } catch (NumberFormatException e) {
              System.err
                  .println("[C01 /api/newJob] Invalid keySize parameter: " + keySizeParam + ". Defaulting to 128.");
              jobMetadata.put("keySize", 128);
            }

            if (iv != null && !iv.isEmpty()) {
              jobMetadata.put("iv", iv);
            } else if ("CBC".equalsIgnoreCase(mode)) {
              System.err.println(
                  "[C01 /api/newJob] Warning: CBC mode specified but IV is missing or empty from request for job "
                      + jobId);
            }

            metadataJsonString = objectMapper.writeValueAsString(jobMetadata);
          }

          int chunkStart = chunkId * CHUNK_SIZE_BYTES;
          int chunkEnd = Math.min(chunkStart + CHUNK_SIZE_BYTES, fileBytes.length);
          byte[] chunkData = new byte[chunkEnd - chunkStart];
          if (fileBytes.length > 0) {
            System.arraycopy(fileBytes, chunkStart, chunkData, 0, chunkData.length);
          }

          ChunkMessage chunkMessage = new ChunkMessage(jobId, chunkId, totalChunks, isFirstChunk,
              metadataJsonString,
              Base64.getEncoder().encodeToString(chunkData));
          String chunkMessageJson = objectMapper.writeValueAsString(chunkMessage);
          rabbitPublishChannel.basicPublish(EXCHANGE_NAME_C03_JOBS, ROUTING_KEY_C03_JOBS, null,
              chunkMessageJson.getBytes(StandardCharsets.UTF_8));
          System.out.println(
              "[C01 /api/newJob] Sent chunk " + chunkId + " of " + totalChunks + " to C03 for jobId: " + jobId);
        }

        ctx.status(202)
            .json(Map.of("jobId", jobId, "status", "accepted", "message", "Job is being processed in chunks."));
        System.out.println("[C01 /api/newJob] Response sent to client for jobId: " + jobId);

      } catch (Exception e) {
        System.err.println("[C01 /api/newJob] Error processing new job request: " + e.getMessage());
        e.printStackTrace();
        ctx.status(500).result("Internal server error while processing the job.");
      }
    });

    app.ws("/api/jobstatus/{jobId}", ws -> {
      ws.onConnect(ctx -> {
        ctx.session.setIdleTimeout(java.time.Duration.ofMinutes(15));
        System.out.println(
            "[C01 WS OnConnect] Handler invoked. Context hash: " + ctx.hashCode() + ". Idle timeout set to 15 mins.");
        String jobId = null;
        try {
          jobId = ctx.pathParam("jobId");
          System.out.println("[C01 WS OnConnect] Attempting to connect WebSocket for jobId: " + jobId);

          webSocketSessions.put(jobId, ctx);
          System.out.println("[C01 WS OnConnect] WebSocket successfully connected and session stored for jobId: "
              + jobId + ". Session count: " + webSocketSessions.size());
        } catch (Exception e) {
          String logJobId = (jobId != null) ? jobId : "unavailable_or_null";
          System.err
              .println("[C01 WS OnConnect] Exception during WebSocket onConnect for (attempted) jobId: " + logJobId
                  + ". Error: " + e.getMessage());
          e.printStackTrace();
        }
      });
      ws.onMessage(ctx -> {
        String jobId = "unknown_onMessage";
        try {
          jobId = ctx.pathParam("jobId");
        } catch (Exception e) {
          System.err.println("[C01 WS OnMessage] Error retrieving jobId from path: " + e.getMessage()
              + ". Context hash: " + ctx.hashCode());
        }
        System.out.println("[C01 WS OnMessage] Received WebSocket message from jobId: " + jobId + " (Context hash: "
            + ctx.hashCode() + "): " + ctx.message());
      });
      ws.onClose(ctx -> {
        String jobId = "unknown_onClose";
        try {
          jobId = ctx.pathParam("jobId");
        } catch (Exception e) {
          System.err.println("[C01 WS OnClose] Error retrieving jobId from path: " + e.getMessage() + ". Context hash: "
              + ctx.hashCode());
        }
        WsContext removedSession = webSocketSessions.remove(jobId);
        boolean removed = removedSession != null;
        System.out.println("[C01 WS OnClose] WebSocket disconnected for jobId: " + jobId +
            " (Context hash: " + ctx.hashCode() + "). Status: " + ctx.status() +
            ", Reason: " + (ctx.reason() == null ? "N/A" : ctx.reason()) +
            ". Session was active and removed: " + removed + ". Remaining sessions: " + webSocketSessions.size());
        if (removedSession != null && removedSession != ctx && !jobId.startsWith("unknown_")) {
          System.err.println("[C01 WS OnClose] CRITICAL: Removed session context hash " + removedSession.hashCode()
              + " does not match closing context hash " + ctx.hashCode() + " for Job ID: " + jobId);
        }
        if (jobId.startsWith("unknown_") && removed) {
          System.err.println(
              "[C01 WS OnClose] Warning: A session was removed but its jobId could not be retrieved from path params during onClose. Key used for removal: "
                  + jobId);
        }
      });
      ws.onError(ctx -> {
        String jobId = "unknown_onError";
        try {
          jobId = ctx.pathParam("jobId");
        } catch (Exception e) {
          System.err.println("[C01 WS OnError] Error retrieving jobId from path: " + e.getMessage() + ". Context hash: "
              + ctx.hashCode());
        }
        Throwable error = ctx.error();
        System.err.println("[C01 WS OnError] WebSocket error for jobId: " + jobId +
            " (Context hash: " + ctx.hashCode() + "). Error: "
            + (error != null ? error.getMessage() : "Unknown error"));
        if (error != null) {
          error.printStackTrace();
        }
        WsContext sessionOnError = webSocketSessions.remove(jobId);
        if (sessionOnError != null) {
          System.out.println("[C01 WS OnError] Session removed for jobId: " + jobId
              + " due to error. Remaining sessions: " + webSocketSessions.size());
          if (jobId.startsWith("unknown_")) {
            System.err.println(
                "[C01 WS OnError] Warning: A session was removed due to error, but its jobId could not be retrieved from path params during onError. Key used for removal: "
                    + jobId);
          }
        }
      });
    });

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      try {
        System.out.println("[C01] Shutdown hook triggered. Closing RabbitMQ connections and channels...");
        if (rabbitConsumeChannel != null && rabbitConsumeChannel.isOpen()) {
          rabbitConsumeChannel.close();
          System.out.println("[C01] RabbitMQ consume channel closed.");
        }
        if (rabbitPublishChannel != null && rabbitPublishChannel.isOpen()) {
          rabbitPublishChannel.close();
          System.out.println("[C01] RabbitMQ publish channel closed.");
        }
        if (rabbitConnection != null && rabbitConnection.isOpen()) {
          rabbitConnection.close();
          System.out.println("[C01] RabbitMQ connection closed.");
        }
      } catch (Exception e) {
        System.err.println("[C01] Error during shutdown: " + e.getMessage());
        e.printStackTrace();
      }
    }));

    System.out.println("[C01] HelloWorld application started. Awaiting requests...");
  }
}
