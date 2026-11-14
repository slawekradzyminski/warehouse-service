package com.awesome.warehouse.config;

import com.awesome.warehouse.entity.fulfilment.LocationProductEntity;
import com.awesome.warehouse.entity.fulfilment.StockingRole;
import com.awesome.warehouse.entity.purchasing.SupplierEntity;
import com.awesome.warehouse.repository.fulfilment.LocationProductRepository;
import com.awesome.warehouse.repository.purchasing.SupplierRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds demo suppliers and the demo category split between the warehouse locations for
 * the local and demo profiles. Gdansk ships {@link #GDANSK_CATEGORIES} (Krakow backs them
 * up); Krakow ships every other category through the migration's {@code "*"} row (Gdansk
 * backs it up), so a cart mixing both kinds is split into two parcels. The switch is a
 * runtime property rather than a bean condition because Spring AOT fixes bean
 * conditions when the native image is built. Existing names are skipped, so
 * restarts are harmless.
 */
@Slf4j
@Component
public class DemoDataSeeder implements ApplicationRunner {

    static final List<DemoSupplier> SUPPLIERS = List.of(
            new DemoSupplier("Nordic Electronics", "orders@nordic-electronics.test", 7),
            new DemoSupplier("Baltic Gadgets", "purchasing@baltic-gadgets.test", 14));

    /** Demo catalogue categories shipped from Gdansk first (the other half stays with Krakow). */
    static final List<String> GDANSK_CATEGORIES = List.of("Books", "Home & Kitchen", "Wearables");

    private final SupplierRepository supplierRepository;
    private final LocationProductRepository stockingRepository;
    private final boolean enabled;

    public DemoDataSeeder(SupplierRepository supplierRepository, LocationProductRepository stockingRepository,
                          @Value("${warehouse.seed-demo-data:false}") boolean enabled) {
        this.supplierRepository = supplierRepository;
        this.stockingRepository = stockingRepository;
        this.enabled = enabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        for (DemoSupplier supplier : SUPPLIERS) {
            if (!supplierRepository.existsByNameIgnoreCase(supplier.name())) {
                supplierRepository.save(SupplierEntity.builder()
                        .name(supplier.name())
                        .email(supplier.email())
                        .leadTimeDays(supplier.leadTimeDays())
                        .build());
                log.info("Seeded demo supplier {}", supplier.name());
            }
        }
        for (String category : GDANSK_CATEGORIES) {
            seedStocking("GDN", category, StockingRole.PRIMARY);
            seedStocking("KRK", category, StockingRole.BACKUP);
        }
    }

    private void seedStocking(String location, String category, StockingRole role) {
        if (!stockingRepository.existsByLocationCodeAndCategory(location, category)) {
            stockingRepository.save(LocationProductEntity.builder()
                    .locationCode(location).category(category).role(role).build());
            log.info("Seeded demo stocking: {} {} {}", location, role, category);
        }
    }

    record DemoSupplier(String name, String email, int leadTimeDays) {
    }
}
