package org.example.NMS.provider;

import org.example.NMS.domain.Channel;
import org.example.NMS.domain.FailureType;

public interface DeliveryProvider {
    Channel channel();

    DeliveryResult send(String deliveryId, String recipientAddress, String subject, String message);

    record DeliveryResult(boolean successful, FailureType failureType, String safeError) {
        public static DeliveryResult success() {
            return new DeliveryResult(true, null, null);
        }

        public static DeliveryResult failure(FailureType failureType, String safeError) {
            return new DeliveryResult(false, failureType, safeError);
        }
    }
}
