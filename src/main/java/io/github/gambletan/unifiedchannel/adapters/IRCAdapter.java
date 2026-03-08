package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * IRC adapter using pure Java sockets (no external dependencies).
 * Supports basic IRC protocol: NICK, USER, JOIN, PRIVMSG, PING/PONG.
 * <p>
 * For TLS, pass {@code useTls = true} to use {@link javax.net.ssl.SSLSocketFactory}.
 */
public final class IRCAdapter extends AbstractAdapter {

    // :nick!user@host PRIVMSG #channel :message text
    private static final Pattern PRIVMSG_PAT = Pattern.compile(
            "^:([^!]+)!([^ ]+) PRIVMSG ([^ ]+) :(.+)$");
    private static final Pattern PING_PAT = Pattern.compile("^PING :?(.+)$");

    private final String server;
    private final int port;
    private final String nickname;
    private final String[] channels;
    private final boolean useTls;

    private Socket socket;
    private BufferedWriter writer;
    private ExecutorService readerExecutor;
    private volatile boolean connected;

    public IRCAdapter(String server, int port, String nickname, boolean useTls, String... channels) {
        this.server = server;
        this.port = port;
        this.nickname = nickname;
        this.channels = channels;
        this.useTls = useTls;
    }

    /** Non-TLS convenience constructor. */
    public IRCAdapter(String server, int port, String nickname, String... channels) {
        this(server, port, nickname, false, channels);
    }

    @Override
    public String channelId() {
        return "irc";
    }

    @Override
    public CompletableFuture<Void> connect() {
        status = new ChannelStatus(channelId(), ChannelStatus.State.CONNECTING, null, null);

        return CompletableFuture.runAsync(() -> {
            try {
                if (useTls) {
                    socket = javax.net.ssl.SSLSocketFactory.getDefault().createSocket(server, port);
                } else {
                    socket = new Socket(server, port);
                }
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

                // Register
                sendRaw("NICK " + nickname);
                sendRaw("USER " + nickname + " 0 * :" + nickname);

                // Start reader thread
                readerExecutor = Executors.newSingleThreadExecutor(r -> {
                    var t = new Thread(r, "irc-reader");
                    t.setDaemon(true);
                    return t;
                });
                readerExecutor.submit(this::readLoop);

                connected = true;
                status = ChannelStatus.connected(channelId());

                // Join channels
                for (var ch : channels) {
                    sendRaw("JOIN " + ch);
                }
            } catch (Exception e) {
                status = ChannelStatus.error(channelId(), e.getMessage());
                throw new RuntimeException("IRC connect failed", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        connected = false;
        try {
            if (writer != null) sendRaw("QUIT :unified-channel shutdown");
        } catch (Exception ignored) {}
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
        if (readerExecutor != null) readerExecutor.shutdown();
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        return CompletableFuture.runAsync(() -> {
            try {
                var text = message.text() != null ? message.text() : "";
                // IRC messages are line-delimited; split multi-line
                for (var line : text.split("\n")) {
                    sendRaw("PRIVMSG " + message.chatId() + " :" + line);
                }
            } catch (Exception e) {
                log.warning("IRC send failed: " + e.getMessage());
            }
        });
    }

    // -- Read loop --

    private void readLoop() {
        try (var reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (connected && (line = reader.readLine()) != null) {
                handleLine(line);
            }
        } catch (Exception e) {
            if (connected) {
                log.warning("IRC read error: " + e.getMessage());
                status = ChannelStatus.error(channelId(), e.getMessage());
            }
        }
    }

    private void handleLine(String line) {
        // Respond to PING
        var pingMatch = PING_PAT.matcher(line);
        if (pingMatch.matches()) {
            try {
                sendRaw("PONG :" + pingMatch.group(1));
            } catch (Exception e) {
                log.fine("Failed to send PONG: " + e.getMessage());
            }
            return;
        }

        // Parse PRIVMSG
        var privmsgMatch = PRIVMSG_PAT.matcher(line);
        if (privmsgMatch.matches()) {
            var senderNick = privmsgMatch.group(1);
            var senderHost = privmsgMatch.group(2);
            var target = privmsgMatch.group(3);
            var text = privmsgMatch.group(4);

            // Determine if it's a channel message or DM
            var chatId = target.startsWith("#") ? target : senderNick;

            var contentType = text.startsWith("/") || text.startsWith("!")
                    ? ContentType.COMMAND : ContentType.TEXT;
            // Normalize ! prefix to / for commands
            var normalizedText = text.startsWith("!") ? "/" + text.substring(1) : text;

            var msg = UnifiedMessage.builder()
                    .channelId(channelId())
                    .sender(new Identity(senderNick, senderNick, senderNick))
                    .chatId(chatId)
                    .content(new MessageContent(contentType, normalizedText, null, null, null))
                    .timestamp(Instant.now())
                    .build();
            emit(msg);
        }
    }

    // -- Helpers --

    private synchronized void sendRaw(String line) throws IOException {
        if (writer != null) {
            writer.write(line + "\r\n");
            writer.flush();
        }
    }
}
