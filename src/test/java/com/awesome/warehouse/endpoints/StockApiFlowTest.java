package com.awesome.warehouse.endpoints;

import com.awesome.warehouse.client.InternalProductDto;
import com.awesome.warehouse.entity.stock.InventoryMovementType;
import com.awesome.warehouse.entity.stock.ReservationStatus;
import com.awesome.warehouse.entity.stock.StockMovementEntity;
import com.awesome.warehouse.service.stock.StockTestAccess;
import com.awesome.warehouse.support.AbstractFlowTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The stock ledger API the backend calls ({@code /internal/v1/stock}): every status code of every
 * endpoint, the JSON bodies of the contract and the ledger rows behind them.
 */
class StockApiFlowTest extends AbstractFlowTest {

    private static final String STOCK = "/internal/v1/stock";
    private static final String SERVICE_TOKEN = "test-backend-service-token";
    private static final String CREATED_AT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,6})?";

    @AfterEach
    void ledgerReady() {
        StockTestAccess.markReady(stockReadiness);
    }

    // --- authentication and readiness, for every endpoint ---------------------------------------

    @Test
    void everyEndpointRejectsAMissingWrongOrUserToken() {
        for (MockHttpServletRequestBuilder request : everyEndpoint()) {
            assertThat(call(request, null).status()).isEqualTo(401);
        }
        for (MockHttpServletRequestBuilder request : everyEndpoint()) {
            Response wrong = call(request, "not-the-token");
            assertThat(wrong.status()).isEqualTo(401);
            assertThat(wrong.message()).isEqualTo("Invalid service token");
        }
        for (MockHttpServletRequestBuilder request : everyEndpoint()) {
            assertThat(call(request, adminToken).status()).as("a user's JWT is not the service token").isEqualTo(401);
        }
    }

    @Test
    void theServiceTokenIsNotAUserTokenOnThePublicApi() {
        assertThat(perform(get("/api/v1/admin/suppliers"), SERVICE_TOKEN).status()).isEqualTo(401);
    }

    @Test
    void everyEndpointAnswers503UntilTheLedgerIsInitialised() {
        StockTestAccess.markNotReady(stockReadiness);

        for (MockHttpServletRequestBuilder request : everyEndpoint()) {
            Response response = call(request, SERVICE_TOKEN);
            assertThat(response.status()).isEqualTo(503);
            assertThat(response.message()).isEqualTo("Stock ledger not initialised");
        }
    }

    private List<MockHttpServletRequestBuilder> everyEndpoint() {
        UUID id = UUID.randomUUID();
        return List.of(
                json(post(STOCK + "/reservations"), Map.of("reservationId", id, "username", "alice",
                        "lines", List.of(Map.of("productId", 1, "quantity", 1)))),
                json(post(STOCK + "/reservations/" + id + "/confirm"), Map.of("orderId", 1)),
                post(STOCK + "/reservations/" + id + "/release"),
                json(post(STOCK + "/adjustments"), Map.of("productId", 1, "delta", 1, "reason", "Found",
                        "requestId", UUID.randomUUID(), "actor", "admin")),
                json(put(STOCK + "/items/1"), Map.of("initialQuantity", 1, "actor", "admin")),
                delete(STOCK + "/items/1"),
                get(STOCK + "/items/1"),
                get(STOCK + "/movements").param("productId", "1"));
    }

    // --- 2.1 reserve ---------------------------------------------------------------------------

    @Test
    void reserveDeductsEveryLineInProductIdOrderAndReplaysTheStoredBody() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        InternalProductDto cable = backend.product("Cable", "Accessories", "2.00", 5);
        UUID reservationId = UUID.randomUUID();
        Map<String, Object> body = reserveBody(reservationId, "alice", cable.id(), 1, phone.id(), 2);

        Response reserved = call(json(post(STOCK + "/reservations"), body), SERVICE_TOKEN);
        Response replayed = call(json(post(STOCK + "/reservations"), body), SERVICE_TOKEN);
        Response otherLines = call(json(post(STOCK + "/reservations"),
                reserveBody(reservationId, "alice", phone.id(), 3)), SERVICE_TOKEN);
        awaitStockPush();

        assertThat(reserved.status()).isEqualTo(201);
        assertThat(reserved.body()).isEqualTo("{\"reservationId\":\"" + reservationId + "\",\"lines\":["
                + "{\"productId\":" + phone.id() + ",\"quantityAfter\":8,\"ledgerVersion\":2},"
                + "{\"productId\":" + cable.id() + ",\"quantityAfter\":4,\"ledgerVersion\":2}]}");
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(replayed.body()).isEqualTo(reserved.body());
        assertThat(otherLines.status()).isEqualTo(409);
        assertThat(otherLines.body()).isEqualTo("{\"message\":\"reservationId already used with different lines\"}");
        assertThat(movementsOf(phone.id())).extracting(StockMovementEntity::getType)
                .containsExactly(InventoryMovementType.INITIAL_STOCK, InventoryMovementType.ORDER_DEDUCTED);
        assertThat(movementsOf(phone.id()).getLast()).satisfies(movement -> {
            assertThat(movement.getDelta()).isEqualTo(-2);
            assertThat(movement.getActor()).isEqualTo("alice");
            assertThat(movement.getReason()).isEqualTo("Order deduction");
            assertThat(movement.getOrderId()).isNull();
            assertThat(movement.getReservationId()).isEqualTo(reservationId);
            assertThat(movement.getLedgerVersion()).isEqualTo(2L);
        });
        assertThat(stockReservations.findById(reservationId).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.PENDING_CONFIRMATION);
        assertThat(backend.stockOf(phone.id())).isEqualTo(8);
        assertThat(backend.ledgerVersionOf(phone.id())).isEqualTo(2);
    }

    @Test
    void reserveFailsOnTheFirstShortProductInIdOrderAndDeductsNothing() {
        InternalProductDto first = backend.product("Phone", "Electronics", "10.00", 1);
        InternalProductDto second = backend.product("Cable", "Accessories", "2.00", 1);
        InternalProductDto plenty = backend.product("Case", "Accessories", "2.00", 50);

        Response shortStock = call(json(post(STOCK + "/reservations"), reserveBody(UUID.randomUUID(), "alice",
                plenty.id(), 1, second.id(), 2, first.id(), 2)), SERVICE_TOKEN);

        assertThat(shortStock.status()).isEqualTo(409);
        assertThat(shortStock.body()).isEqualTo("{\"message\":\"Insufficient stock for product " + first.id()
                + "\",\"productId\":" + first.id() + "}");
        assertThat(stockItems.findById(plenty.id()).orElseThrow().getQuantity()).isEqualTo(50);
        assertThat(stockReservations.count()).isZero();
        assertThat(stockMovements.findAll()).noneMatch(m -> m.getType() == InventoryMovementType.ORDER_DEDUCTED);
    }

    @Test
    void reserveOfAProductWithoutStockItemIs404AndDeductsNothing() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        long unknown = phone.id() + 1000;

        Response missing = call(json(post(STOCK + "/reservations"), reserveBody(UUID.randomUUID(), "alice",
                phone.id(), 1, unknown, 1)), SERVICE_TOKEN);

        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body()).isEqualTo("{\"message\":\"Product " + unknown + " not found\",\"productId\":"
                + unknown + "}");
        assertThat(stockItems.findById(phone.id()).orElseThrow().getQuantity()).isEqualTo(10);
    }

    @Test
    void reserveRejectsMalformedRequests() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);

        Response noLines = call(json(post(STOCK + "/reservations"),
                Map.of("reservationId", UUID.randomUUID(), "username", "alice", "lines", List.of())), SERVICE_TOKEN);
        Response zeroQuantity = call(json(post(STOCK + "/reservations"),
                reserveBody(UUID.randomUUID(), "alice", phone.id(), 0)), SERVICE_TOKEN);
        Response noId = call(json(post(STOCK + "/reservations"), Map.of("username", "alice",
                "lines", List.of(Map.of("productId", phone.id(), "quantity", 1)))), SERVICE_TOKEN);
        Response duplicate = call(json(post(STOCK + "/reservations"),
                reserveBody(UUID.randomUUID(), "alice", phone.id(), 1, phone.id(), 2)), SERVICE_TOKEN);

        assertThat(List.of(noLines, zeroQuantity, noId, duplicate)).allSatisfy(r -> assertThat(r.status()).isEqualTo(400));
        assertThat(noLines.body()).contains("\"lines\"");
        assertThat(noId.body()).contains("\"reservationId\"");
        assertThat(duplicate.message()).isEqualTo("Product " + phone.id() + " is listed more than once");
        assertThat(stockItems.findById(phone.id()).orElseThrow().getQuantity()).isEqualTo(10);
    }

    // --- 2.2 confirm ---------------------------------------------------------------------------

    @Test
    void confirmSetsTheOrderOnTheMovementsAndIsIdempotentForTheSameOrder() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        UUID reservationId = reserve(phone, 2);

        Response confirmed = call(json(post(STOCK + "/reservations/" + reservationId + "/confirm"),
                Map.of("orderId", 123)), SERVICE_TOKEN);
        Response again = call(json(post(STOCK + "/reservations/" + reservationId + "/confirm"),
                Map.of("orderId", 123)), SERVICE_TOKEN);
        Response otherOrder = call(json(post(STOCK + "/reservations/" + reservationId + "/confirm"),
                Map.of("orderId", 124)), SERVICE_TOKEN);

        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(confirmed.body()).isEqualTo("{\"reservationId\":\"" + reservationId + "\",\"lines\":["
                + "{\"productId\":" + phone.id() + ",\"quantityAfter\":8,\"ledgerVersion\":2}]}");
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.body()).isEqualTo(confirmed.body());
        assertThat(otherOrder.status()).isEqualTo(409);
        assertThat(otherOrder.message()).isEqualTo("Reservation already confirmed for another order");
        assertThat(movementsOf(phone.id()).getLast().getOrderId()).isEqualTo(123L);
        assertThat(stockReservations.findById(reservationId).orElseThrow()).satisfies(reservation -> {
            assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(reservation.getOrderId()).isEqualTo(123L);
        });
    }

    @Test
    void confirmOfAReleasedOrUnknownReservationOrWithoutOrderFails() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        UUID released = reserve(phone, 1);
        call(post(STOCK + "/reservations/" + released + "/release"), SERVICE_TOKEN);

        Response afterRelease = call(json(post(STOCK + "/reservations/" + released + "/confirm"),
                Map.of("orderId", 1)), SERVICE_TOKEN);
        Response unknown = call(json(post(STOCK + "/reservations/" + UUID.randomUUID() + "/confirm"),
                Map.of("orderId", 1)), SERVICE_TOKEN);
        Response noOrder = call(json(post(STOCK + "/reservations/" + released + "/confirm"), Map.of()),
                SERVICE_TOKEN);

        assertThat(afterRelease.status()).isEqualTo(409);
        assertThat(afterRelease.message()).isEqualTo("Reservation already released");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.message()).isEqualTo("Reservation not found");
        assertThat(noOrder.status()).isEqualTo(400);
        assertThat(noOrder.body()).contains("\"orderId\"");
    }

    // --- 2.3 release ---------------------------------------------------------------------------

    @Test
    void releasePutsThePendingStockBackOnceAndRefusesConfirmedReservations() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        UUID pending = reserve(phone, 3);
        UUID confirmed = reserve(phone, 1);
        call(json(post(STOCK + "/reservations/" + confirmed + "/confirm"), Map.of("orderId", 9)), SERVICE_TOKEN);

        Response released = call(post(STOCK + "/reservations/" + pending + "/release"), SERVICE_TOKEN);
        Response repeated = call(post(STOCK + "/reservations/" + pending + "/release"), SERVICE_TOKEN);
        Response ofConfirmed = call(post(STOCK + "/reservations/" + confirmed + "/release"), SERVICE_TOKEN);
        Response unknown = call(post(STOCK + "/reservations/" + UUID.randomUUID() + "/release"), SERVICE_TOKEN);
        awaitStockPush();

        assertThat(released.status()).isEqualTo(200);
        assertThat(released.body()).isEqualTo("{\"reservationId\":\"" + pending + "\",\"status\":\"RELEASED\"}");
        assertThat(repeated.status()).isEqualTo(200);
        assertThat(repeated.body()).isEqualTo(released.body());
        assertThat(ofConfirmed.status()).isEqualTo(409);
        assertThat(ofConfirmed.message()).isEqualTo("Reservation already confirmed");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(movementsOf(phone.id()).getLast()).satisfies(movement -> {
            assertThat(movement.getType()).isEqualTo(InventoryMovementType.ORDER_RESTORED);
            assertThat(movement.getDelta()).isEqualTo(3);
            assertThat(movement.getActor()).isEqualTo("system");
            assertThat(movement.getReason()).isEqualTo("Reservation released");
            assertThat(movement.getQuantityAfter()).isEqualTo(9);
        });
        assertThat(movementsOf(phone.id())).filteredOn(m -> m.getType() == InventoryMovementType.ORDER_RESTORED)
                .hasSize(1);
        assertThat(backend.stockOf(phone.id())).isEqualTo(9);
        assertThat(backend.ledgerVersionOf(phone.id())).isEqualTo(4);
    }

    // --- 2.4 adjust ----------------------------------------------------------------------------

    @Test
    void adjustRecordsTheMovementWithItsLedgerVersionAndReplaysIt() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        UUID requestId = UUID.randomUUID();
        Map<String, Object> body = adjustBody(phone.id(), -3, "Damaged", requestId);

        Response adjusted = call(json(post(STOCK + "/adjustments"), body), SERVICE_TOKEN);
        Response replayed = call(json(post(STOCK + "/adjustments"), body), SERVICE_TOKEN);
        Response changed = call(json(post(STOCK + "/adjustments"), adjustBody(phone.id(), -3, "Lost", requestId)),
                SERVICE_TOKEN);
        awaitStockPush();

        assertThat(adjusted.status()).isEqualTo(201);
        JsonNode json = jsonMapper.readTree(adjusted.body());
        assertThat(json.propertyNames()).containsExactly("id", "productId", "orderId", "type", "delta",
                "quantityAfter", "actor", "reason", "requestId", "createdAt", "ledgerVersion");
        assertThat(json.get("orderId").isNull()).isTrue();
        assertThat(json.get("type").asString()).isEqualTo("ADMIN_ADJUSTMENT");
        assertThat(json.get("delta").asInt()).isEqualTo(-3);
        assertThat(json.get("quantityAfter").asInt()).isEqualTo(7);
        assertThat(json.get("actor").asString()).isEqualTo("admin");
        assertThat(json.get("reason").asString()).isEqualTo("Damaged");
        assertThat(json.get("requestId").asString()).isEqualTo(requestId.toString());
        assertThat(json.get("createdAt").asString()).matches(CREATED_AT);
        assertThat(json.get("ledgerVersion").asLong()).isEqualTo(2);
        assertThat(replayed.status()).isEqualTo(201);
        assertThat(replayed.body()).isEqualTo(adjusted.body());
        assertThat(changed.status()).isEqualTo(409);
        assertThat(changed.message()).isEqualTo("requestId already used with different payload");
        assertThat(backend.stockOf(phone.id())).isEqualTo(7);
    }

    @Test
    void adjustKeepsTheQuantityWithinItsBounds() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);

        Response belowZero = call(json(post(STOCK + "/adjustments"),
                adjustBody(phone.id(), -11, "Recount", UUID.randomUUID())), SERVICE_TOKEN);
        Response aboveMaximum = call(json(post(STOCK + "/adjustments"),
                adjustBody(phone.id(), Integer.MAX_VALUE, "Recount", UUID.randomUUID())), SERVICE_TOKEN);
        Response toZero = call(json(post(STOCK + "/adjustments"),
                adjustBody(phone.id(), -10, "Recount", UUID.randomUUID())), SERVICE_TOKEN);

        assertThat(belowZero.status()).isEqualTo(409);
        assertThat(belowZero.message()).isEqualTo("Insufficient stock");
        assertThat(aboveMaximum.status()).isEqualTo(409);
        assertThat(aboveMaximum.message()).isEqualTo("Inventory quantity exceeds supported maximum");
        assertThat(toZero.status()).isEqualTo(201);
        assertThat(stockItems.findById(phone.id()).orElseThrow().getQuantity()).isZero();
    }

    @Test
    void adjustOfAnUnknownProductOrAMalformedRequestFails() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);

        Response unknown = call(json(post(STOCK + "/adjustments"),
                adjustBody(phone.id() + 1000, 1, "Found", UUID.randomUUID())), SERVICE_TOKEN);
        Response zero = call(json(post(STOCK + "/adjustments"),
                adjustBody(phone.id(), 0, "Nothing", UUID.randomUUID())), SERVICE_TOKEN);
        Response noRequestId = call(json(post(STOCK + "/adjustments"),
                Map.of("productId", phone.id(), "delta", 1, "reason", "Found", "actor", "admin")), SERVICE_TOKEN);

        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.body()).isEqualTo("{\"message\":\"Product not found\"}");
        assertThat(zero.status()).isEqualTo(400);
        assertThat(zero.message()).isEqualTo("Inventory delta must not be zero");
        assertThat(noRequestId.status()).isEqualTo(400);
        assertThat(noRequestId.body()).contains("\"requestId\"");
    }

    // --- 2.5 initial stock ---------------------------------------------------------------------

    @Test
    void putCreatesTheItemOnceWithAnInitialStockMovement() {
        long productId = 900_001;

        Response created = call(json(put(STOCK + "/items/" + productId),
                Map.of("initialQuantity", 10, "actor", "admin", "requestId", UUID.randomUUID())), SERVICE_TOKEN);
        Response existing = call(json(put(STOCK + "/items/" + productId),
                Map.of("initialQuantity", 99, "actor", "admin")), SERVICE_TOKEN);
        Response empty = call(json(put(STOCK + "/items/" + (productId + 1)),
                Map.of("initialQuantity", 0, "actor", "admin")), SERVICE_TOKEN);
        Response negative = call(json(put(STOCK + "/items/" + (productId + 2)),
                Map.of("initialQuantity", -1, "actor", "admin")), SERVICE_TOKEN);

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body()).isEqualTo("{\"productId\":" + productId + ",\"quantity\":10,\"ledgerVersion\":1}");
        assertThat(existing.status()).isEqualTo(200);
        assertThat(existing.body()).isEqualTo(created.body());
        assertThat(movementsOf(productId)).singleElement().satisfies(movement -> {
            assertThat(movement.getType()).isEqualTo(InventoryMovementType.INITIAL_STOCK);
            assertThat(movement.getDelta()).isEqualTo(10);
            assertThat(movement.getQuantityAfter()).isEqualTo(10);
            assertThat(movement.getActor()).isEqualTo("admin");
            assertThat(movement.getReason()).isEqualTo("Initial stock");
            assertThat(movement.getRequestId()).isNull();
        });
        assertThat(empty.status()).isEqualTo(201);
        assertThat(empty.body()).isEqualTo("{\"productId\":" + (productId + 1) + ",\"quantity\":0,\"ledgerVersion\":1}");
        assertThat(movementsOf(productId + 1)).isEmpty();
        assertThat(negative.status()).isEqualTo(400);
        assertThat(stockItems.existsById(productId + 2)).isFalse();
    }

    // --- 2.6 delete ----------------------------------------------------------------------------

    @Test
    void deleteRemovesTheItemItsMovementsAndItsReservationLinesIdempotently() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        InternalProductDto cable = backend.product("Cable", "Accessories", "2.00", 10);
        UUID onlyPhone = reserve(phone, 1);
        UUID mixed = UUID.randomUUID();
        call(json(post(STOCK + "/reservations"), reserveBody(mixed, "alice", phone.id(), 1, cable.id(), 1)),
                SERVICE_TOKEN);

        Response deleted = call(delete(STOCK + "/items/" + phone.id()), SERVICE_TOKEN);
        Response again = call(delete(STOCK + "/items/" + phone.id()), SERVICE_TOKEN);

        assertThat(deleted.status()).isEqualTo(204);
        assertThat(again.status()).isEqualTo(204);
        assertThat(stockItems.existsById(phone.id())).isFalse();
        assertThat(movementsOf(phone.id())).isEmpty();
        assertThat(movementsOf(cable.id())).hasSize(2);
        assertThat(stockReservations.existsById(onlyPhone)).isFalse();
        assertThat(call(post(STOCK + "/reservations/" + mixed + "/release"), SERVICE_TOKEN).status()).isEqualTo(200);
        assertThat(stockItems.findById(cable.id()).orElseThrow().getQuantity()).isEqualTo(10);
        assertThat(call(get(STOCK + "/items/" + phone.id()), SERVICE_TOKEN).status()).isEqualTo(404);
    }

    // --- 2.7 reads -----------------------------------------------------------------------------

    @Test
    void getReturnsTheQuantityAndLedgerVersionOr404() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        reserve(phone, 4);

        Response item = call(get(STOCK + "/items/" + phone.id()), SERVICE_TOKEN);
        Response unknown = call(get(STOCK + "/items/" + (phone.id() + 1000)), SERVICE_TOKEN);

        assertThat(item.status()).isEqualTo(200);
        assertThat(item.body()).isEqualTo("{\"productId\":" + phone.id() + ",\"quantity\":6,\"ledgerVersion\":2}");
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.message()).isEqualTo("Product not found");
    }

    @Test
    void movementsArePagedNewestFirstInTheBackendsFormat() {
        InternalProductDto phone = backend.product("Phone", "Electronics", "10.00", 10);
        reserve(phone, 1);
        reserve(phone, 2);

        Response firstPage = call(get(STOCK + "/movements").param("productId", phone.id().toString())
                .param("page", "-1").param("size", "2"), SERVICE_TOKEN);
        Response clamped = call(get(STOCK + "/movements").param("productId", phone.id().toString())
                .param("size", "1000"), SERVICE_TOKEN);
        Response unknown = call(get(STOCK + "/movements").param("productId", "999999"), SERVICE_TOKEN);
        Response noProduct = call(get(STOCK + "/movements"), SERVICE_TOKEN);

        assertThat(firstPage.status()).isEqualTo(200);
        JsonNode page = jsonMapper.readTree(firstPage.body());
        assertThat(page.propertyNames()).containsExactly("content", "pageNumber", "pageSize", "totalElements",
                "totalPages");
        assertThat(page.get("pageNumber").asInt()).isZero();
        assertThat(page.get("pageSize").asInt()).isEqualTo(2);
        assertThat(page.get("totalElements").asInt()).isEqualTo(3);
        assertThat(page.get("totalPages").asInt()).isEqualTo(2);
        List<StockMovementEntity> stored = movementsOf(phone.id());
        assertThat(page.get("content").get(0).get("id").asLong()).isEqualTo(stored.get(2).getId());
        assertThat(page.get("content").get(1).get("id").asLong()).isEqualTo(stored.get(1).getId());
        JsonNode initial = jsonMapper.readTree(clamped.body()).get("content").get(2);
        assertThat(initial.toString()).isEqualTo("{\"id\":" + stored.getFirst().getId() + ",\"productId\":"
                + phone.id() + ",\"orderId\":null,\"type\":\"INITIAL_STOCK\",\"delta\":10,\"quantityAfter\":10,"
                + "\"actor\":\"admin\",\"reason\":\"Initial stock\",\"requestId\":null,\"createdAt\":\""
                + initial.get("createdAt").asString() + "\"}");
        assertThat(initial.get("createdAt").asString()).matches(CREATED_AT);
        assertThat(jsonMapper.readTree(clamped.body()).get("pageSize").asInt()).isEqualTo(100);
        assertThat(unknown.status()).isEqualTo(200);
        assertThat(jsonMapper.readTree(unknown.body()).get("totalElements").asInt()).isZero();
        assertThat(noProduct.status()).isEqualTo(400);
    }

    // --- helpers -------------------------------------------------------------------------------

    private UUID reserve(InternalProductDto product, int quantity) {
        UUID reservationId = UUID.randomUUID();
        Response reserved = call(json(post(STOCK + "/reservations"),
                reserveBody(reservationId, "alice", product.id(), quantity)), SERVICE_TOKEN);
        assertThat(reserved.status()).isEqualTo(201);
        return reservationId;
    }

    /** {@code lines} alternate product id and quantity. */
    private static Map<String, Object> reserveBody(UUID reservationId, String username, Object... lines) {
        List<Map<String, Object>> body = new java.util.ArrayList<>();
        for (int i = 0; i < lines.length; i += 2) {
            body.add(Map.of("productId", lines[i], "quantity", lines[i + 1]));
        }
        return Map.of("reservationId", reservationId, "username", username, "lines", body);
    }

    private static Map<String, Object> adjustBody(Long productId, int delta, String reason, UUID requestId) {
        return Map.of("productId", productId, "delta", delta, "reason", reason, "requestId", requestId,
                "actor", "admin");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, Object body) {
        return builder.contentType(MediaType.APPLICATION_JSON).content(jsonMapper.writeValueAsString(body));
    }

    private Response call(MockHttpServletRequestBuilder builder, String token) {
        return perform(builder, token);
    }

    private Response perform(MockHttpServletRequestBuilder builder, String token) {
        if (token != null) {
            builder.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        try {
            return new Response(mockMvc.perform(builder).andReturn(), jsonMapper);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
