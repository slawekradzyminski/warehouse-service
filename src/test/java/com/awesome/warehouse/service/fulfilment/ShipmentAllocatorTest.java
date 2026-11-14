package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.client.InternalOrderDto;
import com.awesome.warehouse.client.InternalOrderItemDto;
import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.entity.fulfilment.StockingRole;
import com.awesome.warehouse.service.fulfilment.ShipmentAllocator.Line;
import com.awesome.warehouse.service.fulfilment.ShipmentAllocator.Location;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.awesome.warehouse.entity.fulfilment.StockingRole.BACKUP;
import static com.awesome.warehouse.entity.fulfilment.StockingRole.PRIMARY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class ShipmentAllocatorTest {

    private static final Line PHONE = new Line(1L, "Phone", "Electronics", 2);
    private static final Line NOVEL = new Line(2L, "Novel", "Books", 1);
    private static final Line WATCH = new Line(3L, "Watch", "Wearables", 1);

    /** The demo layout: Krakow PRIMARY for everything but Books/Wearables, Gdansk the mirror image. */
    private static List<Location> demo(boolean krakowActive, boolean gdanskActive) {
        return List.of(
                new Location("GDN", 2, gdanskActive, Map.of("*", BACKUP, "books", PRIMARY, "wearables", PRIMARY)),
                new Location("KRK", 1, krakowActive, Map.of("*", PRIMARY, "books", BACKUP, "wearables", BACKUP)));
    }

    @Test
    void anOrderOneLocationCanShipEntirelyStaysInOneParcel() {
        assertThat(ShipmentAllocator.allocate(List.of(PHONE), demo(true, true)))
                .containsExactly(entry("KRK", List.of(PHONE)));
        assertThat(ShipmentAllocator.allocate(List.of(NOVEL, WATCH), demo(true, true)))
                .containsExactly(entry("GDN", List.of(NOVEL, WATCH)));
    }

    @Test
    void aMixedCartIsSplitByPrimaryLocationInRankOrder() {
        assertThat(ShipmentAllocator.allocate(List.of(NOVEL, PHONE, WATCH), demo(true, true)))
                .containsExactly(entry("KRK", List.of(PHONE)), entry("GDN", List.of(NOVEL, WATCH)));
    }

    @Test
    void anInactiveLocationIsSkippedAndItsBackupTakesOver() {
        assertThat(ShipmentAllocator.allocate(List.of(NOVEL, PHONE), demo(true, false)))
                .containsExactly(entry("KRK", List.of(NOVEL, PHONE)));
        assertThat(ShipmentAllocator.allocate(List.of(NOVEL, PHONE), demo(false, true)))
                .containsExactly(entry("GDN", List.of(NOVEL, PHONE)));
    }

    @Test
    void aLineNoActiveLocationStocksFailsTheWholeAllocationWith409() {
        List<Location> krakowOnlyBooks = List.of(new Location("KRK", 1, true, Map.of("books", PRIMARY)),
                new Location("GDN", 2, false, Map.of("*", PRIMARY)));

        assertThatThrownBy(() -> ShipmentAllocator.allocate(List.of(NOVEL, PHONE), krakowOnlyBooks))
                .isInstanceOfSatisfying(CustomException.class, e -> {
                    assertThat(e.getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(e.getMessage()).isEqualTo("No active warehouse location stocks Electronics");
                });
        assertThatThrownBy(() -> ShipmentAllocator.allocate(List.of(PHONE), demo(false, false)))
                .hasMessage("No active warehouse location stocks Electronics");
    }

    @Test
    void theSingleLocationPreferenceBeatsAHigherPriorityLocationThatCannotShipEverything() {
        List<Location> locations = List.of(
                new Location("AAA", 1, true, Map.of("electronics", PRIMARY)),
                new Location("BBB", 2, true, Map.of("electronics", PRIMARY, "books", PRIMARY)));

        assertThat(ShipmentAllocator.allocate(List.of(PHONE, NOVEL), locations))
                .containsExactly(entry("BBB", List.of(PHONE, NOVEL)));
        assertThat(ShipmentAllocator.allocate(List.of(PHONE), locations))
                .containsExactly(entry("AAA", List.of(PHONE)));
    }

    @Test
    void tiesAreBrokenByPriorityThenCode() {
        List<Location> locations = List.of(
                new Location("ZZZ", 1, true, Map.of("*", PRIMARY)),
                new Location("BBB", 1, true, Map.of("*", PRIMARY)),
                new Location("AAA", 2, true, Map.of("*", PRIMARY)));

        assertThat(ShipmentAllocator.allocate(List.of(PHONE), locations)).containsOnlyKeys("BBB");
    }

    @Test
    void aBackupLocationIsOnlyUsedForLinesWithoutAnActivePrimary() {
        List<Location> locations = List.of(
                new Location("KRK", 1, true, Map.of("*", BACKUP)),
                new Location("GDN", 2, true, Map.of("books", PRIMARY)));

        assertThat(ShipmentAllocator.allocate(List.of(NOVEL, PHONE), locations))
                .containsExactly(entry("KRK", List.of(PHONE)), entry("GDN", List.of(NOVEL)));
    }

    @Test
    void explicitCategoryRowsOverrideTheWildcardAndMatchIgnoringCaseAndSpaces() {
        Location krakow = new Location("KRK", 1, true, Map.of("*", PRIMARY, "books", BACKUP));

        assertThat(krakow.roleFor(" BOOKS ")).isEqualTo(BACKUP);
        assertThat(krakow.roleFor("Kites")).isEqualTo(PRIMARY);
        assertThat(krakow.roleFor(null)).isEqualTo(PRIMARY);
        assertThat(new Location("GDN", 2, true, Map.of("books", PRIMARY)).stocks("Kites")).isFalse();
        assertThat(ShipmentAllocator.key(null)).isEmpty();
    }

    @Test
    void anOrderWithoutLinesGoesToTheFirstActiveLocation() {
        assertThat(ShipmentAllocator.allocate(List.of(), demo(false, true))).containsExactly(entry("GDN", List.of()));
        assertThat(ShipmentAllocator.allocate(List.of(), demo(false, false))).isEmpty();
    }

    @Test
    void theAlternativeIsAnotherActiveStockingLocationRankedByTierThenPreferenceThenPriority() {
        List<Location> locations = List.of(
                new Location("KRK", 1, true, Map.of("*", PRIMARY)),
                new Location("GDN", 2, true, Map.of("*", BACKUP)),
                new Location("WAW", 3, true, Map.of("*", BACKUP)),
                new Location("POZ", 4, false, Map.of("*", PRIMARY)));

        assertThat(ShipmentAllocator.alternative(Set.of("Books"), "GDN", locations, c -> true, c -> false))
                .contains("KRK");
        assertThat(ShipmentAllocator.alternative(Set.of("Books"), "KRK", locations, c -> true, c -> false))
                .contains("GDN");
        assertThat(ShipmentAllocator.alternative(Set.of("Books"), "KRK", locations, c -> true, "WAW"::equals))
                .contains("WAW");
        assertThat(ShipmentAllocator.alternative(Set.of("Books"), "KRK", locations, "WAW"::equals, c -> false))
                .contains("WAW");
        assertThat(ShipmentAllocator.alternative(Set.of("Books"), "KRK", locations, c -> false, c -> true)).isEmpty();
        assertThat(ShipmentAllocator.alternative(Set.of("Books", "Kites"), "KRK",
                List.of(locations.get(0), new Location("GDN", 2, true, Map.of("books", PRIMARY))),
                c -> true, c -> true)).isEmpty();
    }

    @Test
    void anAlternativePrimaryForEveryCategoryBeatsAMixOfPrimaryAndBackup() {
        List<Location> locations = List.of(
                new Location("KRK", 1, true, Map.of("*", PRIMARY)),
                new Location("GDN", 2, true, Map.of("books", PRIMARY, "*", BACKUP)),
                new Location("WAW", 3, true, Map.of("*", PRIMARY)));

        assertThat(ShipmentAllocator.alternative(List.of("Books", "Electronics"), "KRK", locations, c -> true,
                "GDN"::equals)).contains("WAW");
    }

    @Test
    void orderItemsAreMergedByProductInFirstAppearanceOrder() {
        InternalOrderDto order = new InternalOrderDto(1L, "u", null, null, null, null, null, null, null, List.of(
                new InternalOrderItemDto(1L, 5L, "Phone", "Electronics", 1, BigDecimal.ONE),
                new InternalOrderItemDto(2L, 6L, "Novel", "Books", 2, BigDecimal.ONE),
                new InternalOrderItemDto(3L, 5L, "Phone", "Electronics", 3, BigDecimal.ONE)), null, null);

        assertThat(WarehouseAllocation.lines(order)).containsExactly(
                new Line(5L, "Phone", "Electronics", 4), new Line(6L, "Novel", "Books", 2));
    }

    /** Property: every line is allocated exactly once, to an active location that stocks it in the line's best tier. */
    @Test
    void randomLayoutsAllocateEveryLineOnceToAnActiveLocationInItsBestTier() {
        Random random = new Random(20261114L);
        List<String> categories = List.of("a", "b", "c", "d", "e");
        for (int run = 0; run < 2_000; run++) {
            List<Location> locations = new ArrayList<>();
            int count = 1 + random.nextInt(4);
            for (int i = 0; i < count; i++) {
                Map<String, StockingRole> roles = new HashMap<>();
                for (String category : List.of("*", "a", "b", "c", "d", "e")) {
                    int pick = random.nextInt(4);
                    if (pick < 2) {
                        roles.put(category, StockingRole.values()[pick]);
                    }
                }
                locations.add(new Location("L" + i, 1 + random.nextInt(3), random.nextInt(4) > 0, roles));
            }
            List<Line> lines = new ArrayList<>();
            int lineCount = 1 + random.nextInt(5);
            for (int i = 0; i < lineCount; i++) {
                lines.add(new Line((long) i, "p" + i, categories.get(random.nextInt(categories.size())), 1));
            }
            String layout = "run " + run + ": " + locations + " " + lines;
            Map<String, List<Line>> allocation;
            try {
                allocation = ShipmentAllocator.allocate(lines, locations);
            } catch (CustomException e) {
                assertThat(lines).as(layout).anySatisfy(line -> assertThat(locations).noneMatch(
                        location -> location.active() && location.stocks(line.category())));
                continue;
            }
            assertThat(allocation.values().stream().flatMap(List::stream).toList()).as(layout)
                    .containsExactlyInAnyOrderElementsOf(lines);
            for (Map.Entry<String, List<Line>> entry : allocation.entrySet()) {
                Location location = locations.stream().filter(l -> l.code().equals(entry.getKey())).findFirst().orElseThrow();
                assertThat(location.active()).as(layout).isTrue();
                for (Line line : entry.getValue()) {
                    boolean primaryExists = locations.stream()
                            .anyMatch(l -> l.active() && l.roleFor(line.category()) == PRIMARY);
                    assertThat(location.roleFor(line.category())).as(layout)
                            .isEqualTo(primaryExists ? PRIMARY : BACKUP);
                }
            }
            if (allocation.size() > 1) {
                assertThat(locations).as(layout + " a single location could ship it all").noneMatch(location ->
                        location.active() && lines.stream().allMatch(line -> location.roleFor(line.category())
                                == allocation.entrySet().stream().filter(e -> e.getValue().contains(line))
                                .map(e -> roleAt(locations, e.getKey(), line.category())).findFirst().orElseThrow()));
            }
        }
    }

    private static StockingRole roleAt(List<Location> locations, String code, String category) {
        return locations.stream().filter(l -> l.code().equals(code)).findFirst().orElseThrow().roleFor(category);
    }
}
