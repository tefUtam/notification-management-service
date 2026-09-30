package org.example.NMS.config;

import org.example.NMS.domain.Channel;
import org.example.NMS.provider.DeliveryProvider;
import org.example.NMS.provider.LoggingDeliveryProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;

@Configuration
public class ProviderConfiguration {
    @Bean
    List<DeliveryProvider> deliveryProviders() {
        return Arrays.stream(Channel.values())
                .<DeliveryProvider>map(LoggingDeliveryProvider::new)
                .toList();
    }
}
