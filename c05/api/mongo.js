import { MongoClient } from "mongodb";

const uri = process.env.MONGO_URL || "mongodb://localhost:27017";
export const mongo = new MongoClient(uri);
await mongo.connect();
export const metrics = mongo.db("snmp").collection("metrics");
