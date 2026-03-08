package io.github.gambletan.unifiedchannel;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigTest {

    @Test
    void parseYamlTopLevelSections() {
        var lines = List.of(
                "channels:",
                "  telegram:",
                "    token: abc123",
                "  discord:",
                "    token: xyz456"
        );
        var result = Config.parseYaml(lines);
        assertNotNull(result.get("channels"));
        @SuppressWarnings("unchecked")
        var channels = (Map<String, Map<String, Object>>) result.get("channels");
        assertEquals("abc123", channels.get("telegram").get("token"));
        assertEquals("xyz456", channels.get("discord").get("token"));
    }

    @Test
    void parseYamlWithLists() {
        var lines = List.of(
                "middleware:",
                "  access:",
                "    allowedUsers:",
                "      - user1",
                "      - user2",
                "      - user3"
        );
        var result = Config.parseYaml(lines);
        @SuppressWarnings("unchecked")
        var middleware = (Map<String, Map<String, Object>>) result.get("middleware");
        @SuppressWarnings("unchecked")
        var allowed = (List<String>) middleware.get("access").get("allowedUsers");
        assertEquals(3, allowed.size());
        assertTrue(allowed.contains("user1"));
        assertTrue(allowed.contains("user3"));
    }

    @Test
    void interpolateEnvVar() {
        // Test with a known env var (PATH always exists)
        var result = Config.interpolateEnv("value: ${PATH}");
        assertFalse(result.contains("${PATH}"));
        assertTrue(result.startsWith("value: "));
    }

    @Test
    void interpolateUnknownEnvReturnsEmpty() {
        var result = Config.interpolateEnv("token: ${DEFINITELY_NOT_A_REAL_ENV_VAR_12345}");
        assertEquals("token: ", result);
    }

    @Test
    void parseConfigCreatesTelegramAdapter() {
        var lines = List.of(
                "channels:",
                "  telegram:",
                "    token: test-token-123"
        );
        var manager = Config.parseConfig(lines);
        var channelIds = manager.getChannelIds();
        assertTrue(channelIds.contains("telegram"));
    }

    @Test
    void parseConfigCreatesMultipleAdapters() {
        var lines = List.of(
                "channels:",
                "  telegram:",
                "    token: tg-token",
                "  discord:",
                "    token: dc-token",
                "  slack:",
                "    botToken: slack-bot",
                "    appToken: slack-app"
        );
        var manager = Config.parseConfig(lines);
        assertEquals(3, manager.getChannelIds().size());
    }

    @Test
    void parseConfigWithAccessMiddleware() {
        var lines = List.of(
                "channels:",
                "  telegram:",
                "    token: test",
                "middleware:",
                "  access:",
                "    allowedUsers:",
                "      - admin1",
                "      - admin2"
        );
        var manager = Config.parseConfig(lines);
        assertTrue(manager.getChannelIds().contains("telegram"));
    }

    @Test
    void skipCommentsAndBlankLines() {
        var lines = List.of(
                "# This is a comment",
                "",
                "channels:",
                "  # Another comment",
                "  telegram:",
                "    token: abc"
        );
        var result = Config.parseYaml(lines);
        @SuppressWarnings("unchecked")
        var channels = (Map<String, Map<String, Object>>) result.get("channels");
        assertNotNull(channels.get("telegram"));
    }
}
