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

    // --- New tests ---

    @Test
    void emptyReply() {
        var reply = new RichReply();
        assertEquals("", reply.toPlainText());
        assertTrue(reply.getParts().isEmpty());
    }

    @Test
    void tableRenderingAlignedColumns() {
        var reply = new RichReply()
                .table(List.of("ID", "Name", "Status"),
                        List.of(
                                List.of("1", "Short", "OK"),
                                List.of("100", "VeryLongName", "ERROR")));
        var text = reply.toPlainText();
        // Verify table structure: header, separator, data rows
        var lines = text.split("\n");
        assertTrue(lines.length >= 4); // header + separator + 2 data rows
        assertTrue(lines[1].contains("-")); // separator line
    }

    @Test
    void tableWithEmptyRows() {
        var reply = new RichReply()
                .table(List.of("Col1", "Col2"), List.of());
        var text = reply.toPlainText();
        assertTrue(text.contains("Col1"));
        assertTrue(text.contains("Col2"));
        // Just header + separator, no data rows
    }

    @Test
    void codeBlockPreservesContent() {
        var code = "function hello() {\n  console.log('world');\n}";
        var reply = new RichReply().code(code, "js");
        var text = reply.toPlainText();
        assertTrue(text.contains("```js"));
        assertTrue(text.contains(code));
        assertTrue(text.endsWith("```"));
    }

    @Test
    void multipleSections() {
        var reply = new RichReply()
                .text("Section 1")
                .divider()
                .text("Section 2")
                .divider()
                .text("Section 3");

        var text = reply.toPlainText();
        assertTrue(text.contains("Section 1"));
        assertTrue(text.contains("Section 2"));
        assertTrue(text.contains("Section 3"));

        var parts = reply.getParts();
        assertEquals(5, parts.size());
        assertInstanceOf(RichReply.TextPart.class, parts.get(0));
        assertInstanceOf(RichReply.DividerPart.class, parts.get(1));
        assertInstanceOf(RichReply.TextPart.class, parts.get(2));
        assertInstanceOf(RichReply.DividerPart.class, parts.get(3));
        assertInstanceOf(RichReply.TextPart.class, parts.get(4));
    }

    @Test
    void toOutboundForDifferentChannels() {
        var reply = new RichReply().text("Hello");

        var telegram = reply.toOutbound("telegram", "tg-chat");
        assertEquals("tg-chat", telegram.chatId());
        assertEquals("Hello", telegram.text());

        var discord = reply.toOutbound("discord", "dc-chat");
        assertEquals("dc-chat", discord.chatId());
        assertEquals("Hello", discord.text());

        var slack = reply.toOutbound("slack", "sl-chat");
        assertEquals("sl-chat", slack.chatId());
        assertEquals("Hello", slack.text());
    }

    @Test
    void toOutboundWithoutButtons() {
        var reply = new RichReply().text("No buttons");
        var outbound = reply.toOutbound("test", "c1");
        assertTrue(outbound.buttons().isEmpty());
    }

    @Test
    void nullTextThrows() {
        var reply = new RichReply();
        assertThrows(NullPointerException.class, () -> reply.text(null));
    }

    @Test
    void nullCodeThrows() {
        var reply = new RichReply();
        assertThrows(NullPointerException.class, () -> reply.code(null, "java"));
    }

    @Test
    void nullImageUrlThrows() {
        var reply = new RichReply();
        assertThrows(NullPointerException.class, () -> reply.image(null, "alt"));
    }

    @Test
    void partRecordEquality() {
        var p1 = new RichReply.TextPart("hello");
        var p2 = new RichReply.TextPart("hello");
        assertEquals(p1, p2);

        var c1 = new RichReply.CodePart("x", "java");
        var c2 = new RichReply.CodePart("x", "java");
        assertEquals(c1, c2);
    }
}
