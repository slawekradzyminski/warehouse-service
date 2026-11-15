package com.awesome.warehouse.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class BackendServiceTokenFilterTest {

    private static final String TOKEN = "backend-token";

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletResponse filter(String expectedToken, String authorization, FilterChain chain)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/v1/stock/items/1");
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        new BackendServiceTokenFilter(expectedToken).doFilter(request, response, chain);
        return response;
    }

    @Test
    void theRightBearerTokenAuthenticatesTheBackend() throws Exception {
        AtomicReference<Authentication> seen = new AtomicReference<>();
        FilterChain chain = (request, response) -> seen.set(SecurityContextHolder.getContext().getAuthentication());

        MockHttpServletResponse response = filter(TOKEN, "Bearer " + TOKEN, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(seen.get().getName()).isEqualTo("BACKEND");
        assertThat(seen.get().isAuthenticated()).isTrue();
        assertThat(seen.get().getAuthorities()).extracting(Object::toString).containsExactly("SERVICE_BACKEND");
    }

    @Test
    void aMissingWrongOrNonBearerTokenIs401() throws Exception {
        for (String header : new String[] {null, "Bearer wrong", "Bearer ", TOKEN, "Basic " + TOKEN,
            "Bearer " + TOKEN + " "}) {
            FilterChain chain = mock(FilterChain.class);

            MockHttpServletResponse response = filter(TOKEN, header, chain);

            assertThat(response.getStatus()).as(String.valueOf(header)).isEqualTo(401);
            assertThat(response.getContentAsString()).isEqualTo("{\"message\":\"Invalid service token\"}");
            verify(chain, never()).doFilter(any(), any());
        }
    }

    @Test
    void withoutAConfiguredTokenTheApiDoesNotExist() throws Exception {
        for (String expected : new String[] {null, "", "  "}) {
            FilterChain chain = mock(FilterChain.class);

            MockHttpServletResponse response = filter(expected, "Bearer " + TOKEN, chain);

            assertThat(response.getStatus()).isEqualTo(404);
            assertThat(response.getContentAsString()).isEqualTo("{\"message\":\"Not found\"}");
            verify(chain, never()).doFilter(any(), any());
        }
    }

    @Test
    void tokensAreComparedAsSha256Digests() {
        assertThat(BackendServiceTokenFilter.digest(TOKEN)).hasSize(32)
                .isEqualTo(BackendServiceTokenFilter.digest(TOKEN))
                .isNotEqualTo(BackendServiceTokenFilter.digest(TOKEN + "x"));
    }
}
