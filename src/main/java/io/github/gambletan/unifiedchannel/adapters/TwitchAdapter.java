package io.github.gambletan.unifiedchannel.adapters;

import io.github.gambletan.unifiedchannel.*;

import java.io.*;
import java.net.Socket;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocketFactory;

/**
 * Twitch adapter using IRC over TLS.
 * <p>
 * Connects to irc.chat.twitch.tv:6697, authenticates with OAuth,
 * joins channels, and parses PRIVMSG for chat messages.
 *
 * @see <a href="https://dev.twitch.tv/docs/irc/">Twitch IRC</a>
 */
public final class TwitchAdapter extends AbstractAdapter {

    private static final String IRC_HOST = "irc.chat.twitch.tv";
    private static final int IRC_PORT = 6697;
    private static final Pattern PRIVMSG_PAT = Pattern.compile(
            ":([^!]+)!\\S+\\s+PRIVMSG\\s+#(\\S+)\\s+:(.+)");
    private static final Pattern PING_PAT = Pattern.compile("^PING\\s+:(.+)$");

    private final String oauthToken;
    private final String botUsername;
    private final String[] channels;
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private volatile boolean running;
    private ExecutorService readThread;

    public TwitchAdapter(String oauthToken, String botUsername, String... channels) {
        this.oauthToken = oauthToken;
        this.botUsername = botUsername;
        this.channels = channels;
    }

    @Override
    public String channelId() { return "twitch"; }

    @Override
    public CompletableFuture<Void> connect() {
        status = new ChannelStatus(channelId(), ChannelStatus.State.CONNECTING, null, null);
        return CompletableFuture.supplyAsync(() -> {
            try {
                var sslFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                socket = sslFactory.createSocket(IRC_HOST, IRC_PORT);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));

                // Authenticate
                sendRaw("PASS oauth:" + oauthToken);
                sendRaw("NICK " + botUsername);

                // Wait for welcome (001)
                String line;
                boolean welcomed = false;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("001")) { welcomed = true; break; }
                    if (line.contains("Login authentication failed")) {
                        throw new RuntimeException("Twitch auth failed");
                    }
                    handlePing(line);
                }

                if (!welcomed) throw new RuntimeException("Twitch connection failed");

                // Request tags for user metadata
                sendRaw("CAP REQ :twitch.tv/tags twitch.tv/commands");

                // Join channels
                for (var ch : channels) {
                    var name = ch.startsWith("#") ? ch : "#" + ch;
                    sendRaw("JOIN " + name.toLowerCase());
                }

                running = true;
                status = ChannelStatus.connected(channelId());

                // Start read loop
                readThread = Executors.newSingleThreadExecutor(r -> {
                    var t = new Thread(r, "twitch-irc");
                    t.setDaemon(true);
                    return t;
                });
                readThread.submit(this::readLoop);

                log.info("Twitch IRC connected, joined " + channels.length + " channels");
            } catch (Exception e) {
                status = ChannelStatus.error(channelId(), e.getMessage());
                throw new RuntimeException("Failed to connect to Twitch IRC", e);
            }
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        running = false;
        try {
            if (writer != null) { sendRaw("QUIT"); writer.close(); }
            if (reader != null) reader.close();
            if (socket != null) socket.close();
        } catch (IOException ignored) {}
        if (readThread != null) readThread.shutdown();
        status = ChannelStatus.disconnected(channelId());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> send(OutboundMessage message) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                var channel = message.chatId().startsWith("#") ? message.chatId() : "#" + message.chatId();
                sendRaw("PRIVMSG " + channel + " :" + (message.text() != null ? message.text() : ""));
            } catch (IOException e) {
                log.warning("Twitch send failed: " + e.getMessage());
            }
            return null;
        });
    }

    private void readLoop() {
        try {
            String line;
            while (running && (line = reader.readLine()) != null) {
                handlePing(line);
                var match = PRIVMSG_PAT.matcher(line);
                if (match.find()) {
                    var username = match.group(1);
                    var channel = match.group(2);
                    var text = match.group(3).trim();

                    var contentType = text.startsWith("/") || text.startsWith("!")
                            ? ContentType.COMMAND : ContentType.TEXT;
                    var msg = UnifiedMessage.builder()
                            .channelId(channelId())
                            .sender(new Identity(username, username))
                            .chatId("#" + channel)
                            .content(new MessageContent(contentType, text, null, null, null))
                            .timestamp(Instant.now())
                            .build();
                    emit(msg);
                }
            }
        } catch (IOException e) {
            if (running) {
                log.warning("Twitch read error: " + e.getMessage());
                status = ChannelStatus.error(channelId(), e.getMessage());
            }
        }
    }

    private void handlePing(String line) {
        var pingMatch = PING_PAT.matcher(line);
        if (pingMatch.find()) {
            try { sendRaw("PONG :" + pingMatch.group(1)); }
            catch (IOException ignored) {}
        }
    }

    private void sendRaw(String line) throws IOException {
        synchronized (writer) {
            writer.write(line + "\r\n");
            writer.flush();
        }
    }
}
