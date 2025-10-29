package io.kneo.broadcaster.agent;

import java.util.List;

public class MessagePrompt {
    private final String detected;
    private final String tone;
    private final String sender;
    private final String text;
    private final String aiDjName;
    private final String guestName;
    private final String action;
    private final String voiceA;
    private final String voiceB;

    public MessagePrompt(String detected, String tone, String sender, String text, String aiDjName, String guestName, String action, String voiceA, String voiceB) {
        this.detected = detected;
        this.tone = tone;
        this.sender = sender;
        this.text = text;
        this.aiDjName = aiDjName;
        this.guestName = guestName;
        this.action = action;
        this.voiceA = voiceA;
        this.voiceB = voiceB;
    }

    public List<String> getMemberNames() {
        return List.of("detected", "tone", "sender", "text", "aiDjName", "guestName", "action", "voiceA", "voiceB");
    }

    public String buildPrompt(String prompt) {
        return prompt
            .replace("{{detected}}", detected != null ? detected : "")
            .replace("{{tone}}", tone != null ? tone : "")
            .replace("{{sender}}", sender != null ? sender : "")
            .replace("{{text}}", text != null ? text : "")
            .replace("{{aiDjName}}", aiDjName != null ? aiDjName : "")
            .replace("{{guestName}}", guestName != null ? guestName : "")
            .replace("{{action}}", action != null ? action : "")
            .replace("{{voiceA}}", voiceA != null ? voiceA : "")
            .replace("{{voiceB}}", voiceB != null ? voiceB : "");
    }
}
