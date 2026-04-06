# Webhook Simulator

A Spring Boot web application for learning, testing, and debugging webhooks. It provides a REST API, a live web dashboard, and an interactive two-application demo that shows a complete webhook lifecycle — from a source app firing an event, through delivery and processing, to a callback confirming the result.

## Requirements

- Java 17+
- Maven 3.8+

## Running the Application

```bash
mvn spring-boot:run
```

The app starts at `http://localhost:8080`.

To build and run a production JAR:

```bash
mvn clean package
java -jar target/webhook-simulator-1.0.0.jar
```

## Pages

| Page | URL | Purpose |
|------|-----|---------|
| Dashboard | `http://localhost:8080/` | Real-time stats and send form |
| Demo | `http://localhost:8080/demo` | Live two-app webhook flow visualization |
| History | `http://localhost:8080/history` | Full event history with search and pagination |
| Test | `http://localhost:8080/test` | Manual webhook sender with response inspector |
| Learn | `http://localhost:8080/learn` | Webhook concepts, flow diagrams, and code walkthroughs |

## Live Demo

The Demo page (`/demo`) simulates two independent applications communicating over webhooks:

1. **Source App** fires an event (e.g. `order.created`) via HTTP POST to the target
2. **Target App** validates, queues, and processes the webhook asynchronously
3. **Target App** POSTs a callback to the source with the processing result
4. **Source App** updates its own record based on the callback

Click any trigger button and watch all four steps animate in real time across both panels.

## Testing with curl

Send a webhook:

```bash
curl -X POST http://localhost:8080/api/webhook/receive \
  -H "Content-Type: application/json" \
  -d '{
    "eventType": "order.created",
    "eventId": "abc-123",
    "timestamp": "2024-01-01T00:00:00",
    "data": {"orderId": 42, "amount": 99.99}
  }'
```

Send with HMAC-SHA256 signature (validated only when header is present):

```bash
curl -X POST http://localhost:8080/api/webhook/receive \
  -H "Content-Type: application/json" \
  -H "X-Webhook-Signature: sha256=<your-signature>" \
  -d '{ ... }'
```

Trigger the demo flow from the source app directly:

```bash
curl -X POST http://localhost:8080/api/source/trigger \
  -H "Content-Type: application/json" \
  -d '{"eventType": "payment.processed"}'
```

Other useful endpoints:

```bash
curl http://localhost:8080/api/webhook/history?limit=20
curl http://localhost:8080/api/webhook/stats
curl -X DELETE http://localhost:8080/api/webhook/history
curl http://localhost:8080/api/source/events
curl -X DELETE http://localhost:8080/api/source/events
```

## API Reference

### Webhook Receiver (Target App)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/webhook/receive` | Receive a webhook (returns HTTP 202) |
| `GET` | `/api/webhook/history?limit=N` | Fetch recent webhook records |
| `GET` | `/api/webhook/stats` | Stats by status and event type |
| `GET` | `/api/stats` | Stats including live queue size |
| `DELETE` | `/api/webhook/history` | Clear webhook history |

### Source App (Demo)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/api/source/trigger` | Fire an event from the source app |
| `POST` | `/api/source/callback` | Receive a callback from the target app |
| `GET` | `/api/source/events` | Fetch source event history |
| `DELETE` | `/api/source/events` | Clear source event history |

### Webhook payload format

```json
{
  "eventType": "order.created",
  "eventId": "unique-event-id",
  "timestamp": "2024-01-01T00:00:00",
  "data": { }
}
```

## Configuration

`src/main/resources/application.properties`:

```properties
server.port=8080
webhook.secret=your-secret-key-here-change-in-production
webhook.receiver.url=http://localhost:8080/api/webhook/receive
source.app.callback-url=http://localhost:8080/api/source/callback
```

## Running Tests

```bash
mvn test                    # All tests
mvn test -Dtest=ClassName   # Single test class
```

## Documentation

| Document | Location | Contents |
|----------|----------|----------|
| Architecture & design rationale | `docs/ARCHITECTURE.md` | Request flows, design decisions, trade-offs |
| Two-app webhook flow | `docs/TWO_APP_FLOW.md` | How source → target → callback works end-to-end |
