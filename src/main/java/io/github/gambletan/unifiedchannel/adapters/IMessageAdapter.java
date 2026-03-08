package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/**
 * iMessage adapter for macOS using sqlite3 (chat.db polling) and osascript (sending).
 * <p>
 * Polls ~/Library/Messages/chat.db for new messages using sqlite3 via ProcessBuilder.
 * Sends messages by invoking osascript to control Messages.app.
 * <p>
 * Requires macOS with Full Disk Access granted to the JVM process.
 */
public final class IMessageAdapter extends AbstractAdapter {

    private static final String CHAT_DB = System.getProperty("user.home") + "/Library/Messages/chat.db";
    private static final Pattern PHONE_PAT = Pattern.compile("[+\\d\\-()\\s]+");

    private volatile boolean polling;
    private ScheduledExecutorService poller;
    private volatile long lastRowId = 0;

    public IMessageAdapter() {}

    @Override
    public String channelId() { return "imessage"; }

    @Override
    public CompletableFuture<Void> connect() {
        status = new ChannelStatus(channelId(), ChannelStatus.State.CONNECTING, null, null);
        return CompletableFuture.supplyAsync(() -> {
            // Check if running on macOS
            if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) {
                status = ChannelStatus.error(channelId(), "iMessage adapter requires macOS");
                throw new RuntimeException("iMessage adapter requires macOS");
            }
            // Get the latest ROWID to avoid replaying old messages
            try {
                var result = runSqlite("SELECT MAX(ROWID) FROM message");
                if (result != null && !result.isBlank()) {
                    lastRowId = Long.parseLong(result.trim());
                }
            } catch (Exception e) {
                log.warning("Could not get latest message ROWID: " + e.getMessage());
            }
            status = ChannelStatus.connected(channelId());
            startPolling();
            log.info("iMessage adapter connected, polling chat.db");
            return null;
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
        return CompletableFuture.supplyAsync(() -> {
            try {
                var text = message.text() != null ? message.text() : "";
                var chatId = message.chatId();
                // Use osascript to send via Messages.app
                var script = String.format(
                        "tell application \"Messages\"\n"
                                + "  set targetService to 1st account whose service type = iMessage\n"
                                + "  set targetBuddy to participant \"%s\" of targetService\n"
                                + "  send \"%s\" to targetBuddy\n"
                                + "end tell",
                        escapeAppleScript(chatId), escapeAppleScript(text));

                var process = new ProcessBuilder("osascript", "-e", script)
                        .redirectErrorStream(true)
                        .start();
                var exitCode = process.waitFor();
                if (exitCode != 0) {
                    var output = new String(process.getInputStream().readAllBytes());
                    log.warning("osascript send failed (exit " + exitCode + "): " + output);
                }
            } catch (Exception e) {
                log.warning("iMessage send failed: " + e.getMessage());
            }
            return null;
        });
    }

    private void startPolling() {
        polling = true;
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "imessage-poll");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::poll, 2, 3, TimeUnit.SECONDS);
    }

    private void poll() {
        if (!polling) return;
        try {
            // Query new messages since last ROWID
            var sql = String.format(
                    "SELECT m.ROWID, m.text, h.id, m.is_from_me, m.date "
                            + "FROM message m "
                            + "LEFT JOIN handle h ON m.handle_id = h.ROWID "
                            + "WHERE m.ROWID > %d AND m.text IS NOT NULL AND m.is_from_me = 0 "
                            + "ORDER BY m.ROWID ASC LIMIT 50",
                    lastRowId);

            var result = runSqlite(sql);
            if (result == null || result.isBlank()) return;

            for (var line : result.split("\n")) {
                var parts = line.split("\\|", 5);
                if (parts.length < 3) continue;

                var rowId = Long.parseLong(parts[0].trim());
                var text = parts[1].trim();
                var handle = parts[2].trim();

                if (rowId > lastRowId) lastRowId = rowId;
                if (text.isEmpty()) continue;

                var contentType = text.startsWith("/") ? ContentType.COMMAND : ContentType.TEXT;
                var msg = UnifiedMessage.builder()
                        .channelId(channelId())
                        .messageId(String.valueOf(rowId))
                        .sender(new Identity(handle, handle))
                        .chatId(handle)
                        .content(new MessageContent(contentType, text, null, null, null))
                        .timestamp(Instant.now())
                        .build();
                emit(msg);
            }
        } catch (Exception e) {
            if (polling) log.fine("iMessage poll error: " + e.getMessage());
        }
    }

    private String runSqlite(String sql) throws Exception {
        var process = new ProcessBuilder("sqlite3", "-separator", "|", CHAT_DB, sql)
                .redirectErrorStream(true)
                .start();
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            var sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sb.isEmpty()) sb.append("\n");
                sb.append(line);
            }
            process.waitFor(5, TimeUnit.SECONDS);
            return sb.toString();
        }
    }

    private static String escapeAppleScript(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
