import express from "express";
import pool from "../db.js";

const router = express.Router();

router.post("/", async (req, res) => {
  try {
    const { jobId, originalFileName, operation, mode } = req.body;

    if (!jobId || !originalFileName || !operation || !mode) {
      return res.status(400).json({
        message:
          "Missing required fields: jobId, originalFileName, operation, mode",
      });
    }

    const opTypeDb = operation;
    const aesModeDb = mode;
    const statusDb = "QUEUED";

    const [result] = await pool.query(
      "INSERT INTO jobs (id, file_name, op_type, aes_mode, status, created_at) VALUES (?, ?, ?, ?, ?, NOW())",
      [jobId, originalFileName, opTypeDb, aesModeDb, statusDb]
    );

    if (result.affectedRows === 1) {
      const [rows] = await pool.query("SELECT * FROM jobs WHERE id = ?", [
        jobId,
      ]);
      res.status(201).json(rows[0]);
    } else {
      throw new Error("Failed to insert job into database.");
    }
  } catch (error) {
    console.error("Error creating job:", error);
    res
      .status(500)
      .json({ message: "Failed to create job", error: error.message });
  }
});

router.get("/", async (req, res) => {
  try {
    const [jobsFromDb] = await pool.query(
      "SELECT * FROM jobs ORDER BY created_at DESC"
    );

    const formattedJobs = jobsFromDb.map((job) => {
      const rawStatus = job.status;

      const downloadLink =
        job.status === "DONE" && job.picture_id
          ? `/api/pictures/${job.picture_id}`
          : null;

      return {
        jobId: job.id,
        createdAt: job.created_at,
        type: job.op_type === "ENCRYPT" ? "CRIPTARE" : "DECRIPTARE",
        originalFileName: job.file_name,
        status: rawStatus,
        downloadLink: downloadLink,
        errorMessage: job.error_message || null,
      };
    });
    res.status(200).json(formattedJobs);
  } catch (error) {
    console.error("Error fetching jobs:", error);
    res
      .status(500)
      .json({ message: "Failed to fetch jobs", error: error.message });
  }
});

router.get("/:jobId", async (req, res) => {
  try {
    const { jobId } = req.params;
    const [rows] = await pool.query("SELECT * FROM jobs WHERE id = ?", [jobId]);
    if (rows.length > 0) {
      res.status(200).json(rows[0]);
    } else {
      res.status(404).json({ message: "Job not found" });
    }
  } catch (error) {
    console.error("Error fetching job by jobId:", error);
    res
      .status(500)
      .json({ message: "Failed to fetch job", error: error.message });
  }
});

router.put("/:jobId", async (req, res) => {
  try {
    const { jobId } = req.params;
    const { status, pictureId, error_message: errorMessage } = req.body;

    console.log(`[C05 PUT /api/jobs/${jobId}] Received body:`, req.body);
    console.log(
      `[C05 PUT /api/jobs/${jobId}] Destructured: status=${status}, pictureId=${pictureId}, errorMessage=${errorMessage}`
    );

    let query = "UPDATE jobs SET finished_at = NOW()";
    const queryParams = [];

    if (status) {
      query += ", status = ?";
      queryParams.push(status);
    }
    if (pictureId) {
      query += ", picture_id = ?";
      queryParams.push(pictureId);
    }
    if (errorMessage) {
      query += ", error_message = ?";
      queryParams.push(errorMessage);
    }

    if (queryParams.length === 0) {
      return res.status(400).json({
        message: "No update fields provided (status, pictureId, errorMessage).",
      });
    }

    query += " WHERE id = ?";
    queryParams.push(jobId);

    const [result] = await pool.query(query, queryParams);

    if (result.affectedRows > 0) {
      const [rows] = await pool.query("SELECT * FROM jobs WHERE id = ?", [
        jobId,
      ]);
      res.status(200).json(rows[0]);
    } else {
      res.status(404).json({ message: "Job not found or no changes made" });
    }
  } catch (error) {
    console.error("Error updating job:", error);
    res
      .status(500)
      .json({ message: "Failed to update job", error: error.message });
  }
});

export default router;
