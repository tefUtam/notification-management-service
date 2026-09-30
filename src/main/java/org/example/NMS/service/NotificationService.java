package org.example.NMS.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.NMS.api.AuditView;
import org.example.NMS.api.NotificationRequest;
import org.example.NMS.api.NotificationResponse;
import org.example.NMS.domain.Channel;
import org.example.NMS.domain.DeliveryStatus;
import org.example.NMS.repository.NotificationRepository;
import org.example.NMS.repository.NotificationRepository.NotificationRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class NotificationService {
    private static final Duration DEDUPLICATION_WINDOW = Duration.ofHours(24);
    private final NotificationRepository repository;
    private final NotificationRoutingPolicy routingPolicy;
    private final ObjectMapper objectMapper;

    public NotificationService(
            NotificationRepository repository,
            NotificationRoutingPolicy routingPolicy,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.routingPolicy = routingPolicy;
        this.objectMapper = objectMapper;
    }

    public record Submission(NotificationResponse response, boolean duplicate) {
    }

    @Transactional(noRollbackFor = ResponseStatusException.class)
    public synchronized Submission submit(NotificationRequest request, String rawIdempotencyKey) {
        String idempotencyKey = normalizeKey(rawIdempotencyKey);
        Instant now = Instant.now();
        validateBusinessRules(request, now);
        String hash = payloadHash(request);

        if (idempotencyKey != null) {
            NotificationRow existing = repository.findByIdempotencyKey(request.sourceSystem(), idempotencyKey);
            if (existing != null) {
                if (!existing.payloadHash().equals(hash)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Idempotency key was already used with a different request");
                }
                repository.addAudit(existing.id(), "DUPLICATE_SUPPRESSED",
                        "Idempotency replay suppressed; no new deliveries created", now);
                return new Submission(response(existing.id(), true), true);
            }
        }

        Set<Channel> notificationChannels = new HashSet<>();
        for (NotificationRequest.RecipientRequest recipient : request.recipients()) {
            Set<Channel> recipientChannels = routingPolicy.route(request, recipient);
            if (recipientChannels.isEmpty()) {
                repository.addAudit(null, "NOTIFICATION_REJECTED",
                        "No eligible channel for source " + request.sourceSystem() + ", event " + request.eventId(), now);
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Routing produced no eligible channel for recipient " + recipient.recipientId());
            }
            notificationChannels.addAll(recipientChannels);
        }

        NotificationRow duplicate = repository.findRecentDuplicate(
                request.sourceSystem(), request.eventId(), request.notificationType(), now.minus(DEDUPLICATION_WINDOW));
        if (duplicate != null) {
            repository.addAudit(duplicate.id(), "DUPLICATE_SUPPRESSED",
                    "Event duplicate suppressed within 24-hour source/event/type window", now);
            return new Submission(response(duplicate.id(), true), true);
        }

        UUID notificationId = UUID.randomUUID();
        Instant scheduledAt = request.scheduledAt();
        NotificationRow row = new NotificationRow(
                notificationId, request.sourceSystem(), request.eventId(), request.notificationType(),
                request.severity(), request.subject(), request.message(), now, scheduledAt,
                request.expiresAt(), idempotencyKey, hash);

        repository.insertNotification(row, request.priority());
        Instant dueAt = scheduledAt == null || scheduledAt.isBefore(now) ? now : scheduledAt;
        for (NotificationRequest.RecipientRequest recipient : request.recipients()) {
            for (Channel channel : routingPolicy.route(request, recipient)) {
                repository.insertDelivery(notificationId, recipient.recipientId(), recipient.address(), channel, dueAt);
            }
        }
        String routedChannels = notificationChannels.stream().sorted()
                .map(Enum::name).collect(Collectors.joining(","));
        repository.addAudit(notificationId, "NOTIFICATION_ACCEPTED", "Notification accepted", now);
        repository.addAudit(notificationId, "ROUTING_DECISION",
                "Selected channels: " + routedChannels + "; recipients: " + request.recipients().size(), now);
        repository.addAudit(notificationId, "DELIVERIES_QUEUED",
                "Queued " + request.recipients().stream()
                        .mapToInt(recipient -> routingPolicy.route(request, recipient).size()).sum()
                        + " recipient/channel deliveries", now);
        return new Submission(response(notificationId, false), false);
    }

    public NotificationResponse get(UUID id) {
        return response(id, false);
    }

    public java.util.List<AuditView> audit(UUID id) {
        requireNotification(id);
        return repository.findAudit(id);
    }

    private NotificationResponse response(UUID id, boolean duplicate) {
        NotificationRow notification = requireNotification(id);
        var deliveries = repository.findDeliveries(id);
        String status = overallStatus(deliveries);
        var selectedChannels = deliveries.stream().map(NotificationResponse.DeliveryView::channel)
                .distinct().sorted().toList();
        return new NotificationResponse(id, status, selectedChannels, deliveries, notification.createdAt(), duplicate);
    }

    private NotificationRow requireNotification(UUID id) {
        NotificationRow row = repository.findNotification(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Notification not found");
        }
        return row;
    }

    private String overallStatus(java.util.List<NotificationResponse.DeliveryView> deliveries) {
        Set<DeliveryStatus> statuses = deliveries.stream()
                .map(delivery -> DeliveryStatus.valueOf(delivery.status())).collect(Collectors.toSet());
        if (statuses.contains(DeliveryStatus.PROCESSING)) {
            return "PROCESSING";
        }
        if (statuses.contains(DeliveryStatus.QUEUED) || statuses.contains(DeliveryStatus.RETRY_SCHEDULED)) {
            return "QUEUED";
        }
        if (statuses.size() == 1 && statuses.contains(DeliveryStatus.SUCCEEDED)) {
            return "DELIVERED";
        }
        if (statuses.size() == 1 && statuses.contains(DeliveryStatus.EXPIRED)) {
            return "EXPIRED";
        }
        if (statuses.contains(DeliveryStatus.SUCCEEDED) || statuses.contains(DeliveryStatus.EXPIRED)) {
            return "PARTIALLY_FAILED";
        }
        return "FAILED";
    }

    private void validateBusinessRules(NotificationRequest request, Instant now) {
        if (request.priority() < 0 || request.priority() > 10) {
            reject(request, now, "Priority must be between 0 and 10");
        }
        if (request.expiresAt() != null && !request.expiresAt().isAfter(now)) {
            reject(request, now, "Expiration timestamp must be in the future");
        }
        if (request.expiresAt() != null && request.scheduledAt() != null
                && !request.expiresAt().isAfter(request.scheduledAt())) {
            reject(request, now, "Expiration must be after the scheduled time");
        }
        Set<String> recipientIds = new HashSet<>();
        for (NotificationRequest.RecipientRequest recipient : request.recipients()) {
            if (!recipientIds.add(recipient.recipientId())) {
                reject(request, now, "Recipient identifiers must be unique within a notification");
            }
        }
    }

    private void reject(NotificationRequest request, Instant now, String reason) {
        repository.addAudit(null, "NOTIFICATION_REJECTED",
                reason + "; source " + request.sourceSystem() + ", event " + request.eventId(), now);
        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }

    private String normalizeKey(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String normalized = key.trim();
        if (normalized.length() > 200) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key must be at most 200 characters");
        }
        return normalized;
    }

    private String payloadHash(NotificationRequest request) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(request));
            return HexFormat.of().formatHex(hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to create request fingerprint", exception);
        }
    }
}
