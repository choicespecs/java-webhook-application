# Architecture & Design Documentation

This document covers the application's internal design, the rationale behind each architectural decision, and a deep dive into webhook concepts as they apply to this codebase.

---

## Table of Contents

1. [What Webhooks Are and Why They Exist](#1-what-webhooks-are-and-why-they-exist)
2. [Full Application Request Flow](#2-full-application-request-flow)
3. [Component Design and Rationale](#3-component-design-and-rationale)
4. [Signature Validation — Security Model](#4-signature-validation--security-model)
5. [The Queue and Why Async Matters](#5-the-queue-and-why-async-matters)
6. [Retry Logic and Exponential Backoff](#6-retry-logic-and-exponential-backoff)
7. [Real-time Updates via WebSockets](#7-real-time-updates-via-websockets)
8. [In-Memory Storage — Trade-offs](#8-in-memory-storage--trade-offs)
9. [Data Model Design](#9-data-model-design)
10. [Circular Dependency Resolution](#10-circular-dependency-resolution)
11. [Configuration Reference](#11-configuration-reference)

---

## 1. What Webhooks Are and Why They Exist

### The Problem with Polling

Before webhooks, the dominant pattern for integrating two systems was **polling**: your application repeatedly asks a remote API "did anything change?" on a fixed interval.

```
Your App                   Remote Service
   |                            |
   |--- GET /orders/new? ------>|
   |<-- [] (nothing yet) -------|
   |        (wait 30s)          |
   |--- GET /orders/new? ------>|
   |<-- [] (still nothing) -----|
   |        (wait 30s)          |
   |--- GET /orders/new? ------>|
   |<-- [{ order: 123 }] -------|   ← finally, an event
```

Polling has three compounding problems:

- **Latency**: If you poll every 30 seconds, events are on average 15 seconds stale.
- **Waste**: 99% of requests return nothing. You pay for every one of them in CPU, bandwidth, and API rate-limit quota.
- **Scale**: With 1,000 customers, you make 1,000 × (24 × 60 × 2) = ~2.9 million calls per day for each data type you track.

### The Webhook Model

A webhook inverts the relationship. Instead of you asking the remote service, the remote service calls you the moment something happens.

```
Remote Service             Your App
   |                            |
   |  [payment succeeds]        |
   |                            |
   |--- POST /api/webhook/receive ---->|
   |<-- HTTP 202 Accepted ------------|
   |                            |
   |  [done — no follow-up needed]    |
```

The remote service sends one HTTP POST. You return 202 immediately to acknowledge receipt. Processing happens asynchronously in the background. The result:

- **Near-instant delivery**: Events arrive within milliseconds of occurring.
- **No wasted calls**: You only receive traffic when something actually happens.
- **Simple integration**: The remote service needs only your endpoint URL and a shared secret.

### When Webhooks Are the Right Choice

Webhooks are ideal when:
- Events are relatively infrequent (orders, payments, user signups — not sensor telemetry)
- Low latency matters more than guaranteed delivery ordering
- The source system supports them

They are less ideal when:
- You need strict message ordering (consider a message queue like Kafka instead)
- The source system is inside a firewall you control (use internal eventing)
- You need replay of past events on demand (webhooks are fire-and-forget)

---

## 2. Full Application Request Flow

### Happy Path (no errors, signature present)

```
Browser / curl
    │
    │  POST /api/webhook/receive
    │  Content-Type: application/json
    │  X-Webhook-Signature: sha256=abc123...
    │  Body: { eventType, eventId, timestamp, data }
    │
    ▼
WebhookController.receive()
    │  @Valid validates payload fields (not-blank, not-null)
    │  Reads X-Webhook-Signature header (optional)
    │
    ▼
WebhookService.receive()
    │  If signature header present → validateSignature()
    │      Computes HMAC-SHA256(body, secret)
    │      Compares with provided signature
    │      Throws IllegalArgumentException on mismatch (→ HTTP 400)
    │
    │  Creates WebhookRecord { id=UUID, status=QUEUED, receivedAt=now }
    │
    ▼
WebhookHistoryService.addRecord()
    │  Prepends record to ConcurrentLinkedDeque
    │  Evicts oldest if size > 1000
    │
    ▼
WebhookQueue.enqueue()
    │  Offers record to LinkedBlockingQueue (capacity 500)
    │  Logs warning and drops if queue full
    │
    ▼
WebhookController ← returns HTTP 202 Accepted
    │  { status: "QUEUED", receivedEventId: "...", processedAt: now }
    │
    ▼  ← response sent to caller; processing continues independently

SimpMessagingTemplate.convertAndSend("/topic/stats")
SimpMessagingTemplate.convertAndSend("/topic/history")
    │  All connected browser tabs receive updated stats + history snapshot
    │
    ▼  [background — one of 3 worker threads]

WebhookQueue worker thread
    │  Blocks on queue.take() until a record arrives
    │
    ▼
WebhookService.processRecord()
    │  Sets status = PROCESSING, calls historyService.updateRecord()
    │  Broadcasts updated state over WebSocket
    │
    │  Simulates processing (Thread.sleep 100ms)
    │  In a real system: call downstream APIs, write to DB, send emails, etc.
    │
    │  Sets status = SUCCESS, processedAt = now
    │  Calls historyService.updateRecord()
    │  Broadcasts final state over WebSocket
    │
    ▼
Done — record is now SUCCESS in history
```

### Error Path (processing throws an exception)

```
WebhookQueue.processWithRetry()
    │
    ├── Attempt 1 fails  →  wait 1 second
    ├── Attempt 2 fails  →  wait 2 seconds
    ├── Attempt 3 fails  →  wait 4 seconds
    └── Attempt 4 fails  →  status = FAILED, errorMessage set
                             historyService.updateRecord()
                             WebSocket broadcast
```

### Signature Validation Failure Path

```
WebhookService.receive()
    │
    └── validateSignature() returns false
        → throws IllegalArgumentException("Invalid webhook signature")
        → Spring maps to HTTP 400 Bad Request
        → No record created, nothing enqueued
```

### Validation Failure Path (malformed payload)

```
WebhookController.receive()
    │
    └── @Valid fails (missing eventType, eventId, or timestamp)
        → MethodArgumentNotValidException
        → Spring maps to HTTP 400 Bad Request
        → No record created
```

---

## 3. Component Design and Rationale

### Layer Separation

The application follows a standard three-layer architecture:

```
┌──────────────────────────────────────┐
│  Controllers (HTTP boundary)         │  WebhookController, StatsController,
│  - Input parsing and validation      │  WebhookUIController
│  - HTTP status code selection        │
│  - No business logic                 │
├──────────────────────────────────────┤
│  Services (business logic)           │  WebhookService, WebhookQueue,
│  - Signature verification            │  WebhookHistoryService
│  - Queue management                  │
│  - Processing and retry              │
│  - WebSocket broadcasting            │
├──────────────────────────────────────┤
│  Models (data structures)            │  WebhookPayload, WebhookRecord,
│  - No behavior                       │  WebhookResponse
│  - Annotated for validation / JSON   │
└──────────────────────────────────────┘
```

Controllers know nothing about HMAC or queues. Services know nothing about HTTP status codes. This makes each layer independently testable and replaceable.

### Why Two Controllers for the Same Domain?

`WebhookController` handles the REST API (`/api/webhook/*`). `WebhookUIController` handles page routes (`/`, `/history`, `/test`, `/learn`). Splitting them means:

- REST endpoints can evolve independently of UI routes (different versioning, different security rules)
- A future migration to a separate frontend (React, etc.) only changes the UI controller or removes it entirely
- The REST controller stays focused on JSON in / JSON out

### Why a Separate `StatsController`?

`StatsController` at `/api/stats` returns everything from `WebhookHistoryService.getStats()` plus the live queue size from `WebhookQueue`. This data crosses two service boundaries, so it lives in its own controller rather than bolting onto either existing one. It is also the endpoint the dashboard fetches on initial load, before WebSocket delivers live updates.

---

## 4. Signature Validation — Security Model

### Why Signatures Are Necessary

Any process on the internet can POST to `http://your-app.com/api/webhook/receive`. Without verification, an attacker can:

- Send fake events to trigger unintended business logic (e.g., fake "payment succeeded")
- Flood the queue with garbage data
- Probe your system's behavior through crafted payloads

A shared HMAC secret solves this by making the signature unforgeable without knowledge of the secret.

### How HMAC-SHA256 Works Here

```
Sender:
  json    = serialize(payload)
  sig     = HMAC-SHA256(key=sharedSecret, message=json)
  header  = "sha256=" + hex(sig)

Receiver (WebhookService.validateSignature):
  expected = HMAC-SHA256(key=sharedSecret, message=rawBody)
  valid    = timingSafeEquals(expected, provided)   ← important: constant-time
```

The implementation in `WebhookService.java`:

```java
Mac mac = Mac.getInstance("HmacSHA256");
mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
byte[] hash = mac.doFinal(json.getBytes(StandardCharsets.UTF_8));
return "sha256=" + HexFormat.of().formatHex(hash);
```

### Why HMAC and Not a Simple API Key?

An API key in a header is static — if it leaks, every past and future request is compromised. HMAC ties the signature to the exact payload content. Replaying a captured request sends an identical signature, but changing even one byte of the payload produces a completely different signature, so attackers cannot tamper with the content.

### The Optionality Trade-off

This application validates the signature only when the `X-Webhook-Signature` header is present. This is intentional for a learning tool — it lets you experiment without needing to pre-compute signatures. In production, validation should always be enforced: reject any request that omits the header.

### Where to Keep the Secret

The secret lives in `application.properties` as `webhook.secret`. For production:

- Inject it via an environment variable: `WEBHOOK_SECRET=...`
- Or use a secrets manager (AWS Secrets Manager, Vault, etc.)
- Never commit a real secret to version control

---

## 5. The Queue and Why Async Matters

### The Core Problem

The source system (Stripe, GitHub, etc.) expects a response within a few seconds. If your handler does anything slow — a database write, an email send, a downstream API call — you risk timing out. The source treats a timeout as a delivery failure and retries. Now you have duplicate events.

The solution is the **receive-acknowledge-process** pattern:

1. Receive the webhook
2. Validate the signature
3. Persist a minimal record
4. Return HTTP 202 immediately
5. Process asynchronously

### Implementation: `LinkedBlockingQueue`

```java
private final BlockingQueue<WebhookRecord> queue = new LinkedBlockingQueue<>(500);
```

`LinkedBlockingQueue` is thread-safe, blocks workers when empty (no busy-waiting), and blocks producers when full. The capacity of 500 acts as backpressure — if the workers fall behind, `queue.offer()` returns false and the record is dropped with a warning log rather than OOM-ing the JVM.

### Implementation: Thread Pool

```java
private final ExecutorService executor = Executors.newFixedThreadPool(3);
```

Three fixed threads are a deliberate choice:

- **Fixed, not cached**: A cached pool (`newCachedThreadPool`) would spin up a thread per webhook under burst load. With hundreds of simultaneous webhooks, you get hundreds of threads — context-switch overhead dominates and throughput falls.
- **Three threads**: Enough parallelism to handle bursts without the overhead of a large pool. Tune this based on what processing actually does (I/O-bound work can support more threads than CPU-bound work).

### Why Not Spring `@Async`?

`@Async` is convenient but hides queue depth — there is no built-in backpressure. `LinkedBlockingQueue` with an explicit bound makes the trade-off visible: when the queue fills up, you see it immediately in the warning log and the queue size stat.

---

## 6. Retry Logic and Exponential Backoff

### Why Retry At All?

Transient failures are common: a downstream database hiccups, a network call times out briefly, a dependent service momentarily restarts. Treating all failures as permanent and immediately marking records `FAILED` would produce unnecessary alerts and require manual reprocessing.

### Why Exponential Backoff?

If 50 webhooks all fail simultaneously and all retry immediately, you generate a burst of 50 concurrent retries against an already-struggling service — making things worse. Spacing retries with increasing delays (1s, 2s, 4s) spreads the load and gives the downstream system time to recover.

The schedule in this app:

```
Attempt 1: immediate
Attempt 2: wait 1 second   (BACKOFF_MS[0])
Attempt 3: wait 2 seconds  (BACKOFF_MS[1])
Attempt 4: wait 4 seconds  (BACKOFF_MS[2])  → FAILED if still failing
```

Total time before giving up: ~7 seconds. This is intentionally short for a learning tool. Production systems might back off to minutes or hours for certain event types.

### Why a Cap?

Retrying indefinitely ties up worker threads. A webhook that will never succeed (e.g., referencing a deleted resource) should not block a thread forever. After `MAX_RETRIES = 3`, the record is marked `FAILED` and stored in history for inspection. A human or a separate recovery process can decide what to do with it.

### The Retry Loop in Detail

```java
private void processWithRetry(WebhookRecord record) {
    for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
        try {
            if (attempt > 0) {
                Thread.sleep(BACKOFF_MS[Math.min(attempt - 1, BACKOFF_MS.length - 1)]);
                record.setRetryCount(attempt);
            }
            webhookService.processRecord(record);
            return;  // success — stop
        } catch (Exception e) {
            if (attempt == MAX_RETRIES) {
                record.setStatus("FAILED");
                record.setErrorMessage("Max retries exceeded: " + e.getMessage());
                webhookService.processRecord(record);  // persist final state
            }
        }
    }
}
```

On each failed attempt, the record's `retryCount` is updated and persisted, so the history view reflects live progress rather than only the final outcome.

---

## 7. Real-time Updates via WebSockets

### Why Not Poll the History API?

The dashboard could just call `GET /api/webhook/history` every second. The problem: with many users, that is a constant stream of HTTP requests hitting the server even when nothing has changed — the same polling anti-pattern webhooks are designed to avoid.

### STOMP over SockJS

This app uses **STOMP** (Simple Text Oriented Messaging Protocol) over **SockJS**. STOMP provides a minimal pub/sub model on top of a raw WebSocket connection. SockJS provides a graceful fallback to long-polling for environments where WebSockets are blocked.

```
Browser                        Spring WebSocket Broker
   │                                    │
   │── CONNECT (/ws) ─────────────────>│  SockJS handshake
   │── SUBSCRIBE /topic/stats ────────>│  Register interest
   │── SUBSCRIBE /topic/history ──────>│  Register interest
   │                                    │
   │         [webhook arrives at server]│
   │                                    │
   │<─ MESSAGE /topic/stats ───────────│  Updated counts
   │<─ MESSAGE /topic/history ─────────│  Updated records (last 50)
```

### What Gets Broadcast and When

`WebhookService.broadcastUpdate()` is called at two moments:

1. **On receipt** — after the record is queued (status: `QUEUED`). The dashboard shows the record appears immediately.
2. **On each state change during processing** — when status transitions to `PROCESSING`, then `SUCCESS` or `FAILED`. The dashboard shows the status badge update in place.

Broadcasting the full history snapshot (last 50 records) on each update is simple but not maximally efficient — a delta update would use less bandwidth. For a learning tool with low traffic, the simplicity is worth it. For high-throughput production, send only the changed record.

### WebSocket Configuration

```java
// WebSocketConfig.java
config.enableSimpleBroker("/topic");          // in-memory broker
config.setApplicationDestinationPrefixes("/app"); // for client→server messages
registry.addEndpoint("/ws").withSockJS();     // SockJS endpoint
```

The simple in-memory broker is fine for a single-node application. For multi-node deployments, replace it with a full message broker (RabbitMQ or Redis pub/sub) so all nodes can broadcast to all connected clients.

---

## 8. In-Memory Storage — Trade-offs

### What Is Stored

`WebhookHistoryService` holds a `ConcurrentLinkedDeque<WebhookRecord>` capped at 1,000 records. Newest records are prepended; oldest are evicted when the cap is hit.

### Why `ConcurrentLinkedDeque`?

Multiple worker threads update records concurrently (status changes during processing). `ConcurrentLinkedDeque` provides thread-safe reads and writes without explicit synchronization. `ArrayList` or `LinkedList` would require external locks and would be prone to `ConcurrentModificationException`.

### Why In-Memory?

- Zero configuration — no database setup, no migrations, no connection pools
- Instant reads — no I/O latency on the stats and history endpoints
- Appropriate scope — this is a learning and debugging tool, not a system of record

### The Consequences

- **Data is lost on restart.** Every `mvn spring-boot:run` starts fresh.
- **Single-node only.** A second instance of the app would have its own independent history.
- **No queries beyond recency.** You can retrieve the last N records; you cannot query by date range or event type without scanning all records.

For a production system that needs durability, replace `WebhookHistoryService` with a JPA repository backed by PostgreSQL or a time-series store. The service interface would not change — only the implementation.

---

## 9. Data Model Design

### `WebhookPayload` — Inbound

```java
@NotBlank  String eventType   // "order.created", "payment.processed", etc.
@NotBlank  String eventId     // Idempotency key — unique per event at the source
@NotNull   LocalDateTime timestamp  // When the event occurred at the source
           Map<String, Object> data // Open-ended event-specific fields
```

`eventId` is the most important field for production webhook handling. If the source retries a delivery, the same `eventId` arrives twice. Your handler should check if it has already processed this ID and skip re-processing — this is called **idempotent handling**. This app stores `eventId` in history but does not deduplicate, because demonstrating idempotency is outside its scope.

### `WebhookRecord` — Internal

```java
String id           // App-assigned UUID — separate from eventId
String eventType
String eventId
LocalDateTime receivedAt   // When this app received the HTTP request
LocalDateTime processedAt  // When processing completed (null until then)
String status       // QUEUED | PROCESSING | SUCCESS | FAILED
String errorMessage // Null unless FAILED
WebhookPayload payload     // Full original payload retained for debugging
int retryCount      // How many retries were needed (0 = first attempt succeeded)
```

`id` and `eventId` are deliberately separate. `eventId` is assigned by the source and may be any string. `id` is a UUID assigned by this app so records can be reliably referenced internally regardless of what the source sends as `eventId`.

### `WebhookResponse` — Outbound

```java
String status           // Always "QUEUED" on success
String message          // Human-readable summary
String receivedEventId  // Echoes back the eventId from the payload
LocalDateTime processedAt // Time of acknowledgment (not processing completion)
```

The response returns `receivedEventId` (not the internal `id`) because the caller already knows their `eventId` and should use it for their own tracking. The internal `id` is an implementation detail.

---

## 10. Circular Dependency Resolution

### The Problem

`WebhookService` needs `WebhookQueue` (to enqueue records). `WebhookQueue` needs `WebhookService` (to call `processRecord` on worker threads). Spring cannot construct either bean first.

### Why This Dependency Structure Exists

Both services are closely related by responsibility. `WebhookService` owns the business logic for both receipt and processing; `WebhookQueue` owns the threading and retry mechanics. Merging them into a single class would create a god object. Splitting them creates the cycle.

### The Solution: `@Lazy`

```java
// WebhookQueue.java
@Autowired
public WebhookQueue(@Lazy WebhookService webhookService) {
    this.webhookService = webhookService;
}
```

`@Lazy` tells Spring to inject a proxy for `WebhookService` rather than the actual bean at construction time. The real bean is resolved on the first method call, by which point both beans are fully initialized. This breaks the construction-time cycle without restructuring the class responsibilities.

An alternative would be to extract `processRecord` into a third service (e.g., `WebhookProcessor`) that neither of the existing services depends on. That is cleaner architecturally but adds a class for what is currently a one-method concern.

---

## 11. Configuration Reference

All tunable values are in `src/main/resources/application.properties`.

| Property | Default | Effect |
|---|---|---|
| `server.port` | `8080` | HTTP port |
| `webhook.secret` | `your-secret-key-here-change-in-production` | HMAC-SHA256 signing key |
| `logging.level.com.example.webhook` | `INFO` | Set to `DEBUG` for verbose output |
| `logging.level.org.springframework.web` | *(not set)* | Set to `DEBUG` to log every request |

Values hardcoded in source (change by editing the class):

| Location | Constant | Default | Effect |
|---|---|---|---|
| `WebhookQueue.java` | `MAX_RETRIES` | `3` | Total retry attempts after first failure |
| `WebhookQueue.java` | `BACKOFF_MS` | `{1000, 2000, 4000}` | Wait time before each retry (ms) |
| `WebhookQueue.java` | Queue capacity | `500` | Max queued-but-unprocessed records |
| `WebhookQueue.java` | Thread pool size | `3` | Concurrent processing workers |
| `WebhookHistoryService.java` | `MAX_HISTORY_SIZE` | `1000` | Max records retained in memory |
