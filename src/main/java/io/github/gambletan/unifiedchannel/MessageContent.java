package io.github.gambletan.unifiedchannel;

import java.util.Objects;

/**
 * Represents the content payload of a message.
 *
 * @param type     content type discriminator
 * @param text     text body (for TEXT, COMMAND, EDIT)
 * @param mediaUrl URL of attached media (for MEDIA)
 * @param mimeType MIME type of attached media
 * @param data     arbitrary extra data (callback data, reaction emoji, etc.)
 */
public record MessageContent(
        ContentType type,
        String text,
        String mediaUrl,
        String mimeType,
        String data
) {
    public MessageContent {
        Objects.requireNonNull(type, "type must not be null");
    }

    /** Convenience factory for plain text content. */
    public static MessageContent text(String text) {
        return new MessageContent(ContentType.TEXT, text, null, null, null);
    }

    /** Convenience factory for command content. */
    public static MessageContent command(String text) {
        return new MessageContent(ContentType.COMMAND, text, null, null, null);
    }

    /** Convenience factory for media content. */
    public static MessageContent media(String mediaUrl, String mimeType, String caption) {
        return new MessageContent(ContentType.MEDIA, caption, mediaUrl, mimeType, null);
    }

    /** Convenience factory for reaction content. */
    public static MessageContent reaction(String emoji) {
        return new MessageContent(ContentType.REACTION, null, null, null, emoji);
    }

    /** Convenience factory for edit content. */
    public static MessageContent edit(String newText) {
        return new MessageContent(ContentType.EDIT, newText, null, null, null);
    }

    /** Convenience factory for callback content. */
    public static MessageContent callback(String callbackData) {
        return new MessageContent(ContentType.CALLBACK, null, null, null, callbackData);
    }
}
