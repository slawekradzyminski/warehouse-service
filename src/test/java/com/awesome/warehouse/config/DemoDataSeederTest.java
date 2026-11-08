package com.awesome.warehouse.config;

import com.awesome.warehouse.entity.purchasing.SupplierEntity;
import com.awesome.warehouse.repository.purchasing.SupplierRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DemoDataSeederTest {

    private final SupplierRepository repository = mock(SupplierRepository.class);

    @Test
    void seedsMissingDemoSuppliersOnly() {
        when(repository.existsByNameIgnoreCase("Nordic Electronics")).thenReturn(true);

        new DemoDataSeeder(repository, true).run(null);

        verify(repository, times(1)).save(any(SupplierEntity.class));
        assertThat(DemoDataSeeder.SUPPLIERS).extracting(DemoDataSeeder.DemoSupplier::name)
                .containsExactly("Nordic Electronics", "Baltic Gadgets");
        assertThat(DemoDataSeeder.SUPPLIERS.get(1).email()).isEqualTo("purchasing@baltic-gadgets.test");
        assertThat(DemoDataSeeder.SUPPLIERS.get(1).leadTimeDays()).isEqualTo(14);
    }

    @Test
    void doesNothingWhenDisabled() {
        new DemoDataSeeder(repository, false).run(null);

        verify(repository, never()).save(any());
    }
}
