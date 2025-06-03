import mysql from "mysql2/promise";

export default mysql.createPool({
  host: process.env.MYSQL_HOST || "127.0.0.1",
  user: process.env.MYSQL_USER || "nodeapp",
  password: process.env.MYSQL_PASS || "nodepass",
  database: "picturesdb",
  waitForConnections: true,
  connectionLimit: 10,
});
