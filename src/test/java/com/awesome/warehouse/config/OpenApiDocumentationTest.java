package com.awesome.warehouse.config;

import com.awesome.warehouse.support.AbstractFlowTest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiDocumentationTest extends AbstractFlowTest {

    @Autowired
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @Test
    void everyControllerEndpointHasSummaryDescriptionAndResponses() {
        List<String> violations = new ArrayList<>();
        requestMappingHandlerMapping.getHandlerMethods().forEach((mapping, handler) -> validate(mapping, handler, violations));

        assertThat(violations).withFailMessage("Missing OpenAPI annotations:\n%s", String.join("\n", violations)).isEmpty();
    }

    @Test
    void apiDocsArePublicAndDescribeThePublicPathsWithBearerAuth() {
        Response docs = getJson("/v3/api-docs", null);
        JsonNode root = jsonMapper.readTree(docs.body());

        assertThat(docs.status()).isEqualTo(200);
        assertThat(root.at("/components/securitySchemes/bearerAuth/scheme").asString()).isEqualTo("bearer");
        assertThat(root.at("/components/securitySchemes/bearerAuth/bearerFormat").asString()).isEqualTo("JWT");
        assertThat(root.get("paths").propertyNames()).contains(
                "/api/v1/warehouse/queue",
                "/api/v1/warehouse/shipments",
                "/api/v1/warehouse/shipments/{id}/pick-list",
                "/api/v1/warehouse/shipments/{id}/cancel",
                "/api/v1/orders/{id}/shipment",
                "/api/v1/orders/{id}/shipments",
                "/api/v1/warehouse/locations",
                "/api/v1/warehouse/locations/{code}",
                "/api/v1/warehouse/shipments/{id}/reallocate",
                "/api/v1/warehouse/shipments/{id}/short-pick",
                "/api/v1/warehouse/reconciliation",
                "/api/v1/orders/{orderId}/returns",
                "/api/v1/returns",
                "/api/v1/returns/{id}",
                "/api/v1/admin/returns/{id}/refund",
                "/api/v1/admin/suppliers",
                "/api/v1/admin/purchase-orders/{id}/receipts",
                "/api/v1/admin/inventory/reorder-suggestions");
        assertThat(root.at("/paths/~1api~1v1~1warehouse~1queue/get/responses/503")).isNotNull();
        assertThat(root.at("/paths/~1api~1v1~1warehouse~1queue/get/responses").has("503")).isTrue();
        assertThat(root.at("/components/schemas/ShipmentDto/properties").propertyNames())
                .contains("locationCode", "locationName", "parcelIndex", "parcelCount", "lines");
    }

    @Test
    void healthAndPrometheusArePublicButOtherActuatorEndpointsAreNot() {
        assertThat(getJson("/actuator/health", null).status()).isEqualTo(200);
        assertThat(getJson("/actuator/prometheus", null).status()).isEqualTo(200);
        assertThat(getJson("/actuator/env", null).status()).isEqualTo(401);
    }

    private static void validate(RequestMappingInfo mapping, HandlerMethod handler, List<String> violations) {
        if (!handler.getBeanType().getPackageName().startsWith("com.awesome.warehouse.controller")) {
            return;
        }
        String endpoint = mapping.getPatternValues() + " -> " + handler.getBeanType().getSimpleName() + "#"
                + handler.getMethod().getName();
        Operation operation = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), Operation.class);
        if (operation == null || operation.summary().isBlank() || operation.description().isBlank()) {
            violations.add(endpoint + " needs @Operation with summary and description");
        }
        if (AnnotatedElementUtils.findMergedRepeatableAnnotations(handler.getMethod(), ApiResponse.class).isEmpty()) {
            violations.add(endpoint + " needs @ApiResponse entries");
        }
    }
}
