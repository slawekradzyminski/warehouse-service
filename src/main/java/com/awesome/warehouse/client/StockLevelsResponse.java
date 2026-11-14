package com.awesome.warehouse.client;

/** The backend's answer to a level push: levels applied and levels ignored (older version or unknown product). */
public record StockLevelsResponse(int applied, int ignored) {
}
