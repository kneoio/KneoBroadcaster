package io.kneo.broadcaster.service;

import io.kneo.broadcaster.config.DOConfig;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

@ApplicationScoped
public class DigitalOceanSpacesService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DigitalOceanSpacesService.class);

    @Inject
    DOConfig doConfig;

    private S3Client s3Client;

    // Hardcoded destination folder
    private static final String DESTINATION_FOLDER = "uploads/";

    public void initS3Client() {
        String endpointUrl = "https://" + doConfig.getRegion() + "." + doConfig.getEndpoint();
        this.s3Client = S3Client.builder()
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(doConfig.getAccessKey(), doConfig.getSecretKey())
                ))
                .region(Region.of(doConfig.getRegion()))
                .endpointOverride(URI.create(endpointUrl))
                .build();
    }

    public Uni<File> getFile(String keyName) {
        return Uni.createFrom().item(() -> {
            if (s3Client == null) {
                initS3Client();
            }

            // Sanitize the key name
           // String sanitizedKeyName = sanitizeFileName(keyName);
            Path destinationPath = Paths.get(DESTINATION_FOLDER, keyName);
            File destinationFile = destinationPath.toFile();


            destinationFile.getParentFile().mkdirs();


            if (destinationFile.exists()) {
                return destinationFile;
            }

            LOGGER.info("request: {}", keyName);
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(doConfig.getBucketName())
                    .key(keyName)
                    .build();

            s3Client.getObject(getObjectRequest, ResponseTransformer.toFile(destinationFile));

            return destinationFile;
        }).runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    public void closeS3Client() {
        if (s3Client != null) {
            s3Client.close();
        }
    }

    private String sanitizeFileName(String fileName) {
        return fileName.replaceAll("[^a-zA-Z0-9.-]", "_");
    }
}