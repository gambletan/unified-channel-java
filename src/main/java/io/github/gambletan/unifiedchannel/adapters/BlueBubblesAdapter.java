package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * BlueBubbles adapter using the BlueBubbles REST API with polling.
 * <p>
 * Polls for new messages and sends via the text message endpoint.
 *
 * @see <a href="https://bluebubbles.app/web-api/">BlueBubbles API</a>
 */
public final class BlueBubblesAdapter extends AbstractAdapter {

    private static final Pattern TEXT_PAT = Pattern.compile("\"text\"\\s*:\\s*\"([^\"\\\\]*(?:\\\\.[^\"\\\\]*)*)\"");
    private static final Pattern HANDLE_PAT = Pattern.compile("\"address\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern GUID_PAT = Pattern.compile("\"guid\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern CHAT_GUID_PAT = Pattern.compile("\"chatGuid\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DATE_PAT = Pattern.compile("\"dateCreated\"\\s*:\\s*(\\d+)");

    private final String serverUrl;
    private final String password;
    private final HttpClient httpClient;
    private volatile boolean polling;
    private ScheduledExecutorService poller;
    private volatile long lastMessageTime = 0;

    public BlueBubblesAdapter(String serverUrl, String password) {
        this.serverUrl = serverUrl.endsWith("/") ? serverUrl.substring(0, serverUrl.length() - 1) : serverUrl;
        this.password = password;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String channelId() { return "bluebubbles"; }

    @Override
    public CompletableFuture<Void> connect() {
        status = new ChannelStatus(channelId(), ChannelStatus.State.CONNECTING, null, null);
        // Verify connection via server info
        var url = serverUrl + "/api/v1/server/info?password=" + password;
        var request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET().build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 400) {
                        status = ChannelStatus.error(channelId(), "Auth failed: " + resp.statusCode());
                        throw new RuntimeException("BlueBubbles auth failed");
                    }
                    lastMessageTime = System.currentTimeMillis();
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
        var url = serverUrl + "/api/v1/message/text?password=" + password;
        var json = "{\"chatGuid\":\"" + escapeJson(message.chatId())
                + "\",\"message\":\"" + escapeJson(message.text() != null ? message.text() : "") + "\"}";
        var request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(resp -> {
                    if (resp.statusCode() >= 400)
                        log.warning("BlueBubbles send failed (" + resp.statusCode() + "): " + resp.body());
                });
    }

    private void startPolling() {
        polling = true;
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "bluebubbles-poll");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::poll, 2, 3, TimeUnit.SECONDS);
    }

    private void poll() {
        if (!polling) return;
        try {
            var url = serverUrl + "/api/v1/message?password=" + password
                    + "&after=" + lastMessageTime + "&limit=50&sort=ASC";
            var request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET().build();
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                processMessages(response.body());
            }
        } catch (Exception e) {
            if (polling) log.fine("BlueBubbles poll error: " + e.getMessage());
        }
    }

    private void processMessages(String body) {
        var textMatch = TEXT_PAT.matcher(body);
        var handleMatch = HANDLE_PAT.matcher(body);
        while (textMatch.find() && handleMatch.find()) {
            var text = textMatch.group(1).replace("\\\"", "\"").replace("\\n", "\n");
            var handle = handleMatch.group(1);
            var guidMatch = GUID_PAT.matcher(body);
            var msgGuid = guidMatch.find() ? guidMatch.group(1) : null;
            var chatGuidMatch = CHAT_GUID_PAT.matcher(body);
            var chatGuid = chatGuidMatch.find() ? chatGuidMatch.group(1) : handle;
            var dateMatch = DATE_PAT.matcher(body);
            if (dateMatch.find()) {
                lastMessageTime = Math.max(lastMessageTime, Long.parseLong(dateMatch.group(1)));
            }

            var contentType = text.startsWith("/") ? ContentType.COMMAND : ContentType.TEXT;
            var msg = UnifiedMessage.builder()
                    .channelId(channelId())
                    .messageId(msgGuid)
                    .sender(new Identity(handle, handle))
                    .chatId(chatGuid)
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
