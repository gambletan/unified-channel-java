package io.github.gambletan.unifiedchannel;

import io.github.gambletan.unifiedchannel.middleware.I18nMiddleware;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class I18nTest {

    private static final Map<String, Map<String, String>> TRANSLATIONS = Map.of(
            "en", Map.of("greeting", "Hello", "rate_limited", "Too fast!", "help", "Need help?"),
            "zh", Map.of("greeting", "你好", "rate_limited", "太快了！", "help", "需要帮助？"),
            "ja", Map.of("greeting", "こんにちは", "rate_limited", "速すぎます！")
    );

    private static UnifiedMessage msg(String senderId) {
        return UnifiedMessage.builder()
                .channelId("test")
                .sender(new Identity(senderId))
                .chatId("chat1")
                .content(MessageContent.text("hello"))
                .build();
    }

    private static final Handler PASS_THROUGH = message ->
            CompletableFuture.completedFuture(HandlerResult.text("passed"));

    @Test
    void usesDefaultLocaleWhenNoDetection() throws Exception {
        var mw = new I18nMiddleware(TRANSLATIONS, "en");

        Handler capture = message -> {
            assertEquals("en", I18nMiddleware.locale());
            assertEquals("Hello", I18nMiddleware.t("greeting"));
            return CompletableFuture.completedFuture(HandlerResult.text("ok"));
        };

        mw.process(msg("user1"), capture).get();
    }

    @Test
    void usesCustomDetectFn() throws Exception {
        var mw = new I18nMiddleware(TRANSLATIONS, "en", _msg -> "zh");

        Handler capture = message -> {
            assertEquals("zh", I18nMiddleware.locale());
            assertEquals("你好", I18nMiddleware.t("greeting"));
            assertEquals("太快了！", I18nMiddleware.t("rate_limited"));
            return CompletableFuture.completedFuture(HandlerResult.text("ok"));
        };

        mw.process(msg("user1"), capture).get();
    }

    @Test
    void fallsBackToDefaultForMissingKeys() throws Exception {
        var mw = new I18nMiddleware(TRANSLATIONS, "en", _msg -> "ja");

        Handler capture = message -> {
            assertEquals("ja", I18nMiddleware.locale());
            assertEquals("こんにちは", I18nMiddleware.t("greeting"));
            // "help" not in ja, should fall back to en
            assertEquals("Need help?", I18nMiddleware.t("help"));
            return CompletableFuture.completedFuture(HandlerResult.text("ok"));
        };

        mw.process(msg("user1"), capture).get();
    }

    @Test
    void returnsKeyWhenNoTranslation() throws Exception {
        var mw = new I18nMiddleware(TRANSLATIONS);

        Handler capture = message -> {
            assertEquals("nonexistent_key", I18nMiddleware.t("nonexistent_key"));
            return CompletableFuture.completedFuture(HandlerResult.text("ok"));
        };

        mw.process(msg("user1"), capture).get();
    }

    @Test
    void returnsExplicitFallback() throws Exception {
        var mw = new I18nMiddleware(TRANSLATIONS);

        Handler capture = message -> {
            assertEquals("default text", I18nMiddleware.t("missing", "default text"));
            return CompletableFuture.completedFuture(HandlerResult.text("ok"));
        };

        mw.process(msg("user1"), capture).get();
    }

    @Test
    void fallsBackWhenDetectFnReturnsUnknownLocale() throws Exception {
        var mw = new I18nMiddleware(TRANSLATIONS, "en", _msg -> "fr");

        Handler capture = message -> {
            assertEquals("en", I18nMiddleware.locale());
            assertEquals("Hello", I18nMiddleware.t("greeting"));
            return CompletableFuture.completedFuture(HandlerResult.text("ok"));
        };

        mw.process(msg("user1"), capture).get();
    }

    @Test
    void cleansUpContextAfterProcessing() throws Exception {
        var mw = new I18nMiddleware(TRANSLATIONS, "en", _msg -> "zh");

        mw.process(msg("user1"), PASS_THROUGH).get();

        // After processing, context should be cleaned up
        assertNull(I18nMiddleware.locale());
        // t() without context should return the key itself
        assertEquals("greeting", I18nMiddleware.t("greeting"));
    }

    @Test
    void passesResultThroughFromNextHandler() throws Exception {
        var mw = new I18nMiddleware(TRANSLATIONS);

        var result = mw.process(msg("user1"), PASS_THROUGH).get();
        assertInstanceOf(HandlerResult.TextReply.class, result);
        assertEquals("passed", ((HandlerResult.TextReply) result).text());
    }
}
