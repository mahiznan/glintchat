package com.glint.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS lives at the edge: the browser only ever calls the gateway (decision of 2026-10-15, D18), so chat-service has
 * none. One origin from configuration per environment, never a wildcard.
 */
@Configuration
public class GatewayCorsConfig implements WebMvcConfigurer {
    private final String allowedOrigin;

    public GatewayCorsConfig(@Value("${gateway.cors.allowed-origin}") String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/conversations/**").allowedOrigins(allowedOrigin).allowedMethods("GET").allowedHeaders("*");
    }
}
