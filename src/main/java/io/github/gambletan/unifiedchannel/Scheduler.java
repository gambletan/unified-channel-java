package io.github.gambletan.unifiedchannel;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Scheduler — schedule periodic messages or tasks across channels.
 *
 * <p>Supports three scheduling modes:
 * <ul>
 *   <li>{@link #every} — repeating at a fixed interval</li>
 *   <li>{@link #cron} — triggered by a cron expression (checked every 60 s)</li>
 *   <li>{@link #once} — one-shot delayed execution</li>
 * </ul>
 */
public final class Scheduler {

    private static final Logger LOG = Logger.getLogger(Scheduler.class.getName());
    private static final AtomicInteger COUNTER = new AtomicInteger();

    private final ChannelManager manager;
    private final ScheduledExecutorService executor;
    private final Map<String, ScheduledTaskEntry> tasks = new ConcurrentHashMap<>();

    public Scheduler(ChannelManager manager) {
        this(manager, Executors.newScheduledThreadPool(2));
    }

    public Scheduler(ChannelManager manager, ScheduledExecutorService executor) {
        this.manager = manager;
        this.executor = executor;
    }

    // -- Scheduling methods --

    /** Schedule a repeating task at a fixed interval. */
    public String every(long intervalMs, String channelId, String chatId, Object callback) {
        String id = nextId();
        ScheduledTaskEntry entry = new ScheduledTaskEntry(id, "every", channelId, chatId, intervalMs, callback);
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(
                () -> execute(entry), intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        entry.future = future;
        tasks.put(id, entry);
        return id;
    }

    /** Schedule a task using a cron expression (checked every 60 s). */
    public String cron(String cronExpr, String channelId, String chatId, Object callback) {
        CronSchedule parsed = parseCron(cronExpr);
        String id = nextId();
        ScheduledTaskEntry entry = new ScheduledTaskEntry(id, "cron", channelId, chatId, cronExpr, callback);
        ScheduledFuture<?> future = executor.scheduleAtFixedRate(() -> {
            LocalDateTime now = LocalDateTime.now();
            if (cronMatches(parsed, now)) {
                execute(entry);
            }
        }, 60_000, 60_000, TimeUnit.MILLISECONDS);
        entry.future = future;
        tasks.put(id, entry);
        return id;
    }

    /** Schedule a one-shot delayed task. */
    public String once(long delayMs, String channelId, String chatId, Object callback) {
        String id = nextId();
        ScheduledTaskEntry entry = new ScheduledTaskEntry(id, "once", channelId, chatId, delayMs, callback);
        ScheduledFuture<?> future = executor.schedule(() -> {
            execute(entry);
            entry.active = false;
        }, delayMs, TimeUnit.MILLISECONDS);
        entry.future = future;
        tasks.put(id, entry);
        return id;
    }

    /** Cancel a scheduled task. Returns true if found and cancelled. */
    public boolean cancel(String taskId) {
        ScheduledTaskEntry entry = tasks.remove(taskId);
        if (entry == null) return false;
        entry.active = false;
        if (entry.future != null) {
            entry.future.cancel(false);
        }
        return true;
    }

    /** List all active scheduled tasks. */
    public List<ScheduledTaskInfo> list() {
        return tasks.values().stream()
                .filter(e -> e.active)
                .map(e -> new ScheduledTaskInfo(e.id, e.type, e.channelId, e.chatId, e.schedule, e.active))
                .collect(Collectors.toList());
    }

    /** Stop all scheduled tasks and shut down the executor. */
    public void stop() {
        for (ScheduledTaskEntry entry : tasks.values()) {
            entry.active = false;
            if (entry.future != null) {
                entry.future.cancel(false);
            }
        }
        tasks.clear();
        executor.shutdownNow();
    }

    // -- Cron parsing --

    /** Parsed cron schedule fields. */
    public record CronSchedule(List<Integer> minute, List<Integer> hour,
                                List<Integer> dom, List<Integer> month,
                                List<Integer> dow) {}

    /** Info about a scheduled task (public, without internal handles). */
    public record ScheduledTaskInfo(String id, String type, String channelId,
                                     String chatId, Object schedule, boolean active) {}

    /**
     * Parse "min hour dom month dow". Supports exact values, "*", comma lists.
     */
    public static CronSchedule parseCron(String expr) {
        String[] parts = expr.trim().split("\\s+");
        if (parts.length != 5) {
            throw new IllegalArgumentException(
                    "Invalid cron expression \"" + expr + "\": expected 5 fields");
        }
        return new CronSchedule(
                parseField(parts[0], 0, 59),
                parseField(parts[1], 0, 23),
                parseField(parts[2], 1, 31),
                parseField(parts[3], 1, 12),
                parseField(parts[4], 0, 6));
    }

    /** Check whether a LocalDateTime matches a cron schedule. */
    public static boolean cronMatches(CronSchedule sched, LocalDateTime dt) {
        return sched.minute().contains(dt.getMinute())
                && sched.hour().contains(dt.getHour())
                && sched.dom().contains(dt.getDayOfMonth())
                && sched.month().contains(dt.getMonthValue())
                && sched.dow().contains(dt.getDayOfWeek().getValue() % 7);
    }

    // -- Internal helpers --

    private static List<Integer> parseField(String field, int min, int max) {
        if ("*".equals(field)) {
            return IntStream.rangeClosed(min, max).boxed().collect(Collectors.toList());
        }
        return Arrays.stream(field.split(","))
                .map(String::trim)
                .map(v -> {
                    int n = Integer.parseInt(v);
                    if (n < min || n > max) {
                        throw new IllegalArgumentException(
                                "Invalid cron field value \"" + v + "\" (expected " + min + "-" + max + ")");
                    }
                    return n;
                })
                .collect(Collectors.toList());
    }

    private static String nextId() {
        return "task_" + COUNTER.incrementAndGet();
    }

    @SuppressWarnings("unchecked")
    private void execute(ScheduledTaskEntry entry) {
        if (!entry.active) return;
        try {
            String text;
            if (entry.callback instanceof String s) {
                text = s;
            } else if (entry.callback instanceof Supplier<?> supplier) {
                Object result = supplier.get();
                text = result != null ? result.toString() : "";
            } else {
                text = entry.callback.toString();
            }
            manager.send(entry.channelId, OutboundMessage.text(entry.chatId, text))
                    .exceptionally(ex -> {
                        LOG.log(Level.WARNING, "Scheduler task " + entry.id + " send failed", ex);
                        return null;
                    });
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Scheduler task " + entry.id + " failed", e);
        }
    }

    /** Internal mutable entry. */
    private static final class ScheduledTaskEntry {
        final String id;
        final String type;
        final String channelId;
        final String chatId;
        final Object schedule;
        final Object callback;
        volatile boolean active = true;
        volatile ScheduledFuture<?> future;

        ScheduledTaskEntry(String id, String type, String channelId, String chatId,
                           Object schedule, Object callback) {
            this.id = id;
            this.type = type;
            this.channelId = channelId;
            this.chatId = chatId;
            this.schedule = schedule;
            this.callback = callback;
        }
    }
}
