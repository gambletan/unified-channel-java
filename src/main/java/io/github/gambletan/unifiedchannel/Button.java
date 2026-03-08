package io.github.gambletan.unifiedchannel;

import java.util.Objects;

/**
 * Represents an interactive button attached to a message.
 *
 * @param label        text displayed on the button
 * @param callbackData data returned when the button is pressed
 * @param url          optional URL for link buttons
 */
public record Button(String label, String callbackData, String url) {

    public Button {
        Objects.requireNonNull(label, "label must not be null");
    }

    public Button(String label, String callbackData) {
        this(label, callbackData, null);
    }
}
