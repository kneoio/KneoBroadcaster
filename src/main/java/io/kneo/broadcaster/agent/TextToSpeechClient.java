package io.kneo.broadcaster.agent;

import io.smallrye.mutiny.Uni;

public interface TextToSpeechClient {
    Uni<byte[]> textToSpeech(String text, String voiceId, String modelId);
}
