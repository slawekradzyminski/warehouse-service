package com.awesome.warehouse.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Runs the event feed poller, the shipment reconciler, the carrier simulator, the notification
 * outbox retries, the stock level push retries and the reservation reaper.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
}
