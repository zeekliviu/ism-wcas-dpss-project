import { Router } from "express";
import { metrics } from "../mongo.js";

const r = Router();

r.post("/", async (req, res) => {
  try {
    const doc = {
      ...req.body,
      ts: new Date(),
      receivedAt: new Date().toISOString(),
    };
    await metrics.insertOne(doc);
    console.log(
      `Metrics received from ${doc.containerId}: CPU=${doc.cpuUsage}%, RAM=${doc.ramUsage}%`
    );
    res.sendStatus(201);
  } catch (error) {
    console.error("Error storing metrics:", error);
    res.status(500).json({ error: "Failed to store metrics" });
  }
});

r.get("/", async (req, res) => {
  try {
    const limit = parseInt(req.query.limit ?? "100", 10);
    const cursor = metrics.find().sort({ ts: -1 }).limit(limit);
    const results = await cursor.toArray();
    res.json(results);
  } catch (error) {
    console.error("Error retrieving metrics:", error);
    res.status(500).json({ error: "Failed to retrieve metrics" });
  }
});

r.get("/latest", async (req, res) => {
  try {
    const containers = ["C01", "C02", "C03", "C04", "C05"];
    const latestMetrics = {};

    for (const containerId of containers) {
      const latestMetric = await metrics.findOne(
        { containerId },
        { sort: { ts: -1 } }
      );
      if (latestMetric) {
        latestMetrics[containerId] = latestMetric;
      }
    }

    res.json(latestMetrics);
  } catch (error) {
    console.error("Error retrieving latest metrics:", error);
    res.status(500).json({ error: "Failed to retrieve latest metrics" });
  }
});

r.get("/dashboard", async (req, res) => {
  try {
    const containers = ["C01", "C02", "C03", "C04", "C05"];
    const dashboard = {
      containers: {},
      summary: {
        totalContainers: containers.length,
        avgCpuUsage: 0,
        avgRamUsage: 0,
        lastUpdated: new Date().toISOString(),
      },
    };

    let totalCpu = 0;
    let totalRam = 0;
    let activeContainers = 0;

    for (const containerId of containers) {
      const latestMetric = await metrics.findOne(
        { containerId },
        { sort: { ts: -1 } }
      );

      if (latestMetric) {
        dashboard.containers[containerId] = {
          containerId: latestMetric.containerId,
          hostname: latestMetric.hostname,
          osName: latestMetric.osName,
          cpuUsage: latestMetric.cpuUsage,
          ramUsage: latestMetric.ramUsage,
          timestamp: latestMetric.timestamp,
          lastSeen: latestMetric.ts,
          status: isRecentMetric(latestMetric.ts) ? "online" : "offline",
        };

        if (isRecentMetric(latestMetric.ts)) {
          totalCpu += latestMetric.cpuUsage;
          totalRam += latestMetric.ramUsage;
          activeContainers++;
        }
      } else {
        dashboard.containers[containerId] = {
          containerId,
          status: "no-data",
        };
      }
    }

    if (activeContainers > 0) {
      dashboard.summary.avgCpuUsage = Math.round(totalCpu / activeContainers);
      dashboard.summary.avgRamUsage = Math.round(totalRam / activeContainers);
    }
    dashboard.summary.activeContainers = activeContainers;

    res.json(dashboard);
  } catch (error) {
    console.error("Error generating dashboard:", error);
    res.status(500).json({ error: "Failed to generate dashboard" });
  }
});

function isRecentMetric(timestamp) {
  const now = new Date();
  const metricTime = new Date(timestamp);
  const diffMinutes = (now - metricTime) / (1000 * 60);
  return diffMinutes <= 2;
}

export default r;
