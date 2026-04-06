# Two-Application Webhook Flow

This document explains the pattern demonstrated by the `/demo` page: a **Source App** that fires webhook events, a **Target App** that receives and processes them, and a **callback** that closes the loop back to the source.

---

## Table of Contents

1. [The Problem This Pattern Solves](#1-the-problem-this-pattern-solves)
2. [The Three-Phase Flow](#2-the-three-phase-flow)
3. [Detailed Step-by-Step Walkthrough](#3-detailed-step-by-step-walkthrough)
4. [How This Application Implements It](#4-how-this-application-implements-it)
5. [The Callback Contract](#5-the-callback-contract)
6. [State Machine: Source App](#6-state-machine-source-app)
7. [State Machine: Target App](#7-state-machine-target-app)
8. [Real-World Analogues](#8-real-world-analogues)
9. [What the Demo Visualizes](#9-what-the-demo-visualizes)

---

## 1. The Problem This Pattern Solves

In a simple webhook integration, the source sends an event and the target silently processes it. The source never finds out whether processing succeeded. This works for fire-and-forget scenarios (audit logs, analytics) but fails for anything where the source needs to react:

- A payment platform sends a `payment.processed` event. The merchant's system (target) tries to fulfill the order — but fulfillment fails. How does the payment platform know to issue a refund or hold the funds?
- A subscription service sends `subscription.cancelled`. The downstream billing system processes it — but encounters an error. How does the subscription service know the cancellation didn't complete?

The **webhook + callback pattern** solves this by adding a return path: after the target finishes processing, it notifies the source of the outcome. The source can then take a follow-up action based on success or failure.

---

## 2. The Three-Phase Flow

```
┌───────────────────────────────────────────────────────────┐
│                                                           │
│  PHASE 1: Event Dispatch                                  │
│                                                           │
│  Source App                        Target App             │
│  ──────────                        ──────────             │
│  Event occurs                                             │
│  Build webhook payload                                    │
│  POST /api/webhook/receive ──────► Validate signature     │
│                                    Store record (QUEUED)  │
│  ◄──────────────────────────────── HTTP 202 Accepted      │
│  Mark event as DELIVERED                                  │
│                                                           │
├───────────────────────────────────────────────────────────┤
│                                                           │
│  PHASE 2: Async Processing (Target side)                  │
│                                                           │
│                                    Worker thread picks up │
│                                    Status → PROCESSING    │
│                                    Execute business logic │
│                                    Status → SUCCESS/FAILED│
│                                                           │
├───────────────────────────────────────────────────────────┤
│                                                           │
│  PHASE 3: Callback                                        │
│                                                           │
│  Source App                        Target App             │
│  ──────────                        ──────────             │
│                    POST /api/source/callback              │
│  ◄────────────────────────────────                        │
│  { eventId, status: SUCCESS }                             │
│  Update event → CONFIRMED                                 │
│  Take follow-up action                                    │
│  ──────────── OR ──────────────                           │
│  { eventId, status: FAILED }                              │
│  Update event → FAILED                                    │
│  Trigger compensating action                              │
│                                                           │
└───────────────────────────────────────────────────────────┘
```

---

## 3. Detailed Step-by-Step Walkthrough

### Step 1 — Source fires the event

Something happens in the source application: a user places an order, a payment is charged, a subscription lapses. The source builds a structured payload describing what occurred and who it affects.

```json
POST /api/webhook/receive
Content-Type: application/json
X-Webhook-Signature: sha256=abc123...

{
  "eventType": "order.created",
  "eventId":   "evt-a4f2b91c3d",
  "timestamp": "2024-06-15T14:32:00",
  "data": {
    "orderId":    "ORD-8821",
    "amount":     149.99,
    "currency":   "USD",
    "customerId": "CUST-441"
  }
}
```

The `eventId` is critical — it is the source's globally unique identifier for this occurrence. It is used by the target to deduplicate retries, and by the callback to route the response back to the right record.

### Step 2 — Target acknowledges immediately

The target validates the signature, records the event, enqueues it for async processing, and returns **HTTP 202 Accepted** within milliseconds — before any real work is done.

```json
HTTP/1.1 202 Accepted

{
  "status":          "QUEUED",
  "message":         "Webhook received and queued",
  "receivedEventId": "evt-a4f2b91c3d",
  "processedAt":     "2024-06-15T14:32:00.123"
}
```

**Why 202, not 200?** HTTP 200 implies the request was fully processed. HTTP 202 correctly signals "received and accepted, but processing is ongoing." This distinction matters: the source knows it should not expect the work to be complete — it must wait for the callback.

**Why so fast?** The source has a timeout (typically 5–30 seconds). Any real work — database writes, third-party API calls, email dispatch — done synchronously risks hitting that timeout. A timed-out delivery looks like a failure to the source, triggering retries and potentially creating duplicate events.

### Step 3 — Target processes asynchronously

A background worker thread picks the event from the queue and executes the actual business logic. This is fully decoupled from the HTTP request that delivered the webhook.

During processing, the record transitions through states:

```
QUEUED → PROCESSING → SUCCESS
                    → FAILED (with retry)
```

If processing fails, the target retries with exponential backoff before marking the record `FAILED`.

### Step 4 — Target sends callback

Once the target reaches a terminal state (`SUCCESS` or `FAILED`), it POSTs a callback to the source's callback endpoint:

```json
POST /api/source/callback
Content-Type: application/json

{
  "eventId":       "evt-a4f2b91c3d",
  "webhookStatus": "SUCCESS",
  "message":       "Processed by target app — status: SUCCESS",
  "processedAt":   "2024-06-15T14:32:01.004"
}
```

The `eventId` is the same value the source originally sent. This is the join key that lets the source look up which of its records this callback refers to.

### Step 5 — Source acts on the result

The source looks up the event by `eventId` and takes action based on `webhookStatus`:

- **SUCCESS** → mark order as confirmed, send confirmation email, ship goods
- **FAILED** → put order on hold, alert support team, trigger a refund

```
Source event state: PENDING → DELIVERED → CONFIRMED
                                        → FAILED
```

---

## 4. How This Application Implements It

Both "applications" run inside the same JVM, but they communicate exclusively over HTTP — as if they were deployed on separate servers.

```
SourceAppService.triggerEvent()
    │
    │  RestTemplate POST http://localhost:8080/api/webhook/receive
    │  (same server, different controller — simulates network call)
    ▼
WebhookController.receive()
    │
    ▼
WebhookService.receive()          ← validates signature, creates record
    │
    ▼
WebhookQueue.enqueue()            ← puts on LinkedBlockingQueue
    │
    ▼  [HTTP 202 returned to SourceAppService here]
    │  SourceAppService marks event DELIVERED
    │
    ▼  [background worker thread]
WebhookService.processRecord()    ← PROCESSING → SUCCESS/FAILED
    │
    │  RestTemplate POST http://localhost:8080/api/source/callback
    │  (same server, different controller — simulates callback to source)
    ▼
SourceAppController.callback()
    │
    ▼
SourceAppService.handleCallback() ← DELIVERED → CONFIRMED/FAILED
```

The `webhook.receiver.url` and `source.app.callback-url` properties are both `http://localhost:8080/...` by default. Changing them to real remote URLs would make the same code work across genuinely separate services.

### Key classes

| Class | Role |
|---|---|
| `SourceAppService` | Maintains source event state, fires outbound webhooks via `RestTemplate`, handles incoming callbacks |
| `SourceAppController` | HTTP endpoints for the source side: `/api/source/trigger`, `/api/source/callback`, `/api/source/events` |
| `WebhookService` | Receives and processes webhooks, dispatches `sendCallback()` after each terminal state |
| `WebhookQueue` | Decouples receipt from processing; manages retry and backoff |
| `FlowStep` | WebSocket message broadcast at each transition, drives the demo page animation |

---

## 5. The Callback Contract

A callback is itself a small webhook — an HTTP POST from the target back to the source. It must follow the same principles as any webhook:

**The target should:**
- Always send a callback, even on failure — silence is indistinguishable from a lost request
- Include the original `eventId` so the source can correlate
- Send it even if the source's callback endpoint is temporarily down (retry the callback too)
- Not include sensitive data beyond what's needed for the source to act

**The source's callback endpoint should:**
- Return HTTP 200 quickly — it is itself a webhook receiver
- Be idempotent — the target may retry the callback if it doesn't receive a 200
- Not trust the callback payload blindly — validate it or use a shared secret on this endpoint too

**What this app does (simplified for clarity):**
- The target sends one callback attempt; it logs a warning if it fails but does not retry the callback itself
- The source endpoint returns HTTP 200 immediately after updating state
- No signature validation on the callback endpoint (acceptable for a learning tool, not for production)

---

## 6. State Machine: Source App

Each `SourceEvent` record on the source side progresses through these states:

```
         ┌──────────┐
         │  PENDING │  Event triggered locally, not yet sent
         └────┬─────┘
              │ RestTemplate POST succeeds (HTTP 202)
              ▼
        ┌───────────┐
        │ DELIVERED │  Target acknowledged receipt
        └─────┬─────┘
              │ Callback received with SUCCESS
              ├────────────────────────────────► CONFIRMED  ✓ take follow-up action
              │
              │ Callback received with FAILED
              └────────────────────────────────► FAILED     ✗ compensate or alert

         (if RestTemplate POST itself fails)
         PENDING ──────────────────────────────► SEND_FAILED  network/target unreachable
```

**PENDING** — The source has created its internal record and is about to call the target. This state is transient and normally lasts milliseconds.

**DELIVERED** — The target returned HTTP 202. The event is in the target's queue. The source does not yet know whether processing will succeed — it must wait for the callback.

**CONFIRMED** — The callback arrived with `webhookStatus: SUCCESS`. The source can now safely take the next business action (fulfil, notify, release funds, etc.).

**FAILED** — The callback arrived with `webhookStatus: FAILED`. The source knows processing did not complete and must decide what to do: retry, alert, compensate, or surface an error to the user.

**SEND_FAILED** — The HTTP call to the target never succeeded. The target never received the event. The source should retry the delivery directly or put the event on a dead-letter queue.

---

## 7. State Machine: Target App

Each `WebhookRecord` on the target side progresses through:

```
         ┌────────┐
         │ QUEUED │  Received and on the BlockingQueue
         └───┬────┘
             │ Worker thread picks up
             ▼
      ┌────────────┐
      │ PROCESSING │  Business logic executing
      └─────┬──────┘
            │ Success on attempt N (N ≤ 1 + MAX_RETRIES)
            ├──────────────────────────────────► SUCCESS  → send callback (SUCCESS)
            │
            │ All attempts exhausted
            └──────────────────────────────────► FAILED   → send callback (FAILED)

Retry schedule (before FAILED):
  Attempt 1: immediate
  Attempt 2: wait 1s
  Attempt 3: wait 2s
  Attempt 4: wait 4s  →  FAILED
```

The target broadcasts a `FlowStep` WebSocket message on every state transition. The demo page listens for these messages and animates the step indicators and arrows accordingly.

---

## 8. Real-World Analogues

This pattern appears across every major platform that exposes webhooks:

### Stripe — Payment Processing
```
Stripe (source)              Your Server (target)
    │                               │
    │── payment_intent.created ────►│
    │◄── 200 OK ────────────────────│
    │                               │  (async: reserve inventory, send receipt)
    │◄── POST /stripe/webhook ──────│  (if your server calls Stripe to confirm)
    │   { charge_id, status }       │
    │── update payment status ──────│
```

Stripe actually retries webhook delivery for up to 72 hours if your endpoint doesn't return 2xx. Your server's callback here might be a follow-up API call to Stripe (e.g., confirming a charge) rather than a webhook back to Stripe.

### GitHub — CI/CD Pipeline
```
GitHub (source)              CI Server (target)
    │                               │
    │── push event ────────────────►│
    │◄── 202 Accepted ──────────────│
    │                               │  (async: run tests, build artifact)
    │◄── POST /api/github/status ───│  using GitHub Commit Status API
    │   { sha, state: "success" }   │
    │── update PR check ────────────│
```

GitHub's commit status API is the callback mechanism. The CI server is the target; it uses GitHub's API to report back rather than a separate webhook endpoint on GitHub.

### Shopify — Order Fulfillment
```
Shopify (source)             Fulfillment Service (target)
    │                               │
    │── orders/create ─────────────►│
    │◄── 200 OK ────────────────────│
    │                               │  (async: pick, pack, ship)
    │◄── POST /webhooks/fulfillment─│
    │   { order_id, tracking_no }   │
    │── mark order fulfilled ───────│
```

Shopify lets fulfillment partners register webhook endpoints and expects callbacks (fulfillments via Shopify's API) to update order status.

### The Common Pattern

In all three cases:
1. The source sends an event describing something that happened
2. The target acknowledges receipt quickly (2xx) and processes asynchronously
3. The target notifies the source of the outcome — via a callback webhook, a status API call, or a platform-specific mechanism
4. The source updates its state based on the notification

The specific transport (webhook-back vs. REST API call vs. message queue) varies, but the logical pattern is identical.

---

## 9. What the Demo Visualizes

The `/demo` page makes the invisible visible. All four steps happen over real HTTP, all state changes happen in real data structures, and WebSocket messages broadcast every transition to the browser as it happens.

### The two panels

**Source App panel (blue)** — mirrors `SourceAppService.events`. Shows each `SourceEvent` and the human-readable interpretation of its current status. As the source moves from PENDING → DELIVERED → CONFIRMED, the row updates in place.

**Target App panel (green)** — mirrors `WebhookHistoryService.history`. Shows each `WebhookRecord` as it transitions from QUEUED → PROCESSING → SUCCESS/FAILED.

### The flow channel

The center channel shows:
- **Right arrow** (→) — pulses when `SourceAppService` sends the outbound webhook (Phase 1)
- **Five step dots** — advance as `FlowStep` WebSocket messages arrive, one per state transition
- **Left arrow** (←) — pulses when `WebhookService.sendCallback()` fires (Phase 3)

### The activity log

Every `FlowStep` broadcast appends a timestamped entry showing the direction, event type, and detail message. This gives a chronological audit trail of the full exchange, matching what you would see in server logs in a real deployment.

### Triggering via curl instead of the UI

The demo page is purely a visualization layer. The underlying APIs work identically from curl:

```bash
# Fire an event from the source app
curl -X POST http://localhost:8080/api/source/trigger \
  -H "Content-Type: application/json" \
  -d '{"eventType": "order.created"}'

# Watch the source event state
curl http://localhost:8080/api/source/events

# Watch the target processing state
curl http://localhost:8080/api/webhook/history?limit=5
```

The same flow — outbound webhook, async processing, callback, state update — runs identically whether triggered from the UI or the terminal.
