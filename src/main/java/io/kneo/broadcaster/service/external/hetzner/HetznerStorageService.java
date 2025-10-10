package io.kneo.broadcaster.service.external.hetzner;

import io.kneo.broadcaster.config.HetznerConfig;
import io.kneo.broadcaster.model.FileMetadata;
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

import java.net.URI;
import java.nio.file.Paths;

@ApplicationScoped
public class HetznerStorageService {
    private static final Logger LOGGER = LoggerFactory.getLogger(HetznerStorageService.class);

    private final HetznerConfig hetznerConfig;
    private S3Client s3Client;

    @Inject
    public HetznerStorageService(HetznerConfig hetznerConfig) {
        this.hetznerConfig = hetznerConfig;
    }

    @PostConstruct
    public void init() {
        String endpointUrl = "https://" + hetznerConfig.getEndpoint();
        LOGGER.info("Initializing Hetzner S3 client with endpoint: {}, bucket: {}", 
                endpointUrl, hetznerConfig.getBucketName());
        this.s3Client = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(hetznerConfig.getAccessKey(), hetznerConfig.getSecretKey())
                ))
                .region(Region.EU_CENTRAL_1)
                .endpointOverride(URI.create(endpointUrl))
                .forcePathStyle(true)
                .build();
    }

    public Uni<FileMetadata> getFileStream(String keyName) {
        return Uni.createFrom().item(() -> {
                    LOGGER.debug("Retrieving file stream for key: {}", keyName);

                    GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                            .bucket(hetznerConfig.getBucketName())
                            .key(keyName)
                            .build();

                    var responseInputStream = s3Client.getObject(getObjectRequest, ResponseTransformer.toInputStream());

                    FileMetadata metadata = new FileMetadata();
                    metadata.setInputStream(responseInputStream);
                    metadata.setMimeType(responseInputStream.response().contentType());
                    metadata.setContentLength(responseInputStream.response().contentLength());
                    metadata.setFileKey(keyName);

                    LOGGER.debug("Stream created for key: {}, size: {} bytes", keyName, responseInputStream.response().contentLength());
                    return metadata;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().invoke(throwable -> {
                    LOGGER.error("Error retrieving file stream: {} from Hetzner bucket: {}", keyName, hetznerConfig.getBucketName(), throwable);
                    LOGGER.error("Full error details:", throwable);
                })
                .onFailure().recoverWithUni(Uni.createFrom()::failure);
    }

    public Uni<Void> uploadFile(String keyName, String fileToUpload, String mimeType) {
        return Uni.createFrom().<Void>item(() -> {
                    LOGGER.info("Uploading file with key: {}", keyName);
                    PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                            .bucket(hetznerConfig.getBucketName())
                            .key(keyName)
                            .contentType(mimeType)
                            .build();
                    s3Client.putObject(putObjectRequest, RequestBody.fromFile(Paths.get(fileToUpload)));
                    LOGGER.info("Successfully uploaded file with key: {}", keyName);
                    return null;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().invoke(throwable -> LOGGER.error("Error uploading file to Hetzner. Key: {}, Bucket: {}", keyName, hetznerConfig.getBucketName(), throwable))
                .onFailure().recoverWithUni(Uni.createFrom()::failure);
    }

    public Uni<Void> deleteFile(String keyName) {
        if (keyName == null || keyName.isBlank()) {
            return Uni.createFrom().voidItem();
        }
        return Uni.createFrom().<Void>item(() -> {
                    DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
                            .bucket(hetznerConfig.getBucketName())
                            .key(keyName)
                            .build();
                    s3Client.deleteObject(deleteObjectRequest);
                    return null;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().invoke(throwable -> LOGGER.error("Error deleting file from Hetzner. Key: {}, Bucket: {}", keyName, hetznerConfig.getBucketName(), throwable))
                .onFailure().recoverWithUni(Uni.createFrom()::failure);
    }

    public void closeS3Client() {
        if (s3Client != null) {
            s3Client.close();
        }
    }
}