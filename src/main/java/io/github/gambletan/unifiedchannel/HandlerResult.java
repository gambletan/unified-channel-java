package io.github.gambletan.unifiedchannel;

/**
 * Result of processing a message through the middleware pipeline.
 * Can contain a text reply, an outbound message, or be empty (message consumed / dropped).
 */
public sealed interface HandlerResult {

    /** No reply; message was consumed or dropped. */
    record Empty() implements HandlerResult {}

    /** Reply with a text string. */
    record TextReply(String text) implements HandlerResult {
        public TextReply {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("text must not be blank");
            }
        }
    }

    /** Reply with a full outbound message. */
    record MessageReply(OutboundMessage message) implements HandlerResult {
        public MessageReply {
            if (message == null) {
                throw new IllegalArgumentException("message must not be null");
            }
        }
    }

    // -- Factory methods --

    static HandlerResult empty() {
        return new Empty();
    }

    static HandlerResult text(String text) {
        return new TextReply(text);
    }

    static HandlerResult message(OutboundMessage message) {
        return new MessageReply(message);
    }
}
