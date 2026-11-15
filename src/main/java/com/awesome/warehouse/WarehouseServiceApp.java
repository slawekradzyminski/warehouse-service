package com.awesome.warehouse;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class WarehouseServiceApp {

    public static void main(String[] args) {
        usePooledNettyAllocator();
        SpringApplication.run(WarehouseServiceApp.class, args);
    }

    /**
     * Netty 4.2 (Spring Boot 4.1) defaults to its adaptive buffer allocator. Under it the Artemis client
     * (built for Netty 4.1) lost listener sessions in the native image right after start-up ("Session is
     * closed", IllegalReferenceCountException in ActiveMQFrameDecoder2), so a broker delivery could be
     * rolled back until it was dead-lettered. The pooled allocator, Netty 4.1's default, does not show it.
     * Read at run time, so it must be set before any Netty class loads; an explicit
     * -Dio.netty.allocator.type wins.
     */
    static void usePooledNettyAllocator() {
        String allocator = "io.netty.allocator.type";
        if (System.getProperty(allocator) == null) {
            System.setProperty(allocator, "pooled");
        }
    }
}
