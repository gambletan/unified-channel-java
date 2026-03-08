package io.github.gambletan.unifiedchannel;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * An inbound message normalized across all channels.
 */
public final class UnifiedMessage {

    private final String channelId;
    private final String messageId;
    private final Identity sender;
    private final String chatId;
    private final MessageContent content;
    private final String replyToId;
    private final String threadId;
    private final Instant timestamp;
    private final Map<String, Object> raw;

    private UnifiedMessage(Builder builder) {
        this.channelId = Objects.requireNonNull(builder.channelId, "channelId required");
        this.messageId = builder.messageId;
        this.sender = Objects.requireNonNull(builder.sender, "sender required");
        this.chatId = Objects.requireNonNull(builder.chatId, "chatId required");
        this.content = Objects.requireNonNull(builder.content, "content required");
        this.replyToId = builder.replyToId;
        this.threadId = builder.threadId;
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now();
        this.raw = builder.raw != null ? Map.copyOf(builder.raw) : Map.of();
    }

    public String channelId()          { return channelId; }
    public String messageId()          { return messageId; }
    public Identity sender()           { return sender; }
    public String chatId()             { return chatId; }
    public MessageContent content()    { return content; }
    public String replyToId()          { return replyToId; }
    public String threadId()           { return threadId; }
    public Instant timestamp()         { return timestamp; }
    public Map<String, Object> raw()   { return raw; }

    /** Shorthand: the text body of this message, or empty string if absent. */
    public String text() {
        return content.text() != null ? content.text() : "";
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "UnifiedMessage[channel=%s, chat=%s, sender=%s, content=%s]"
                .formatted(channelId, chatId, sender.id(), content.type());
    }

    public static final class Builder {
        private String channelId;
        private String messageId;
        private Identity sender;
        private String chatId;
        private MessageContent content;
        private String replyToId;
        private String threadId;
        private Instant timestamp;
        private Map<String, Object> raw;

        private Builder() {}

        public Builder channelId(String channelId)       { this.channelId = channelId; return this; }
        public Builder messageId(String messageId)       { this.messageId = messageId; return this; }
        public Builder sender(Identity sender)           { this.sender = sender; return this; }
        public Builder chatId(String chatId)             { this.chatId = chatId; return this; }
        public Builder content(MessageContent content)   { this.content = content; return this; }
        public Builder replyToId(String replyToId)       { this.replyToId = replyToId; return this; }
        public Builder threadId(String threadId)         { this.threadId = threadId; return this; }
        public Builder timestamp(Instant timestamp)      { this.timestamp = timestamp; return this; }
        public Builder raw(Map<String, Object> raw)      { this.raw = raw; return this; }

        public UnifiedMessage build() {
            return new UnifiedMessage(this);
        }
    }
}
