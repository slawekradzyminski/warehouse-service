package com.awesome.warehouse.service.event;

import jakarta.jms.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

/**
 * The listener container of the broker transport. It always exists (the native image fixes the bean
 * graph at build time) and starts only with {@code warehouse.events.transport=broker}. One transacted
 * session per consumer, so a failure rolls the message back to the broker; {@code concurrency}
 * consumers share the queue, and the broker's message groups keep one order's events on one of them.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class EventBrokerConfig {

    @Bean
    DefaultMessageListenerContainer eventBrokerListenerContainer(
            ConnectionFactory connectionFactory, EventBrokerListener listener,
            @Value("${warehouse.events.transport:feed}") String transport,
            @Value("${warehouse.events.broker.address:awesome.commerce.events}") String address,
            @Value("${warehouse.events.broker.concurrency:1}") int concurrency) {
        DefaultMessageListenerContainer container = new DefaultMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setDestinationName(EventBrokerListener.queueOf(address));
        container.setPubSubDomain(false);
        container.setSessionTransacted(true);
        container.setConcurrentConsumers(concurrency);
        container.setMaxConcurrentConsumers(concurrency);
        container.setCacheLevel(DefaultMessageListenerContainer.CACHE_CONSUMER);
        container.setRecoveryInterval(5000);
        container.setMessageListener(listener);
        container.setErrorHandler(failure -> log.warn("Event message rolled back for redelivery: {}",
                rootMessage(failure)));
        container.setAutoStartup(EventTransport.from(transport) == EventTransport.BROKER);
        return container;
    }

    private static String rootMessage(Throwable failure) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(failure);
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
