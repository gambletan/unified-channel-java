package io.github.gambletan.unifiedchannel;

import java.util.Objects;

/**
 * Represents a user identity across channels.
 *
 * @param id          unique identifier for the user on their channel
 * @param username    optional username / handle
 * @param displayName optional human-readable display name
 */
public record Identity(String id, String username, String displayName) {

    public Identity {
        Objects.requireNonNull(id, "id must not be null");
    }

    public Identity(String id) {
        this(id, null, null);
    }

    public Identity(String id, String username) {
        this(id, username, null);
    }
}
