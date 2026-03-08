# unified-channel-java

**The missing messaging layer for AI Agents.**

Give your AI agent a voice on every messaging platform. unified-channel-java provides a single API to send and receive messages across 18 channels -- write your agent's communication logic once, deploy it to Telegram, Discord, Slack, and more.

Built for the agentic era: plug this into your LLM orchestration pipeline (LangChain, Spring AI, custom agents) and let your agent talk to users wherever they are.

> Also available in **[Python](https://github.com/gambletan/unified-channel)** and **[TypeScript](https://github.com/gambletan/unified-channel-js)**.

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                  Your AI Agent                       │
│          (LangChain / Spring AI / custom)            │
└────────────────────────┬────────────────────────────┘
                         │
┌────────────────────────▼────────────────────────────┐
│                  ChannelManager                      │
│                                                      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐          │
│  │ Telegram │  │ Discord  │  │  Slack   │  ...x18   │
│  │ Adapter  │  │ Adapter  │  │ Adapter  │           │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘          │
│       │              │              │                │
│       └──────────────┴──────────────┘                │
│                      │                               │
│              UnifiedMessage                          │
│                      │                               │
│       ┌──────────────┼──────────────┐                │
│       ▼              ▼              ▼                │
│  ┌─────────┐  ┌───────────┐  ┌──────────┐          │
│  │ Access  │  │  Command  │  │ Memory   │          │
│  │Middleware│  │Middleware │  │Middleware │          │
│  └─────────┘  └───────────┘  └──────────┘          │
│                      │                               │
│                      ▼                               │
│              Message Handlers                        │
└──────────────────────────────────────────────────────┘
```

## Supported Channels

| Channel | Status | Adapter Class | Notes |
|---------|--------|---------------|-------|
| Telegram | Fully implemented | `TelegramAdapter` | Long polling, java.net.http |
| Discord | Fully implemented | `DiscordAdapter` | Gateway WebSocket + REST, java.net.http |
| Slack | Fully implemented | `SlackAdapter` | Socket Mode + Web API, java.net.http |
| Mattermost | Fully implemented | `MattermostAdapter` | WebSocket + REST, java.net.http |
| IRC | Fully implemented | `IRCAdapter` | Raw socket, SSL support |
| WhatsApp | Fully implemented | `WhatsAppAdapter` | Cloud API webhook + REST |
| Matrix | Fully implemented | `MatrixAdapter` | /sync polling + REST |
| MS Teams | Fully implemented | `MSTeamsAdapter` | Bot Framework webhook + REST |
| LINE | Fully implemented | `LineAdapter` | Webhook + push API |
| Feishu | Fully implemented | `FeishuAdapter` | Event subscription + REST, token refresh |
| Google Chat | Fully implemented | `GoogleChatAdapter` | Webhook + REST |
| Nextcloud | Fully implemented | `NextcloudAdapter` | OCS REST polling |
| Synology | Fully implemented | `SynologyAdapter` | Incoming/outgoing webhooks |
| Zalo | Fully implemented | `ZaloAdapter` | Webhook + OA API |
| Nostr | Fully implemented | `NostrAdapter` | WebSocket relay, NIP-01 |
| BlueBubbles | Fully implemented | `BlueBubblesAdapter` | REST polling |
| Twitch | Fully implemented | `TwitchAdapter` | IRC over TLS |
| iMessage | Fully implemented | `IMessageAdapter` | macOS sqlite3 + osascript |

## Quick Start

```xml
<!-- Add to your pom.xml (once published to Maven Central) -->
<dependency>
    <groupId>io.github.gambletan</groupId>
    <artifactId>unified-channel</artifactId>
    <version>0.1.0</version>
</dependency>
```

```java
var manager = new ChannelManager();

// Add channels your agent will communicate through
manager.addChannel(new TelegramAdapter(System.getenv("TELEGRAM_TOKEN")));
manager.addChannel(new DiscordAdapter(System.getenv("DISCORD_TOKEN")));
manager.addChannel(new SlackAdapter(
    System.getenv("SLACK_BOT_TOKEN"),
    System.getenv("SLACK_APP_TOKEN")));

// Add middleware (access control, command routing, etc.)
manager.addMiddleware(new AccessMiddleware(Set.of("admin-user-123")));

var commands = new CommandMiddleware();
commands.registerSync("help", ctx ->
    HandlerResult.text("Available: /help, /status, /ping"));
commands.registerSync("ping", ctx ->
    HandlerResult.text("pong!"));
manager.addMiddleware(commands);

// Handle messages -- this is where your AI agent logic goes
manager.onMessage(msg -> {
    String reply = yourAgent.chat(msg.text());  // call your LLM
    manager.send(msg.channelId(),
        OutboundMessage.text(msg.chatId(), reply));
});

// Connect all channels
manager.run().join();
```

## ServiceBridge

The `ServiceBridge` is a high-level wrapper that exposes application functions as chat commands with auto-generated `/help`.

```java
var manager = new ChannelManager();
manager.addChannel(new TelegramAdapter(System.getenv("TELEGRAM_TOKEN")));

var bridge = new ServiceBridge(manager)
    .expose("deploy", args -> deployService(args[0]), "Deploy a service")
    .expose("restart", args -> restartService(args[0]), "Restart a service")
    .exposeStatus(() -> getServiceStatus())
    .exposeLogs(args -> tailLogs(args.length > 0 ? Integer.parseInt(args[0]) : 20));

// Auto-generates: /help, /deploy, /restart, /status, /logs
bridge.run();
```

## YAML Configuration

Load channels and middleware from a YAML config file with `${ENV_VAR}` interpolation:

```yaml
# config.yml
channels:
  telegram:
    token: ${TELEGRAM_TOKEN}
  discord:
    token: ${DISCORD_TOKEN}
  slack:
    botToken: ${SLACK_BOT_TOKEN}
    appToken: ${SLACK_APP_TOKEN}

middleware:
  access:
    allowedUsers:
      - admin-user-123
      - operator-456
```

```java
var manager = Config.loadConfig("config.yml");
manager.onMessage(msg -> {
    // Handle messages from all configured channels
});
manager.run().join();
```

## Conversation Memory

The `ConversationMemory` middleware tracks conversation history per chat, with pluggable storage backends.

```java
// In-memory (default)
var memory = new ConversationMemory();

// SQLite-backed (persistent)
var memory = new ConversationMemory(new SQLiteStore("conversations.db"), 100);

manager.addMiddleware(memory);
manager.onMessage(msg -> {
    // History is available in message metadata
    @SuppressWarnings("unchecked")
    var history = (List<HistoryEntry>) msg.raw().get("history");

    // Feed history to your LLM for context-aware responses
    var reply = yourAgent.chatWithHistory(msg.text(), history);
    manager.send(msg.channelId(), OutboundMessage.text(msg.chatId(), reply));
});
```

## Rich Replies

Build multi-part replies with tables, code blocks, images, and buttons:

```java
var reply = new RichReply()
    .text("Server Status Report")
    .divider()
    .table(
        List.of("Service", "Status", "Latency"),
        List.of(
            List.of("API", "UP", "12ms"),
            List.of("Database", "UP", "3ms"),
            List.of("Cache", "DOWN", "N/A")))
    .divider()
    .code("{ \"uptime\": \"3d 12h\" }", "json")
    .buttons(List.of(List.of(
        new Button("Restart Cache", "restart_cache"),
        new Button("View Logs", "view_logs"))));

manager.send("telegram", reply.toOutbound("telegram", chatId));
// Or get plain text: reply.toPlainText()
```

## Streaming Middleware

Show typing indicators while processing messages (useful for LLM responses):

```java
var streaming = new StreamingMiddleware((channelId, chatId) -> {
    // Send platform-specific typing indicator
    manager.send(channelId, OutboundMessage.builder()
        .chatId(chatId).text("typing...").build());
});
manager.addMiddleware(streaming);
```

Use `StreamingReply` to collect chunked LLM responses:

```java
var chunks = llm.streamChat(message);  // returns Iterator<String>
var reply = new StreamingReply(chunks);
var fullText = reply.collect();
```

## Core Concepts

### ChannelAdapter
Interface for bridging a messaging platform. Implements `connect()`, `disconnect()`, `send()`, and `onMessage()`.

### Middleware
Intercepts inbound messages before they reach your handlers. Chain multiple middleware for access control, command routing, conversation memory, typing indicators, and more.

### ServiceBridge
High-level wrapper that exposes functions as chat commands with auto-generated help.

### HandlerResult
Sealed interface with three variants: `Empty` (consumed/dropped), `TextReply`, `MessageReply`.

### UnifiedMessage / OutboundMessage
Normalized message types with builders for ergonomic construction.

### RichReply
Builder for multi-part messages with text, tables, code blocks, images, and buttons.

### ConversationMemory
Middleware that tracks per-chat conversation history with pluggable storage (in-memory or SQLite).

## Building

```bash
mvn clean package
```

## Testing

```bash
mvn test
```

## Requirements

- Java 17+
- Maven 3.8+

## Also Available In

| Language | Repository |
|----------|-----------|
| Python | [github.com/gambletan/unified-channel](https://github.com/gambletan/unified-channel) |
| TypeScript | [github.com/gambletan/unified-channel-js](https://github.com/gambletan/unified-channel-js) |

## License

MIT
