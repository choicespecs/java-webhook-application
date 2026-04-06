package com.example.webhook.service;

import com.example.webhook.model.WebhookRecord;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.concurrent.*;

@Slf4j
@Service
public class WebhookQueue {

    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {1000, 2000, 4000};

    private final BlockingQueue<WebhookRecord> queue = new LinkedBlockingQueue<>(500);
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final WebhookService webhookService;

    @Autowired
    public WebhookQueue(@Lazy WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostConstruct
    public void startWorkers() {
        for (int i = 0; i < 3; i++) {
            executor.submit(this::workerLoop);
        }
    }

    public void enqueue(WebhookRecord record) {
        if (!queue.offer(record)) {
            log.warn("Queue full, dropping webhook {}", record.getId());
        }
    }

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                WebhookRecord record = queue.take();
                processWithRetry(record);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void processWithRetry(WebhookRecord record) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                if (attempt > 0) {
                    Thread.sleep(BACKOFF_MS[Math.min(attempt - 1, BACKOFF_MS.length - 1)]);
                    record.setRetryCount(attempt);
                }
                webhookService.processRecord(record);
                return;
            } catch (Exception e) {
                log.warn("Attempt {} failed for webhook {}: {}", attempt + 1, record.getId(), e.getMessage());
                if (attempt == MAX_RETRIES) {
                    record.setStatus("FAILED");
                    record.setErrorMessage("Max retries exceeded: " + e.getMessage());
                    webhookService.processRecord(record);
                }
            }
        }
    }

    public int getQueueSize() {
        return queue.size();
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
