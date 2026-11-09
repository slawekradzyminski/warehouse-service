package com.awesome.warehouse.client;

import com.awesome.warehouse.controller.exception.CustomException;
import com.awesome.warehouse.dto.order.PageDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** {@link BackendClient} over HTTP with Spring's {@link RestClient}. */
public class RestBackendClient implements BackendClient {

    public static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private static final ParameterizedTypeReference<PageDto<InternalOrderDto>> ORDER_PAGE =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<InternalOrderDto>> ORDER_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<InternalProductDto>> PRODUCT_LIST =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public RestBackendClient(RestClient.Builder builder, String baseUrl, String serviceToken) {
        if (serviceToken == null || serviceToken.isBlank()) {
            throw new IllegalStateException("warehouse.service-token must be configured");
        }
        this.restClient = builder
                .baseUrl(baseUrl)
                .defaultHeader(SERVICE_TOKEN_HEADER, serviceToken)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw BackendErrorMapper.fromResponse(
                            response.getStatusCode(), StreamUtils.copyToByteArray(response.getBody()));
                })
                .build();
    }

    @Override
    public InternalOrderDto getOrder(long orderId) {
        return call(() -> restClient.get()
                .uri("/internal/v1/orders/{id}", orderId)
                .retrieve()
                .body(InternalOrderDto.class));
    }

    @Override
    public PageDto<InternalOrderDto> fulfilmentQueue(int page, int size) {
        return call(() -> restClient.get()
                .uri(uri -> uri.path("/internal/v1/orders")
                        .queryParam("status", "PAID")
                        .queryParam("fulfilment", "NONE")
                        .queryParam("page", page)
                        .queryParam("size", size)
                        .build())
                .retrieve()
                .body(ORDER_PAGE));
    }

    @Override
    public List<InternalOrderDto> getOrders(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String joined = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return call(() -> restClient.get()
                .uri(uri -> uri.path("/internal/v1/orders").queryParam("ids", joined).build())
                .retrieve()
                .body(ORDER_LIST));
    }

    @Override
    public PageDto<InternalOrderDto> ordersAwaitingPick(int page, int size) {
        return call(() -> restClient.get()
                .uri(uri -> uri.path("/internal/v1/orders")
                        .queryParam("status", "PAID")
                        .queryParam("fulfilment", "AWAITING_PICK")
                        .queryParam("page", page)
                        .queryParam("size", size)
                        .build())
                .retrieve()
                .body(ORDER_PAGE));
    }

    @Override
    public InternalOrderDto updateFulfilment(long orderId, FulfilmentState state) {
        return call(() -> restClient.put()
                .uri("/internal/v1/orders/{id}/fulfilment", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new FulfilmentUpdateRequest(state))
                .retrieve()
                .body(InternalOrderDto.class));
    }

    @Override
    public InternalOrderDto warehouseCancel(long orderId) {
        return call(() -> restClient.post()
                .uri("/internal/v1/orders/{id}/warehouse-cancel", orderId)
                .retrieve()
                .body(InternalOrderDto.class));
    }

    @Override
    public List<InternalProductDto> getProducts(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String joined = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return call(() -> restClient.get()
                .uri(uri -> uri.path("/internal/v1/products").queryParam("ids", joined).build())
                .retrieve()
                .body(PRODUCT_LIST));
    }

    @Override
    public List<InternalProductDto> getAllProducts() {
        return call(() -> restClient.get()
                .uri("/internal/v1/products")
                .retrieve()
                .body(PRODUCT_LIST));
    }

    @Override
    public InventoryReceiptResponse receiveInventory(InventoryReceiptRequest request) {
        return call(() -> restClient.post()
                .uri("/internal/v1/inventory/receipts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(InventoryReceiptResponse.class));
    }

    @Override
    public void sendNotification(NotificationRequest request) {
        call(() -> restClient.post()
                .uri("/internal/v1/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .toBodilessEntity());
    }

    @Override
    public InternalOrderDto refundOrder(long orderId, OrderRefundRequest request) {
        return call(() -> restClient.post()
                .uri("/internal/v1/orders/{id}/refunds", orderId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(InternalOrderDto.class));
    }

    @Override
    public JwkSet jwks() {
        return call(() -> restClient.get()
                .uri("/internal/v1/jwks.json")
                .retrieve()
                .body(JwkSet.class));
    }

    private static <T> T call(Supplier<T> request) {
        try {
            T body = request.get();
            if (body == null) {
                throw BackendErrorMapper.unavailable(new IllegalStateException("Backend returned an empty body"));
            }
            return body;
        } catch (CustomException e) {
            throw e;
        } catch (RestClientException e) {
            throw BackendErrorMapper.unavailable(e);
        }
    }
}
