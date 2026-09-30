package org.example.NMS;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.NMS.api.NotificationRequest;
import org.example.NMS.domain.Channel;
import org.example.NMS.domain.Severity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification-test;DB_CLOSE_DELAY=-1",
        "notifications.worker.poll-interval-ms=60000"
})
@AutoConfigureMockMvc
class NotificationApiTest {
    @Autowired
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void acceptsNotificationRoutesToRecipientPreferenceAndReplaysIdempotently() throws Exception {
        NotificationRequest request = new NotificationRequest(
                "billing", "invoice-42", "PAYMENT_FAILED", Severity.CRITICAL, 1,
                "Payment failed", "Retry the charge", List.of(
                new NotificationRequest.RecipientRequest("user-1", "user@example.test",
                        List.of(Channel.EMAIL, Channel.SMS))), null, null, null);

        String accepted = mockMvc.perform(post("/api/v1/notifications")
                        .header("Idempotency-Key", "request-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.deliveries.length()", is(2)))
                .andReturn().getResponse().getContentAsString();
        String notificationId = objectMapper.readTree(accepted).get("notificationId").asText();

        mockMvc.perform(post("/api/v1/notifications")
                        .header("Idempotency-Key", "request-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationId", is(notificationId)))
                .andExpect(jsonPath("$.duplicate", is(true)));

        mockMvc.perform(get("/api/v1/notifications/" + notificationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveries.length()", is(2)));

        mockMvc.perform(get("/api/v1/notifications/" + notificationId + "/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action == 'DUPLICATE_SUPPRESSED')]").isArray());
    }

    @Test
    void rejectsReuseOfIdempotencyKeyWithDifferentPayload() throws Exception {
        NotificationRequest first = request("event-unique", "First");
        NotificationRequest changed = request("event-unique", "Changed");
        String key = "conflict-key";

        mockMvc.perform(post("/api/v1/notifications").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/v1/notifications").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(changed)))
                .andExpect(status().isConflict());
    }

    @Test
    void suppressesRepeatedSourceEventAndTypeEvenWithDifferentKeys() throws Exception {
        NotificationRequest first = request("event-dedup", "First");
        NotificationRequest replay = request("event-dedup", "Updated subject");
        String firstResponse = mockMvc.perform(post("/api/v1/notifications")
                        .header("Idempotency-Key", "event-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(first)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String firstId = objectMapper.readTree(firstResponse).get("notificationId").asText();

        mockMvc.perform(post("/api/v1/notifications")
                        .header("Idempotency-Key", "event-key-2")
                        .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(replay)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationId", is(firstId)))
                .andExpect(jsonPath("$.duplicate", is(true)));
    }

    private NotificationRequest request(String eventId, String subject) {
        return new NotificationRequest("test", eventId, "TEST", Severity.NORMAL, 5, subject, "Body",
                List.of(new NotificationRequest.RecipientRequest("user", "user@example.test", null)),
                null, null, null);
    }
}
