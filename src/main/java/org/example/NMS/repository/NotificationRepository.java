package org.example.NMS.repository;

import org.example.NMS.api.AuditView;
import org.example.NMS.api.NotificationResponse;
import org.example.NMS.domain.Channel;
import org.example.NMS.domain.DeliveryStatus;
import org.example.NMS.domain.FailureType;
import org.example.NMS.domain.Severity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class NotificationRepository {
    private final JdbcTemplate jdbc;

    public NotificationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record NotificationRow(
            UUID id, String sourceSystem, String eventId, String notificationType,
            Severity severity, String subject, String message, Instant createdAt,
            Instant scheduledAt, Instant expiresAt, String idempotencyKey, String payloadHash) {
    }

    public record DeliveryJob(
            UUID id, UUID notificationId, String recipientAddress, Channel channel,
            String subject, String message, Instant expiresAt) {
    }

    public void insertNotification(NotificationRow row, int priority) {
        jdbc.update("""
                INSERT INTO notifications
                (id, source_system, event_id, notification_type, severity, priority, subject, message,
                 created_at, scheduled_at, expires_at, idempotency_key, payload_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                row.id().toString(), row.sourceSystem(), row.eventId(), row.notificationType(),
                row.severity().name(), priority, row.subject(), row.message(), timestamp(row.createdAt()),
                timestamp(row.scheduledAt()), timestamp(row.expiresAt()), row.idempotencyKey(), row.payloadHash());
    }

    public void insertDelivery(UUID notificationId, String recipientId, String address, Channel channel, Instant dueAt) {
        jdbc.update("""
                INSERT INTO deliveries
                (id, notification_id, recipient_id, recipient_address, channel, status, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), notificationId.toString(), recipientId, address,
                channel.name(), DeliveryStatus.QUEUED.name(), timestamp(dueAt));
    }

    public void addAudit(UUID notificationId, String action, String detail, Instant occurredAt) {
        jdbc.update("INSERT INTO audit_events(notification_id, action, detail, occurred_at) VALUES (?, ?, ?, ?)",
                notificationId == null ? null : notificationId.toString(), action, detail, timestamp(occurredAt));
    }

    public NotificationRow findByIdempotencyKey(String sourceSystem, String key) {
        List<NotificationRow> rows = jdbc.query("""
                SELECT * FROM notifications WHERE source_system = ? AND idempotency_key = ?
                """, this::mapNotification, sourceSystem, key);
        return rows.stream().findFirst().orElse(null);
    }

    public NotificationRow findRecentDuplicate(String sourceSystem, String eventId, String type, Instant since) {
        List<NotificationRow> rows = jdbc.query("""
                SELECT * FROM notifications
                WHERE source_system = ? AND event_id = ? AND notification_type = ? AND created_at >= ?
                ORDER BY created_at DESC LIMIT 1
                """, this::mapNotification, sourceSystem, eventId, type, timestamp(since));
        return rows.stream().findFirst().orElse(null);
    }

    public NotificationRow findNotification(UUID id) {
        List<NotificationRow> rows = jdbc.query(
                "SELECT * FROM notifications WHERE id = ?", this::mapNotification, id.toString());
        return rows.stream().findFirst().orElse(null);
    }

    public List<NotificationResponse.DeliveryView> findDeliveries(UUID notificationId) {
        return jdbc.query("""
                SELECT recipient_id, channel, status, attempt_count, last_attempt_at, completed_at,
                       last_failure_type, last_error
                FROM deliveries WHERE notification_id = ? ORDER BY recipient_id, channel
                """, (rs, rowNum) -> new NotificationResponse.DeliveryView(
                rs.getString("recipient_id"), rs.getString("channel"), rs.getString("status"),
                rs.getInt("attempt_count"), instant(rs, "last_attempt_at"), instant(rs, "completed_at"),
                rs.getString("last_failure_type"), rs.getString("last_error")), notificationId.toString());
    }

    public List<AuditView> findAudit(UUID notificationId) {
        return jdbc.query("""
                SELECT action, detail, occurred_at FROM audit_events
                WHERE notification_id = ? ORDER BY occurred_at, id
                """, (rs, rowNum) -> new AuditView(
                rs.getString("action"), rs.getString("detail"), instant(rs, "occurred_at")),
                notificationId.toString());
    }

    public List<DeliveryJob> findDueDeliveries(Instant now, int limit) {
        return jdbc.query("""
                SELECT d.id delivery_id, d.notification_id, d.recipient_address, d.channel,
                       n.subject, n.message, n.expires_at
                FROM deliveries d JOIN notifications n ON n.id = d.notification_id
                WHERE d.status IN ('QUEUED', 'RETRY_SCHEDULED')
                  AND d.next_attempt_at <= ?
                  AND (n.scheduled_at IS NULL OR n.scheduled_at <= ?)
                  AND (n.expires_at IS NULL OR n.expires_at > ?)
                ORDER BY n.priority DESC, d.next_attempt_at LIMIT ?
                """, (rs, rowNum) -> new DeliveryJob(
                UUID.fromString(rs.getString("delivery_id")),
                UUID.fromString(rs.getString("notification_id")), rs.getString("recipient_address"),
                Channel.valueOf(rs.getString("channel")), rs.getString("subject"), rs.getString("message"),
                instant(rs, "expires_at")), timestamp(now), timestamp(now), timestamp(now), limit);
    }

    public boolean claim(UUID deliveryId, Instant now) {
        return jdbc.update("""
                UPDATE deliveries SET status = 'PROCESSING', attempt_count = attempt_count + 1,
                    last_attempt_at = ?, last_error = NULL, last_failure_type = NULL
                WHERE id = ? AND status IN ('QUEUED', 'RETRY_SCHEDULED')
                """, timestamp(now), deliveryId.toString()) == 1;
    }

    public int attemptCount(UUID deliveryId) {
        return jdbc.queryForObject("SELECT attempt_count FROM deliveries WHERE id = ?",
                Integer.class, deliveryId.toString());
    }

    public void markSucceeded(UUID deliveryId, Instant at) {
        jdbc.update("UPDATE deliveries SET status = 'SUCCEEDED', completed_at = ?, next_attempt_at = NULL WHERE id = ?",
                timestamp(at), deliveryId.toString());
    }

    public void markRetry(UUID deliveryId, Instant nextAttempt, FailureType type, String safeError) {
        jdbc.update("""
                UPDATE deliveries SET status = 'RETRY_SCHEDULED', next_attempt_at = ?,
                    last_failure_type = ?, last_error = ?
                WHERE id = ?
                """, timestamp(nextAttempt), type.name(), safeError, deliveryId.toString());
    }

    public void markFailed(UUID deliveryId, Instant at, FailureType type, String safeError) {
        jdbc.update("""
                UPDATE deliveries SET status = 'FAILED', completed_at = ?, next_attempt_at = NULL,
                    last_failure_type = ?, last_error = ?
                WHERE id = ?
                """, timestamp(at), type.name(), safeError, deliveryId.toString());
    }

    public List<ExpiredDelivery> findExpiredDeliveries(Instant now, int limit) {
        return jdbc.query("""
                SELECT d.id, d.notification_id FROM deliveries d JOIN notifications n ON n.id = d.notification_id
                WHERE d.status IN ('QUEUED', 'RETRY_SCHEDULED') AND n.expires_at <= ?
                LIMIT ?
                """, (rs, rowNum) -> new ExpiredDelivery(
                UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("notification_id"))),
                timestamp(now), limit);
    }

    public record ExpiredDelivery(UUID deliveryId, UUID notificationId) {
    }

    public boolean markExpired(UUID deliveryId, Instant at) {
        return jdbc.update("""
                UPDATE deliveries SET status = 'EXPIRED', completed_at = ?, next_attempt_at = NULL
                WHERE id = ? AND status IN ('QUEUED', 'RETRY_SCHEDULED')
                """, timestamp(at), deliveryId.toString()) == 1;
    }

    public int recoverProcessing(Instant now) {
        return jdbc.update("""
                UPDATE deliveries SET status = 'RETRY_SCHEDULED', next_attempt_at = ?, last_error = ?
                WHERE status = 'PROCESSING'
                """, timestamp(now), "Recovered after worker restart");
    }

    private NotificationRow mapNotification(ResultSet rs, int rowNum) throws SQLException {
        return new NotificationRow(UUID.fromString(rs.getString("id")), rs.getString("source_system"),
                rs.getString("event_id"), rs.getString("notification_type"),
                Severity.valueOf(rs.getString("severity")), rs.getString("subject"), rs.getString("message"),
                instant(rs, "created_at"), instant(rs, "scheduled_at"), instant(rs, "expires_at"),
                rs.getString("idempotency_key"), rs.getString("payload_hash"));
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
