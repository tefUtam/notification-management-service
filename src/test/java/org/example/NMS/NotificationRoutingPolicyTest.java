package org.example.NMS;

import org.example.NMS.api.NotificationRequest;
import org.example.NMS.domain.Channel;
import org.example.NMS.domain.Severity;
import org.example.NMS.service.NotificationRoutingPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotificationRoutingPolicyTest {
    private final NotificationRoutingPolicy policy = new NotificationRoutingPolicy();

    @Test
    void criticalDefaultsIncludeAllChannelsAndRecipientPreferencesNarrowTheRoute() {
        NotificationRequest request = request(Severity.CRITICAL, null);
        NotificationRequest.RecipientRequest unrestricted = recipient(null);
        NotificationRequest.RecipientRequest preferred = recipient(List.of(Channel.SMS));

        assertEquals(Set.of(Channel.EMAIL, Channel.SMS, Channel.WEBHOOK, Channel.IN_APP),
                policy.route(request, unrestricted));
        assertEquals(Set.of(Channel.SMS), policy.route(request, preferred));
    }

    @Test
    void explicitChannelsOverrideSeverityDefaults() {
        assertEquals(Set.of(Channel.WEBHOOK), policy.route(
                request(Severity.LOW, List.of(Channel.WEBHOOK)), recipient(null)));
    }

    private NotificationRequest request(Severity severity, List<Channel> channels) {
        return new NotificationRequest("source", "event", "type", severity, 1, "subject", "message",
                List.of(recipient(null)), channels, null, null);
    }

    private NotificationRequest.RecipientRequest recipient(List<Channel> preferences) {
        return new NotificationRequest.RecipientRequest("user", "user@example.test", preferences);
    }
}
