package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.controller.exception.CustomException;
import org.springframework.http.HttpStatus;

import java.io.Serial;

/**
 * The backend reports the order as cancelled (for example by the customer
 * while the shipment was still awaiting pick). The local shipment is marked
 * CANCELLED and that change is committed even though the request fails.
 */
public class ShipmentCancelledException extends CustomException {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final String MESSAGE = "Order was cancelled by the customer";

    public ShipmentCancelledException() {
        super(MESSAGE, HttpStatus.CONFLICT);
    }
}
