package com.awesome.warehouse.service.event;

import com.awesome.warehouse.client.FeedEvent;
import com.awesome.warehouse.correlation.CorrelationContext;
import com.awesome.warehouse.service.stock.StockReadiness;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.MessageListener;
import jakarta.jms.TextMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jms.support.JmsUtils;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Optional;

/**
 * Broker half of the event transport (docs/EVENT_BROKER_CONTRACT.md in awesome-localstack): the body
 * of every message on this service's queue is one feed event, handled by the same code as the poller
 * ({@link EventFeedPoller#processDelivered}). Runs in the listener container's transacted session:
 * whatever fails here (a body that does not parse, a malformed restore, a handler or database failure)
 * rolls the message back, the broker redelivers it with a growing delay and, after its last attempt,
 * moves it to the queue's dead-letter queue. Like the poller it waits for the stock ledger to be
 * initialised, so no restore is attempted during the startup import (and no delivery attempt is used
 * up meanwhile). The {@code traceparent} property (the backend relay's publish span), when valid,
 * becomes the parent of the {@code process <type>} span instead of the writing request's span.
 */
@Slf4j
@Component
public class EventBrokerListener implements MessageListener {

    static final String TRACEPARENT = "traceparent";
    static final Duration READINESS_TIMEOUT = Duration.ofMinutes(5);

    private final EventFeedPoller poller;
    private final StockReadiness stockReadiness;
    private final JsonMapper jsonMapper;

    public EventBrokerListener(EventFeedPoller poller, StockReadiness stockReadiness, JsonMapper jsonMapper) {
        this.poller = poller;
        this.stockReadiness = stockReadiness;
        this.jsonMapper = jsonMapper;
    }

    /** {@code <address>::<address>.<consumer>}: this service's queue by its fully qualified name. */
    public static String queueOf(String address) {
        return address + "::" + address + "." + EventFeedPoller.CONSUMER;
    }

    @Override
    public void onMessage(Message message) {
        if (!stockReadiness.awaitReady(READINESS_TIMEOUT)) {
            throw new IllegalStateException("Stock ledger still not initialised; the event is rolled back");
        }
        try {
            if (!(message instanceof TextMessage text)) {
                throw new IllegalArgumentException("Event message " + message.getJMSMessageID() + " is not a text message");
            }
            FeedEvent event = parentedBy(parse(text), message.getStringProperty(TRACEPARENT));
            poller.processDelivered(event);
        } catch (JMSException e) {
            throw JmsUtils.convertJmsAccessException(e);
        }
    }

    private FeedEvent parse(TextMessage message) throws JMSException {
        String body = message.getText();
        try {
            FeedEvent event = jsonMapper.readValue(body, FeedEvent.class);
            if (event.id() == null || event.type() == null) {
                throw new IllegalArgumentException("Event message " + message.getJMSMessageID() + " has no id or type");
            }
            return event;
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Event message " + message.getJMSMessageID() + " is not a feed event: "
                    + e.getOriginalMessage(), e);
        }
    }

    /** The event with the publish span of a valid {@code traceparent} as its parent; unchanged otherwise. */
    static FeedEvent parentedBy(FeedEvent event, String traceparent) {
        Optional<CorrelationContext.Traceparent> parent = CorrelationContext.Traceparent.parse(traceparent);
        if (parent.isEmpty()) {
            return event;
        }
        return new FeedEvent(event.sequence(), event.id(), event.type(), event.occurredAt(), event.orderId(),
                event.username(), event.data(), event.requestId(), parent.get().traceId(), parent.get().spanId());
    }
}
