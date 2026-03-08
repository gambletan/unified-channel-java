package io.github.gambletan.unifiedchannel;

import java.util.List;
import java.util.Objects;

/**
 * A message to be sent through a channel.
 */
public final class OutboundMessage {

    private final String chatId;
    private final String text;
    private final String mediaUrl;
    private final String mimeType;
    private final String replyToId;
    private final String threadId;
    private final List<List<Button>> buttons;
    private final ParseMode parseMode;

    public enum ParseMode { PLAIN, MARKDOWN, HTML }

    private OutboundMessage(Builder builder) {
        this.chatId = Objects.requireNonNull(builder.chatId, "chatId required");
        this.text = builder.text;
        this.mediaUrl = builder.mediaUrl;
        this.mimeType = builder.mimeType;
        this.replyToId = builder.replyToId;
        this.threadId = builder.threadId;
        this.buttons = builder.buttons != null ? List.copyOf(builder.buttons) : List.of();
        this.parseMode = builder.parseMode != null ? builder.parseMode : ParseMode.PLAIN;
    }

    public String chatId()              { return chatId; }
    public String text()                { return text; }
    public String mediaUrl()            { return mediaUrl; }
    public String mimeType()            { return mimeType; }
    public String replyToId()           { return replyToId; }
    public String threadId()            { return threadId; }
    public List<List<Button>> buttons() { return buttons; }
    public ParseMode parseMode()        { return parseMode; }

    public static Builder builder() {
        return new Builder();
    }

    /** Quick text-only message factory. */
    public static OutboundMessage text(String chatId, String text) {
        return builder().chatId(chatId).text(text).build();
    }

    @Override
    public String toString() {
        return "OutboundMessage[chat=%s, text=%s]".formatted(chatId,
                text != null ? text.substring(0, Math.min(text.length(), 40)) : "<no text>");
    }

    public static final class Builder {
        private String chatId;
        private String text;
        private String mediaUrl;
        private String mimeType;
        private String replyToId;
        private String threadId;
        private List<List<Button>> buttons;
        private ParseMode parseMode;

        private Builder() {}

        public Builder chatId(String chatId)              { this.chatId = chatId; return this; }
        public Builder text(String text)                  { this.text = text; return this; }
        public Builder mediaUrl(String mediaUrl)          { this.mediaUrl = mediaUrl; return this; }
        public Builder mimeType(String mimeType)          { this.mimeType = mimeType; return this; }
        public Builder replyToId(String replyToId)        { this.replyToId = replyToId; return this; }
        public Builder threadId(String threadId)          { this.threadId = threadId; return this; }
        public Builder buttons(List<List<Button>> buttons){ this.buttons = buttons; return this; }
        public Builder parseMode(ParseMode parseMode)     { this.parseMode = parseMode; return this; }

        public OutboundMessage build() {
            return new OutboundMessage(this);
        }
    }
}
