package org.example.NMS.domain;

public enum DeliveryStatus {
    QUEUED,
    PROCESSING,
    RETRY_SCHEDULED,
    SUCCEEDED,
    FAILED,
    EXPIRED
}
