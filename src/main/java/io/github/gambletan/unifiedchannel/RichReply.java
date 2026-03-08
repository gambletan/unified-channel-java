package io.github.gambletan.unifiedchannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builder for rich multi-part message replies.
 * <p>
 * Supports text blocks, tables, buttons, images, code blocks, and dividers.
 * Can be rendered to plain text or converted to an {@link OutboundMessage}.
 *
 * <pre>{@code
 * var reply = new RichReply()
 *     .text("Server Status")
 *     .divider()
 *     .table(List.of("Service", "Status"), List.of(
 *         List.of("API", "OK"),
 *         List.of("DB", "OK")))
 *     .code("{ \"uptime\": \"3d 12h\" }", "json")
 *     .buttons(List.of(List.of(
 *         new Button("Restart", "restart"),
 *         new Button("Logs", "logs"))));
 *
 * manager.send("telegram", reply.toOutbound("telegram", chatId));
 * }</pre>
 */
public final class RichReply {

    private final List<Part> parts = new ArrayList<>();

    /** Append a text block. */
    public RichReply text(String text) {
        Objects.requireNonNull(text, "text must not be null");
        parts.add(new TextPart(text));
        return this;
    }

    /** Append a table with headers and rows. */
    public RichReply table(List<String> headers, List<List<String>> rows) {
        Objects.requireNonNull(headers, "headers must not be null");
        Objects.requireNonNull(rows, "rows must not be null");
        parts.add(new TablePart(headers, rows));
        return this;
    }

    /** Append interactive buttons (rows of buttons). */
    public RichReply buttons(List<List<Button>> buttons) {
        Objects.requireNonNull(buttons, "buttons must not be null");
        parts.add(new ButtonsPart(buttons));
        return this;
    }

    /** Append an image. */
    public RichReply image(String url, String alt) {
        Objects.requireNonNull(url, "url must not be null");
        parts.add(new ImagePart(url, alt));
        return this;
    }

    /** Append a code block. */
    public RichReply code(String code, String language) {
        Objects.requireNonNull(code, "code must not be null");
        parts.add(new CodePart(code, language));
        return this;
    }

    /** Append a horizontal divider. */
    public RichReply divider() {
        parts.add(new DividerPart());
        return this;
    }

    /** Get the list of parts (for custom renderers). */
    public List<Part> getParts() {
        return List.copyOf(parts);
    }

    /**
     * Render all parts to plain text.
     * Tables are rendered as aligned columns, code blocks with markdown fences.
     */
    public String toPlainText() {
        var sb = new StringBuilder();
        for (var part : parts) {
            if (!sb.isEmpty()) sb.append("\n");
            if (part instanceof TextPart t) {
                sb.append(t.text());
            } else if (part instanceof TablePart t) {
                sb.append(renderTable(t.headers(), t.rows()));
            } else if (part instanceof ButtonsPart b) {
                sb.append(renderButtons(b.buttons()));
            } else if (part instanceof ImagePart i) {
                sb.append("[").append(i.alt() != null ? i.alt() : "image")
                        .append("](").append(i.url()).append(")");
            } else if (part instanceof CodePart c) {
                sb.append("```").append(c.language() != null ? c.language() : "").append("\n");
                sb.append(c.code()).append("\n```");
            } else if (part instanceof DividerPart) {
                sb.append("---");
            }
        }
        return sb.toString();
    }

    /**
     * Convert this rich reply to an {@link OutboundMessage}.
     * Text is rendered as plain text; buttons are attached if present.
     */
    public OutboundMessage toOutbound(String channel, String chatId) {
        var builder = OutboundMessage.builder()
                .chatId(chatId)
                .text(toPlainText());

        // Extract buttons from parts
        for (var part : parts) {
            if (part instanceof ButtonsPart bp) {
                builder.buttons(bp.buttons());
                break; // Only one button set supported in OutboundMessage
            }
        }

        return builder.build();
    }

    // -- Part types --

    public sealed interface Part {}
    public record TextPart(String text) implements Part {}
    public record TablePart(List<String> headers, List<List<String>> rows) implements Part {}
    public record ButtonsPart(List<List<Button>> buttons) implements Part {}
    public record ImagePart(String url, String alt) implements Part {}
    public record CodePart(String code, String language) implements Part {}
    public record DividerPart() implements Part {}

    // -- Rendering helpers --

    private static String renderTable(List<String> headers, List<List<String>> rows) {
        // Calculate column widths
        int cols = headers.size();
        int[] widths = new int[cols];
        for (int i = 0; i < cols; i++) {
            widths[i] = headers.get(i).length();
        }
        for (var row : rows) {
            for (int i = 0; i < Math.min(cols, row.size()); i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }

        var sb = new StringBuilder();

        // Header row
        for (int i = 0; i < cols; i++) {
            if (i > 0) sb.append(" | ");
            sb.append(pad(headers.get(i), widths[i]));
        }
        sb.append("\n");

        // Separator
        for (int i = 0; i < cols; i++) {
            if (i > 0) sb.append("-+-");
            sb.append("-".repeat(widths[i]));
        }
        sb.append("\n");

        // Data rows
        for (var row : rows) {
            for (int i = 0; i < cols; i++) {
                if (i > 0) sb.append(" | ");
                var cell = i < row.size() ? row.get(i) : "";
                sb.append(pad(cell, widths[i]));
            }
            sb.append("\n");
        }

        return sb.toString().stripTrailing();
    }

    private static String renderButtons(List<List<Button>> buttons) {
        var sb = new StringBuilder();
        for (var row : buttons) {
            if (!sb.isEmpty()) sb.append("\n");
            var labels = row.stream().map(b -> "[" + b.label() + "]").toList();
            sb.append(String.join("  ", labels));
        }
        return sb.toString();
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }
}
