package org.example.NMS.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.example.NMS.domain.Channel;
import org.example.NMS.domain.Severity;

import java.time.Instant;
import java.util.List;

public record NotificationRequest(
        @NotBlank @Size(max = 120) String sourceSystem,
        @NotBlank @Size(max = 200) String eventId,
        @NotBlank @Size(max = 100) String notificationType,
        @NotNull Severity severity,
        @NotNull Integer priority,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 10000) String message,
        @NotEmpty List<@Valid RecipientRequest> recipients,
        List<@NotNull Channel> channels,
        Instant scheduledAt,
        Instant expiresAt) {

    public record RecipientRequest(
            @NotBlank @Size(max = 120) String recipientId,
            @NotBlank @Size(max = 320) String address,
            List<@NotNull Channel> preferredChannels) {
    }
}
