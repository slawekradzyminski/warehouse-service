package com.awesome.warehouse.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security. Role rules live on the controllers
 * ({@code @PreAuthorize}); this chain only separates public documentation,
 * health and parcel-tracking endpoints from authenticated API calls.
 * The stock ledger API ({@value #STOCK_API}) has its own chain, ordered first, that accepts
 * only the backend's service token ({@link BackendServiceTokenFilter}).
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class WebSecurityConfig {

    static final String STOCK_API = "/internal/v1/stock/**";

    @Bean
    @Order(1)
    SecurityFilterChain stockApiFilterChain(HttpSecurity http,
                                            @Value("${warehouse.backend-service-token:}") String backendServiceToken) {
        http.securityMatcher(STOCK_API)
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .anyRequest().hasAuthority(BackendServiceTokenFilter.AUTHORITY))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                ErrorResponses.send(response, HttpStatus.UNAUTHORIZED, "Invalid service token"))
                        .accessDeniedHandler((request, response, e) ->
                                ErrorResponses.send(response, HttpStatus.FORBIDDEN, "Access denied")))
                .addFilterBefore(new BackendServiceTokenFilter(backendServiceToken),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain filterChain(HttpSecurity http, JwtTokenValidator validator) {
        http.csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/tracking/*").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                ErrorResponses.send(response, HttpStatus.UNAUTHORIZED, "Unauthorized"))
                        .accessDeniedHandler((request, response, e) ->
                                ErrorResponses.send(response, HttpStatus.FORBIDDEN, "Access denied")))
                .addFilterBefore(new JwtTokenFilter(validator), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** No local users: replaces Boot's generated default user. */
    @Bean
    UserDetailsService userDetailsService() {
        return new InMemoryUserDetailsManager();
    }
}
