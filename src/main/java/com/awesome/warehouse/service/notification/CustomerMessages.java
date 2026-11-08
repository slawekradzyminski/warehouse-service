package com.awesome.warehouse.service.notification;

import com.awesome.warehouse.client.NotificationRequest;
import com.awesome.warehouse.client.NotificationTemplate;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.returns.ReturnRequestEntity;

import java.util.Map;

/** Customer email texts, unchanged from the backend's commerce notifications. */
public final class CustomerMessages {

    private CustomerMessages() {
    }

    public static NotificationRequest shipped(ShipmentEntity shipment) {
        String user = shipment.getUsername();
        Long orderId = shipment.getOrderId();
        return new NotificationRequest(
                user,
                NotificationTemplate.ORDER_SHIPPED,
                "Order #" + orderId + " has shipped",
                "Hi " + user + ",\n\nYour order #" + orderId + " is on its way with " + shipment.getCarrier()
                        + ". Tracking number: " + shipment.getTrackingNumber() + ".",
                Map.of(
                        "username", user,
                        "orderId", String.valueOf(orderId),
                        "carrier", String.valueOf(shipment.getCarrier()),
                        "trackingNumber", String.valueOf(shipment.getTrackingNumber())));
    }

    public static NotificationRequest delivered(ShipmentEntity shipment) {
        String user = shipment.getUsername();
        Long orderId = shipment.getOrderId();
        return new NotificationRequest(
                user,
                NotificationTemplate.ORDER_DELIVERED,
                "Order #" + orderId + " was delivered",
                "Hi " + user + ",\n\nYour order #" + orderId
                        + " was delivered. You can now review the products and request a return within 30 days.",
                Map.of("username", user, "orderId", String.valueOf(orderId)));
    }

    public static NotificationRequest returnUpdated(ReturnRequestEntity request) {
        String user = request.getUsername();
        String refund = request.getRefundAmount() == null ? "" : " Refund: " + request.getRefundAmount() + ".";
        String note = request.getResolutionNote() == null ? "" : " Note: " + request.getResolutionNote();
        return new NotificationRequest(
                user,
                NotificationTemplate.RETURN_UPDATED,
                "Return #" + request.getId() + " is " + request.getStatus(),
                "Hi " + user + ",\n\nYour return #" + request.getId() + " for order #" + request.getOrderId()
                        + " is now " + request.getStatus() + "." + refund + note,
                Map.of(
                        "username", user,
                        "returnId", String.valueOf(request.getId()),
                        "status", request.getStatus().name()));
    }
}
