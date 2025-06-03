package com.ase.ism.wcdad.c03;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpPut;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class C03Consumer {

    private static final String EXCHANGE_NAME = "image_processing_exchange";
    private static final String QUEUE_NAME = "image_processing_queue";
    private static final String ROUTING_KEY = "image.job";
    private static final String C05_API_URL_ENV = "C05_API_URL";
    private static final String DEFAULT_C05_API_BASE_URL = "http://c05:3000";
    private static final String C05_JOBS_ENDPOINT = "/api/jobs";
    private static final String C05_INITIATE_CHUNKED_UPLOAD_ENDPOINT = "/api/pictures/initiate-chunked-upload";
    private static final String C05_UPLOAD_CHUNK_ENDPOINT = "/api/pictures/upload-chunk";
    private static final String C05_FINALIZE_CHUNKED_UPLOAD_ENDPOINT = "/api/pictures/finalize-chunked-upload";

    private static final String NATIVE_EXECUTABLE_PATH_ENV = "NATIVE_EXECUTABLE_PATH";
    private static final String DEFAULT_NATIVE_EXECUTABLE_PATH = "/home/mpiuser/app/process_image_mpi";
    private static final String TEMP_DIR = "/tmp/img_processing";
    private static final String HOSTFILE_PATH = TEMP_DIR + "/hostfile";

    private static final String JOB_NOTIFICATION_EXCHANGE_NAME = "job_updates_exchange";
    private static final String JOB_NOTIFICATION_ROUTING_KEY = "job.update";

    private static ObjectMapper objectMapper = new ObjectMapper();
    private static ExecutorService processingExecutor = Executors.newFixedThreadPool(4);
    private static Channel staticRabbitMqChannel;
    private static final Map<String, JobChunkAggregator> activeJobChunks = new ConcurrentHashMap<>();
    private static final int C03_UPLOAD_CHUNK_SIZE = 1024 * 512;

    static class C05JobPayload {
        public String jobId;
        public String fileName;
        public String status;
        public String operation;
        public int keySize;
        public String mode;
        public String originalMetadata;

        public C05JobPayload(String jobId, ImageProcessingJob details, String status, String originalMetadataBase64) {
            this.jobId = jobId;
            this.fileName = details.getFileName();
            this.status = status;
            this.operation = details.getOperation();
            this.keySize = details.getKeySize();
            this.mode = details.getMode();
            this.originalMetadata = originalMetadataBase64;
        }
    }

    static class C05InitialJobPayload {
        @JsonProperty("jobId")
        String jobId;
        @JsonProperty("originalFileName")
        String originalFileName;
        @JsonProperty("operation")
        String operation;
        @JsonProperty("mode")
        String mode;

        public C05InitialJobPayload(String jobId, String originalFileName, String operation, String mode) {
            this.jobId = jobId;
            this.originalFileName = originalFileName;
            this.operation = operation;
            this.mode = mode;
        }
    }

    static class C05JobUpdatePayload {
        public String status;
        public String pictureId;
        @JsonProperty("error_message")
        public String errorMessage;

        public C05JobUpdatePayload(String status, String pictureId, String errorMessage) {
            this.status = status;
            this.pictureId = pictureId;
            this.errorMessage = errorMessage;
        }
    }

    static class InitiateUploadResponse {
        @JsonProperty("message")
        public String message;
        @JsonProperty("jobId")
        public String jobId;

        public InitiateUploadResponse() {
        }
    }

    static class FinalizeUploadResponse {
        public String pictureId;
        public String message;

        public FinalizeUploadResponse() {
        }
    }

    static class ChunkMessage {
        public String jobId;
        public int chunkId;
        public int totalChunks;
        public boolean firstChunk;
        public String metadataJson;
        public String chunkDataB64;

        public ChunkMessage() {
        }
    }

    static class JobChunkAggregator {
        String jobId;
        ImageProcessingJob initialJobDetails;
        byte[] originalMetadataBytes;
        int totalChunks = -1;
        long lastActivityTime;
        boolean metadataInitialized = false;
        private final ObjectMapper objMapper;

        private Path assembledDataFilePath;
        private FileOutputStream dataFileOutputStream;
        private final Map<Integer, byte[]> pendingChunks = new TreeMap<>();
        private int nextChunkIdToWrite = 0;
        private int chunksSuccessfullyWritten = 0;
        private final File tempDirFile;

        public JobChunkAggregator(String jobId, ObjectMapper objectMapperInstance, String tempDirectoryPath)
                throws IOException {
            this.jobId = jobId;
            this.lastActivityTime = System.currentTimeMillis();
            this.objMapper = objectMapperInstance;
            this.tempDirFile = new File(tempDirectoryPath);
            if (!this.tempDirFile.exists() && !this.tempDirFile.mkdirs()) {
                throw new IOException(
                        "Failed to create temporary directory: " + tempDirectoryPath + " for job " + jobId);
            }
        }

        public synchronized void initializeMetadata(ChunkMessage chunk) throws IOException {
            if (!chunk.firstChunk) {
                System.err.println(
                        " [!] Job " + jobId + ": Attempted to initialize metadata with a non-first chunk (chunkId: "
                                + chunk.chunkId + "). Ignoring.");
                return;
            }
            if (this.metadataInitialized) {
                System.err.println(" [!] Job " + jobId
                        + ": Metadata already initialized. Ignoring new metadata from chunkId: " + chunk.chunkId);
                return;
            }

            this.totalChunks = chunk.totalChunks;
            this.initialJobDetails = this.objMapper.readValue(chunk.metadataJson, ImageProcessingJob.class);
            System.out.println(" [dbg] Job " + jobId + ": Parsed originalFileSize from metadata: "
                    + (this.initialJobDetails != null ? this.initialJobDetails.getOriginalFileSize()
                            : "null JobDetails"));
            this.originalMetadataBytes = chunk.metadataJson.getBytes(StandardCharsets.UTF_8);
            this.metadataInitialized = true;
            System.out.println(" [i] Metadata initialized for job " + jobId + ". Total chunks: " + this.totalChunks
                    + ". Original metadata length: "
                    + (this.originalMetadataBytes != null ? this.originalMetadataBytes.length : 0));

            if (this.totalChunks > 0) {
                this.assembledDataFilePath = Paths.get(this.tempDirFile.getAbsolutePath(),
                        this.jobId + "_aggregated.dat");
                Files.deleteIfExists(this.assembledDataFilePath);
                this.dataFileOutputStream = new FileOutputStream(this.assembledDataFilePath.toFile());
                System.out.println(
                        " [i] Job " + jobId + ": Opened temp file for chunk data: " + this.assembledDataFilePath);
            } else if (this.totalChunks == 0) {
                System.out.println(
                        " [i] Job " + jobId + ": Metadata-only job (totalChunks is 0), no data file will be created.");
                this.assembledDataFilePath = null;
            } else {
                throw new IOException("Job " + jobId + ": Invalid totalChunks value in metadata: " + this.totalChunks);
            }
        }

        public synchronized void addChunkData(ChunkMessage chunk) throws IOException {
            if (!metadataInitialized) {
                throw new IllegalStateException("Job " + jobId + ": Cannot add chunk data for chunkId " + chunk.chunkId
                        + ", metadata not initialized.");
            }
            if (totalChunks == 0) {
                System.err.println(" [!] Job " + jobId + ": Received data chunk " + chunk.chunkId
                        + " for a metadata-only job. Ignoring.");
                return;
            }
            if (chunk.chunkId < 0 || chunk.chunkId >= totalChunks) {
                System.err.println(" [!] Job " + jobId + ": Received chunkId " + chunk.chunkId
                        + " out of expected range [0-" + (totalChunks - 1) + "]. Ignoring.");
                return;
            }
            byte[] decodedData = (chunk.chunkDataB64 != null && !chunk.chunkDataB64.isEmpty())
                    ? Base64.getDecoder().decode(chunk.chunkDataB64)
                    : new byte[0];
            pendingChunks.put(chunk.chunkId, decodedData);
            System.out.println(" [i] Job " + jobId + ": Added chunk " + chunk.chunkId + " to pending map. Size: "
                    + decodedData.length + " bytes. Pending map size: " + pendingChunks.size());
            this.lastActivityTime = System.currentTimeMillis();
            writePendingChunksToFile();
        }

        private synchronized void writePendingChunksToFile() throws IOException {
            if (totalChunks == 0)
                return;
            if (dataFileOutputStream == null && totalChunks > 0) {
                throw new IllegalStateException("Job " + jobId
                        + ": Data file stream not initialized for writing chunks, but chunks are expected.");
            }
            while (pendingChunks.containsKey(nextChunkIdToWrite)) {
                byte[] dataToWrite = pendingChunks.remove(nextChunkIdToWrite);
                if (dataFileOutputStream != null) {
                    dataFileOutputStream.write(dataToWrite);
                }
                chunksSuccessfullyWritten++;
                System.out.println(" [i] Job " + jobId + ": Wrote chunk " + nextChunkIdToWrite
                        + " to file. Total written: " + chunksSuccessfullyWritten + "/" + totalChunks);
                nextChunkIdToWrite++;
            }
            if (isComplete()) {
                if (dataFileOutputStream != null) {
                    try {
                        dataFileOutputStream.flush();
                        dataFileOutputStream.close();
                        System.out.println(" [i] Job " + jobId + ": All chunks written (" + chunksSuccessfullyWritten
                                + "/" + totalChunks + ") and data file closed: " + assembledDataFilePath);
                    } catch (IOException e) {
                        System.err.println(
                                " [!] Job " + jobId + ": Error closing data file output stream: " + e.getMessage());
                        throw e;
                    } finally {
                        dataFileOutputStream = null;
                    }
                } else if (totalChunks > 0) {
                    System.err.println(" [!] Job " + jobId
                            + ": Aggregation complete but dataFileOutputStream is null for a data-bearing job.");
                }
            }
        }

        public synchronized boolean isComplete() {
            if (!metadataInitialized || totalChunks < 0)
                return false;
            if (totalChunks == 0)
                return true;
            return chunksSuccessfullyWritten == totalChunks;
        }

        public synchronized Path getAggregatedDataPath() throws IOException {
            if (!isComplete()) {
                throw new IllegalStateException(
                        "Job " + jobId + ": Aggregation is not complete. Cannot get data path.");
            }
            if (dataFileOutputStream != null) {
                System.out.println(" [i] Job " + jobId
                        + ": DataFileOutputStream was still open in getAggregatedDataPath. Closing now.");
                try {
                    dataFileOutputStream.flush();
                    dataFileOutputStream.close();
                } catch (IOException e) {
                    System.err.println(" [!] Job " + jobId
                            + ": Error closing data file output stream in getAggregatedDataPath: " + e.getMessage());
                } finally {
                    dataFileOutputStream = null;
                }
            }
            if (assembledDataFilePath == null && totalChunks > 0) {
                throw new IllegalStateException(
                        "Job " + jobId + ": Assembled data file path is null for a data-bearing job that is complete.");
            }
            return assembledDataFilePath;
        }

        public ImageProcessingJob getInitialJobDetails() {
            return initialJobDetails;
        }

        public byte[] getOriginalMetadataBytes() {
            return originalMetadataBytes;
        }

        public synchronized void cleanupTemporaryFiles() {
            if (this.dataFileOutputStream != null) {
                try {
                    if (this.dataFileOutputStream.getChannel().isOpen()) {
                        this.dataFileOutputStream.close();
                        System.out.println(" [i] Job " + jobId + ": Closed dataFileOutputStream during cleanup.");
                    }
                } catch (IOException e) {
                    System.err.println(" [!] Job " + jobId + ": Error closing dataFileOutputStream during cleanup: "
                            + e.getMessage());
                }
                this.dataFileOutputStream = null;
            }
            if (this.assembledDataFilePath != null) {
                try {
                    if (Files.exists(this.assembledDataFilePath)) {
                        Files.delete(this.assembledDataFilePath);
                        System.out.println(
                                " [i] Job " + jobId + ": Cleaned up temporary aggregated data file during cleanup: "
                                        + this.assembledDataFilePath);
                    } else {
                        System.out.println(" [i] Job " + jobId
                                + ": Temporary aggregated data file already deleted or never created: "
                                + this.assembledDataFilePath);
                    }
                } catch (IOException e) {
                    System.err.println(" [!] Job " + jobId + ": Failed to delete temporary aggregated data file "
                            + this.assembledDataFilePath + " during cleanup: " + e.getMessage());
                }
            }
        }
    }

    private static String getC05ApiBaseUrl() {
        return System.getenv().getOrDefault(C05_API_URL_ENV, DEFAULT_C05_API_BASE_URL);
    }

    public static void main(String[] args) {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(System.getenv().getOrDefault("RABBITMQ_HOST", "rabbitmq"));
        factory.setPort(Integer.parseInt(System.getenv().getOrDefault("RABBITMQ_PORT", "5672")));
        factory.setUsername(System.getenv().getOrDefault("RABBITMQ_USER", "user"));
        factory.setPassword(System.getenv().getOrDefault("RABBITMQ_PASS", "password"));
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(10000);

        try {
            Connection connection = factory.newConnection();
            Channel channel = connection.createChannel();
            staticRabbitMqChannel = connection.createChannel();

            channel.exchangeDeclare(EXCHANGE_NAME, BuiltinExchangeType.TOPIC, true);
            channel.queueDeclare(QUEUE_NAME, true, false, false, null);
            channel.queueBind(QUEUE_NAME, EXCHANGE_NAME, ROUTING_KEY);

            staticRabbitMqChannel.exchangeDeclare(JOB_NOTIFICATION_EXCHANGE_NAME, BuiltinExchangeType.TOPIC, true);

            System.out.println(" [*] Waiting for messages. To exit press CTRL+C");

            try {
                createHostfile();
            } catch (IOException e) {
                System.err.println(" [!] Failed to create MPI hostfile on startup: " + e.getMessage());
                e.printStackTrace();
            }

            File tempDir = new File(TEMP_DIR);
            if (!tempDir.exists()) {
                if (tempDir.mkdirs()) {
                    System.out.println(" [i] Created temporary directory: " + TEMP_DIR);
                } else {
                    System.err.println(" [!] Failed to create temporary directory: " + TEMP_DIR + ". Exiting.");
                    return;
                }
            }

            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                String messageBody = new String(delivery.getBody(), StandardCharsets.UTF_8);
                ChunkMessage chunk = null;
                String jobId = null;

                try {
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> tempParse = objectMapper.readValue(messageBody, Map.class);
                        if (tempParse.containsKey("jobId")) {
                            jobId = (String) tempParse.get("jobId");
                        }
                    } catch (Exception e) {
                        System.err.println(" [!] Pre-parse for jobId failed: " + e.getMessage() + ". Body: "
                                + messageBody.substring(0, Math.min(messageBody.length(), 100)));
                    }

                    chunk = objectMapper.readValue(messageBody, ChunkMessage.class);
                    if (jobId == null)
                        jobId = chunk.jobId;

                    if (jobId == null || jobId.isEmpty()) {
                        System.err.println(" [!] Received chunk with null or empty jobId. Discarding. Body: "
                                + messageBody.substring(0, Math.min(messageBody.length(), 200)));
                        channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);
                        return;
                    }

                    final String finalJobId = jobId;

                    JobChunkAggregator aggregator = activeJobChunks.computeIfAbsent(finalJobId, k -> {
                        try {
                            System.out.println(" [i] Creating new JobChunkAggregator for job: " + k);
                            return new JobChunkAggregator(k, objectMapper, TEMP_DIR);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to create JobChunkAggregator for job " + k, e);
                        }
                    });

                    if (chunk.firstChunk) {
                        aggregator.initializeMetadata(chunk);
                        if (aggregator.getInitialJobDetails() != null && aggregator.metadataInitialized) {
                            ImageProcessingJob jobDetails = aggregator.getInitialJobDetails();
                            System.out.println(" [dbg C03] Deserialized jobDetails: " + jobDetails.toString());
                            System.out.println(" [dbg C03] Deserialized keySize: " + jobDetails.getKeySize());
                            if (jobDetails != null && aggregator.originalMetadataBytes != null) {
                                boolean jobRecordCreated = createInitialJobRecordInC05(finalJobId, jobDetails);

                                if (jobRecordCreated) {
                                    String metadataJsonBase64 = Base64.getEncoder()
                                            .encodeToString(aggregator.originalMetadataBytes);

                                    createJobEntryInC05(finalJobId, jobDetails, aggregator.totalChunks,
                                            metadataJsonBase64);
                                } else {
                                    System.err.println(" [!] Job " + finalJobId +
                                            ": Failed to create initial job record in C05. Aborting picture upload initiation.");
                                }
                            } else {
                                System.err.println(" [!] Job " + finalJobId
                                        + ": Cannot create C05 entry, jobDetails or originalMetadataBytes is null after metadata initialization.");
                                updateJobStatusInC05(finalJobId, "ERROR", null,
                                        "Internal error: Missing job details or metadata for C05 registration.");
                                publishJobNotification(finalJobId, "ERROR", null,
                                        "Internal error: Missing job details or metadata for C05 registration.");
                            }
                        }
                        if (chunk.chunkDataB64 != null && !chunk.chunkDataB64.isEmpty() && aggregator.totalChunks > 0) {
                            System.out.println(" [i] Job " + finalJobId
                                    + ": First chunk also contains data for chunkId " + chunk.chunkId + ". Adding it.");
                            aggregator.addChunkData(chunk);
                        }
                    } else {
                        aggregator.addChunkData(chunk);
                    }

                    if (aggregator.isComplete()) {
                        System.out.println(
                                " [i] Job " + finalJobId
                                        + " data aggregation is complete. Removing from active map and submitting for processing.");
                        activeJobChunks.remove(finalJobId);

                        ImageProcessingJob jobDetails = aggregator.getInitialJobDetails();
                        Path assembledDataPath = aggregator.getAggregatedDataPath();

                        if (jobDetails == null) {
                            System.err.println(" [!] Job " + finalJobId
                                    + ": Aggregation complete but jobDetails is null. Cannot process.");
                            updateJobStatusInC05(finalJobId, "ERROR", null,
                                    "Internal error: Missing job details after aggregation.");
                            publishJobNotification(finalJobId, "ERROR", null,
                                    "Internal error: Missing job details after aggregation.");
                            aggregator.cleanupTemporaryFiles();
                            channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                            return;
                        }

                        if (aggregator.totalChunks > 0) {
                            if (assembledDataPath == null) {
                                System.err.println(" [!] Job " + finalJobId
                                        + ": Aggregation complete for data-bearing job, but assembledDataPath is null. Cannot process.");
                                updateJobStatusInC05(finalJobId, "ERROR", null,
                                        "Internal error: Missing aggregated data file path.");
                                publishJobNotification(finalJobId, "ERROR", null,
                                        "Internal error: Missing aggregated data file path.");
                                aggregator.cleanupTemporaryFiles();
                                channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                                return;
                            }

                            final JobChunkAggregator completedAggregator = aggregator;
                            processingExecutor.submit(() -> {
                                try {
                                    processMessage(assembledDataPath, finalJobId, jobDetails);
                                } catch (Exception ex) {
                                    System.err.println(" [!] Exception in processing thread for job " + finalJobId
                                            + ": " + ex.getMessage());
                                    ex.printStackTrace();
                                    handleProcessingError(finalJobId, ex, completedAggregator);
                                    try {
                                        if (Files.exists(assembledDataPath)) {
                                            Files.delete(assembledDataPath);
                                            System.out.println(
                                                    " [i] Cleaned up temporary aggregated file in executor catch: "
                                                            + assembledDataPath + " for job " + finalJobId);
                                        }
                                    } catch (IOException ioex) {
                                        System.err.println(" [!] Failed to delete temporary aggregated file "
                                                + assembledDataPath + " in executor catch for job " + finalJobId + ": "
                                                + ioex.getMessage());
                                    }
                                }
                            });
                        } else if (aggregator.totalChunks == 0) {
                            System.out.println(" [i] Job " + finalJobId
                                    + ": Metadata-only job is complete. No processing via MPI needed.");
                            aggregator.cleanupTemporaryFiles();
                        }
                    }
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);

                } catch (Exception e) {
                    String errorJobId = (jobId != null) ? jobId
                            : ((chunk != null && chunk.jobId != null) ? chunk.jobId : "UNKNOWN_JOB");
                    System.err.println(" [!] Unhandled error in DeliverCallback for job "
                            + errorJobId + ": " + e.getMessage());
                    e.printStackTrace();
                    channel.basicNack(delivery.getEnvelope().getDeliveryTag(), false, false);

                    if (!"UNKNOWN_JOB".equals(errorJobId)) {
                        updateJobStatusInC05(errorJobId, "ERROR", null,
                                "Internal error in C03 chunk handling: " + e.getMessage());
                        publishJobNotification(errorJobId, "ERROR", null,
                                "Internal error in C03 chunk handling: " + e.getMessage());
                        JobChunkAggregator existingAggregator = activeJobChunks.remove(errorJobId);
                        if (existingAggregator != null) {
                            System.out.println(" [i] Cleaning up aggregator for job " + errorJobId
                                    + " due to error in DeliverCallback.");
                            existingAggregator.cleanupTemporaryFiles();
                        }
                    }
                }
            };
            channel.basicConsume(QUEUE_NAME, false, deliverCallback, consumerTag -> {
                System.out.println(" [i] Consumer " + consumerTag + " cancelled.");
            });

        } catch (IOException | TimeoutException e) {
            System.err.println(" [!] RabbitMQ connection or channel setup failed: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void handleProcessingError(String jobId, Exception e, JobChunkAggregator aggregatorForCleanup) {
        System.err.println(" [!] Error processing job " + jobId + ": " + e.getMessage());
        e.printStackTrace();
        String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown processing error";
        updateJobStatusInC05(jobId, "ERROR", null, "Processing failed: " + errorMessage);
        publishJobNotification(jobId, "ERROR", null, "Processing failed: " + errorMessage);
    }

    private static void publishJobNotification(String jobId, String status, String pictureId, String errorMessage) {
        if (staticRabbitMqChannel == null || !staticRabbitMqChannel.isOpen()) {
            System.err.println(" [!] Cannot publish job notification, RabbitMQ channel is not available.");
            return;
        }
        try {
            Map<String, Object> notification = new HashMap<>();
            notification.put("jobId", jobId);
            notification.put("status", status);
            if (pictureId != null) {
                notification.put("pictureId", pictureId);
            }
            if (errorMessage != null) {
                notification.put("errorMessage", errorMessage);
            }
            String message = objectMapper.writeValueAsString(notification);
            staticRabbitMqChannel.basicPublish(JOB_NOTIFICATION_EXCHANGE_NAME,
                    JOB_NOTIFICATION_ROUTING_KEY + "." + jobId,
                    new AMQP.BasicProperties.Builder().contentType("application/json").build(),
                    message.getBytes(StandardCharsets.UTF_8));
            System.out.println(" [>] Sent job notification for " + jobId + ": " + status);
        } catch (Exception e) {
            System.err.println(" [!] Failed to publish job notification for " + jobId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static class C05InitiateUploadRequest {
        @JsonProperty("jobId")
        String jobId;
        @JsonProperty("originalFileName")
        String originalFileName;
        @JsonProperty("operationType")
        String operationType;
        @JsonProperty("mimeType")
        String mimeType;
        @JsonProperty("totalChunks")
        int totalChunks;
        @JsonProperty("fileSize")
        long fileSize;
        @JsonProperty("metadataJsonBase64")
        String metadataJsonBase64;

        public C05InitiateUploadRequest(String jobId, String originalFileName, String operationType, String mimeType,
                int totalChunks, long fileSize,
                String metadataJsonBase64) {
            this.jobId = jobId;
            this.originalFileName = originalFileName;
            this.operationType = operationType;
            this.mimeType = mimeType;
            this.totalChunks = totalChunks;
            this.fileSize = fileSize;
            this.metadataJsonBase64 = metadataJsonBase64;
        }
    }

    private static boolean createInitialJobRecordInC05(String jobId, ImageProcessingJob jobDetails) {
        System.out.println(" [i] Job " + jobId + ": Attempting to create initial job record in C05. File: "
                + jobDetails.getFileName() + ", Operation: " + jobDetails.getOperation() + ", Mode: "
                + jobDetails.getMode());

        String url = getC05ApiBaseUrl() + C05_JOBS_ENDPOINT;

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost request = new HttpPost(url);

            String operationUpper = jobDetails.getOperation() != null ? jobDetails.getOperation().toUpperCase()
                    : "UNKNOWN";
            String modeUpper = jobDetails.getMode() != null ? jobDetails.getMode().toUpperCase() : "UNKNOWN";

            C05InitialJobPayload payload = new C05InitialJobPayload(
                    jobId,
                    jobDetails.getFileName(),
                    operationUpper,
                    modeUpper);
            String jsonPayload = objectMapper.writeValueAsString(payload);

            request.setEntity(new StringEntity(jsonPayload, ContentType.APPLICATION_JSON));
            request.setHeader(HttpHeaders.ACCEPT, "application/json");
            request.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");

            System.out.println(" [>] Creating initial job record in C05 for " + jobId + " at " + url + " with payload: "
                    + jsonPayload);

            HttpClientResponseHandler<Boolean> responseHandler = response -> {
                int status = response.getCode();
                HttpEntity entity = response.getEntity();
                String responseBody = entity != null ? EntityUtils.toString(entity) : null;
                if (status == 201) {
                    System.out.println(
                            " [i] Job " + jobId + ": Successfully created initial job record in C05. Response: "
                                    + responseBody);
                    return true;
                } else {
                    System.err.println(
                            " [!] Job " + jobId + ": Failed to create initial job record in C05. Status: " + status
                                    + ". Response: " + responseBody);
                    updateJobStatusInC05(jobId, "ERROR", null,
                            "Failed to create initial C05 job record (HTTP " + status + "): " + responseBody);
                    publishJobNotification(jobId, "ERROR", null,
                            "Failed to create initial C05 job record (HTTP " + status + ")");
                    return false;
                }
            };
            return httpClient.execute(request, responseHandler);
        } catch (Exception e) {
            System.err.println(
                    " [!] Job " + jobId + ": Exception while creating initial job record in C05: " + e.getMessage());
            e.printStackTrace();
            updateJobStatusInC05(jobId, "ERROR", null, "Exception during C05 initial job creation: " + e.getMessage());
            publishJobNotification(jobId, "ERROR", null,
                    "Exception during C05 initial job creation: " + e.getMessage());
            return false;
        }
    }

    private static void initiatePictureUploadInC05(String jobId, ImageProcessingJob jobDetails,
            int totalAggregatorChunks, String metadataJsonBase64) {
        System.out.println(" [i] Job " + jobId + ": Attempting to initiate picture upload in C05. File: "
                + jobDetails.getFileName()
                + ", Chunks: " + totalAggregatorChunks + ", Operation: " + jobDetails.getOperation() + ", FileSize: "
                + jobDetails.getOriginalFileSize());
        System.out.println(" [dbg] Job " + jobId + ": Preparing C05InitiateUploadRequest with originalFileSize: "
                + jobDetails.getOriginalFileSize());

        String mimeType = "application/octet-stream";
        if (jobDetails.getFileName() != null && jobDetails.getFileName().toLowerCase().endsWith(".png")) {
            mimeType = "image/png";
        } else if (jobDetails.getFileName() != null && (jobDetails.getFileName().toLowerCase().endsWith(".jpg")
                || jobDetails.getFileName().toLowerCase().endsWith(".jpeg"))) {
            mimeType = "image/jpeg";
        }

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost request = new HttpPost(getC05ApiBaseUrl() + C05_INITIATE_CHUNKED_UPLOAD_ENDPOINT);

            C05InitiateUploadRequest payload = new C05InitiateUploadRequest(
                    jobId,
                    jobDetails.getFileName(),
                    jobDetails.getOperation(),
                    mimeType,
                    totalAggregatorChunks,
                    jobDetails.getOriginalFileSize(),
                    metadataJsonBase64);
            String jsonPayload = objectMapper.writeValueAsString(payload);

            request.setEntity(new StringEntity(jsonPayload, ContentType.APPLICATION_JSON));
            request.setHeader(HttpHeaders.ACCEPT, "application/json");
            request.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");

            System.out.println(" [>] Initiating picture upload in C05 for " + jobId + " at " + request.getUri()
                    + " with payload: " + jsonPayload);

            HttpClientResponseHandler<String> responseHandler = response -> {
                int status = response.getCode();
                HttpEntity entity = response.getEntity();
                String responseBody = entity != null ? EntityUtils.toString(entity) : null;
                if (status >= 200 && status < 300) {
                    System.out.println(
                            " [i] Job " + jobId + ": Successfully initiated picture upload in C05. Response: "
                                    + responseBody);
                    return responseBody;
                } else {
                    System.err.println(
                            " [!] Job " + jobId + ": Failed to initiate picture upload in C05. Status: " + status
                                    + ". Response: " + responseBody);
                    updateJobStatusInC05(jobId, "ERROR", null,
                            "Failed to initiate C05 picture upload (HTTP " + status + "): " + responseBody);
                    publishJobNotification(jobId, "ERROR", null,
                            "Failed to initiate C05 picture upload (HTTP " + status + ")");
                    return null;
                }
            };

            String c05Response = httpClient.execute(request, responseHandler);
            if (c05Response == null) {
                System.err.println(" [!] Job " + jobId
                        + ": Null response from C05 picture upload initiation, indicating failure.");
            }

        } catch (Exception e) {
            System.err.println(
                    " [!] Job " + jobId + ": Exception while initiating picture upload in C05: " + e.getMessage());
            e.printStackTrace();
            updateJobStatusInC05(jobId, "ERROR", null,
                    "Exception during C05 picture upload initiation: " + e.getMessage());
            publishJobNotification(jobId, "ERROR", null,
                    "Exception during C05 picture upload initiation: " + e.getMessage());
        }
    }

    private static void createJobEntryInC05(String jobId, ImageProcessingJob jobDetails, int totalAggregatorChunks,
            String metadataJsonBase64) {
        initiatePictureUploadInC05(jobId, jobDetails, totalAggregatorChunks, metadataJsonBase64);
    }

    private static void updateJobStatusInC05(String jobId, String status, String pictureId, String errorMessage) {
        String url = getC05ApiBaseUrl() + C05_JOBS_ENDPOINT + "/" + jobId;
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPut httpPut = new HttpPut(url);
            C05JobUpdatePayload payload = new C05JobUpdatePayload(status, pictureId, errorMessage);
            String jsonPayload = objectMapper.writeValueAsString(payload);
            StringEntity entity = new StringEntity(jsonPayload, ContentType.APPLICATION_JSON);
            httpPut.setEntity(entity);

            System.out.println(" [>] Updating job status in C05 for " + jobId + " to " + status + " at " + url
                    + " with payload: " + jsonPayload);
            HttpClientResponseHandler<String> responseHandler = new HttpClientResponseHandler<String>() {
                @Override
                public String handleResponse(final ClassicHttpResponse response) throws HttpException, IOException {
                    int statusCode = response.getCode();
                    HttpEntity responseEntity = response.getEntity();
                    String responseBody = responseEntity != null ? EntityUtils.toString(responseEntity) : null;
                    if (statusCode >= 200 && statusCode < 300) {
                        System.out.println(" [ok] Successfully updated job status in C05 for " + jobId + ". Response: "
                                + responseBody);
                        return responseBody;
                    } else {
                        System.err.println(" [!] Failed to update job status in C05 for " + jobId + ". Status: "
                                + statusCode + ". Response: " + responseBody);
                        return null;
                    }
                }
            };
            httpClient.execute(httpPut, responseHandler);

        } catch (Exception e) {
            System.err.println(" [!] Error updating job status in C05 for " + jobId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void createHostfile() throws IOException {
        int slotsC03 = Integer.parseInt(System.getenv().getOrDefault("MPI_SLOTS_C03", "2"));
        int slotsC04 = Integer.parseInt(System.getenv().getOrDefault("MPI_SLOTS_C04", "2"));
        String c03ServiceName = System.getenv().getOrDefault("C03_MPI_HOSTNAME", "c03");
        String c04ServiceName = System.getenv().getOrDefault("C04_MPI_HOSTNAME", "c04");

        File tempDirFile = new File(TEMP_DIR);
        if (!tempDirFile.exists()) {
            if (!tempDirFile.mkdirs()) {
                String errorMsg = " [!] Failed to create temporary directory for hostfile: " + TEMP_DIR;
                System.err.println(errorMsg);
                throw new IOException(errorMsg);
            }
        }

        Path hostfilePath = Paths.get(HOSTFILE_PATH);
        String hostfileContent = String.format("%s slots=%d\n%s slots=%d\n",
                c03ServiceName, slotsC03,
                c04ServiceName, slotsC04);

        try {
            Files.writeString(hostfilePath, hostfileContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            System.out.println(" [i] Created MPI hostfile at " + hostfilePath + " with content:\n" + hostfileContent);
        } catch (IOException e) {
            System.err.println(" [!] Failed to write MPI hostfile " + hostfilePath + ": " + e.getMessage());
            throw e;
        }
    }

    private static boolean testSSHConnectivity() {
        System.out.println(" [i] Testing SSH connectivity to MPI nodes...");

        String[] testHosts = { "localhost", "c04" };
        boolean allConnectionsWork = true;

        for (String host : testHosts) {
            try {
                List<String> sshTestCommand = Arrays.asList(
                        "ssh",
                        "-o", "StrictHostKeyChecking=no",
                        "-o", "UserKnownHostsFile=/home/mpiuser/.ssh/known_hosts",
                        "-o", "PasswordAuthentication=no",
                        "-o", "PreferredAuthentications=publickey",
                        "-o", "IdentityFile=/home/mpiuser/.ssh/id_rsa",
                        "-o", "ConnectTimeout=10",
                        "-o", "BatchMode=yes",
                        "mpiuser@" + host,
                        "echo 'SSH test successful to " + host + "'");

                ProcessBuilder pb = new ProcessBuilder(sshTestCommand);
                pb.redirectErrorStream(true);
                Process process = pb.start();

                boolean finished = process.waitFor(15, TimeUnit.SECONDS);
                int exitCode = finished ? process.exitValue() : -1;

                if (!finished) {
                    process.destroyForcibly();
                    System.err.println(" [!] SSH test to " + host + " timed out after 15 seconds");
                    allConnectionsWork = false;
                } else if (exitCode == 0) {
                    System.out.println(" [✓] SSH connectivity to " + host + " is working");
                } else {
                    System.err.println(" [!] SSH test to " + host + " failed with exit code: " + exitCode);

                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                        String line;
                        System.err.println(" [!] SSH test output for " + host + ":");
                        while ((line = reader.readLine()) != null) {
                            System.err.println("     " + line);
                        }
                    }
                    allConnectionsWork = false;
                }
            } catch (IOException | InterruptedException e) {
                System.err.println(" [!] Exception during SSH test to " + host + ": " + e.getMessage());
                allConnectionsWork = false;
            }
        }

        if (allConnectionsWork) {
            System.out.println(" [✓] All SSH connectivity tests passed");
        } else {
            System.err.println(" [!] Some SSH connectivity tests failed");
        }

        return allConnectionsWork;
    }

    private static void processMessage(Path imageDataFilePath, String jobId, ImageProcessingJob jobDetails)
            throws IOException, InterruptedException {
        System.out.println(" [i] Processing job: " + jobId + " for file: " + jobDetails.getFileName() +
                ", Operation: " + jobDetails.getOperation() +
                ", KeySize: " + jobDetails.getKeySize() +
                ", Mode: " + jobDetails.getMode());
        publishJobNotification(jobId, "RUNNING", null, null);

        String nativeExecutablePath = System.getenv().getOrDefault(NATIVE_EXECUTABLE_PATH_ENV,
                DEFAULT_NATIVE_EXECUTABLE_PATH);
        String inputFilePath = imageDataFilePath.toString();
        Path outputDir = Paths.get(TEMP_DIR, jobId);
        Files.createDirectories(outputDir);
        String outputFileName = "processed_" + jobDetails.getFileName();
        Path outputFilePath = outputDir.resolve(outputFileName);

        String operationType = jobDetails.getOperation().toLowerCase();
        String key = jobDetails.getKey();
        int keySize = jobDetails.getKeySize();
        String mode = jobDetails.getMode();

        createHostfile();

        if (!testSSHConnectivity()) {
            System.err.println(" [!] Job " + jobId + ": SSH connectivity test failed. Aborting MPI execution.");
            updateJobStatusInC05(jobId, "ERROR", null, "SSH connectivity test failed");
            publishJobNotification(jobId, "ERROR", null, "SSH connectivity test failed");
            cleanupTemporaryFiles(jobId, imageDataFilePath, outputFilePath, outputDir);
            return;
        }

        List<String> command = new ArrayList<>();
        command.add("mpiexec");
        command.add("--hostfile");
        command.add(HOSTFILE_PATH);

        int slotsC03 = Integer.parseInt(System.getenv().getOrDefault("MPI_SLOTS_C03", "1"));
        int slotsC04 = Integer.parseInt(System.getenv().getOrDefault("MPI_SLOTS_C04", "1"));
        int totalProcesses = slotsC03 + slotsC04;

        if (totalProcesses <= 0) {
            System.err
                    .println(" [!] Job " + jobId + ": Total MPI processes is " + totalProcesses + ". Defaulting to 1.");
            totalProcesses = 1; // Fallback to at least 1 process
        }
        command.add("-n");
        command.add(String.valueOf(totalProcesses));

        command.add("--mca");
        command.add("orte_rsh_agent");
        command.add(
                "ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/home/mpiuser/.ssh/known_hosts -o PasswordAuthentication=no -o PreferredAuthentications=publickey -o IdentityFile=/home/mpiuser/.ssh/id_rsa -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=3");

        command.add("--mca");
        command.add("orte_keep_fqdn_hostnames");
        command.add("1");

        command.add("--mca");
        command.add("orte_launch_agent");
        command.add("orted");
        command.add("--mca");
        command.add("plm_rsh_no_tree_spawn");
        command.add("1");

        command.add("-x");
        command.add("PROCESSING_KEY");

        command.add(nativeExecutablePath);
        command.add(inputFilePath);
        command.add(outputFilePath.toString());
        command.add(operationType);
        command.add(String.valueOf(keySize));
        command.add(mode);

        if ("CBC".equalsIgnoreCase(mode)) {
            String iv = jobDetails.getIv();
            if (iv != null && !iv.isEmpty()) {
                command.add(iv);
                System.out.println(" [dbg] Job " + jobId + ": Added IV for CBC mode (Hex): " + iv);
            } else {
                System.err.println(" [!] Job " + jobId
                        + ": CBC mode specified, but IV is null or empty in jobDetails. The native application is expected to fail if it requires an IV.");
            }
        }

        command.add("--mca");
        command.add("orte_rsh_agent");
        command.add(
                "ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/home/mpiuser/.ssh/known_hosts -o PasswordAuthentication=no -o PreferredAuthentications=publickey -o IdentityFile=/home/mpiuser/.ssh/id_rsa -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=3");

        command.add("--mca");
        command.add("orte_keep_fqdn_hostnames");
        command.add("1");

        command.add("--mca");
        command.add("orte_launch_agent");
        command.add("orted");
        command.add("--mca");
        command.add("plm_rsh_no_tree_spawn");
        command.add("1");

        command.add("-x");
        command.add("PROCESSING_KEY");

        command.add(nativeExecutablePath);
        command.add(inputFilePath);
        command.add(outputFilePath.toString());
        command.add(operationType);
        command.add(String.valueOf(keySize));
        command.add(mode);

        if ("CBC".equalsIgnoreCase(mode)) {
            String iv = jobDetails.getIv();
            if (iv != null && !iv.isEmpty()) {
                command.add(iv);
                System.out.println(" [dbg] Job " + jobId + ": Added IV for CBC mode (Hex): " + iv);
            } else {
                System.err.println(" [!] Job " + jobId
                        + ": CBC mode specified, but IV is null or empty in jobDetails. The native application is expected to fail if it requires an IV.");
            }
        }

        command.add("--mca");
        command.add("orte_rsh_agent");
        command.add(
                "ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/home/mpiuser/.ssh/known_hosts -o PasswordAuthentication=no -o PreferredAuthentications=publickey -o IdentityFile=/home/mpiuser/.ssh/id_rsa -o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=3");

        command.add("--mca");
        command.add("orte_keep_fqdn_hostnames");
        command.add("1");

        command.add("--mca");
        command.add("orte_launch_agent");
        command.add("orted");
        command.add("--mca");
        command.add("plm_rsh_no_tree_spawn");
        command.add("1");

        command.add("-x");
        command.add("PROCESSING_KEY");

        command.add(nativeExecutablePath);
        command.add(inputFilePath);
        command.add(outputFilePath.toString());
        command.add(operationType);
        command.add(String.valueOf(keySize));
        command.add(mode);

        if ("CBC".equalsIgnoreCase(mode)) {
            String iv = jobDetails.getIv();
            if (iv != null && !iv.isEmpty()) {
                command.add(iv);
                System.out.println(" [dbg] Job " + jobId + ": Added IV for CBC mode (Hex): " + iv);
            } else {
                System.err.println(" [!] Job " + jobId
                        + ": CBC mode specified, but IV is null or empty in jobDetails. The native application is expected to fail if it requires an IV.");
            }
        }

        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.redirectErrorStream(true);

        processBuilder.environment().put("PROCESSING_KEY", key);

        Process process = processBuilder.start();

        StringBuilder processOutputLog = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println(" [MPI Process Output] " + line);
                processOutputLog.append(line).append(System.lineSeparator());
            }
        }

        boolean exited = process.waitFor(5, TimeUnit.MINUTES);
        int exitCode = -1;

        if (exited) {
            exitCode = process.exitValue();
        } else {
            process.destroyForcibly();
            System.err
                    .println(" [!] Job " + jobId + ": MPI process timed out after 5 minutes and was forcibly killed.");
            updateJobStatusInC05(jobId, "ERROR", null, "MPI process timed out. Log: " + processOutputLog.toString());
            publishJobNotification(jobId, "ERROR", null, "MPI process timed out");
            cleanupTemporaryFiles(jobId, imageDataFilePath, outputFilePath, outputDir);
            return;
        }

        System.out.println(" [i] Job " + jobId + ": MPI process exited with code " + exitCode);
        if (exitCode == 0 && Files.exists(outputFilePath) && Files.size(outputFilePath) > 0) {
            System.out.println(" [i] Job " + jobId + ": MPI process successful. Output file: " + outputFilePath
                    + ", Size: " + Files.size(outputFilePath));
            String pictureId = sendProcessedImageToC05(jobId, outputFilePath, jobDetails);
            if (pictureId != null) {
                updateJobStatusInC05(jobId, "DONE", pictureId, null);
                publishJobNotification(jobId, "DONE", pictureId, null);
            } else {
                updateJobStatusInC05(jobId, "ERROR", null,
                        "Failed to upload processed image to C05. MPI Log: " + processOutputLog.toString());
                publishJobNotification(jobId, "ERROR", null, "Failed to upload processed image to C05");
            }
        } else {
            String processOutput = processOutputLog.toString();
            String userFriendlyErrorMsg;

            if (processOutput.contains("bad decrypt") ||
                    processOutput.contains("wrong final block length") ||
                    processOutput.contains("error:0606506D") ||
                    processOutput.contains("digital envelope routines") ||
                    processOutput.contains("bad magic number")) {
                userFriendlyErrorMsg = "Cheie incorectă!";
            } else {
                userFriendlyErrorMsg = "MPI process failed. Exit code: " + exitCode + ". MPI Log: " + processOutput;
                if (!Files.exists(outputFilePath)) {
                    userFriendlyErrorMsg += " Output file missing: " + outputFilePath;
                } else if (Files.exists(outputFilePath) && Files.size(outputFilePath) == 0) {
                    userFriendlyErrorMsg += " Output file is empty: " + outputFilePath;
                }
            }

            System.err.println(" [!] Job " + jobId + ": " + userFriendlyErrorMsg);
            updateJobStatusInC05(jobId, "ERROR", null, userFriendlyErrorMsg);
            publishJobNotification(jobId, "ERROR", null, userFriendlyErrorMsg);
        }

        cleanupTemporaryFiles(jobId, imageDataFilePath, outputFilePath, outputDir);
    }

    private static void cleanupTemporaryFiles(String jobId, Path inputPath, Path outputPath, Path outputDir) {
        System.out.println(" [i] Job " + jobId + ": Cleaning up temporary files: " + inputPath + ", " + outputPath
                + ", " + outputDir);
        try {
            if (inputPath != null && Files.exists(inputPath))
                Files.delete(inputPath);
        } catch (IOException e) {
            System.err.println(
                    " [!] Job " + jobId + ": Warning: Error deleting input file " + inputPath + ": " + e.getMessage());
        }
        try {
            if (outputPath != null && Files.exists(outputPath))
                Files.delete(outputPath);
        } catch (IOException e) {
            System.err.println(" [!] Job " + jobId + ": Warning: Error deleting output file " + outputPath + ": "
                    + e.getMessage());
        }

        try {
            if (outputPath != null) {
                Path metadataPath = Paths.get(outputPath.toString() + ".metadata.json");
                if (Files.exists(metadataPath)) {
                    Files.delete(metadataPath);
                    System.out.println(" [i] Job " + jobId + ": Cleaned up BMP metadata file: " + metadataPath);
                }
            }
        } catch (IOException e) {
            System.err.println(" [!] Job " + jobId + ": Warning: Error deleting BMP metadata file: " + e.getMessage());
        }

        try {
            if (outputDir != null && Files.exists(outputDir)) {
                Files.delete(outputDir);
            }
        } catch (IOException e) {
            System.err.println(" [!] Job " + jobId + ": Warning: Error deleting output directory " + outputDir + ": "
                    + e.getMessage());
        }
    }

    private static String sendProcessedImageToC05(String jobId, Path imagePath, ImageProcessingJob originalJobDetails) {
        String c05UploadIdForProcessedFile = null;
        int totalChunksForFinalize = 0;
        long fileSize = 0;

        try {
            fileSize = Files.size(imagePath);
            String processedOperationType = "PROCESSED_" + originalJobDetails.getOperation().toUpperCase();

            String bmpMetadata = null;
            Path metadataPath = Paths.get(imagePath.toString() + ".metadata.json");
            if (Files.exists(metadataPath)) {
                try {
                    bmpMetadata = new String(Files.readAllBytes(metadataPath), StandardCharsets.UTF_8);
                    System.out.println(" [i] Job " + jobId + ": Found BMP metadata file: " + metadataPath);
                    System.out.println(" [dbg] Job " + jobId + ": BMP metadata content: " + bmpMetadata);
                } catch (IOException e) {
                    System.err.println(" [!] Job " + jobId + ": Failed to read BMP metadata file: " + e.getMessage());
                }
            } else {
                System.out.println(" [i] Job " + jobId + ": No BMP metadata file found at: " + metadataPath);
            }

            totalChunksForFinalize = (int) Math.ceil((double) fileSize / C03_UPLOAD_CHUNK_SIZE);
            if (fileSize == 0)
                totalChunksForFinalize = 0;

            System.out.println(" [i] Job " + jobId + ": Initiating upload of PROCESSED file to C05. Name: "
                    + originalJobDetails.getFileName() +
                    ", Size: " + fileSize + " bytes, Chunks: " + totalChunksForFinalize + ", OperationType: "
                    + processedOperationType);

            try (CloseableHttpClient initHttpClient = HttpClients.createDefault()) {
                HttpPost initRequest = new HttpPost(getC05ApiBaseUrl() + C05_INITIATE_CHUNKED_UPLOAD_ENDPOINT);
                C05InitiateUploadRequest initPayload = new C05InitiateUploadRequest(
                        jobId,
                        originalJobDetails.getFileName(),
                        processedOperationType,
                        "application/octet-stream",
                        totalChunksForFinalize,
                        fileSize,
                        null);
                String jsonInitPayload = objectMapper.writeValueAsString(initPayload);
                initRequest.setEntity(new StringEntity(jsonInitPayload, ContentType.APPLICATION_JSON));
                initRequest.setHeader(HttpHeaders.ACCEPT, "application/json");
                initRequest.setHeader(HttpHeaders.CONTENT_TYPE, "application/json");

                InitiateUploadResponse initResponse = initHttpClient.execute(initRequest, response -> {
                    int status = response.getCode();
                    HttpEntity entity = response.getEntity();
                    String responseBody = entity != null ? EntityUtils.toString(entity) : null;
                    if (status >= 200 && status < 300) {
                        System.out.println(" [i] Job " + jobId
                                + ": Successfully initiated processed file upload to C05. Response: " + responseBody);
                        return objectMapper.readValue(responseBody, InitiateUploadResponse.class);
                    } else {
                        System.err.println(
                                " [!] Job " + jobId + ": Failed to initiate PROCESSED file upload to C05. Status: "
                                        + status + ". Response: " + responseBody);
                        throw new IOException("Failed to initiate processed file upload to C05 (HTTP " + status + "): "
                                + responseBody);
                    }
                });

                if (initResponse == null || initResponse.jobId == null) {
                    throw new IOException(
                            "Failed to get a valid jobId from C05 for processed file initiation. Response: "
                                    + (initResponse != null ? objectMapper.writeValueAsString(initResponse) : "null"));
                }
                c05UploadIdForProcessedFile = initResponse.jobId;
                System.out
                        .println(" [i] Job " + jobId + ": C05 confirmed initiation for processed file. Using uploadId: "
                                + c05UploadIdForProcessedFile);

            }

            if (fileSize == 0 && !"metadata-only".equals(originalJobDetails.getOperation())) {
                System.out.println(
                        " [i] Job " + jobId + ": Processed file is empty. Skipping upload chunks and finalization.");
                return c05UploadIdForProcessedFile;
            }

            String uploadChunkUrl = getC05ApiBaseUrl() + C05_UPLOAD_CHUNK_ENDPOINT;
            byte[] buffer = new byte[C03_UPLOAD_CHUNK_SIZE];
            int chunkNumber = 0;
            try (FileInputStream fis = new FileInputStream(imagePath.toFile());
                    CloseableHttpClient chunkHttpClient = HttpClients.createDefault()) {
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    HttpPost httpPostChunk = new HttpPost(uploadChunkUrl);
                    Map<String, Object> chunkPayloadMap = new HashMap<>();
                    chunkPayloadMap.put("jobId", c05UploadIdForProcessedFile);
                    chunkPayloadMap.put("chunkId", chunkNumber);

                    byte[] actualChunkData;
                    if (bytesRead < C03_UPLOAD_CHUNK_SIZE) {
                        actualChunkData = Arrays.copyOf(buffer, bytesRead);
                    } else {
                        actualChunkData = buffer;
                    }
                    chunkPayloadMap.put("chunkDataB64", Base64.getEncoder().encodeToString(actualChunkData));
                    String jsonChunkPayload = objectMapper.writeValueAsString(chunkPayloadMap);
                    httpPostChunk.setEntity(new StringEntity(jsonChunkPayload, ContentType.APPLICATION_JSON));

                    System.out.println(" [>] Job " + jobId + ": Sending chunk " + chunkNumber + " to C05. Size: "
                            + actualChunkData.length);

                    ClassicHttpResponse chunkResponse = chunkHttpClient.execute(httpPostChunk, response -> response);
                    int chunkStatus = chunkResponse.getCode();
                    EntityUtils.consume(chunkResponse.getEntity());

                    if (chunkStatus < 200 || chunkStatus >= 300) {
                        System.err.println(" [!] Job " + jobId + ": Failed to upload chunk " + chunkNumber
                                + " to C05. Status: " + chunkStatus);
                        throw new IOException(
                                "Failed to upload chunk " + chunkNumber + " to C05, status: " + chunkStatus);
                    }
                    System.out.println(
                            " [ok] Job " + jobId + ": Successfully uploaded chunk " + chunkNumber + " to C05.");
                    chunkNumber++;
                }
            }

            if (chunkNumber != totalChunksForFinalize && fileSize > 0) {
                System.err.println(" [!] Job " + jobId + ": Mismatch in sent chunks (" + chunkNumber
                        + ") vs calculated chunks (" + totalChunksForFinalize + ").");
                throw new IOException("Chunk count mismatch during upload to C05 for job " + jobId);
            }
            String finalizeUrl = getC05ApiBaseUrl() + C05_FINALIZE_CHUNKED_UPLOAD_ENDPOINT;
            try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
                HttpPost httpPostFinalize = new HttpPost(finalizeUrl);
                Map<String, Object> finalizePayload = new HashMap<>();
                finalizePayload.put("jobId", c05UploadIdForProcessedFile);
                finalizePayload.put("fileName", originalJobDetails.getFileName());
                finalizePayload.put("totalChunks", totalChunksForFinalize);

                if (bmpMetadata != null) {
                    try {
                        ObjectMapper metadataMapper = new ObjectMapper();
                        Object bmpMetadataObj = metadataMapper.readValue(bmpMetadata, Object.class);
                        finalizePayload.put("bmpMetadata", bmpMetadataObj);
                        System.out.println(" [i] Job " + jobId + ": Including BMP metadata in finalize request");
                    } catch (Exception e) {
                        System.err.println(
                                " [!] Job " + jobId + ": Failed to parse BMP metadata JSON: " + e.getMessage());
                    }
                }

                String jsonFinalizePayload = objectMapper.writeValueAsString(finalizePayload);
                httpPostFinalize.setEntity(new StringEntity(jsonFinalizePayload, ContentType.APPLICATION_JSON));

                System.out.println(" [>] Finalizing chunked upload to C05 for job " + jobId + " (uploadId: "
                        + c05UploadIdForProcessedFile + ") at " + finalizeUrl);

                HttpClientResponseHandler<String> finalizeResponseHandler = response -> {
                    int statusCode = response.getCode();
                    HttpEntity responseEntity = response.getEntity();
                    String responseBody = responseEntity != null ? EntityUtils.toString(responseEntity) : null;
                    if (statusCode == 200 || statusCode == 201) {
                        FinalizeUploadResponse finalizeResponse = objectMapper.readValue(responseBody,
                                FinalizeUploadResponse.class);
                        System.out.println(" [ok] Successfully finalized C05 upload for job " + jobId + ". Picture ID: "
                                + finalizeResponse.pictureId);
                        return finalizeResponse.pictureId;
                    } else {
                        System.err.println(" [!] Failed to finalize C05 upload for job " + jobId + ". Status: "
                                + statusCode + ". Response: " + responseBody);
                        throw new IOException(
                                "Failed to finalize C05 upload, status: " + statusCode + ", Body: " + responseBody);
                    }
                };
                return httpClient.execute(httpPostFinalize, finalizeResponseHandler);
            }

        } catch (Exception e) {
            System.err.println(" [!] Error sending processed image to C05 for job " + jobId + ": " + e.getMessage());
            e.printStackTrace();
            updateJobStatusInC05(jobId, "ERROR", null, "Failed to send processed image to C05: " + e.getMessage());
            publishJobNotification(jobId, "ERROR", null, "Failed to send processed image to C05: " + e.getMessage());
            return null;
        }
    }
}
