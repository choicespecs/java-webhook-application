# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

A **Webhook Simulator Web Application** — an educational tool for learning, testing, and debugging webhooks. Provides a REST API, a Thymeleaf web interface, and an interactive two-application demo that simulates a complete webhook lifecycle: source app fires event → target app processes it → target posts callback → source app acts on result. All updates are pushed to connected browsers via WebSockets.

## Build & Run Commands

```bash
mvn spring-boot:run            # Run the application (http://localhost:8080)
mvn clean install              # Build and run all tests
mvn test                       # Run all tests
mvn test -Dtest=ClassName      # Run a single test class
mvn clean package              # Build production JAR
java -jar target/webhook-simulator-1.0.0.jar
```

## Tech Stack

- **Java 17**, **Spring Boot 3.1.5**, **Maven**
- **Spring WebSocket** (STOMP/SockJS) — real-time updates on `/topic/stats`, `/topic/history`, `/topic/source`, `/topic/flow`
- **Spring Web** + **RestTemplate** — internal HTTP calls between the two simulated apps
- **Thymeleaf** — server-side templating (use `th:inline="none"` on `<script>` tags that contain `[[...]]` syntax)
- **Lombok**, **Jackson**, **Spring Validation**
- **Bootstrap 5**, **jQuery**, **DataTables**, **SockJS + STOMP.js** (frontend via CDN)
- **Spring DevTools** — hot reload in development

## Architecture

```
src/main/java/com/example/webhook/
├── WebhookApplication.java
├── config/
│   ├── AppConfig.java            # RestTemplate bean
│   └── WebSocketConfig.java      # STOMP broker: /topic, endpoint /ws
├── controller/
│   ├── WebhookController.java    # REST: /api/webhook/* (target app)
│   ├── SourceAppController.java  # REST: /api/source/* (source app demo)
│   ├── StatsController.java      # REST: /api/stats
│   └── WebhookUIController.java  # Pages: /, /history, /test, /learn, /demo
├── service/
│   ├── WebhookService.java       # Core logic: signature validation, processRecord(), callback dispatch
│   ├── WebhookQueue.java         # LinkedBlockingQueue (cap 500) + 3-thread pool + retry/backoff
│   ├── WebhookHistoryService.java # ConcurrentLinkedDeque (cap 1000)
│   └── SourceAppService.java     # Source-side event state, outbound webhook HTTP, callback handling
├── model/
│   ├── WebhookPayload.java       # Inbound webhook (@Valid)
│   ├── WebhookRecord.java        # Internal processing record
│   ├── WebhookResponse.java      # API response
│   ├── SourceEvent.java          # Source app event state
│   ├── CallbackPayload.java      # Callback from target → source
│   └── FlowStep.java             # WebSocket message driving demo animation
└── templates/
    ├── dashboard.html            # Real-time stats + send form
    ├── demo.html                 # Two-app flow visualization
    ├── history.html              # DataTables history view
    ├── test.html                 # Manual webhook sender
    └── learn.html                # Webhook concepts guide
```

## Key Request Flows

**Standard webhook receipt:**
`POST /api/webhook/receive` → `WebhookService.receive()` (validate signature) → `WebhookHistoryService.addRecord()` → `WebhookQueue.enqueue()` → HTTP 202 → broadcast `/topic/stats` + `/topic/history` + `/topic/flow`

**Async processing (worker thread):**
`WebhookQueue` worker → `WebhookService.processRecord()` (PROCESSING → SUCCESS/FAILED) → `WebhookService.sendCallback()` → `POST /api/source/callback` → broadcast `/topic/flow`

**Demo two-app flow:**
`POST /api/source/trigger` → `SourceAppService.triggerEvent()` → `RestTemplate POST /api/webhook/receive` → *(standard receipt flow)* → `RestTemplate POST /api/source/callback` → `SourceAppService.handleCallback()` → broadcast `/topic/source` + `/topic/flow`

## WebSocket Topics

| Topic | Published by | Payload |
|-------|-------------|---------|
| `/topic/stats` | `WebhookService` | `Map<String,Object>` — counts by status/eventType |
| `/topic/history` | `WebhookService` | `List<WebhookRecord>` — last 50 |
| `/topic/source` | `SourceAppService` | `List<SourceEvent>` — source app state |
| `/topic/flow` | `WebhookService`, `SourceAppService` | `FlowStep` — drives demo animation |

## Circular Dependency

`WebhookService` → `WebhookQueue` → `WebhookService`. Resolved with `@Lazy` on the `WebhookService` parameter in `WebhookQueue`'s constructor.

## Configuration

```properties
server.port=8080
webhook.secret=your-secret-key-here-change-in-production
webhook.receiver.url=http://localhost:8080/api/webhook/receive
source.app.callback-url=http://localhost:8080/api/source/callback
```

Key constants in source (edit the class to change):

| File | Constant | Default |
|------|----------|---------|
| `WebhookQueue.java` | `MAX_RETRIES` | `3` |
| `WebhookQueue.java` | `BACKOFF_MS` | `{1000, 2000, 4000}` ms |
| `WebhookQueue.java` | Queue capacity | `500` |
| `WebhookQueue.java` | Thread pool size | `3` |
| `WebhookHistoryService.java` | `MAX_HISTORY_SIZE` | `1000` |
| `SourceAppService.java` | `MAX_EVENTS` | `30` |

## Documentation

- `docs/ARCHITECTURE.md` — design rationale, trade-off explanations, component decisions
- `docs/TWO_APP_FLOW.md` — the source→target→callback pattern in depth
