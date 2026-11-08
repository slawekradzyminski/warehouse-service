package com.awesome.warehouse.service.notification;

import com.awesome.warehouse.client.BackendClient;
import com.awesome.warehouse.client.NotificationRequest;
import com.awesome.warehouse.controller.exception.CustomException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sends customer emails through the backend only after the warehouse
 * transaction has committed, so a rolled-back change never notifies anyone.
 * A failed notification is logged; it never undoes the business change.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerNotifier {

    private final BackendClient backendClient;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onNotification(NotificationRequest notification) {
        try {
            backendClient.sendNotification(notification);
        } catch (CustomException e) {
            log.error("Could not send {} notification to {}: {}",
                    notification.template(), notification.username(), e.getMessage());
        }
    }
}
