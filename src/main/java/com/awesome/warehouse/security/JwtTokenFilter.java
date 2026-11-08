package com.awesome.warehouse.security;

import com.awesome.warehouse.controller.exception.CustomException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates requests that carry a bearer token. Requests without a token
 * continue anonymously (protected endpoints then answer 401 "Unauthorized");
 * a present but invalid token is rejected immediately with 401.
 */
@RequiredArgsConstructor
public class JwtTokenFilter extends OncePerRequestFilter {

    private final JwtTokenValidator validator;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = JwtTokenValidator.extractToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (token == null) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            SecurityContextHolder.getContext().setAuthentication(validator.authenticate(token));
        } catch (CustomException e) {
            SecurityContextHolder.clearContext();
            ErrorResponses.send(response, e.getHttpStatus(), e.getMessage());
            return;
        }
        filterChain.doFilter(request, response);
    }
}
