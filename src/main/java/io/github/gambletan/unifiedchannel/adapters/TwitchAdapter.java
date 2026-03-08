package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.util.concurrent.CompletableFuture;

/**
 * Twitch adapter stub.
 * <p>
 * TODO: Implement using Twitch IRC (TMI) and/or EventSub.
 * - Connect to irc.chat.twitch.tv:6697 (TLS) via IRC protocol
 * - Authenticate with OAuth token (PASS oauth:...)
 * - JOIN channels, parse PRIVMSG for chat messages
 * - Handle Twitch-specific IRCv3 tags (badges, emotes, bits)
 * - Optionally use EventSub webhooks for subscription/follow events
 *
 * @see <a href="https://dev.twitch.tv/docs/irc/">Twitch IRC</a>
 */
public final class TwitchAdapter extends AbstractAdapter {

    private final String oauthToken;
    private final String botUsername;
    private final String[] channels;

    public TwitchAdapter(String oauthToken, String botUsername, String... channels) {
        this.oauthToken = oauthToken;
        this.botUsername = botUsername;
        this.channels = channels;
    }

    @Override public String channelId() { return "twitch"; }

    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Twitch adapter not yet implemented"));
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Twitch send not yet implemented"));
    }
}
