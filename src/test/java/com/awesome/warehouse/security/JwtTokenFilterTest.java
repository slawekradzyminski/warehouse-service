package com.awesome.warehouse.security;

import com.awesome.warehouse.support.TestTokens;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenFilterTest {

    private final JwtTokenFilter filter = new JwtTokenFilter(new JwtTokenValidator(TestTokens.SECRET, false));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validTokenAuthenticatesTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TestTokens.token("alice", "ROLE_WAREHOUSE"));
        AtomicReference<Authentication> seen = new AtomicReference<>();
        FilterChain chain = (req, res) -> seen.set(SecurityContextHolder.getContext().getAuthentication());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seen.get().getName()).isEqualTo("alice");
    }

    @Test
    void requestWithoutTokenContinuesAnonymously() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void invalidTokenIsRejectedWithAnErrorBodyAndStopsTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TestTokens.token(TestTokens.SECRET, "alice", -5_000L, "ROLE_ADMIN"));
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getContentAsString()).isEqualTo("{\"message\":\"Invalid or expired token\"}");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void invalidTokenClearsAnyAuthenticationLeftOnTheThread() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new JwtTokenValidator(TestTokens.SECRET, false).authenticate(TestTokens.token("stale", "ROLE_ADMIN")));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer forged.token.value");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
