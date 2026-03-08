package io.github.gambletan.unifiedchannel;

import io.github.gambletan.unifiedchannel.adapters.*;
import io.github.gambletan.unifiedchannel.middleware.AccessMiddleware;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple YAML-like config loader for ChannelManager.
 * <p>
 * Supports a flat YAML structure with environment variable interpolation
 * via {@code ${ENV_VAR}} syntax. Falls back to empty string for undefined vars.
 *
 * <pre>
 * channels:
 *   telegram:
 *     token: ${TELEGRAM_TOKEN}
 *   discord:
 *     token: ${DISCORD_TOKEN}
 *   slack:
 *     botToken: ${SLACK_BOT_TOKEN}
 *     appToken: ${SLACK_APP_TOKEN}
 *
 * middleware:
 *   access:
 *     allowedUsers:
 *       - user1
 *       - user2
 * </pre>
 */
public final class Config {

    private static final Pattern ENV_PAT = Pattern.compile("\\$\\{([^}]+)}");

    private Config() {}

    /**
     * Load channels from a YAML config file, returns a configured ChannelManager.
     */
    public static ChannelManager loadConfig(String path) {
        return loadConfig(Path.of(path));
    }

    /**
     * Load channels from a YAML config file path, returns a configured ChannelManager.
     */
    public static ChannelManager loadConfig(Path path) {
        try {
            var lines = Files.readAllLines(path);
            return parseConfig(lines);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read config file: " + path, e);
        }
    }

    /**
     * Parse config from a list of lines (for testing without file I/O).
     */
    public static ChannelManager parseConfig(List<String> lines) {
        var manager = new ChannelManager();
        var parsed = parseYaml(lines);

        // Parse channels section
        @SuppressWarnings("unchecked")
        var channels = (Map<String, Map<String, Object>>) parsed.get("channels");
        if (channels != null) {
            for (var entry : channels.entrySet()) {
                var channelType = entry.getKey();
                var props = entry.getValue();
                var adapter = createAdapter(channelType, props);
                if (adapter != null) {
                    manager.addChannel(adapter);
                }
            }
        }

        // Parse middleware section
        @SuppressWarnings("unchecked")
        var middleware = (Map<String, Map<String, Object>>) parsed.get("middleware");
        if (middleware != null) {
            if (middleware.containsKey("access")) {
                var accessProps = middleware.get("access");
                @SuppressWarnings("unchecked")
                var allowedUsers = (List<String>) accessProps.get("allowedUsers");
                if (allowedUsers != null && !allowedUsers.isEmpty()) {
                    manager.addMiddleware(new AccessMiddleware(new LinkedHashSet<>(allowedUsers)));
                }
            }
        }

        return manager;
    }

    // Simple line-by-line YAML parser (handles 2 levels of nesting + lists)
    // Level 0 (indent 0): top-level keys (e.g. "channels:", "middleware:")
    // Level 1 (indent 2): subsection keys (e.g. "telegram:", "access:")
    // Level 2 (indent 4+): properties (e.g. "token: abc") or list keys
    // Level 3 (indent 6+): list items (e.g. "- user1")
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseYaml(List<String> lines) {
        var root = new LinkedHashMap<String, Object>();
        String currentSection = null;
        String currentSubSection = null;
        String currentListKey = null;

        for (var rawLine : lines) {
            // Skip comments and blank lines
            if (rawLine.trim().isEmpty() || rawLine.trim().startsWith("#")) continue;

            var interpolated = interpolateEnv(rawLine);
            var indent = countIndent(interpolated);
            var trimmed = interpolated.trim();

            // List item (- value)
            if (trimmed.startsWith("- ")) {
                var value = trimmed.substring(2).trim();
                if (currentSection != null && currentSubSection != null && currentListKey != null) {
                    var section = (Map<String, Object>) root.computeIfAbsent(currentSection, k -> new LinkedHashMap<>());
                    var sub = (Map<String, Object>) section.computeIfAbsent(currentSubSection, k -> new LinkedHashMap<>());
                    var list = (List<String>) sub.computeIfAbsent(currentListKey, k -> new ArrayList<>());
                    list.add(value);
                }
                continue;
            }

            // Key: value or Key:
            int colonIdx = trimmed.indexOf(':');
            if (colonIdx < 0) continue;

            var key = trimmed.substring(0, colonIdx).trim();
            var value = trimmed.substring(colonIdx + 1).trim();

            if (indent == 0) {
                // Top-level section (channels, middleware)
                currentSection = key;
                currentSubSection = null;
                currentListKey = null;
                if (!value.isEmpty()) {
                    root.put(key, value);
                }
            } else if (indent <= 3 && currentSection != null) {
                // Subsection (channel name like "telegram" or middleware name like "access")
                currentSubSection = key;
                currentListKey = null;
                // Subsection with no value - just creates the map
            } else if (currentSection != null && currentSubSection != null) {
                // Property within subsection (token: abc) or list key (allowedUsers:)
                var section = (Map<String, Object>) root.computeIfAbsent(currentSection, k -> new LinkedHashMap<>());
                var sub = (Map<String, Object>) section.computeIfAbsent(currentSubSection, k -> new LinkedHashMap<>());
                if (value.isEmpty()) {
                    currentListKey = key;
                } else {
                    sub.put(key, value);
                }
            }
        }

        return root;
    }

    static String interpolateEnv(String line) {
        Matcher m = ENV_PAT.matcher(line);
        var sb = new StringBuilder();
        while (m.find()) {
            var envName = m.group(1);
            var envValue = System.getenv(envName);
            m.appendReplacement(sb, Matcher.quoteReplacement(envValue != null ? envValue : ""));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static int countIndent(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') count++;
            else if (c == '\t') count += 2;
            else break;
        }
        return count;
    }

    private static ChannelAdapter createAdapter(String type, Map<String, Object> props) {
        return switch (type.toLowerCase()) {
            case "telegram" -> new TelegramAdapter(str(props, "token"));
            case "discord" -> new DiscordAdapter(str(props, "token"));
            case "slack" -> new SlackAdapter(str(props, "botToken"), str(props, "appToken"));
            case "mattermost" -> new MattermostAdapter(str(props, "serverUrl"), str(props, "token"));
            case "irc" -> new IRCAdapter(str(props, "server"),
                    Integer.parseInt(str(props, "port", "6667")),
                    str(props, "nick"),
                    str(props, "channel"));
            case "whatsapp" -> new WhatsAppAdapter(str(props, "phoneNumberId"), str(props, "accessToken"));
            case "matrix" -> new MatrixAdapter(str(props, "homeserverUrl"), str(props, "accessToken"));
            case "msteams" -> new MSTeamsAdapter(str(props, "appId"), str(props, "appSecret"));
            case "line" -> new LineAdapter(str(props, "channelAccessToken"), str(props, "channelSecret"));
            case "feishu" -> new FeishuAdapter(str(props, "appId"), str(props, "appSecret"));
            case "googlechat" -> new GoogleChatAdapter(str(props, "serviceAccountJson"));
            case "nextcloud" -> new NextcloudAdapter(str(props, "serverUrl"), str(props, "username"), str(props, "password"));
            case "synology" -> new SynologyAdapter(str(props, "incomingWebhookUrl"));
            case "zalo" -> new ZaloAdapter(str(props, "oaAccessToken"));
            case "nostr" -> new NostrAdapter(str(props, "privateKeyHex"), str(props, "relayUrls", "").split(","));
            case "bluebubbles" -> new BlueBubblesAdapter(str(props, "serverUrl"), str(props, "password"));
            case "twitch" -> new TwitchAdapter(str(props, "oauthToken"), str(props, "botUsername"),
                    str(props, "channels", "").split(","));
            case "imessage" -> new IMessageAdapter();
            default -> null;
        };
    }

    private static String str(Map<String, Object> props, String key) {
        var val = props.get(key);
        return val != null ? val.toString() : "";
    }

    private static String str(Map<String, Object> props, String key, String defaultValue) {
        var val = props.get(key);
        return val != null ? val.toString() : defaultValue;
    }
}
