package io.kneo.broadcaster.model;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ConversationMemory {
    private List<Message> history = new ArrayList<>();

    public static class Message {
        private String type;
        private String content;


    }


}