CREATE DATABASE IF NOT EXISTS picturesdb
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE picturesdb;

CREATE TABLE IF NOT EXISTS pictures (
  id         BIGINT PRIMARY KEY AUTO_INCREMENT,
  blob_data  LONGBLOB NOT NULL,
  iv         VARBINARY(16),
  bmp_metadata TEXT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS jobs (
  id            CHAR(36) PRIMARY KEY,
  file_name     VARCHAR(255) NOT NULL,
  op_type       ENUM('ENCRYPT','DECRYPT') NOT NULL,
  aes_mode      ENUM('ECB','CBC') NOT NULL,
  status        ENUM('QUEUED','RUNNING','DONE','ERROR') NOT NULL,
  picture_id    BIGINT,
  error_message TEXT,
  created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  finished_at   TIMESTAMP NULL,
  CONSTRAINT fk_picture FOREIGN KEY (picture_id) REFERENCES pictures(id)
);