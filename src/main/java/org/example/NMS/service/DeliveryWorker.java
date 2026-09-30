package org.example.NMS.service;

import org.example.NMS.domain.FailureType;
import org.example.NMS.provider.DeliveryProvider;
import org.example.NMS.provider.DeliveryProvider.DeliveryResult;
import org.example.NMS.repository.NotificationRepository;
import org.example.NMS.repository.NotificationRepository.DeliveryJob;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class DeliveryWorker {
    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);
    private static final int MAX_ATTEMPTS = 3;
    private final NotificationRepository repository;
    private final Map<org.example.NMS.domain.Channel, DeliveryProvider> providers;

    public DeliveryWorker(NotificationRepository repository, List<DeliveryProvider> providerList) {
        this.repository = repository;
        this.providers = new EnumMap<>(org.example.NMS.domain.Channel.class);
        providerList.forEach(provider -> providers.put(provider.channel(), provider));
    }

    @PostConstruct
    public void recoverInterruptedDeliveries() {
        int recovered = repository.recoverProcessing(Instant.now());
        if (recovered > 0) {
            log.warn("Recovered {} interrupted deliveries for retry", recovered);
        }
    }

    @Scheduled(fixedDelayString = "${notifications.worker.poll-interval-ms:500}")
    public void processDueDeliveries() {
        Instant now = Instant.now();
        for (var expired : repository.findExpiredDeliveries(now, 100)) {
            if (repository.markExpired(expired.deliveryId(), now)) {
                repository.addAudit(expired.notificationId(), "DELIVERY_EXPIRED",
                        "A queued delivery expired before processing", now);
            }
        }
        List<DeliveryJob> jobs = repository.findDueDeliveries(now, 50);
        for (DeliveryJob job : jobs) {
            process(job);
        }
    }

    private void process(DeliveryJob job) {
        Instant startedAt = Instant.now();
        if (!repository.claim(job.id(), startedAt)) {
            return;
        }
        int attempt = repository.attemptCount(job.id());
        repository.addAudit(job.notificationId(), "DELIVERY_ATTEMPTED",
                "Delivery " + job.id() + " attempt " + attempt + " via " + job.channel(), startedAt);

        DeliveryProvider provider = providers.get(job.channel());
        DeliveryResult result = provider.send(job.id().toString(), job.recipientAddress(), job.subject(), job.message());
        Instant finishedAt = Instant.now();
        if (result.successful()) {
            repository.markSucceeded(job.id(), finishedAt);
            repository.addAudit(job.notificationId(), "DELIVERY_SUCCEEDED",
                    "Delivery " + job.id() + " succeeded via " + job.channel(), finishedAt);
            return;
        }

        FailureType failureType = result.failureType();
        String safeError = safeError(result.safeError());
        boolean retryable = switch (failureType) {
            case TRANSIENT_PROVIDER_FAILURE, RATE_LIMITED, TIMEOUT -> true;
            case PERMANENT_PROVIDER_REJECTION, INVALID_RECIPIENT, AUTHORIZATION_ERROR -> false;
        };
        if (retryable && attempt < MAX_ATTEMPTS) {
            Instant retryAt = finishedAt.plusSeconds(1L << attempt);
            repository.markRetry(job.id(), retryAt, failureType, safeError);
            repository.addAudit(job.notificationId(), "RETRY_SCHEDULED",
                    "Delivery " + job.id() + " retry " + (attempt + 1) + " scheduled after " + failureType, finishedAt);
        } else {
            repository.markFailed(job.id(), finishedAt, failureType, safeError);
            repository.addAudit(job.notificationId(), "DELIVERY_FAILED",
                    "Delivery " + job.id() + " failed after attempt " + attempt + " with " + failureType, finishedAt);
        }
    }

    private String safeError(String error) {
        if (error == null || error.isBlank()) {
            return "Provider reported a delivery failure";
        }
        return error.length() <= 500 ? error : error.substring(0, 500);
    }
}
