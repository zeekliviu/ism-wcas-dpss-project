import express from "express";
import expressWs from "express-ws";
import cors from "cors";
import jobsRoute from "./routes/jobs.js";
import picturesRoute from "./routes/pictures.js";
import snmpRoute from "./routes/snmp.js";

const app = express();
expressWs(app);

app.use(cors());
app.use(express.json({ limit: "10mb" }));

app.use("/api/jobs", jobsRoute);
app.use("/api/pictures", picturesRoute);
app.use("/api/snmp", snmpRoute);

app.get("/", (req, res) => {
  res.status(200).send("OK");
});

app.set(
  "wss",
  app.ws("/ws", (ws) => {
    ws.on("close", () => {});
  })
);

app.listen(3000, () => console.log("C05 API up on :3000"));
