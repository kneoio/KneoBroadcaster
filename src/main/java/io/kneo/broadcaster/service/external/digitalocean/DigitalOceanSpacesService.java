package io.kneo.broadcaster.service.external.digitalocean;

import io.kneo.broadcaster.config.BroadcasterConfig;
import io.kneo.broadcaster.config.DOConfig;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Paths;

@ApplicationScoped
public class DigitalOceanSpacesService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DigitalOceanSpacesService.class);

    private final DOConfig doConfig;
    private S3Client s3Client;
    private final String outputDir;

    @Inject
    public DigitalOceanSpacesService(DOConfig doConfig, BroadcasterConfig broadcasterConfig) {
        this.doConfig = doConfig;
        this.outputDir = broadcasterConfig.getPathForExternalServiceUploads() + File.separator + "digital_ocean";
        new File(outputDir).mkdirs();
    }

    @PostConstruct
    public void init() {
        String endpointUrl = "https://" + doConfig.getRegion() + "." + doConfig.getEndpoint();
        this.s3Client = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(doConfig.getAccessKey(), doConfig.getSecretKey())
                ))
                .region(Region.of(doConfig.getRegion()))
                .endpointOverride(URI.create(endpointUrl))
                .build();
    }

    // ===== NEW STREAMING METHOD =====
    public Uni<StreamedFile> getFileStream(String keyName) {
        return Uni.createFrom().item(() -> {
                    LOGGER.debug("Retrieving file stream for key: {}", keyName);

                    GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                            .bucket(doConfig.getBucketName())
                            .key(keyName)
                            .build();

                    // Get object as stream - NO disk download
                    var responseInputStream = s3Client.getObject(getObjectRequest, ResponseTransformer.toInputStream());

                    StreamedFile streamedFile = new StreamedFile();
                    streamedFile.setInputStream(responseInputStream);
                    streamedFile.setContentType(responseInputStream.response().contentType());
                    streamedFile.setContentLength(responseInputStream.response().contentLength());
                    streamedFile.setLastModified(responseInputStream.response().lastModified());
                    streamedFile.setKey(keyName);

                    LOGGER.debug("Stream created for key: {}, size: {} bytes", keyName, responseInputStream.response().contentLength());
                    return streamedFile;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().invoke(throwable -> LOGGER.error("Error retrieving file stream: {} from S3 bucket: {}", keyName, doConfig.getBucketName(), throwable))
                .onFailure().recoverWithUni(Uni.createFrom()::failure);
    }

    public Uni<Void> uploadFile(String keyName, String fileToUpload, String mimeType) {
        return Uni.createFrom().<Void>item(() -> {
                    PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                            .bucket(doConfig.getBucketName())
                            .key(keyName)
                            .contentType(mimeType)
                            .build();
                    s3Client.putObject(putObjectRequest, RequestBody.fromFile(Paths.get(fileToUpload)));
                    return null;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().invoke(throwable -> LOGGER.error("Error uploading file to S3. Key: {}, Bucket: {}", keyName, doConfig.getBucketName(), throwable))
                .onFailure().recoverWithUni(Uni.createFrom()::failure);
    }

    public Uni<Void> deleteFile(String keyName) {
        if (keyName == null || keyName.isBlank()) {
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().<Void>item(() -> {
                    DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                            .bucket(doConfig.getBucketName())
                            .key(keyName)
                            .build();
                    s3Client.deleteObject(deleteObjectRequest);
                    return null;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().invoke(throwable -> LOGGER.error("Error deleting file from S3. Key: {}, Bucket: {}", keyName, doConfig.getBucketName(), throwable))
                .onFailure().recoverWithUni(Uni.createFrom()::failure);
    }

    public void closeS3Client() {
        if (s3Client != null) {
            s3Client.close();
        }
    }

    // ===== STREAMING FILE WRAPPER =====
    public static class StreamedFile {
        private InputStream inputStream;
        private String contentType;
        private Long contentLength;
        private java.time.Instant lastModified;
        private String key;

        // Getters and setters
        public InputStream getInputStream() { return inputStream; }
        public void setInputStream(InputStream inputStream) { this.inputStream = inputStream; }

        public String getContentType() { return contentType; }
        public void setContentType(String contentType) { this.contentType = contentType; }

        public Long getContentLength() { return contentLength; }
        public void setContentLength(Long contentLength) { this.contentLength = contentLength; }

        public java.time.Instant getLastModified() { return lastModified; }
        public void setLastModified(java.time.Instant lastModified) { this.lastModified = lastModified; }

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
    }
}