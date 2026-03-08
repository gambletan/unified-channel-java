package io.github.gambletan.unifiedchannel.queue;

import io.github.gambletan.unifiedchannel.HandlerResult;
import io.github.gambletan.unifiedchannel.UnifiedMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Simple in-memory message queue backed by a LinkedBlockingQueue and a thread pool.
 */
public class InMemoryQueue implements MessageQueue {

    private static final Logger logger = Logger.getLogger(InMemoryQueue.class.getName());

    private final int maxSize;
    private final LinkedBlockingQueue<UnifiedMessage> queue;
    private final ExecutorService executor;
    private final Semaphore concurrencySemaphore;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger inFlight = new AtomicInteger(0);
    private volatile MessageProcessor processor;
    private final List<CompletableFuture<Void>> drainFutures = new CopyOnWriteArrayList<>();
    private Thread dispatchThread;

    public InMemoryQueue(int concurrency, int maxSize) {
        this.maxSize = maxSize;
        this.queue = new LinkedBlockingQueue<>(maxSize);
        this.executor = Executors.newFixedThreadPool(concurrency);
        this.concurrencySemaphore = new Semaphore(concurrency);
    }

    /** Create with defaults: concurrency=5, maxSize=1000. */
    public InMemoryQueue() {
        this(5, 1000);
    }

    @Override
    public boolean enqueue(UnifiedMessage message) {
        return queue.offer(message);
    }

    @Override
    public void onProcess(MessageProcessor processor) {
        this.processor = processor;
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            dispatchThread = new Thread(this::dispatchLoop, "queue-dispatcher");
            dispatchThread.setDaemon(true);
            dispatchThread.start();
        }
    }

    @Override
    public void stop() {
        running.set(false);
        if (dispatchThread != null) {
            dispatchThread.interrupt();
        }
        executor.shutdown();
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public CompletableFuture<Void> drain() {
        if (queue.isEmpty() && inFlight.get() == 0) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        drainFutures.add(future);
        // Check again in case it drained between the check and adding the future
        checkDrain();
        return future;
    }

    private void dispatchLoop() {
        while (running.get()) {
            try {
                UnifiedMessage msg = queue.poll(100, TimeUnit.MILLISECONDS);
                if (msg == null) {
                    checkDrain();
                    continue;
                }
                concurrencySemaphore.acquire();
                inFlight.incrementAndGet();
                executor.submit(() -> processOne(msg));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void processOne(UnifiedMessage msg) {
        try {
            if (processor != null) {
                processor.process(msg).join();
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error processing queued message " + msg.messageId(), e);
        } finally {
            concurrencySemaphore.release();
            inFlight.decrementAndGet();
            checkDrain();
        }
    }

    private void checkDrain() {
        if (queue.isEmpty() && inFlight.get() == 0 && !drainFutures.isEmpty()) {
            List<CompletableFuture<Void>> toResolve = new ArrayList<>(drainFutures);
            drainFutures.clear();
            for (CompletableFuture<Void> f : toResolve) {
                f.complete(null);
            }
        }
    }
}
