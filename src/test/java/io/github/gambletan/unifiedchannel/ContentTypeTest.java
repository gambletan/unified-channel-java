package io.github.gambletan.unifiedchannel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ContentTypeTest {

    @Test
    void allContentTypesExist() {
        var types = ContentType.values();
        assertEquals(6, types.length);
        assertNotNull(ContentType.valueOf("TEXT"));
        assertNotNull(ContentType.valueOf("COMMAND"));
        assertNotNull(ContentType.valueOf("MEDIA"));
        assertNotNull(ContentType.valueOf("REACTION"));
        assertNotNull(ContentType.valueOf("EDIT"));
        assertNotNull(ContentType.valueOf("CALLBACK"));
    }

    @Test
    void messageContentFactories() {
        var text = MessageContent.text("hello");
        assertEquals(ContentType.TEXT, text.type());
        assertEquals("hello", text.text());
        assertNull(text.mediaUrl());

        var cmd = MessageContent.command("/start");
        assertEquals(ContentType.COMMAND, cmd.type());
        assertEquals("/start", cmd.text());

        var media = MessageContent.media("https://example.com/img.png", "image/png", "caption");
        assertEquals(ContentType.MEDIA, media.type());
        assertEquals("https://example.com/img.png", media.mediaUrl());
        assertEquals("image/png", media.mimeType());
        assertEquals("caption", media.text());

        var reaction = MessageContent.reaction("thumbsup");
        assertEquals(ContentType.REACTION, reaction.type());
        assertEquals("thumbsup", reaction.data());

        var edit = MessageContent.edit("updated text");
        assertEquals(ContentType.EDIT, edit.type());
        assertEquals("updated text", edit.text());

        var callback = MessageContent.callback("btn_1");
        assertEquals(ContentType.CALLBACK, callback.type());
        assertEquals("btn_1", callback.data());
    }

    @Test
    void identityRequiresId() {
        assertThrows(NullPointerException.class, () -> new Identity(null));
    }

    @Test
    void identityConvenienceConstructors() {
        var full = new Identity("123", "user", "User Name");
        assertEquals("123", full.id());
        assertEquals("user", full.username());
        assertEquals("User Name", full.displayName());

        var idOnly = new Identity("456");
        assertEquals("456", idOnly.id());
        assertNull(idOnly.username());
        assertNull(idOnly.displayName());
    }

    @Test
    void buttonRecord() {
        var btn = new Button("Click me", "click_1", "https://example.com");
        assertEquals("Click me", btn.label());
        assertEquals("click_1", btn.callbackData());
        assertEquals("https://example.com", btn.url());

        var simple = new Button("OK", "ok");
        assertNull(simple.url());
    }

    @Test
    void handlerResultSealed() {
        HandlerResult empty = HandlerResult.empty();
        assertInstanceOf(HandlerResult.Empty.class, empty);

        HandlerResult text = HandlerResult.text("reply");
        assertInstanceOf(HandlerResult.TextReply.class, text);
        assertEquals("reply", ((HandlerResult.TextReply) text).text());

        var outbound = OutboundMessage.text("chat1", "msg");
        HandlerResult msg = HandlerResult.message(outbound);
        assertInstanceOf(HandlerResult.MessageReply.class, msg);
        assertEquals(outbound, ((HandlerResult.MessageReply) msg).message());
    }

    @Test
    void handlerResultTextRejectBlank() {
        assertThrows(IllegalArgumentException.class, () -> HandlerResult.text(""));
        assertThrows(IllegalArgumentException.class, () -> HandlerResult.text("   "));
    }

    @Test
    void unifiedMessageBuilder() {
        var msg = UnifiedMessage.builder()
                .channelId("test")
                .messageId("m1")
                .sender(new Identity("u1", "alice"))
                .chatId("c1")
                .content(MessageContent.text("hello"))
                .replyToId("r1")
                .threadId("t1")
                .build();

        assertEquals("test", msg.channelId());
        assertEquals("m1", msg.messageId());
        assertEquals("u1", msg.sender().id());
        assertEquals("c1", msg.chatId());
        assertEquals("hello", msg.text());
        assertEquals("r1", msg.replyToId());
        assertEquals("t1", msg.threadId());
        assertNotNull(msg.timestamp());
        assertTrue(msg.raw().isEmpty());
    }

    @Test
    void unifiedMessageRequiresFields() {
        assertThrows(NullPointerException.class, () ->
                UnifiedMessage.builder().build());

        assertThrows(NullPointerException.class, () ->
                UnifiedMessage.builder()
                        .channelId("x")
                        .sender(new Identity("u"))
                        .build()); // missing chatId and content
    }

    @Test
    void outboundMessageBuilder() {
        var msg = OutboundMessage.builder()
                .chatId("c1")
                .text("hello")
                .parseMode(OutboundMessage.ParseMode.MARKDOWN)
                .build();

        assertEquals("c1", msg.chatId());
        assertEquals("hello", msg.text());
        assertEquals(OutboundMessage.ParseMode.MARKDOWN, msg.parseMode());
        assertTrue(msg.buttons().isEmpty());
    }

    @Test
    void outboundMessageTextFactory() {
        var msg = OutboundMessage.text("c1", "hi");
        assertEquals("c1", msg.chatId());
        assertEquals("hi", msg.text());
        assertEquals(OutboundMessage.ParseMode.PLAIN, msg.parseMode());
    }

    @Test
    void channelStatusFactories() {
        var connected = ChannelStatus.connected("tg");
        assertEquals(ChannelStatus.State.CONNECTED, connected.state());
        assertNotNull(connected.connectedAt());

        var disconnected = ChannelStatus.disconnected("tg");
        assertEquals(ChannelStatus.State.DISCONNECTED, disconnected.state());

        var error = ChannelStatus.error("tg", "timeout");
        assertEquals(ChannelStatus.State.ERROR, error.state());
        assertEquals("timeout", error.error());
    }
}
