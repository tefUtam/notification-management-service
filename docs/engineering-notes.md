# Engineering notes

## Architecture and control flow

`NotificationController` validates the API contract, `NotificationService` owns acceptance and deduplication, `NotificationRoutingPolicy` determines per-recipient channels, `NotificationRepository` persists state using JDBC, and `DeliveryWorker` claims due deliveries on a scheduled poll. Channel adapters implement `DeliveryProvider`; the included adapters only log a delivery ID and simulate success. H2 makes the prototype runnable without external infrastructure.

Submission is transactional: validate business rules, check the source-scoped idempotency key, apply the 24-hour event deduplication boundary, persist the notification and one row per recipient/channel, and record acceptance/routing/queue audit actions. A single scheduled worker atomically claims due rows, records attempts, calls the channel adapter, and records success, terminal failure, or a bounded retry. Status is derived from recipient/channel delivery states and includes attempt count, attempt/completion timestamps, and safe failure classification.

Audit entries contain action, stable delivery identifiers, channel/count, failure category, and timestamps—not message text, addresses, or credentials. Notification message content and destination addresses are stored because the prototype must deliver and report to the recipient; protect and expire those records under an actual deployment's data-retention policy.

## Scenario 1: Greenfield notification flow

**Decomposition:** Define the request/status contract; model notifications, recipient/channel deliveries, and audit entries; implement routing; add a queue worker and provider boundary; then expose status and audit APIs.

**Execution:** The API accepts source/event/type/severity/priority, recipients and optional preferences, optional requested channels, scheduling, expiration, and message content. Routing defaults are critical = all four channels, high = email/webhook, normal = email, low = in-app. Explicit channels replace defaults; recipient preferences intersect with that set. The scheduler asynchronously processes queued rows. `GET` returns overall and per-recipient/channel state.

**Validation:** `NotificationApiTest` exercises acceptance, routed deliveries, status retrieval, audit visibility, and idempotent replay. `NotificationRoutingPolicyTest` covers severity defaults, explicit channels, and recipient preferences.

## Scenario 2: Brownfield multi-layer enhancement

**Change:** Add `IN_APP` as an additional channel, and consolidate delivery execution behind the `DeliveryProvider` interface instead of coupling routing and queue processing to one provider implementation. Extend schema/enums, channel adapters, routing, response/status, and tests together.

**Execution:** Channel selection remains policy-owned; the worker resolves the provider by channel and uses the same claim/attempt/retry/audit flow for every provider. This limits channel-specific behavior to adapters while preserving shared retry and reporting rules.

**Validation:** Routing tests assert `IN_APP` eligibility and explicit routing. API tests verify recipient-channel rows are returned. Worker tests verify transient failures retry and invalid recipients fail permanently.

## Scenario 3: Ambiguous requirement and explicit interpretation

**Ambiguity:** “Determine delivery channels using factors such as requested channel, severity, recipient preferences, and routing policy” does not specify precedence, whether preferences are mandatory, or what happens when the intersection is empty.

**Engineer-owned decision:** Explicit requested channels replace severity defaults; recipient preferences act as an allow-list intersection; when no channels remain for any recipient, reject with `422 Unprocessable Entity` rather than silently drop the recipient or widen delivery. Without requested channels, use the documented severity defaults above. This gives deterministic, least-surprise behavior and avoids sending to an unapproved channel.

**Validation:** Routing unit tests cover precedence/intersection; API validation covers the externally visible contract. Rejections are recorded in the audit table without attaching message content.

## Reliability, deduplication, and failure policy

- Idempotency keys are unique per source system and retained with the notification for the lifetime of the database. A replay whose serialized request fingerprint matches returns the original notification; using the same key with a different fingerprint returns `409`.
- A second deduplication boundary suppresses the same `(sourceSystem, eventId, notificationType)` for 24 hours, independent of idempotency key. Suppression records an audit action against the original notification and returns its ID. In a production deployment, define cleanup/retention together with the business replay window.
- Event deduplication is a lookup rather than a database-enforced unique claim, so concurrent submissions across instances can race; it needs stronger coordination before horizontal scaling.
- The worker has three maximum attempts. Transient provider failure, rate limit, and timeout retry with exponential delays (2 and 4 seconds); permanent rejection, invalid recipient, and authorization errors fail immediately. The last retryable failure becomes terminal at attempt three.
- Delivery IDs are stable across retries and passed to adapters as the idempotency reference. A real adapter must use provider-side idempotency where supported. A crash after an external side effect but before local success persistence can still cause a repeated call; this prototype provides at-least-once processing, not exactly-once delivery.
- Expiration prevents queued/retry work from being sent after its expiry. Scheduling uses UTC instants.

## Setup, testing, limitations, and trade-offs

Java 17 and Maven are required. Run `mvn spring-boot:run` to start against a local file-backed H2 database, `mvn test` for focused API/routing/retry tests, and `mvn package` to build the jar. The provider implementation is intentionally simulated.

This is a single-process prototype, not a production deployment: the scheduled database poller replaces a broker; H2 replaces a managed database; there is no authentication, tenant isolation, per-user preference service, provider credential management, distributed worker lease, metrics, rate limiting, or PII encryption/retention job. A horizontally scaled service should use a durable broker or database claim strategy with leases, a production database, encrypted/secrets-managed provider integrations, access controls, explicit audit retention, and operational metrics. Real channel providers must map vendor responses into the failure taxonomy and sanitize error strings before returning them.

The engineer remains responsible for requirement interpretation, change scope, provider behavior, security controls, and review of generated/tested outputs; AI acceleration is used for implementation and validation tasks rather than autonomous deployment or orchestration.
