package org.example.NMS;

import org.example.NMS.domain.Channel;
import org.example.NMS.domain.FailureType;
import org.example.NMS.provider.DeliveryProvider;
import org.example.NMS.repository.NotificationRepository;
import org.example.NMS.repository.NotificationRepository.DeliveryJob;
import org.example.NMS.service.DeliveryWorker;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryWorkerTest {
    private final NotificationRepository repository = mock(NotificationRepository.class);
    private final DeliveryProvider provider = mock(DeliveryProvider.class);
    private final UUID deliveryId = UUID.randomUUID();
    private final UUID notificationId = UUID.randomUUID();
    private final DeliveryJob job = new DeliveryJob(deliveryId, notificationId, "person@example.test",
            Channel.EMAIL, "Subject", "Body", null);

    @Test
    void retriesTransientFailureWithBoundedBackoff() {
        when(provider.channel()).thenReturn(Channel.EMAIL);
        when(repository.findExpiredDeliveries(any(), anyInt())).thenReturn(List.of());
        when(repository.findDueDeliveries(any(), anyInt())).thenReturn(List.of(job));
        when(repository.claim(eq(deliveryId), any())).thenReturn(true);
        when(repository.attemptCount(deliveryId)).thenReturn(1);
        when(provider.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(DeliveryProvider.DeliveryResult.failure(
                        FailureType.RATE_LIMITED, "Provider rate limit"));

        new DeliveryWorker(repository, List.of(provider)).processDueDeliveries();

        verify(repository).markRetry(eq(deliveryId), any(Instant.class),
                eq(FailureType.RATE_LIMITED), eq("Provider rate limit"));
        verify(repository, never()).markFailed(any(), any(), any(), any());
    }

    @Test
    void doesNotRetryPermanentRecipientFailure() {
        when(provider.channel()).thenReturn(Channel.EMAIL);
        when(repository.findExpiredDeliveries(any(), anyInt())).thenReturn(List.of());
        when(repository.findDueDeliveries(any(), anyInt())).thenReturn(List.of(job));
        when(repository.claim(eq(deliveryId), any())).thenReturn(true);
        when(repository.attemptCount(deliveryId)).thenReturn(1);
        when(provider.send(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(DeliveryProvider.DeliveryResult.failure(FailureType.INVALID_RECIPIENT, "Invalid recipient"));

        new DeliveryWorker(repository, List.of(provider)).processDueDeliveries();

        verify(repository).markFailed(eq(deliveryId), any(Instant.class),
                eq(FailureType.INVALID_RECIPIENT), eq("Invalid recipient"));
        verify(repository, never()).markRetry(any(), any(), any(), any());
    }
}
