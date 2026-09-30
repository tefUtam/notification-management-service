package org.example.NMS.service;

import org.example.NMS.api.NotificationRequest;
import org.example.NMS.domain.Channel;
import org.example.NMS.domain.Severity;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

@Component
public class NotificationRoutingPolicy {
    public Set<Channel> route(NotificationRequest request, NotificationRequest.RecipientRequest recipient) {
        Set<Channel> eligible = request.channels() == null || request.channels().isEmpty()
                ? defaultsFor(request.severity())
                : EnumSet.copyOf(request.channels());

        if (recipient.preferredChannels() != null && !recipient.preferredChannels().isEmpty()) {
            eligible.retainAll(recipient.preferredChannels());
        }
        return eligible;
    }

    private Set<Channel> defaultsFor(Severity severity) {
        return switch (severity) {
            case CRITICAL -> EnumSet.of(Channel.EMAIL, Channel.SMS, Channel.WEBHOOK, Channel.IN_APP);
            case HIGH -> EnumSet.of(Channel.EMAIL, Channel.WEBHOOK);
            case NORMAL -> EnumSet.of(Channel.EMAIL);
            case LOW -> EnumSet.of(Channel.IN_APP);
        };
    }
}
