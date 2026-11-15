package com.awesome.warehouse.support;

import org.apache.activemq.artemis.api.core.QueueConfiguration;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.core.config.CoreAddressConfiguration;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.springframework.boot.artemis.autoconfigure.ArtemisConfigurationCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The commerce event topology of awesome-localstack's artemis/commerce-events.properties, scaled down for
 * the embedded test broker: this service's queue on the multicast address, three delivery attempts
 * without delay, then its own dead-letter queue (filtered on the queue the message failed on).
 */
@TestConfiguration(proxyBeanMethods = false)
public class EmbeddedEventBroker {

    public static final String ADDRESS = "awesome.commerce.events";
    public static final String QUEUE = ADDRESS + ".warehouse-service";
    public static final String DEAD_LETTER_ADDRESS = "commerce.events.DLA";
    /** The dead-letter queue of {@link #QUEUE}, by its fully qualified name. */
    public static final String DEAD_LETTER_QUEUE = DEAD_LETTER_ADDRESS + "::DLQ." + QUEUE;
    public static final int MAX_DELIVERY_ATTEMPTS = 3;

    @Bean
    ArtemisConfigurationCustomizer commerceEventTopology() {
        return configuration -> {
            configuration.addAddressConfiguration(new CoreAddressConfiguration()
                    .setName(ADDRESS)
                    .addRoutingType(RoutingType.MULTICAST)
                    .addQueueConfiguration(QueueConfiguration.of(QUEUE).setAddress(ADDRESS)
                            .setRoutingType(RoutingType.MULTICAST)));
            configuration.addAddressConfiguration(new CoreAddressConfiguration()
                    .setName(DEAD_LETTER_ADDRESS)
                    .addRoutingType(RoutingType.MULTICAST)
                    .addQueueConfiguration(QueueConfiguration.of("DLQ." + QUEUE).setAddress(DEAD_LETTER_ADDRESS)
                            .setRoutingType(RoutingType.MULTICAST)
                            .setFilterString("_AMQ_ORIG_QUEUE = '" + QUEUE + "'")));
            configuration.addAddressSetting(ADDRESS, new AddressSettings()
                    .setMaxDeliveryAttempts(MAX_DELIVERY_ATTEMPTS)
                    .setRedeliveryDelay(0)
                    .setDeadLetterAddress(SimpleString.of(DEAD_LETTER_ADDRESS)));
        };
    }
}
