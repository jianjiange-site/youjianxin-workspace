package com.dating.im.model;

import com.dating.youjianxin.proto.im.MessageType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Vendor-agnostic chat message — the business-layer model (was proto {@code ImMessage}, now owned by
 * im-service). Carried by {@link com.dating.im.model.event.MessageSentEvent} /
 * {@link com.dating.im.model.event.MessageBeforeSendEvent} on the inbound (callback) path, and built
 * directly on the outbound (send) path.
 *
 * <p>{@code type} reuses proto {@link MessageType}: it stays part of the outbound
 * {@code ImService.SendMessage} contract, so im-service depends on im-proto regardless.
 */
public record ImMessage(String messageId,
                        String fromUserId,
                        String toUserId,
                        String content,
                        MessageType type,
                        String conversationType,
                        String provider,
                        Map<String, String> metadata,
                        long timestamp) {

    public ImMessage {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        content = content == null ? "" : content;
    }

    /** Mirrors the old proto {@code getMetadataOrDefault}. */
    public String metadataOrDefault(String key, String defaultValue) {
        return metadata.getOrDefault(key, defaultValue);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Mutable builder for the multi-field construction sites (adaptor / sendMessage / AI reply). */
    public static final class Builder {
        private String messageId = "";
        private String fromUserId = "";
        private String toUserId = "";
        private String content = "";
        private MessageType type = MessageType.MESSAGE_TYPE_UNKNOWN;
        private String conversationType = "";
        private String provider = "";
        private final Map<String, String> metadata = new LinkedHashMap<>();
        private long timestamp;

        public Builder messageId(String v) { this.messageId = v; return this; }
        public Builder fromUserId(String v) { this.fromUserId = v; return this; }
        public Builder toUserId(String v) { this.toUserId = v; return this; }
        public Builder content(String v) { this.content = v; return this; }
        public Builder type(MessageType v) { this.type = v; return this; }
        public Builder conversationType(String v) { this.conversationType = v; return this; }
        public Builder provider(String v) { this.provider = v; return this; }
        public Builder putMetadata(String k, String v) { this.metadata.put(k, v); return this; }
        public Builder putAllMetadata(Map<String, String> m) { if (m != null) this.metadata.putAll(m); return this; }
        public Builder timestamp(long v) { this.timestamp = v; return this; }

        public ImMessage build() {
            return new ImMessage(messageId, fromUserId, toUserId, content, type,
                    conversationType, provider, metadata, timestamp);
        }
    }
}
