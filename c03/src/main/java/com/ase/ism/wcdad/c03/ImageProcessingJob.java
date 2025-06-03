package com.ase.ism.wcdad.c03;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ImageProcessingJob {
    private String jobId;
    private String fileName;
    private int keySize;
    private String iv;
    private String algorithm;
    private String key;
    private String mode;
    private String operation;
    private long originalFileSize;
    private transient byte[] originalJsonMetadataBytes;

    @JsonCreator
    public ImageProcessingJob(
            @JsonProperty("jobId") String jobId,
            @JsonProperty("fileName") String fileName,
            @JsonProperty("keySize") int keySize,
            @JsonProperty("iv") String iv,
            @JsonProperty("algorithm") String algorithm,
            @JsonProperty("key") String key,
            @JsonProperty("mode") String mode,
            @JsonProperty("operation") String operation,
            @JsonProperty("originalFileSize") long originalFileSize) {
        this.jobId = jobId;
        this.fileName = fileName;
        this.keySize = keySize;
        this.iv = iv;
        this.algorithm = algorithm;
        this.key = key;
        this.mode = mode;
        this.operation = operation;
        this.originalFileSize = originalFileSize;
    }

    public void setOriginalJsonMetadataBytes(byte[] metadataBytes) {
        this.originalJsonMetadataBytes = metadataBytes;
    }

    public byte[] getOriginalJsonMetadataBytes() {
        return originalJsonMetadataBytes;
    }

    public int getJsonMetadataLength() {
        if (originalJsonMetadataBytes != null) {
            return originalJsonMetadataBytes.length;
        }
        try {
            return new ObjectMapper().writeValueAsBytes(this).length;
        } catch (JsonProcessingException e) {
            return 0;
        }
    }

    public String getJobId() {
        return jobId;
    }

    public String getFileName() {
        return fileName;
    }

    public int getKeySize() {
        return keySize;
    }

    public String getIv() {
        return iv;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getKey() {
        return key;
    }

    public String getMode() {
        return mode;
    }

    public String getOperation() {
        return operation;
    }

    public long getOriginalFileSize() {
        return originalFileSize;
    }

    @Override
    public String toString() {
        return "ImageProcessingJob{" +
                "jobId='" + jobId + "'," +
                "fileName='" + fileName + '\'' +
                ", keySize=" + keySize +
                ", iv='" + iv + '\'' +
                ", algorithm='" + algorithm + '\'' +
                ", key='" + key + '\'' +
                ", mode='" + mode + '\'' +
                ", operation='" + operation + '\'' +
                '}';
    }
}
