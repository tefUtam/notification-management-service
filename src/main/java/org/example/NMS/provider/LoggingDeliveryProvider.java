package org.example.NMS.provider;

import org.example.NMS.domain.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LoggingDeliveryProvider implements DeliveryProvider {
    private static final Logger log = LoggerFactory.getLogger(LoggingDeliveryProvider.class);
    private final Channel channel;

    public LoggingDeliveryProvider(Channel channel) {
        this.channel = channel;
    }

    @Override
    public Channel channel() {
        return channel;
    }

    @Override
    public DeliveryResult send(String deliveryId, String recipientAddress, String subject, String message) {
        log.info("Simulated {} delivery succeeded for delivery {}", channel, deliveryId);
        return DeliveryResult.success();
    }
}
