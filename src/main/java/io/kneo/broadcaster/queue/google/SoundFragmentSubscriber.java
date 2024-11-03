package io.kneo.broadcaster.queue.google;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.Storage;
import com.google.pubsub.v1.PubsubMessage;
import io.kneo.broadcaster.config.PubSubConfig;
import io.kneo.broadcaster.model.FragmentStatus;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.store.AudioFileStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


@ApplicationScoped  // Changed to @ApplicationScoped because we want a single instance
public class SoundFragmentSubscriber {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentSubscriber.class);

    @Inject
    private AudioFileStore audioFileStore;

    @Inject
    private Storage storage;

    @Inject
    PubSubConfig config;

    private Subscriber subscriber;
    private final ObjectMapper mapper = new ObjectMapper();

    public void init() {
        LOGGER.info("Initializing SoundFragmentSubscriber with subscription ID: {}", config.subscriptionId());
        try {
            if (subscriber == null || !subscriber.isRunning()) {
                subscriber = Subscriber.newBuilder(config.subscriptionId(), this::receiveMessage).build();
                subscriber.startAsync().awaitRunning();
                LOGGER.info("SoundFragmentSubscriber started successfully.");
            } else {
                LOGGER.warn("Subscriber is already running.");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to start SoundFragmentSubscriber", e);
            throw new RuntimeException("Failed to initialize PubSub subscriber", e);
        }
    }

    private void receiveMessage(PubsubMessage message, AckReplyConsumer consumer) {
        try {
            LOGGER.info("Received message: {}", message.getMessageId());
            SoundFragment soundFragment = convertMessageToSoundFragment(message);
            String fileUri = soundFragment.getFileUri();

            Blob blob = downloadFromGcs(fileUri);
            if (blob != null) {
                soundFragment.setFile(blob.getContent());
                soundFragment.setStatus(FragmentStatus.NOT_PROCESSED);

                try {
                    SoundFragment savedFragment = audioFileStore.saveFragment(soundFragment);
                    LOGGER.info("Saved fragment with ID: {}", savedFragment.getId());
                    consumer.ack();
                } catch (Exception e) {
                    LOGGER.error("Failed to save fragment", e);
                    consumer.nack();
                }
            } else {
                LOGGER.error("File not found in GCS: {}", fileUri);
                consumer.nack();
            }
        } catch (Exception e) {
            LOGGER.error("Error processing message: {}", message.getMessageId(), e);
            consumer.nack();
        }
    }

    private SoundFragment convertMessageToSoundFragment(PubsubMessage message) throws JsonProcessingException {
        String json = message.getData().toStringUtf8();
        JsonNode rootNode = mapper.readTree(json);
        return mapper.treeToValue(rootNode, SoundFragment.class);
    }

    private Blob downloadFromGcs(String gcsUri) {
        Pattern pattern = Pattern.compile("gs://([^/]+)/(.+)");
        Matcher matcher = pattern.matcher(gcsUri);

        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid GCS URI format: " + gcsUri);
        }

        String bucketName = matcher.group(1);
        String objectName = matcher.group(2);

        BlobId blobId = BlobId.of(bucketName, objectName);
        Blob blob = storage.get(blobId);

        if (blob == null) {
            LOGGER.error("File not found in GCS: {}", gcsUri);
            return null;
        }

        LOGGER.info("Found file in GCS: {} (size: {} bytes)", gcsUri, blob.getSize());
        return blob;
    }

    @PreDestroy
    public void shutdown() {
        if (subscriber != null) {
            LOGGER.info("Shutting down SoundFragmentSubscriber.");
            subscriber.stopAsync();
        }
    }
}