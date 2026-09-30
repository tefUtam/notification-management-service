package org.example.NMS.api;

import jakarta.validation.Valid;
import org.example.NMS.service.NotificationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<NotificationResponse> submit(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody NotificationRequest request) {
        NotificationService.Submission submission = service.submit(request, idempotencyKey);
        return ResponseEntity.status(submission.duplicate() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .body(submission.response());
    }

    @GetMapping("/{notificationId}")
    public NotificationResponse get(@PathVariable UUID notificationId) {
        return service.get(notificationId);
    }

    @GetMapping("/{notificationId}/audit")
    public List<AuditView> audit(@PathVariable UUID notificationId) {
        return service.audit(notificationId);
    }
}
