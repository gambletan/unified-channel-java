package io.github.gambletan.unifiedchannel.streaming;

import java.util.Iterator;
import java.util.Objects;

/**
 * Represents a streaming reply composed of text chunks.
 * <p>
 * Used with LLM streaming APIs where the response arrives incrementally.
 * Call {@link #collect()} to join all chunks into the final text.
 */
public final class StreamingReply {

    private final Iterator<String> chunks;

    public StreamingReply(Iterator<String> chunks) {
        this.chunks = Objects.requireNonNull(chunks, "chunks must not be null");
    }

    /**
     * Collect all remaining chunks into a single string.
     */
    public String collect() {
        var sb = new StringBuilder();
        while (chunks.hasNext()) {
            sb.append(chunks.next());
        }
        return sb.toString();
    }

    /**
     * Check if there are more chunks available.
     */
    public boolean hasNext() {
        return chunks.hasNext();
    }

    /**
     * Get the next chunk.
     */
    public String next() {
        return chunks.next();
    }
}
