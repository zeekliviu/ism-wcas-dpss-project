import { Router } from "express";
import db from "../db.js";
import multer from "multer";
import path from "path";
import fs from "fs";
import fsp from "fs/promises";

const r = Router();

function reconstructBmpFromMetadata(pixelData, metadata) {
  console.log("=== BMP RECONSTRUCTION DEBUG ===");
  console.log("Input pixel data size:", pixelData.length);
  console.log("Metadata:", JSON.stringify(metadata, null, 2));

  const fileHeaderSize = 14;
  const infoHeaderSize = 40;
  const headerTotalSize = fileHeaderSize + infoHeaderSize;

  const bytesPerPixel = metadata.bit_count / 8;
  const width = Math.abs(metadata.width);
  const height = Math.abs(metadata.height);
  const rowSizeWithoutPadding = width * bytesPerPixel;
  const padding = (4 - (rowSizeWithoutPadding % 4)) % 4;
  const rowSizeWithPadding = rowSizeWithoutPadding + padding;
  const pixelDataSize = rowSizeWithPadding * height;
  const totalFileSize = headerTotalSize + pixelDataSize;

  console.log("Calculated dimensions:");
  console.log("- Width:", width, "Height:", height, "BPP:", metadata.bit_count);
  console.log("- Bytes per pixel:", bytesPerPixel);
  console.log("- Row size without padding:", rowSizeWithoutPadding);
  console.log("- Padding per row:", padding);
  console.log("- Row size with padding:", rowSizeWithPadding);
  console.log("- Expected pixel data size (with padding):", pixelDataSize);
  console.log(
    "- Expected pure pixel data size:",
    width * height * bytesPerPixel
  );
  console.log("- Total BMP file size:", totalFileSize);

  const bmpBuffer = Buffer.alloc(totalFileSize);
  let offset = 0;

  bmpBuffer.writeUInt16LE(0x4d42, offset);
  offset += 2;
  bmpBuffer.writeUInt32LE(totalFileSize, offset);
  offset += 4;
  bmpBuffer.writeUInt32LE(0, offset);
  offset += 4;
  bmpBuffer.writeUInt32LE(headerTotalSize, offset);
  offset += 4;

  bmpBuffer.writeUInt32LE(infoHeaderSize, offset);
  offset += 4;
  bmpBuffer.writeInt32LE(metadata.width, offset);
  offset += 4;
  bmpBuffer.writeInt32LE(metadata.height, offset);
  offset += 4;
  bmpBuffer.writeUInt16LE(1, offset);
  offset += 2;
  bmpBuffer.writeUInt16LE(metadata.bit_count, offset);
  offset += 2;
  bmpBuffer.writeUInt32LE(0, offset);
  offset += 4;
  bmpBuffer.writeUInt32LE(pixelDataSize, offset);
  offset += 4;
  bmpBuffer.writeInt32LE(2835, offset);
  offset += 4;
  bmpBuffer.writeInt32LE(2835, offset);
  offset += 4;
  bmpBuffer.writeUInt32LE(0, offset);
  offset += 4;
  bmpBuffer.writeUInt32LE(0, offset);
  offset += 4;

  for (let row = 0; row < height; row++) {
    const sourceStart = row * rowSizeWithoutPadding;
    const sourceEnd = sourceStart + rowSizeWithoutPadding;

    if (sourceEnd <= pixelData.length) {
      pixelData.copy(bmpBuffer, offset, sourceStart, sourceEnd);
    } else {
      const availableBytes = Math.max(0, pixelData.length - sourceStart);
      if (availableBytes > 0) {
        pixelData.copy(
          bmpBuffer,
          offset,
          sourceStart,
          sourceStart + availableBytes
        );
      }
      for (let i = availableBytes; i < rowSizeWithoutPadding; i++) {
        bmpBuffer[offset + i] = 0;
      }
    }
    offset += rowSizeWithoutPadding;
    offset += padding;
  }

  console.log("BMP reconstruction completed:");
  console.log("- Final buffer size:", bmpBuffer.length);
  console.log("- Final offset:", offset);
  console.log("=== END BMP RECONSTRUCTION DEBUG ===");

  return bmpBuffer;
}

const UPLOAD_DIR = "./uploads/";

const SHARED_DIR_C05_NODE = "/opt/app/mysql_shared_uploads";

const SHARED_DIR_MYSQL_SERVER = "/opt/app/mysql_shared_uploads";

const chunkUploadsStore = {};

if (!fs.existsSync(UPLOAD_DIR)) {
  fs.mkdirSync(UPLOAD_DIR, { recursive: true });
}
if (!fs.existsSync(SHARED_DIR_C05_NODE)) {
  fs.mkdirSync(SHARED_DIR_C05_NODE, { recursive: true });
  console.log(
    `Created shared directory for MySQL LOAD_FILE: ${SHARED_DIR_C05_NODE}`
  );
}

const storage = multer.diskStorage({
  destination: function (req, file, cb) {
    cb(null, UPLOAD_DIR);
  },
  filename: function (req, file, cb) {
    cb(
      null,
      file.fieldname + "-" + Date.now() + path.extname(file.originalname)
    );
  },
});
const upload = multer({ storage: storage });

r.post("/initiate-chunked-upload", async (req, res) => {
  const { jobId, originalFileName, operationType, totalChunks, fileSize } =
    req.body;

  if (
    !jobId ||
    !originalFileName ||
    !operationType ||
    totalChunks === undefined ||
    fileSize === undefined
  ) {
    return res.status(400).json({
      error: "Missing required fields for initiating chunked upload.",
    });
  }

  const jobUploadDir = path.join(UPLOAD_DIR, jobId.toString());

  try {
    if (chunkUploadsStore[jobId]) {
      console.log(
        `Re-initiating chunked upload for jobId: ${jobId}. Cleaning up previous attempt.`
      );
      if (fs.existsSync(chunkUploadsStore[jobId].jobUploadDir)) {
        await fsp.rm(chunkUploadsStore[jobId].jobUploadDir, {
          recursive: true,
          force: true,
        });
      }
    }

    await fsp.mkdir(jobUploadDir, { recursive: true });
    console.log(`Created temporary directory for chunks: ${jobUploadDir}`);

    chunkUploadsStore[jobId] = {
      metadata: {
        originalFileName,
        operationType,
        totalChunks: parseInt(totalChunks, 10),
        fileSize: parseInt(fileSize, 10),
      },
      jobUploadDir: jobUploadDir,
      receivedChunks: 0,
      chunkStatus: new Array(parseInt(totalChunks, 10)).fill(false),
      createdAt: Date.now(),
    };

    console.log(
      `Initiated chunked upload for jobId: ${jobId}, totalChunks: ${totalChunks}, uploadDir: ${jobUploadDir}`
    );
    res
      .status(200)
      .json({ message: "Chunked upload initiated successfully.", jobId });
  } catch (error) {
    console.error(`Error initiating chunked upload for jobId ${jobId}:`, error);
    res.status(500).json({
      error: "Failed to initiate chunked upload.",
      details: error.message,
    });
  }
});

r.post("/upload-chunk", async (req, res) => {
  const { jobId, chunkId, chunkDataB64 } = req.body;
  const chunkIndex = parseInt(chunkId, 10);

  if (!jobId || chunkId === undefined || !chunkDataB64) {
    return res
      .status(400)
      .json({ error: "Missing required fields for uploading chunk." });
  }

  const jobData = chunkUploadsStore[jobId];
  if (!jobData) {
    return res.status(404).json({
      error: `Upload not initiated or already finalized for jobId: ${jobId}.`,
    });
  }

  if (chunkIndex < 0 || chunkIndex >= jobData.metadata.totalChunks) {
    return res.status(400).json({
      error: `Invalid chunkId: ${chunkId} for jobId: ${jobId}. Total chunks: ${jobData.metadata.totalChunks}`,
    });
  }

  const chunkFilePath = path.join(
    jobData.jobUploadDir,
    `chunk_${chunkIndex}.tmp`
  );

  try {
    const chunkBuffer = Buffer.from(chunkDataB64, "base64");
    await fsp.writeFile(chunkFilePath, chunkBuffer);

    if (!jobData.chunkStatus[chunkIndex]) {
      jobData.chunkStatus[chunkIndex] = true;
      jobData.receivedChunks++;
    } else {
      console.warn(
        `Chunk ${chunkIndex} for jobId ${jobId} already received and processed. Overwriting file.`
      );
    }

    console.log(
      `Received and saved chunk ${chunkIndex}/${
        jobData.metadata.totalChunks - 1
      } for jobId: ${jobId} to ${chunkFilePath}. Total received: ${
        jobData.receivedChunks
      }`
    );
    res.status(200).json({
      message: `Chunk ${chunkIndex} received and saved successfully for jobId: ${jobId}.`,
    });
  } catch (error) {
    console.error(
      `Error processing/saving chunk ${chunkIndex} for jobId ${jobId}:`,
      error
    );
    res
      .status(500)
      .json({ error: "Failed to process chunk data.", details: error.message });
  }
});

r.post("/finalize-chunked-upload", async (req, res) => {
  const { jobId, bmpMetadata } = req.body;

  if (!jobId) {
    return res
      .status(400)
      .json({ error: "Missing jobId for finalizing chunked upload." });
  }

  const jobData = chunkUploadsStore[jobId];
  if (!jobData) {
    return res.status(404).json({
      error: `Upload not initiated or already finalized/cleared for jobId: ${jobId}.`,
    });
  }

  const { metadata, jobUploadDir, receivedChunks, chunkStatus } = jobData;

  if (receivedChunks !== metadata.totalChunks) {
    const missingChunks = chunkStatus
      .map((status, i) => (status ? -1 : i))
      .filter((i) => i !== -1);
    console.log(
      `Finalization failed for ${jobId}: Expected ${
        metadata.totalChunks
      }, got ${receivedChunks}. Missing chunks: ${missingChunks.join(", ")}`
    );
    return res.status(400).json({
      error: `Not all chunks received for jobId: ${jobId}. Expected ${metadata.totalChunks}, but received ${receivedChunks}. Missing: ${missingChunks.length}`,
      missingChunks: missingChunks,
    });
  }

  for (let i = 0; i < metadata.totalChunks; i++) {
    const chunkFilePath = path.join(jobUploadDir, `chunk_${i}.tmp`);
    if (!fs.existsSync(chunkFilePath)) {
      console.error(
        `Finalization check failed for ${jobId}: Chunk file ${chunkFilePath} is missing.`
      );
      return res.status(400).json({
        error: `Chunk file for chunk ${i} is missing for jobId: ${jobId}.`,
      });
    }
  }

  let finalFileBuffer;
  const chunkBuffers = [];

  const tempSqlFileName = `${jobId}_final_for_mysql.bin`;
  const tempSqlFilePathNode = path.join(SHARED_DIR_C05_NODE, tempSqlFileName);
  const tempSqlFilePathMySql = path.join(
    SHARED_DIR_MYSQL_SERVER,
    tempSqlFileName
  );

  try {
    console.log(
      `Finalizing upload for jobId: ${jobId}. Reading chunks from ${jobUploadDir}`
    );
    if (metadata.totalChunks === 0) {
      finalFileBuffer = Buffer.alloc(0);
    } else {
      for (let i = 0; i < metadata.totalChunks; i++) {
        const chunkFilePath = path.join(jobUploadDir, `chunk_${i}.tmp`);
        chunkBuffers.push(await fsp.readFile(chunkFilePath));
      }
      finalFileBuffer = Buffer.concat(chunkBuffers);
    }

    if (finalFileBuffer.length !== metadata.fileSize) {
      console.warn(
        `Final file size for jobId ${jobId} (${finalFileBuffer.length}) does not match expected size (${metadata.fileSize}). Proceeding anyway.`
      );
    }

    await fsp.writeFile(tempSqlFilePathNode, finalFileBuffer);
    console.log(
      `Temporarily wrote assembled file for jobId ${jobId} to ${tempSqlFilePathNode} for MySQL LOAD_FILE.`
    );

    let insertQuery, insertParams;

    if (bmpMetadata) {
      console.log(`Storing BMP metadata for jobId ${jobId}:`, bmpMetadata);
      insertQuery =
        "INSERT INTO pictures (blob_data, bmp_metadata, created_at) VALUES (LOAD_FILE(?), ?, NOW())";
      insertParams = [tempSqlFilePathMySql, JSON.stringify(bmpMetadata)];
    } else {
      insertQuery =
        "INSERT INTO pictures (blob_data, created_at) VALUES (LOAD_FILE(?), NOW())";
      insertParams = [tempSqlFilePathMySql];
    }

    const [pictureResult] = await db.query(insertQuery, insertParams);
    const pictureId = pictureResult.insertId;

    if (!pictureId) {
      console.error(
        `LOAD_FILE() for ${tempSqlFilePathMySql} (jobId: ${jobId}) did not return a valid pictureId. MySQL warnings might provide more details.`
      );

      throw new Error(
        `Failed to insert picture using LOAD_FILE for jobId: ${jobId}. pictureId is invalid.`
      );
    }

    const [jobUpdateResult] = await db.query(
      "UPDATE jobs SET status = ?, picture_id = ?, finished_at = NOW() WHERE id = ?",
      ["DONE", pictureId, jobId]
    );

    if (jobUpdateResult.affectedRows === 0) {
      console.error(
        `Failed to update job status for jobId: ${jobId}. Job not found or no change made.`
      );
    }

    try {
      await fsp.unlink(tempSqlFilePathNode);
      console.log(
        `Cleaned up temporary MySQL LOAD_FILE file ${tempSqlFilePathNode}`
      );
    } catch (unlinkError) {
      console.error(
        `Error cleaning up temporary MySQL LOAD_FILE file ${tempSqlFilePathNode}:`,
        unlinkError
      );
    }

    try {
      if (fs.existsSync(jobUploadDir)) {
        await fsp.rm(jobUploadDir, { recursive: true, force: true });
      }
      delete chunkUploadsStore[jobId];
      console.log(
        `Cleaned up chunk directory ${jobUploadDir} and store entry for jobId ${jobId}.`
      );
    } catch (cleanupError) {
      console.error(
        `Error during cleanup for jobId ${jobId} (dir: ${jobUploadDir}):`,
        cleanupError
      );
    }

    console.log(
      `File for jobId: ${jobId} (Picture ID: ${pictureId}) reassembled, saved to DB via LOAD_FILE. Job status updated. All temporary files cleared.`
    );

    res.status(200).json({
      message: "File reassembled and saved to database successfully.",
      pictureId: pictureId,
    });
  } catch (error) {
    console.error(`Error finalizing upload for jobId ${jobId}:`, error);

    if (fs.existsSync(tempSqlFilePathNode)) {
      try {
        await fsp.unlink(tempSqlFilePathNode);
        console.log(
          `Cleaned up temporary MySQL LOAD_FILE file ${tempSqlFilePathNode} after error.`
        );
      } catch (unlinkErr) {
        console.error(
          `Error cleaning up ${tempSqlFilePathNode} after main error:`,
          unlinkErr
        );
      }
    }
    if (
      jobData &&
      jobData.jobUploadDir &&
      fs.existsSync(jobData.jobUploadDir)
    ) {
      try {
        await fsp.rm(jobData.jobUploadDir, { recursive: true, force: true });
        console.log(
          `Cleaned up chunk directory ${jobData.jobUploadDir} after error for jobId ${jobId}.`
        );
      } catch (cleanupErr) {
        console.error(
          `Error cleaning up chunk directory ${jobData.jobUploadDir} after main error:`,
          cleanupErr
        );
      }
    }

    res.status(500).json({
      error: "Failed to finalize chunked upload and save to database.",
      details: error.message,
    });
  }
});

r.get("/:id", async (req, res) => {
  const { id } = req.params;

  if (!id) {
    return res.status(400).json({ error: "Missing picture ID." });
  }
  try {
    const [rows] = await db.query(
      "SELECT blob_data, bmp_metadata, created_at FROM pictures WHERE id = ?",
      [id]
    );

    if (rows.length === 0) {
      return res.status(404).json({ error: "Picture not found." });
    }
    const picture = rows[0];

    if (!picture.blob_data) {
      console.error(`Picture data (blob_data) is missing for ID ${id}.`);
      return res
        .status(500)
        .json({ error: "Picture data is missing or corrupt." });
    }

    let responseData = picture.blob_data;

    if (picture.bmp_metadata) {
      try {
        const metadata = JSON.parse(picture.bmp_metadata);
        console.log(
          `Reconstructing BMP file for picture ${id} using metadata:`,
          metadata
        );

        const fullBuffer = picture.blob_data;
        const pixelOffset = metadata.offset_data;
        const pixelData = Buffer.from(fullBuffer).slice(pixelOffset);
        console.log(
          `Extracted pixel data size: ${pixelData.length} (offset:${pixelOffset})`
        );

        responseData = reconstructBmpFromMetadata(pixelData, metadata);
        console.log(
          `BMP reconstruction successful. Final size: ${responseData.length} bytes`
        );
      } catch (metadataError) {
        console.error(
          `Error reconstructing BMP for picture ${id}:`,
          metadataError
        );
        console.log(`Falling back to raw blob_data for picture ${id}`);
      }
    }

    res.set({
      "Content-Type": "image/bmp",
      "Content-Disposition": `attachment; filename="picture_${id}.bmp"`,
    });
    res.send(responseData);
  } catch (error) {
    console.error(`Error fetching picture with id ${id}:`, error);
    res
      .status(500)
      .json({ error: "Failed to fetch picture.", details: error.message });
  }
});

r.get("/", (req, res) => {
  res.status(404).json({
    error: "No picture ID specified. Use /api/pictures/:id to get a picture.",
  });
});

export default r;
