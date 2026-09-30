# Engineering assessment

This document explains the design and implementation choices behind the Notification Management Service and evaluates the prototype against production-oriented engineering criteria. It complements the runnable setup and scenario notes in [engineering-notes.md](engineering-notes.md).

## 1. Architecture and system design

### Components and responsibilities

| Component | Responsibility | Why it is separated |
|---|---|---|
| `NotificationController` and request records | HTTP endpoints, request shape, and bean validation | Keeps transport concerns out of delivery and persistence logic. |
| `NotificationService` | Business validation, idempotency, event deduplication, acceptance transaction, and status composition | Centralizes notification-level invariants and acceptance behavior. |
| `NotificationRoutingPolicy` | Severity defaults, requested-channel selection, recipient preference intersection | Makes routing rules directly testable and replaceable without changing adapters. |
| `NotificationRepository` | SQL persistence and atomic delivery state transitions | Keeps database details behind a small service-facing boundary. |
| `DeliveryWorker` | Poll due work, claim rows, handle expiration, call providers, record outcomes and retry timing | Isolates asynchronous processing from the synchronous API path. |
| `DeliveryProvider` implementations | Per-channel integration boundary | Allows channel vendor behavior to be replaced independently of routing and retry policy. |
| H2 schema | Notification, delivery, and audit records with indexes and uniqueness constraints | Provides local persistence and a runnable prototype with no external services. |

### Control flow

1. `POST /api/v1/notifications` validates the request shape. The service checks business rules and calculates a SHA-256 fingerprint of the serialized request.
2. An existing idempotency key scoped to the source system is checked first. An identical fingerprint returns the existing notification; reuse with a different fingerprint returns `409 Conflict`.
3. The service routes each recipient. Explicit requested channels replace severity defaults; recipient preferences narrow that set. An empty set is rejected rather than widened.
4. A second check suppresses a matching source-system/event/type tuple submitted in the preceding 24 hours. Otherwise, a notification and its recipient/channel delivery rows are saved with audit events in the acceptance transaction.
5. The API returns `202 Accepted` and the persisted current state. A scheduled worker polls due delivery rows independently of the request thread.
6. The worker uses a conditional state update to claim a row, then calls its channel provider. Success, retry scheduling, terminal failure, and expiration update delivery state and append audit entries.
7. `GET /api/v1/notifications/{id}` derives overall status from its delivery rows and includes selected channels and per-recipient/channel results. The audit endpoint returns the associated history.

### State and persistence decisions

The notification is the logical request; each delivery row represents one recipient/channel pair. That decomposition allows partial success, independent retries, and channel-specific timestamps without treating a multi-recipient notification as a single indivisible send. Overall state is derived rather than separately persisted, avoiding a second status field that could drift from the delivery rows.

The H2 database uses a file-backed URL for local runs. A relational store was chosen to make acceptance, state changes, deduplication constraints, and status reads concrete and inspectable without requiring a broker or database service to be installed. The scheduled poller stands in for a queue; it is intentionally not presented as the production scaling solution.

## 2. Decomposition and execution quality

The implementation breaks the requirement into work that can be changed and validated separately:

| Workstream | Main code surface | Validation/evidence |
|---|---|---|
| Define request and status contract | `api/NotificationRequest`, `NotificationResponse`, `NotificationController` | API integration tests cover accepted requests, returned delivery rows, and status lookup. |
| Establish routing behavior | `service/NotificationRoutingPolicy` | Unit tests cover severity defaults, explicit channels, and recipient preferences. |
| Persist logical notification and delivery work | `repository/NotificationRepository`, `resources/schema.sql` | API tests exercise persisted status and replay; SQL constraints express key and delivery uniqueness. |
| Process work outside the HTTP request | `service/DeliveryWorker`, `provider/DeliveryProvider` | Worker tests cover retryable and permanent failure behavior; live smoke testing exercised eventual delivered status. |
| Avoid duplicate side effects | `NotificationService`, unique idempotency index, deduplication lookup | Integration tests cover same-key replay, mismatched-key reuse, and repeated event suppression with different keys. |
| Preserve operational history | `audit_events`, audit API | API test checks suppression history; smoke test retrieved the notification audit trail. |
| Describe ambiguity and readiness boundaries | `docs/engineering-notes.md`, this document | Decisions, trade-offs, validation, and production gaps are recorded for review. |

The work proceeds from API/domain and persistence contracts to routing, worker/provider behavior, and then end-to-end validation. The greenfield implementation established the logical notification and delivery lifecycle. The brownfield change crosses enum/schema, provider configuration, routing, API output, and tests for the `IN_APP` channel; keeping those updates together avoids a channel appearing in policy without being representable or deliverable. The ambiguous routing precedence is stated explicitly instead of being left to incidental implementation behavior.

AI is an implementation and analysis aid, not the owner of requirements or deployment. The engineer is responsible for choosing the scope, resolving policy ambiguity, checking generated changes against the actual code, reviewing failure and privacy behavior, and deciding what evidence is adequate. No autonomous deployment or production integration is implied by this prototype.

## 3. Realism and quality of outputs

The output is deliberately executable rather than only architectural: it has a Maven build, Java 17 application, HTTP endpoints, schema, local persistent database, background worker, replaceable provider interface, integration and unit tests, and setup documentation. Its outputs include:

- A stable notification ID and explicit acceptance/duplicate response.
- Selected channels and a delivery result per recipient/channel, including attempts, relevant timestamps, failure category, and a bounded diagnostic string.
- Audit actions for acceptance, routing, queued work, delivery attempt/outcome, retry, expiration, and duplicate suppression.
- A failure taxonomy separating transient provider errors, permanent rejection, invalid recipient, rate limiting, timeout, and authorization error.

Realism has deliberate boundaries. The current providers log a delivery ID and simulate success; they do not deliver email, SMS, webhook, or in-app messages. The worker is a local scheduled poller, not a distributed message broker. Therefore, this is evidence that the service flow and contracts work, not evidence of vendor interoperability, throughput, delivery guarantees, or production operations. Failure tests mock provider outcomes; they do not prove a live provider's classification is correct.

## 4. Validation and risk management

### Evidence performed

- `mvn test` passes seven tests across API integration, routing policy, and worker failure handling.
- `mvn package` builds the executable Spring Boot jar.
- A live HTTP smoke test submitted a notification, observed the worker move it to `DELIVERED`, and read its audit trail.
- The API tests exercise idempotency replay, key/payload conflict, and event deduplication with distinct keys.

The tests target boundaries rather than only implementation details: API tests use Spring MVC and H2, routing tests directly exercise policy, and worker tests isolate provider outcomes with mocks. The smoke test adds evidence that application startup, HTTP wiring, database initialization, and scheduled processing work together in one local process.

### Risks and mitigations

| Risk | Current mitigation | Remaining risk / production action |
|---|---|---|
| Duplicate API submissions | Source-scoped idempotency key with a unique database index; payload mismatch is a conflict. | The request fingerprint hashes Jackson serialization, not a canonical semantic representation. Define canonicalization and key retention in the public contract. |
| Duplicate event submissions | 24-hour `(sourceSystem, eventId, notificationType)` check and suppression audit. | The lookup is not protected by a matching unique constraint or cross-instance lock; simultaneous requests on multiple instances can race. Add a transactional uniqueness/claim strategy and define retention before scaling. |
| Duplicate provider side effect after a crash | Stable delivery ID is passed to the provider; interrupted `PROCESSING` rows are recovered for retry. | A crash after provider success but before local success persistence can repeat the call. Use provider-side idempotency where supported and document at-least-once semantics. |
| Retry storm or permanent failure retry | Maximum of three attempts; transient/rate-limit/timeout retry; permanent/invalid-recipient/authorization failure is terminal. | Backoff is fixed exponential with no jitter, provider-specific `Retry-After`, circuit breaker, or global rate limiter. Add these when provider behavior and load are known. |
| Sensitive content exposure | Audit records omit message body, destination address, and credentials; provider interface keeps credentials out of the API model. | Message and destination must be persisted to deliver. There is no authentication, authorization, encryption policy, PII retention job, or credential integration. Never expose this prototype publicly as-is. |
| Provider error leakage | Error field is capped at 500 characters. | Length limits do not sanitize secrets or personal data. Real adapters must classify and sanitize vendor errors before persistence. |
| Worker contention or stale claims | Conditional update prevents two local workers from claiming the same eligible row; startup recovers `PROCESSING` rows. | No leases, heartbeat, or stale-processing timeout exists. Recovery assumes a single process is starting; a multi-instance deployment requires lease-based claims and safe recovery rules. |
| Low observability | Persistent audit trail and structured delivery state provide a basic trace. | No metrics, tracing, alerting, dead-letter workflow, or audit retention/immutability policy is included. |

## 5. Clarity and defensibility of decisions

The requirement leaves channel precedence and empty-route behavior open. The documented rule is deterministic: explicit requested channels replace severity defaults, recipient preferences are an allow-list intersection, and no eligible route is rejected with `422` rather than silently dropped or sent through an unapproved channel. This prioritizes recipient control and predictable routing.

Severity defaults are intentionally simple and visible in code: critical selects all implemented channels, high selects email and webhook, normal selects email, and low selects in-app. They are starting policy, not universal business truth; a production owner must approve and version routing policy.

Deduplication has two intentionally different boundaries. Idempotency keys represent client retries and are scoped by source system; event deduplication suppresses semantically repeated events for a stated 24-hour window. Both duplicate paths return the original logical notification and record suppression history. The key is retained for the lifetime of the database; event comparison uses the fixed 24-hour window. The event deduplication strategy is not production-safe across concurrent service instances yet.

Three attempts and a short exponential delay make failure behavior testable while bounding retry work. The selected retryable/permanent classifications are prototypes: a real provider adapter must translate vendor responses into the taxonomy, including rate limits and authorization errors, and product owners must decide whether any authentication failure should be retried after credential recovery.

## 6. Engineering principles and safe change management

### Modularity and testability

HTTP, business policy, SQL persistence, worker orchestration, and provider calls have separate types and responsibilities. `DeliveryProvider` is the seam for vendor integrations; `NotificationRoutingPolicy` can be tested without the database. This reduces the surface area of channel enhancements and permits focused tests.

### Reliability

The design persists accepted work before processing it, keeps one state row per delivery, conditionally claims eligible work, bounds retries, persists failure classifications and timestamps, and recovers work left processing at startup. These are useful reliability foundations, but they do not amount to exactly-once delivery or high availability.

### Security and privacy

Request validation bounds key content and message sizes. SQL uses parameter binding. Audit data avoids message content and recipient addresses. These controls reduce accidental exposure and malformed input but do not provide an authorization boundary. Production use requires authenticated/authorized clients, tenant isolation as applicable, TLS, encryption at rest, secrets management, log/error scrubbing, retention/deletion policy, and abuse controls.

### Scalability

The logical model and provider interface can be retained as throughput grows, but the current runtime is intentionally single-process. A production evolution path is a managed database plus durable broker or a database queue with leases, horizontally scalable workers, provider-level rate controls, and measured partitioning/index tuning. Load and contention benchmarks should precede capacity claims.

### Safe change management

The `IN_APP` enhancement updates the enum, provider registration, routing, API-selected-channel projection, and tests as one coherent change. Existing channels share the provider contract and worker lifecycle. The schema uses `IF NOT EXISTS` to support local restarts; it is not a substitute for versioned migrations. Before a production schema or provider change, add backward-compatible migrations, regression tests for existing channels, rollout/rollback steps, and observable success/error metrics.

## 7. Engineering judgment and production-readiness conclusion

The prototype favors a small, inspectable system over infrastructure that would obscure the core workflow: relational persistence makes state and audit reviewable, a poller keeps setup simple, and provider adapters make channel behavior replaceable. The most consequential engineering judgment is to make ambiguous routing and retry behavior explicit while naming the correctness gaps that remain.

This is suitable for demonstrating end-to-end workflow and as a starting point for design review. It is not ready to process real user notifications: simulated providers, absent API security, privacy controls, distributed claims, event-deduplication race protection, operational monitoring, and production-grade migrations are blockers. The next responsible engineering step is to confirm product policy and security/data-retention requirements, then select real provider and broker/database integration designs before deployment.
