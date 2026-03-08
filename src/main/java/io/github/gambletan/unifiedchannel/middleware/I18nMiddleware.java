package io.github.gambletan.unifiedchannel.middleware;

import io.github.gambletan.unifiedchannel.Handler;
import io.github.gambletan.unifiedchannel.HandlerResult;
import io.github.gambletan.unifiedchannel.Middleware;
import io.github.gambletan.unifiedchannel.UnifiedMessage;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Middleware that detects the user's locale and provides a translation function.
 * <p>
 * Because {@link UnifiedMessage} is immutable, the resolved locale and translate
 * function are stored in a {@link ThreadLocal} context accessible via
 * {@link #locale()} and {@link #t(String)} / {@link #t(String, String)}.
 * <p>
 * Detection order (default):
 * <ol>
 *   <li>Custom {@code detectFn} result (if provided)</li>
 *   <li>{@code defaultLocale} (defaults to "en")</li>
 * </ol>
 */
public final class I18nMiddleware implements Middleware {

    /**
     * Thread-local context holding the current locale and translate function.
     */
    private static final ThreadLocal<I18nContext> CONTEXT = new ThreadLocal<>();

    private final Map<String, Map<String, String>> translations;
    private final String defaultLocale;
    private final Function<UnifiedMessage, String> detectFn;

    /**
     * @param translations map of locale -> (key -> translated string)
     * @param defaultLocale fallback locale when detection fails
     * @param detectFn custom locale detection function; may return null
     */
    public I18nMiddleware(
            Map<String, Map<String, String>> translations,
            String defaultLocale,
            Function<UnifiedMessage, String> detectFn
    ) {
        this.translations = Objects.requireNonNull(translations, "translations required");
        this.defaultLocale = defaultLocale != null ? defaultLocale : "en";
        this.detectFn = detectFn;
    }

    public I18nMiddleware(Map<String, Map<String, String>> translations, String defaultLocale) {
        this(translations, defaultLocale, null);
    }

    public I18nMiddleware(Map<String, Map<String, String>> translations) {
        this(translations, "en", null);
    }

    /** Get the current locale for the message being processed (thread-local). */
    public static String locale() {
        var ctx = CONTEXT.get();
        return ctx != null ? ctx.locale : null;
    }

    /** Translate a key using the current locale context. Returns the key if not found. */
    public static String t(String key) {
        return t(key, null);
    }

    /** Translate a key with an explicit fallback. */
    public static String t(String key, String fallback) {
        var ctx = CONTEXT.get();
        if (ctx == null) {
            return fallback != null ? fallback : key;
        }
        return ctx.translate(key, fallback);
    }

    private String resolveLocale(UnifiedMessage message) {
        if (detectFn != null) {
            var detected = detectFn.apply(message);
            if (detected != null && translations.containsKey(detected)) {
                return detected;
            }
        }
        return defaultLocale;
    }

    @Override
    public CompletableFuture<HandlerResult> process(UnifiedMessage message, Handler next) {
        var locale = resolveLocale(message);
        var ctx = new I18nContext(locale, translations, defaultLocale);
        CONTEXT.set(ctx);
        try {
            return next.handle(message).whenComplete((result, ex) -> CONTEXT.remove());
        } catch (Exception e) {
            CONTEXT.remove();
            throw e;
        }
    }

    /** Internal context holding locale and translations for the current thread. */
    private record I18nContext(
            String locale,
            Map<String, Map<String, String>> translations,
            String defaultLocale
    ) {
        String translate(String key, String fallback) {
            // Try current locale
            var table = translations.get(locale);
            if (table != null && table.containsKey(key)) {
                return table.get(key);
            }
            // Fall back to default locale
            var defaultTable = translations.get(defaultLocale);
            if (defaultTable != null && defaultTable.containsKey(key)) {
                return defaultTable.get(key);
            }
            return fallback != null ? fallback : key;
        }
    }
}
