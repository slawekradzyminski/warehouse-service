package com.awesome.warehouse.controller.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.io.Serial;

/** A stock API error that names the product it is about ({@code {"message":...,"productId":1}}). */
@Getter
public class StockException extends CustomException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long productId;

    public StockException(String message, HttpStatus httpStatus, Long productId) {
        super(message, httpStatus);
        this.productId = productId;
    }

    public StockException(String message, HttpStatus httpStatus, Long productId, Throwable cause) {
        super(message, httpStatus, cause);
        this.productId = productId;
    }
}
