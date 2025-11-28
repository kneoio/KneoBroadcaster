package io.kneo.broadcaster.model.cnst;

/**
 * Message types for chat domain communication.
 * These are the actual message content types that appear in the nested "data" field.
 * 
 * Note: "message" and "history" are wrapper types at a different semantic level
 * and should remain as string literals, not enum values.
 */
public enum MessageType {
    USER,
    BOT,
    PROCESSING,
    CHUNK,
    ERROR;
}

