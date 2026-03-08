package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * Nextcloud Talk adapter using the OCS REST API with polling.
 * <p>
 * Polls for new messages and sends via the chat endpoint.
 *
 * @see <a href="https://nextcloud-talk.readthedocs.io/en/latest/">Nextcloud Talk API</a>
 */
public final class NextcloudAdapter extends AbstractAdapter {

    private static final Pattern MSG_PAT = Pattern.compile("\"message\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern ACTOR_ID_PAT = Pattern.compile("\"actorId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ACTOR_NAME_PAT = Pattern.compile("\"actorDisplayName\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern TOKEN_PAT = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ID_PAT = Pattern.compile("\"id\"\\s*:\\s*(\\d+)");

    private final String serverUrl;
    private final String username;
    private final String password;
    private final HttpClient httpClient;
    private final String authHeader;
    private volatile boolean polling;
    private ScheduledExecutorService poller;
    private volatile long lastKnownMessageId = 0;

    public NextcloudAdapter(String serverUrl, String username, String password) {
        this.serverUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        this.username = username;
        this.password = password;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String channelId() { return "nextcloud"; }

    @Override
    public CompletableFuture<Void> connect() {
        status = new ChannelStatus(channelId(), ChannelStatus.State.CONNECTING, null, null);
        // Verify credentials via capabilities endpoint
        var url = serverUrl + "/ocs/v2.php/cloud/capabilities";
        var request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", authHeader)
                .header("OCS-APIRequest", "true")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 400) {
                        status = ChannelStatus.error(channelId(), "Auth failed: " + resp.statusCode());
                        throw new RuntimeException("Nextcloud auth failed");
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
        var url = serverUrl + "/ocs/v2.php/apps/spreed/api/v1/chat/" + message.chatId();
        var json = "{\"message\":\"" + escapeJson(message.text() != null ? message.text() : "") + "\"}";
        var request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", authHeader)
                .header("OCS-APIRequest", "true")
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 400)
                        log.warning("Nextcloud send failed (" + resp.statusCode() + "): " + resp.body());
                });
    }

    private void startPolling() {
        polling = true;
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "nextcloud-poll");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::pollRooms, 0, 3, TimeUnit.SECONDS);
    }

    private void pollRooms() {
        if (!polling) return;
        try {
            // Get conversations
            var url = serverUrl + "/ocs/v2.php/apps/spreed/api/v4/room";
            var request = HttpRequest.newBuilder(URI.create(url))
                    .header("Authorization", authHeader)
                    .header("OCS-APIRequest", "true")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                // Extract room tokens and poll each for messages
                var tokenMatcher = TOKEN_PAT.matcher(response.body());
                while (tokenMatcher.find()) {
                    pollRoom(tokenMatcher.group(1));
                }
            }
        } catch (Exception e) {
            if (polling) log.fine("Nextcloud poll error: " + e.getMessage());
        }
    }

    private void pollRoom(String roomToken) throws Exception {
        var url = serverUrl + "/ocs/v2.php/apps/spreed/api/v1/chat/" + roomToken
                + "?lookIntoFuture=1&timeout=0&lastKnownMessageId=" + lastKnownMessageId;
        var request = HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", authHeader)
                .header("OCS-APIRequest", "true")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(5))
                .GET().build();
        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 200) {
            processMessages(response.body(), roomToken);
        }
    }

    private void processMessages(String body, String roomToken) {
        var msgMatch = MSG_PAT.matcher(body);
        var actorMatch = ACTOR_ID_PAT.matcher(body);
        var idMatch = ID_PAT.matcher(body);
        while (msgMatch.find() && actorMatch.find()) {
            var text = msgMatch.group(1).replace("\\\"", "\"").replace("\\n", "\n");
            var actorId = actorMatch.group(1);
            var msgId = idMatch.find() ? idMatch.group(1) : null;
            if (msgId != null) {
                var id = Long.parseLong(msgId);
                if (id > lastKnownMessageId) lastKnownMessageId = id;
            }
            // Skip own messages
            if (actorId.equals(username)) continue;

            var actorNameMatch = ACTOR_NAME_PAT.matcher(body);
            var actorName = actorNameMatch.find() ? actorNameMatch.group(1) : null;

            var contentType = text.startsWith("/") ? ContentType.COMMAND : ContentType.TEXT;
            var msg = UnifiedMessage.builder()
                    .channelId(channelId())
                    .messageId(msgId)
                    .sender(new Identity(actorId, actorId, actorName))
                    .chatId(roomToken)
                    .content(new MessageContent(contentType, text, null, null, null))
                    .timestamp(Instant.now())
                    .build();
            emit(msg);
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
