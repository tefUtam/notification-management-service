# Notification Management Service

A runnable Java 17 / Spring Boot prototype for accepting notifications, routing each recipient to eligible channels, queueing deliveries asynchronously, tracking outcomes, and exposing an audit trail.

## Run

```powershell
mvn spring-boot:run
```

The API listens on `http://localhost:8080`. H2 persists data to `./data/notification-db`; the directory is created on startup. Run the test suite with `mvn test`, or package a runnable jar with `mvn package` and run `java -jar target/notification-management-service-0.1.0.jar`.

## API

Submit with `POST /api/v1/notifications`. The `Idempotency-Key` header is optional but recommended:

```json
{
  "sourceSystem": "payments",
  "eventId": "charge-123",
  "notificationType": "PAYMENT_FAILED",
  "severity": "HIGH",
  "priority": 2,
  "subject": "Payment failed",
  "message": "The charge needs review.",
  "recipients": [
    {
      "recipientId": "operator-7",
      "address": "operator@example.test",
      "preferredChannels": ["EMAIL", "WEBHOOK"]
    }
  ],
  "channels": null,
  "scheduledAt": null,
  "expiresAt": null
}
```

The endpoint returns `202 Accepted` for a new notification and `200 OK` for a suppressed duplicate, with the existing notification ID. Reusing a key with a different payload returns `409 Conflict`. Read current state via `GET /api/v1/notifications/{id}` and audit actions via `GET /api/v1/notifications/{id}/audit`.

Delivery providers are simulated logging adapters: they report success and never contact a real user. Replace them with real channel adapters before using this service operationally.

## Engineering scenarios and decisions

The implementation and scenario decomposition, execution, and validation notes are in [docs/engineering-notes.md](docs/engineering-notes.md). The broader assessment of architecture, implementation quality, validation, risk, engineering principles, and production-readiness judgment is in [docs/engineering-assessment.md](docs/engineering-assessment.md).
