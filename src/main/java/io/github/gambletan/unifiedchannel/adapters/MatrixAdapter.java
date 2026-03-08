package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Matrix Client-Server API adapter using /sync long-polling.
 * <p>
 * Connects to a Matrix homeserver, polls for events via /sync,
 * and sends messages via PUT on the room send endpoint.
 *
 * @see <a href="https://spec.matrix.org/latest/client-server-api/">Matrix CS API</a>
 */
public final class MatrixAdapter extends AbstractAdapter {

    private static final Pattern SENDER_PAT = Pattern.compile("\"sender\"\\s*:\\s*\"(@[^\"]+)\"");
    private static final Pattern BODY_PAT = Pattern.compile("\"body\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern EVENT_ID_PAT = Pattern.compile("\"event_id\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern NEXT_BATCH_PAT = Pattern.compile("\"next_batch\"\\s*:\\s*\"([^\"]+)\"");

    private final String homeserverUrl;
    private final String accessToken;
    private final HttpClient httpClient;
    private final AtomicLong txnCounter = new AtomicLong(0);
    private volatile String syncToken;
    private volatile boolean polling;
    private ScheduledExecutorService poller;

    public MatrixAdapter(String homeserverUrl, String accessToken) {
        this.homeserverUrl = homeserverUrl.endsWith("/")
                ? homeserverUrl.substring(0, homeserverUrl.length() - 1) : homeserverUrl;
        this.accessToken = accessToken;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String channelId() { return "matrix"; }

    @Override
    public CompletableFuture<Void> connect() {
        status = new ChannelStatus(channelId(), ChannelStatus.State.CONNECTING, null, null);
        var url = homeserverUrl + "/_matrix/client/v3/account/whoami";
        var request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() != 200) {
                        status = ChannelStatus.error(channelId(), "Auth failed: " + resp.statusCode());
                        throw new RuntimeException("Matrix auth failed: " + resp.body());
                    }
                    status = ChannelStatus.connected(channelId());
                    startPolling();
                });
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        polling = false;
        if (poller != null) poller.shutdown();
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        var txnId = "uc_" + txnCounter.incrementAndGet() + "_" + System.currentTimeMillis();
        var url = homeserverUrl + "/_matrix/client/v3/rooms/"
                + urlEncode(message.chatId()) + "/send/m.room.message/" + txnId;
        var json = "{\"msgtype\":\"m.text\",\"body\":\"" + escapeJson(message.text() != null ? message.text() : "") + "\"}";
        var request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .PUT(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 400) {
                        log.warning("Matrix send failed (" + resp.statusCode() + "): " + resp.body());
                    }
                });
    }

    private void startPolling() {
        polling = true;
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "matrix-sync");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::sync, 0, 100, TimeUnit.MILLISECONDS);
    }

    private void sync() {
        if (!polling) return;
        try {
            var url = homeserverUrl + "/_matrix/client/v3/sync?timeout=30000"
                    + (syncToken != null ? "&since=" + syncToken : "");
            var request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .timeout(Duration.ofSeconds(35))
                    .GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                processSyncResponse(response.body());
            }
        } catch (Exception e) {
            if (polling) log.fine("Sync error (will retry): " + e.getMessage());
        }
    }

    private void processSyncResponse(String body) {
        var batchMatch = NEXT_BATCH_PAT.matcher(body);
        if (batchMatch.find()) syncToken = batchMatch.group(1);

        // Find m.room.message events
        var events = body.split("\"event_id\"");
        for (int i = 1; i < events.length; i++) {
            var event = "\"event_id\"" + events[i];
            if (!event.contains("\"m.room.message\"") || !event.contains("\"m.text\"")) continue;

            var senderMatch = SENDER_PAT.matcher(event);
            var bodyMatch = BODY_PAT.matcher(event);
            var eventIdMatch = EVENT_ID_PAT.matcher(event);

            if (!senderMatch.find() || !bodyMatch.find()) continue;

            var sender = senderMatch.group(1);
            var text = bodyMatch.group(1).replace("\\\"", "\"").replace("\\n", "\n");
            var eventId = eventIdMatch.find() ? eventIdMatch.group(1) : null;

            var contentType = text.startsWith("/") ? ContentType.COMMAND : ContentType.TEXT;
            var msg = UnifiedMessage.builder()
                    .channelId(channelId())
                    .messageId(eventId)
                    .sender(new Identity(sender, sender))
                    .chatId(sender)
                    .content(new MessageContent(contentType, text, null, null, null))
                    .timestamp(Instant.now())
                    .build();
            emit(msg);
        }
    }

    private static String urlEncode(String s) {
        return s.replace("!", "%21").replace("#", "%23")
                .replace(":", "%3A").replace("@", "%40");
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
