package com.awesome.warehouse.service.tracking;

import com.awesome.warehouse.entity.fulfilment.Carrier;
import com.awesome.warehouse.repository.fulfilment.ShipmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Random;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrackingNumberGeneratorTest {

    private final ShipmentRepository repository = mock(ShipmentRepository.class);

    @ParameterizedTest
    @EnumSource(Carrier.class)
    void generatedNumbersMatchTheCarrierFormat(Carrier carrier) {
        TrackingNumberGenerator generator = new TrackingNumberGenerator(repository);

        for (int i = 0; i < 50; i++) {
            assertThat(carrier.acceptsTrackingNumber(generator.generate(carrier))).isTrue();
        }
    }

    @Test
    void everyCharacterOfTheAlphabetCanBeDrawn() {
        RandomGenerator lowest = fixed(0);
        RandomGenerator highest = new RandomGenerator() {
            @Override
            public long nextLong() {
                return 0;
            }

            @Override
            public int nextInt(int bound) {
                return bound - 1;
            }
        };

        assertThat(new TrackingNumberGenerator(repository, lowest).candidate(Carrier.DHL)).isEqualTo("0000000000");
        assertThat(new TrackingNumberGenerator(repository, highest).candidate(Carrier.DPD)).isEqualTo("99999999999999");
        assertThat(new TrackingNumberGenerator(repository, highest).candidate(Carrier.INPOST)).isEqualTo("9".repeat(24));
        assertThat(new TrackingNumberGenerator(repository, highest).candidate(Carrier.UPS)).isEqualTo("1Z" + "9".repeat(16));
        assertThat(new TrackingNumberGenerator(repository, lowest).candidate(Carrier.UPS)).isEqualTo("1Z" + "A".repeat(16));
    }

    @Test
    void aNumberAlreadyInUseIsDrawnAgain() {
        when(repository.existsByTrackingNumber(anyString())).thenReturn(true, true, false);
        TrackingNumberGenerator generator = new TrackingNumberGenerator(repository, new Random(42));

        String generated = generator.generate(Carrier.DHL);

        assertThat(Carrier.DHL.acceptsTrackingNumber(generated)).isTrue();
        verify(repository, times(3)).existsByTrackingNumber(anyString());
    }

    @Test
    void generationGivesUpAfterTheMaximumNumberOfCollisions() {
        when(repository.existsByTrackingNumber(anyString())).thenReturn(true);
        TrackingNumberGenerator generator = new TrackingNumberGenerator(repository, new Random(42));

        assertThatThrownBy(() -> generator.generate(Carrier.UPS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not generate a unique UPS tracking number");
        verify(repository, times(TrackingNumberGenerator.MAX_ATTEMPTS)).existsByTrackingNumber(anyString());
    }

    private static RandomGenerator fixed(int value) {
        return new RandomGenerator() {
            @Override
            public long nextLong() {
                return value;
            }

            @Override
            public int nextInt(int bound) {
                return value;
            }
        };
    }
}
