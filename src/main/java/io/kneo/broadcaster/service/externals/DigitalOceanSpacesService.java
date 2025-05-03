package io.kneo.broadcaster.service.externals;

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

    //TODO it needs to mention DO in the folder name
    private static final String DESTINATION_FOLDER = "uploads/";

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
        LOGGER.info("S3 client initialized successfully.");
    }

    public Uni<Path> getFile(String keyName) {
        return Uni.createFrom().item(() -> {
                    Path destinationPath = Paths.get(DESTINATION_FOLDER, keyName);
                    File destinationFile = destinationPath.toFile();
                    if (!destinationFile.getParentFile().exists()) {
                        destinationFile.getParentFile().mkdirs();
                    }
                    if (destinationFile.exists()) {
                        LOGGER.info("File already exists: {}", keyName);
                        return destinationPath;
                    }
                    LOGGER.info("Downloading file from S3: {}", keyName);
                    GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                            .bucket(doConfig.getBucketName())
                            .key(keyName)
                            .build();
                    s3Client.getObject(getObjectRequest, ResponseTransformer.toFile(destinationFile));
                    return destinationPath;
                })
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .onFailure().recoverWithUni(throwable -> {
                    LOGGER.error("Error retrieving file: {}", keyName, throwable);
                    return Uni.createFrom().failure(throwable);
                });
    }

    public void closeS3Client() {
        if (s3Client != null) {
            s3Client.close();
        }
    }

}