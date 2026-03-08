# Contributing to unified-channel-java

Thanks for your interest in contributing! This document covers the basics.

## Getting Started

1. Fork the repository
2. Clone your fork: `git clone https://github.com/<your-user>/unified-channel-java.git`
3. Create a branch: `git checkout -b my-feature`
4. Make your changes
5. Run tests: `mvn clean test`
6. Commit and push
7. Open a pull request

## Requirements

- Java 17+
- Maven 3.8+

## Code Style

- Follow standard Java conventions
- Keep adapters self-contained: prefer `java.net.http` over heavy third-party dependencies
- Use `java.util.logging` (via `AbstractAdapter.log`) for adapter logging
- Add Javadoc for public classes and methods

## Adding a New Adapter

1. Create a class in `src/main/java/.../adapters/` extending `AbstractAdapter`
2. Implement `channelId()`, `connect()`, `disconnect()`, and `send()`
3. Call `emit(msg)` when inbound messages arrive
4. Add tests in `src/test/java/.../`
5. Update the supported channels table in `README.md`

## Running Tests

```bash
mvn clean test
```

## Commit Messages

Use concise, action-oriented messages:

```
feat: add LINE adapter with webhook support
fix: handle null text in Telegram updates
docs: update supported channels table
```

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
