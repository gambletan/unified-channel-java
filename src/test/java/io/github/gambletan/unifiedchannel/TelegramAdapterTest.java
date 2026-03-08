package io.github.gambletan.unifiedchannel;

import io.github.gambletan.unifiedchannel.adapters.TelegramAdapter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TelegramAdapterTest {

    @Test
    void defaultsToPollingMode() {
        var adapter = new TelegramAdapter("123:ABC");
        assertEquals("telegram", adapter.channelId());
        assertEquals(TelegramAdapter.Mode.POLLING, adapter.mode());
    }

    @Test
    void webhookModeWithConfig() {
        var config = new TelegramAdapter.WebhookConfig("https://example.com", 9000, "/hook");
        var adapter = new TelegramAdapter("123:ABC", TelegramAdapter.Mode.WEBHOOK, config);
        assertEquals(TelegramAdapter.Mode.WEBHOOK, adapter.mode());
        assertEquals("telegram", adapter.channelId());
    }

    @Test
    void webhookConfigDefaults() {
        var config = new TelegramAdapter.WebhookConfig("https://example.com");
        assertEquals("https://example.com", config.webhookUrl());
        assertEquals(8443, config.port());
        assertEquals("/telegram-webhook", config.path());
    }

    @Test
    void webhookConfigCustomValues() {
        var config = new TelegramAdapter.WebhookConfig("https://my.server.com", 4000, "/tg");
        assertEquals("https://my.server.com", config.webhookUrl());
        assertEquals(4000, config.port());
        assertEquals("/tg", config.path());
    }

    @Test
    void statusDisconnectedByDefault() {
        var adapter = new TelegramAdapter("123:ABC");
        var status = adapter.getStatus();
        assertEquals(ChannelStatus.State.DISCONNECTED, status.state());
        assertEquals("telegram", status.channelId());
    }

    @Test
    void pollingModeExplicit() {
        var adapter = new TelegramAdapter("123:ABC", TelegramAdapter.Mode.POLLING, null);
        assertEquals(TelegramAdapter.Mode.POLLING, adapter.mode());
    }
}
