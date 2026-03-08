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
│  │ Access  │  │  Command  │  │ Custom   │          │
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
| WhatsApp | Stub | `WhatsAppAdapter` | Cloud API planned |
| Matrix | Stub | `MatrixAdapter` | Client-Server API planned |
| MS Teams | Stub | `MSTeamsAdapter` | Bot Framework planned |
| LINE | Stub | `LineAdapter` | Messaging API planned |
| Feishu | Stub | `FeishuAdapter` | Open API planned |
| Google Chat | Stub | `GoogleChatAdapter` | Chat API planned |
| Nextcloud | Stub | `NextcloudAdapter` | Talk API planned |
| Synology | Stub | `SynologyAdapter` | Chat API planned |
| Zalo | Stub | `ZaloAdapter` | OA API planned |
| Nostr | Stub | `NostrAdapter` | NIP-01 planned |
| BlueBubbles | Stub | `BlueBubblesAdapter` | REST API planned |
| Twitch | Stub | `TwitchAdapter` | IRC/EventSub planned |
| iMessage | Stub | `IMessageAdapter` | AppleScript bridge planned |

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

## Core Concepts

### ChannelAdapter
Interface for bridging a messaging platform. Implements `connect()`, `disconnect()`, `send()`, and `onMessage()`.

### Middleware
Intercepts inbound messages before they reach your handlers. Chain multiple middleware for access control, command routing, logging, rate limiting, etc.

### HandlerResult
Sealed interface with three variants: `Empty` (consumed/dropped), `TextReply`, `MessageReply`.

### UnifiedMessage / OutboundMessage
Normalized message types with builders for ergonomic construction.

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
