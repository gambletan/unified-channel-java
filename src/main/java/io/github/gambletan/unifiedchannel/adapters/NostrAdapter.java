package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Nostr protocol adapter using WebSocket connections to relays.
 * <p>
 * Connects to one or more relays, subscribes to text note events (kind 1),
 * and publishes text notes. Uses the standard NIP-01 protocol.
 * <p>
 * Note: Event signing (secp256k1) is simplified; for production use, consider
 * a proper Nostr signing library.
 *
 * @see <a href="https://github.com/nostr-protocol/nips">Nostr NIPs</a>
 */
public final class NostrAdapter extends AbstractAdapter {

    private static final Pattern CONTENT_PAT = Pattern.compile("\"content\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern PUBKEY_PAT = Pattern.compile("\"pubkey\"\\s*:\\s*\"([0-9a-f]{64})\"");
    private static final Pattern EVENT_ID_PAT = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-f]{64})\"");
    private static final Pattern KIND_PAT = Pattern.compile("\"kind\"\\s*:\\s*(\\d+)");

    private final String privateKeyHex;
    private final String[] relayUrls;
    private final HttpClient httpClient;
    private final CopyOnWriteArrayList<WebSocket> relayConnections = new CopyOnWriteArrayList<>();
    private volatile boolean connected;

    public NostrAdapter(String privateKeyHex, String... relayUrls) {
        this.privateKeyHex = privateKeyHex;
        this.relayUrls = relayUrls.length > 0 ? relayUrls : new String[]{"wss://relay.damus.io"};
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String channelId() { return "nostr"; }

    @Override
    public CompletableFuture<Void> connect() {
        status = new ChannelStatus(channelId(), ChannelStatus.State.CONNECTING, null, null);
        var futures = new CompletableFuture<?>[relayUrls.length];
        for (int i = 0; i < relayUrls.length; i++) {
            var relayUrl = relayUrls[i].trim();
            if (relayUrl.isEmpty()) continue;
            futures[i] = connectToRelay(relayUrl);
        }
        // Filter nulls
        var nonNull = java.util.Arrays.stream(futures)
                .filter(java.util.Objects::nonNull)
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(nonNull).thenRun(() -> {
            connected = true;
            status = ChannelStatus.connected(channelId());
        });
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        connected = false;
        for (var ws : relayConnections) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
        relayConnections.clear();
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        if (relayConnections.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Not connected to any relay"));
        }
        // Build a kind 1 event (simplified; real impl needs proper signing)
        var now = Instant.now().getEpochSecond();
        var content = escapeJson(message.text() != null ? message.text() : "");
        var eventJson = "[\"EVENT\",{\"id\":\"0000000000000000000000000000000000000000000000000000000000000000\","
                + "\"pubkey\":\"" + privateKeyHex.substring(0, Math.min(64, privateKeyHex.length())) + "\","
                + "\"created_at\":" + now + ","
                + "\"kind\":1,"
                + "\"tags\":[],"
                + "\"content\":\"" + content + "\","
                + "\"sig\":\"0000000000000000000000000000000000000000000000000000000000000000"
                + "0000000000000000000000000000000000000000000000000000000000000000\"}]";

        var futures = relayConnections.stream()
                .map(ws -> ws.sendText(eventJson, true).toCompletableFuture()
                        .thenAccept(x -> {}))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    private CompletableFuture<Void> connectToRelay(String relayUrl) {
        var future = new CompletableFuture<Void>();
        httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(relayUrl), new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket ws) {
                        relayConnections.add(ws);
                        // Subscribe to kind 1 events
                        ws.sendText("[\"REQ\",\"uc-sub\",{\"kinds\":[1],\"limit\":0}]", true);
                        ws.request(1);
                        future.complete(null);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) {
                            processRelayMessage(buffer.toString());
                            buffer.setLength(0);
                        }
                        ws.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                        relayConnections.remove(ws);
                        return null;
                    }

                    @Override
                    public void onError(WebSocket ws, Throwable error) {
                        relayConnections.remove(ws);
                        if (!future.isDone()) future.completeExceptionally(error);
                    }
                })
                .exceptionally(ex -> {
                    log.warning("Failed to connect to relay " + relayUrl + ": " + ex.getMessage());
                    if (!future.isDone()) future.complete(null); // Don't fail all on one relay failure
                    return null;
                });
        return future;
    }

    private void processRelayMessage(String raw) {
        if (!raw.startsWith("[\"EVENT\"")) return;

        var kindMatch = KIND_PAT.matcher(raw);
        if (!kindMatch.find() || !"1".equals(kindMatch.group(1))) return;

        var contentMatch = CONTENT_PAT.matcher(raw);
        var pubkeyMatch = PUBKEY_PAT.matcher(raw);
        var idMatch = EVENT_ID_PAT.matcher(raw);
        if (!contentMatch.find() || !pubkeyMatch.find()) return;

        var content = contentMatch.group(1).replace("\\\"", "\"").replace("\\n", "\n");
        var pubkey = pubkeyMatch.group(1);
        var eventId = idMatch.find() ? idMatch.group(1) : null;

        var contentType = content.startsWith("/") ? ContentType.COMMAND : ContentType.TEXT;
        var msg = UnifiedMessage.builder()
                .channelId(channelId())
                .messageId(eventId)
                .sender(new Identity(pubkey, pubkey.substring(0, 8)))
                .chatId(pubkey)
                .content(new MessageContent(contentType, content, null, null, null))
                .timestamp(Instant.now())
                .build();
        emit(msg);
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
