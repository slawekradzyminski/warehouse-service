package com.awesome.warehouse.config;

import com.awesome.warehouse.entity.purchasing.SupplierEntity;
import com.awesome.warehouse.repository.purchasing.SupplierRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Seeds demo suppliers for the local and demo profiles. The switch is a
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

    private final SupplierRepository supplierRepository;
    private final boolean enabled;

    public DemoDataSeeder(SupplierRepository supplierRepository,
                          @Value("${warehouse.seed-demo-data:false}") boolean enabled) {
        this.supplierRepository = supplierRepository;
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
    }

    record DemoSupplier(String name, String email, int leadTimeDays) {
    }
}
