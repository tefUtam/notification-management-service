package org.example.NMS.api;

import java.time.Instant;

public record AuditView(String action, String detail, Instant occurredAt) {
}
