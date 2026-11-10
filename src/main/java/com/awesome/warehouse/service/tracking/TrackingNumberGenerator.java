package com.awesome.warehouse.service.tracking;

import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/** Random tracking numbers in each carrier's format, unique among existing shipments. */
@Component
public class TrackingNumberGenerator {

    static final int MAX_ATTEMPTS = 5;
    private static final String DIGITS = "0123456789";
    private static final String UPS_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final ShipmentRepository shipmentRepository;
    private final RandomGenerator random;

    @Autowired
    public TrackingNumberGenerator(ShipmentRepository shipmentRepository) {
        this(shipmentRepository, new SecureRandom());
    }

    TrackingNumberGenerator(ShipmentRepository shipmentRepository, RandomGenerator random) {
        this.shipmentRepository = shipmentRepository;
        this.random = random;
    }

    public String generate(Carrier carrier) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = candidate(carrier);
            if (!shipmentRepository.existsByTrackingNumber(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not generate a unique " + carrier + " tracking number");
    }

    String candidate(Carrier carrier) {
        return switch (carrier) {
            case INPOST -> randomString(DIGITS, 24);
            case DPD -> randomString(DIGITS, 14);
            case DHL -> randomString(DIGITS, 10);
            case UPS -> "1Z" + randomString(UPS_CHARACTERS, 16);
        };
    }

    private String randomString(String alphabet, int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }
}
