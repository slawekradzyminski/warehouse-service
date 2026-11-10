package com.awesome.warehouse.service.notification;

import com.awesome.warehouse.client.NotificationTemplate;
import com.awesome.warehouse.entity.fulfilment.ShipmentEntity;
import com.awesome.warehouse.entity.returns.ReturnRequestEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Customer notifications as template name plus properties. The backend owns the email
 * text and renders it from these properties.
 */
public final class CustomerMessages {

    private CustomerMessages() {
    }

    /** A notification for one customer, before it gets its outbox requestId. */
    public record CustomerMessage(String username, NotificationTemplate template, Map<String, String> properties) {

        public CustomerMessage {
            properties = Map.copyOf(properties);
        }
    }

    public static CustomerMessage shipped(ShipmentEntity shipment) {
        return carrierUpdate(shipment, NotificationTemplate.ORDER_SHIPPED);
    }

    public static CustomerMessage outForDelivery(ShipmentEntity shipment) {
        return carrierUpdate(shipment, NotificationTemplate.SHIPMENT_OUT_FOR_DELIVERY);
    }

    public static CustomerMessage deliveryFailed(ShipmentEntity shipment) {
        return carrierUpdate(shipment, NotificationTemplate.SHIPMENT_DELIVERY_FAILED);
    }

    private static CustomerMessage carrierUpdate(ShipmentEntity shipment, NotificationTemplate template) {
        return new CustomerMessage(shipment.getUsername(), template, Map.of(
                "orderId", String.valueOf(shipment.getOrderId()),
                "carrier", String.valueOf(shipment.getCarrier()),
                "trackingNumber", String.valueOf(shipment.getTrackingNumber())));
    }

    public static CustomerMessage delivered(ShipmentEntity shipment) {
        return new CustomerMessage(shipment.getUsername(), NotificationTemplate.ORDER_DELIVERED,
                Map.of("orderId", String.valueOf(shipment.getOrderId())));
    }

    public static CustomerMessage returnUpdated(ReturnRequestEntity request) {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("returnId", String.valueOf(request.getId()));
        properties.put("orderId", String.valueOf(request.getOrderId()));
        properties.put("status", request.getStatus().name());
        if (request.getRefundAmount() != null) {
            properties.put("refundAmount", request.getRefundAmount().toPlainString());
        }
        if (request.getResolutionNote() != null) {
            properties.put("note", request.getResolutionNote());
        }
        return new CustomerMessage(request.getUsername(), NotificationTemplate.RETURN_UPDATED, properties);
    }
}
