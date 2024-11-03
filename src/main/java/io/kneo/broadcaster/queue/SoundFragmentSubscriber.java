package io.kneo.broadcaster.queue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.pubsub.v1.PubsubMessage;
import io.kneo.broadcaster.config.PubSubConfig;
import io.kneo.broadcaster.model.SoundFragment;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class SoundFragmentSubscriber {
    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentSubscriber.class);

    @Inject
    SoundFragmentQueue soundFragmentQueue;

    private Subscriber subscriber;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    PubSubConfig config;

    public SoundFragmentSubscriber() {
        System.out.println("SoundFragmentSubscriber constructor called");
    }

    @PostConstruct
    public void init() {
        System.out.println("Initializing SoundFragmentSubscriber...");
        LOGGER.info("Initializing SoundFragmentSubscriber with subscription ID: {}", config.subscriptionId());
        try {
            subscriber = Subscriber.newBuilder(config.subscriptionId(), this::receiveMessage).build();
            subscriber.startAsync().awaitRunning();
            LOGGER.info("SoundFragmentSubscriber started successfully.");
        } catch (Exception e) {
            LOGGER.error("Failed to start SoundFragmentSubscriber", e);
        }
    }

    private void receiveMessage(PubsubMessage message, AckReplyConsumer consumer) {
        try {
            LOGGER.info("Received message ID: {}", message.getMessageId());
            String json = message.getData().toStringUtf8();
            JsonNode rootNode = mapper.readTree(json);
            LOGGER.info("rootNode: {}", rootNode);
            SoundFragment soundFragment = convertMessageToSoundFragment(message);
            LOGGER.info("Converted message ID: {} to SoundFragment with source: {}", message.getMessageId(), soundFragment.getSource());

            soundFragmentQueue.addSoundFragment(soundFragment);
            LOGGER.info("Added SoundFragment with URI: {}", soundFragment.getFileUri());

            // Uncomment consumer.ack() if you want to acknowledge the message in production
            // consumer.ack();
        } catch (JsonProcessingException e) {
            LOGGER.error("Failed to convert message ID: {} to SoundFragment", message.getMessageId(), e);
            consumer.nack();
        } catch (Exception e) {
            LOGGER.error("Error processing message ID: {}", message.getMessageId(), e);
            consumer.nack();
        }
    }

    private SoundFragment convertMessageToSoundFragment(PubsubMessage message) throws JsonProcessingException {
        String json = message.getData().toStringUtf8();
        LOGGER.debug("Received message ID: {} as JSON: {}", message.getMessageId(), json);

        JsonNode rootNode = mapper.readTree(json);
        String fileUri = rootNode.get("file_uri").asText();

        LOGGER.info("Using file URI directly from message: {}", fileUri);

        ((ObjectNode) rootNode).put("file_uri", fileUri);  // Ensure it's set directly as URI
        return mapper.treeToValue(rootNode, SoundFragment.class);
    }

    @PreDestroy
    public void shutdown() {
        if (subscriber != null) {
            LOGGER.info("Shutting down SoundFragmentSubscriber.");
            subscriber.stopAsync();
        }
    }
}
