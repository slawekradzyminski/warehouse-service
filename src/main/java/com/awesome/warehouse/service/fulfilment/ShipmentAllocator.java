package com.awesome.warehouse.service.fulfilment;

import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.entity.fulfilment.LocationProductEntity;
import com.awesome.warehouse.entity.fulfilment.StockingRole;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Allocates order lines to warehouse locations (pure: no I/O).
 *
 * <p>A line's <em>tier</em> is PRIMARY when some active location stocks its category as
 * PRIMARY, otherwise BACKUP when some active location stocks it as BACKUP; a line no
 * active location stocks fails the whole allocation with 409. Locations are ranked by
 * priority (lower first), then code. When one active location stocks every line in that
 * line's tier, the highest-ranked such location takes the whole order (one parcel);
 * otherwise each line goes to the highest-ranked active location stocking it in its tier.
 */
public final class ShipmentAllocator {

    static final Comparator<Location> RANK = Comparator.comparingInt(Location::priority).thenComparing(Location::code);

    private ShipmentAllocator() {
    }

    /** A location with its stocking roles, keyed by {@link #key(String)} ({@code "*"} matches any category). */
    public record Location(String code, int priority, boolean active, Map<String, StockingRole> roles) {

        public Location {
            roles = Map.copyOf(roles);
        }

        /** The role for the category: its own row, else the {@code "*"} row, else null (not stocked). */
        public StockingRole roleFor(String category) {
            StockingRole own = roles.get(key(category));
            return own != null ? own : roles.get(LocationProductEntity.ANY_CATEGORY);
        }

        public boolean stocks(String category) {
            return roleFor(category) != null;
        }
    }

    /** One product of an order with its total quantity. */
    public record Line(Long productId, String productName, String category, int quantity) {
    }

    /**
     * Location code to the lines it ships, in location rank order.
     *
     * @throws CustomException 409 {@code No active warehouse location stocks <category>}
     */
    public static Map<String, List<Line>> allocate(List<Line> lines, Collection<Location> locations) {
        List<Location> active = locations.stream().filter(Location::active).sorted(RANK).toList();
        List<StockingRole> tiers = new ArrayList<>(lines.size());
        for (Line line : lines) {
            tiers.add(tier(line.category(), active));
        }
        Optional<Location> whole = active.stream().filter(location -> servesAll(location, lines, tiers)).findFirst();
        Map<String, List<Line>> allocation = new LinkedHashMap<>();
        if (whole.isPresent()) {
            allocation.put(whole.get().code(), List.copyOf(lines));
            return allocation;
        }
        for (Location location : active) {
            List<Line> mine = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (firstWithRole(active, lines.get(i).category(), tiers.get(i)).code().equals(location.code())) {
                    mine.add(lines.get(i));
                }
            }
            if (!mine.isEmpty()) {
                allocation.put(location.code(), List.copyOf(mine));
            }
        }
        return allocation;
    }

    /**
     * Another active location (not {@code excluded}) that stocks every category and passes
     * {@code eligible}; ranked by tier (PRIMARY for every category first), then
     * {@code preferred} (for example: the order already has a parcel there), then rank.
     */
    public static Optional<String> alternative(Collection<String> categories, String excluded,
                                               Collection<Location> locations, Predicate<String> eligible,
                                               Predicate<String> preferred) {
        return locations.stream()
                .filter(Location::active)
                .filter(location -> !location.code().equals(excluded))
                .filter(location -> categories.stream().allMatch(location::stocks))
                .filter(location -> eligible.test(location.code()))
                .min(Comparator.comparing((Location location) -> !primaryForAll(location, categories))
                        .thenComparing(location -> !preferred.test(location.code()))
                        .thenComparing(RANK))
                .map(Location::code);
    }

    /** Lower-cased, trimmed category used as the stocking key; null becomes the empty string. */
    public static String key(String category) {
        return category == null ? "" : category.trim().toLowerCase(Locale.ROOT);
    }

    private static StockingRole tier(String category, List<Location> active) {
        for (StockingRole role : StockingRole.values()) {
            if (active.stream().anyMatch(location -> location.roleFor(category) == role)) {
                return role;
            }
        }
        throw new CustomException("No active warehouse location stocks " + category, HttpStatus.CONFLICT);
    }

    private static boolean servesAll(Location location, List<Line> lines, List<StockingRole> tiers) {
        for (int i = 0; i < lines.size(); i++) {
            if (location.roleFor(lines.get(i).category()) != tiers.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static Location firstWithRole(List<Location> active, String category, StockingRole role) {
        return active.stream().filter(location -> location.roleFor(category) == role).findFirst().orElseThrow();
    }

    private static boolean primaryForAll(Location location, Collection<String> categories) {
        return categories.stream().allMatch(category -> location.roleFor(category) == StockingRole.PRIMARY);
    }
}
