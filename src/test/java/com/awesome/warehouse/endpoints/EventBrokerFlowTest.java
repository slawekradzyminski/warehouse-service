package com.awesome.warehouse.endpoints;

import com.awesome.warehouse.client.FeedEvent;
import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalProductDto;
import com.awesome.warehouse.dto.order.OrderStatus;
import com.awesome.warehouse.entity.stock.InventoryMovementType;
import com.awesome.warehouse.repository.event.ProcessedEventRepository;
import com.awesome.warehouse.service.event.EventFeedPoller;
import com.awesome.warehouse.support.AbstractFlowTest;
import com.awesome.warehouse.support.EmbeddedEventBroker;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stock restores through the broker transport against an embedded Artemis broker with the commerce event
 * topology (docs/EVENT_BROKER_CONTRACT.md in awesome-localstack): delivery, duplicate deliveries and a
 * poison message that ends in the dead-letter queue without blocking the queue.
 */
@Import(EmbeddedEventBroker.class)
@TestPropertySource(properties = {
        "warehouse.events.transport=broker",
        "spring.artemis.mode=embedded",
        "spring.artemis.embedded.enabled=true",
        "spring.artemis.embedded.persistent=false"})
class EventBrokerFlowTest extends AbstractFlowTest {

    private static final Duration DEADLINE = Duration.ofSeconds(15);

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private DefaultMessageListenerContainer eventBrokerListenerContainer;

    @Autowired
    private EventFeedPoller poller;

    @Autowired
    private ProcessedEventRepository processedEvents;

    private JmsTemplate sender;

    @BeforeEach
    void sender() {
        sender = new JmsTemplate(connectionFactory);
        sender.setPubSubDomain(true);
    }

    @Test
    void theListenerRunsOnThisServicesQueueInsteadOfThePoller() {
        assertThat(eventBrokerListenerContainer.isRunning()).isTrue();
        assertThat(eventBrokerListenerContainer.getDestinationName())
                .isEqualTo("awesome.commerce.events::awesome.commerce.events.warehouse-service");
    }

    @Test
    void aStockRestoreThroughTheBrokerIsAppliedOnceEvenWhenDeliveredTwice() {
        // given
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        InternalOrderDto order = backend.order("alice", OrderStatus.PAID, null, phone, 2);
        FeedEvent restore = backend.cancelWithStockRestore(order.id(), "alice");
        long cursorBefore = poller.cursor();

        // when
        publish(restore);
        publish(restore);

        // then
        await(() -> processedEvents.existsById(restore.id()));
        awaitStockPush();
        assertThat(movementsOf(phone.id())).filteredOn(m -> m.getType() == InventoryMovementType.ORDER_RESTORED)
                .singleElement().satisfies(movement -> {
                    assertThat(movement.getOrderId()).isEqualTo(order.id());
                    assertThat(movement.getDelta()).isEqualTo(2);
                });
        assertThat(stockItems.findById(phone.id()).orElseThrow().getQuantity()).isEqualTo(12);
        assertThat(poller.cursor()).as("broker deliveries leave the feed cursor alone").isEqualTo(cursorBefore);
    }

    @Test
    void aPoisonMessageEndsInTheDeadLetterQueueAndTheNextEventIsStillHandled() throws Exception {
        // given
        InternalProductDto cable = backend.product("Cable", "Accessories", "2.00", 4);
        InternalOrderDto order = backend.order("bob", OrderStatus.PAID, null, cable, 1);
        FeedEvent good = backend.cancelWithStockRestore(order.id(), "bob");

        // when
        sender.send(EmbeddedEventBroker.ADDRESS, session -> {
            TextMessage message = session.createTextMessage("{\"not\":\"an event\"");
            message.setStringProperty("type", "stock.restore-requested");
            return message;
        });
        publish(good);

        // then
        await(() -> processedEvents.existsById(good.id()));
        JmsTemplate receiver = new JmsTemplate(connectionFactory);
        receiver.setReceiveTimeout(DEADLINE.toMillis());
        Message dead = receiver.receive(EmbeddedEventBroker.DEAD_LETTER_QUEUE);
        assertThat(dead).isInstanceOf(TextMessage.class);
        assertThat(((TextMessage) dead).getText()).isEqualTo("{\"not\":\"an event\"");
        assertThat(dead.getStringProperty("_AMQ_ORIG_QUEUE")).isEqualTo(EmbeddedEventBroker.QUEUE);
    }

    private void publish(FeedEvent event) {
        String body = jsonMapper.writeValueAsString(event);
        sender.send(EmbeddedEventBroker.ADDRESS, session -> {
            TextMessage message = session.createTextMessage(body);
            message.setStringProperty("type", event.type());
            message.setStringProperty("JMSXGroupID", "order-" + event.orderId());
            return message;
        });
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plus(DEADLINE);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Condition not met within " + DEADLINE);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
