# unified-channel-java

A messaging middleware library supporting 18 channels with a unified API. Write your bot logic once, deploy to any platform.

## Architecture

```
┌─────────────────────────────────────────────────┐
│                 ChannelManager                   │
│                                                  │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐      │
│  │ Telegram │  │ Discord  │  │  Slack   │ ...   │
│  │ Adapter  │  │ Adapter  │  │ Adapter  │       │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘      │
│       │              │              │            │
│       └──────────────┴──────────────┘            │
│                      │                           │
│              UnifiedMessage                      │
│                      │                           │
│       ┌──────────────┼──────────────┐            │
│       ▼              ▼              ▼            │
│  ┌─────────┐  ┌───────────┐  ┌──────────┐      │
│  │ Access  │  │  Command  │  │ Custom   │      │
│  │Middleware│  │Middleware │  │Middleware │      │
│  └─────────┘  └───────────┘  └──────────┘      │
│                      │                           │
│                      ▼                           │
│              Message Listeners                   │
└─────────────────────────────────────────────────┘
```

## Quick Start

```java
var manager = new ChannelManager();

// Add channels
manager.addChannel(new TelegramAdapter(System.getenv("TELEGRAM_TOKEN")));
manager.addChannel(new DiscordAdapter(System.getenv("DISCORD_TOKEN")));
manager.addChannel(new IRCAdapter("irc.libera.chat", 6697, "mybot", true, "#mychannel"));

// Add middleware
manager.addMiddleware(new AccessMiddleware(Set.of("admin-user-123")));

var commands = new CommandMiddleware();
commands.registerSync("help", ctx ->
    HandlerResult.text("Available: /help, /status, /ping"));
commands.registerSync("ping", ctx ->
    HandlerResult.text("pong!"));
manager.addMiddleware(commands);

// Handle messages that pass through middleware
manager.onMessage(msg -> {
    System.out.printf("[%s] %s: %s%n",
        msg.channelId(), msg.sender().id(), msg.text());
});

// Connect all channels
manager.run().join();

// Send a message
manager.send("telegram", OutboundMessage.text("123456", "Hello from Java!"));

// Broadcast to all connected channels
manager.broadcast(OutboundMessage.text("general", "System announcement"));
```

## Supported Channels

| Channel | Status | Adapter Class | Dependencies |
|---------|--------|---------------|-------------|
| Telegram | Implemented | `TelegramAdapter` | None (java.net.http) |
| Discord | Implemented | `DiscordAdapter` | None (java.net.http) |
| Slack | Implemented | `SlackAdapter` | None (java.net.http) |
| Mattermost | Implemented | `MattermostAdapter` | None (java.net.http) |
| IRC | Implemented | `IRCAdapter` | None (java.net.Socket) |
| WhatsApp | Stub | `WhatsAppAdapter` | - |
| Matrix | Stub | `MatrixAdapter` | - |
| MS Teams | Stub | `MSTeamsAdapter` | - |
| LINE | Stub | `LineAdapter` | - |
| Feishu | Stub | `FeishuAdapter` | - |
| Google Chat | Stub | `GoogleChatAdapter` | - |
| Nextcloud | Stub | `NextcloudAdapter` | - |
| Synology | Stub | `SynologyAdapter` | - |
| Zalo | Stub | `ZaloAdapter` | - |
| Nostr | Stub | `NostrAdapter` | - |
| BlueBubbles | Stub | `BlueBubblesAdapter` | - |
| Twitch | Stub | `TwitchAdapter` | - |
| iMessage | Stub | `IMessageAdapter` | - |

## Core Concepts

### ChannelAdapter
Interface for bridging a messaging platform. Implements `connect()`, `disconnect()`, `send()`, and `onMessage()`.

### Middleware
Intercepts inbound messages before they reach your listeners. Chain multiple middleware for access control, command routing, logging, rate limiting, etc.

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

## License

MIT
