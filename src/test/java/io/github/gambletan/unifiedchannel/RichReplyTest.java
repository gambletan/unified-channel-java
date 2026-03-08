package io.github.gambletan.unifiedchannel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RichReplyTest {

    @Test
    void textOnly() {
        var reply = new RichReply().text("Hello world");
        assertEquals("Hello world", reply.toPlainText());
    }

    @Test
    void multipleTextBlocks() {
        var reply = new RichReply()
                .text("Line 1")
                .text("Line 2");
        assertEquals("Line 1\nLine 2", reply.toPlainText());
    }

    @Test
    void tableRendering() {
        var reply = new RichReply()
                .table(List.of("Name", "Status"),
                        List.of(List.of("API", "OK"), List.of("DB", "Down")));
        var text = reply.toPlainText();
        assertTrue(text.contains("Name"));
        assertTrue(text.contains("Status"));
        assertTrue(text.contains("API"));
        assertTrue(text.contains("OK"));
        assertTrue(text.contains("DB"));
        assertTrue(text.contains("Down"));
        // Check separator line exists
        assertTrue(text.contains("---"));
    }

    @Test
    void codeBlock() {
        var reply = new RichReply().code("var x = 1;", "javascript");
        var text = reply.toPlainText();
        assertTrue(text.contains("```javascript"));
        assertTrue(text.contains("var x = 1;"));
        assertTrue(text.contains("```"));
    }

    @Test
    void codeBlockNoLanguage() {
        var reply = new RichReply().code("echo hello", null);
        var text = reply.toPlainText();
        assertTrue(text.startsWith("```"));
        assertTrue(text.contains("echo hello"));
    }

    @Test
    void imageRendering() {
        var reply = new RichReply().image("https://example.com/img.png", "Screenshot");
        var text = reply.toPlainText();
        assertEquals("[Screenshot](https://example.com/img.png)", text);
    }

    @Test
    void imageNoAlt() {
        var reply = new RichReply().image("https://example.com/img.png", null);
        var text = reply.toPlainText();
        assertEquals("[image](https://example.com/img.png)", text);
    }

    @Test
    void divider() {
        var reply = new RichReply()
                .text("Above")
                .divider()
                .text("Below");
        var text = reply.toPlainText();
        assertTrue(text.contains("---"));
    }

    @Test
    void buttons() {
        var reply = new RichReply()
                .buttons(List.of(
                        List.of(new Button("Yes", "yes"), new Button("No", "no")),
                        List.of(new Button("Cancel", "cancel"))));
        var text = reply.toPlainText();
        assertTrue(text.contains("[Yes]"));
        assertTrue(text.contains("[No]"));
        assertTrue(text.contains("[Cancel]"));
    }

    @Test
    void toOutbound() {
        var reply = new RichReply()
                .text("Hello")
                .buttons(List.of(List.of(new Button("OK", "ok"))));

        var outbound = reply.toOutbound("telegram", "chat123");
        assertEquals("chat123", outbound.chatId());
        assertTrue(outbound.text().contains("Hello"));
        assertEquals(1, outbound.buttons().size());
        assertEquals("OK", outbound.buttons().get(0).get(0).label());
    }

    @Test
    void getParts() {
        var reply = new RichReply()
                .text("hi")
                .divider()
                .code("x", "py");

        var parts = reply.getParts();
        assertEquals(3, parts.size());
        assertInstanceOf(RichReply.TextPart.class, parts.get(0));
        assertInstanceOf(RichReply.DividerPart.class, parts.get(1));
        assertInstanceOf(RichReply.CodePart.class, parts.get(2));
    }

    @Test
    void complexReply() {
        var reply = new RichReply()
                .text("Server Status Report")
                .divider()
                .table(List.of("Service", "Status", "Latency"),
                        List.of(
                                List.of("API", "UP", "12ms"),
                                List.of("Database", "UP", "3ms"),
                                List.of("Cache", "DOWN", "N/A")))
                .divider()
                .code("{\"uptime\": \"3d 12h\"}", "json")
                .buttons(List.of(List.of(
                        new Button("Restart Cache", "restart_cache"),
                        new Button("View Logs", "view_logs"))));

        var text = reply.toPlainText();
        assertTrue(text.contains("Server Status Report"));
        assertTrue(text.contains("API"));
        assertTrue(text.contains("Cache"));
        assertTrue(text.contains("```json"));
        assertTrue(text.contains("[Restart Cache]"));
    }
}
