package org.example.NMS.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record NotificationResponse(
        UUID notificationId,
        String status,
        List<String> selectedChannels,
        List<DeliveryView> deliveries,
        Instant createdAt,
        boolean duplicate) {

    public record DeliveryView(
            String recipientId,
            String channel,
            String status,
            int attempts,
            Instant lastAttemptAt,
            Instant completedAt,
            String lastFailureType,
            String lastError) {
    }
}
