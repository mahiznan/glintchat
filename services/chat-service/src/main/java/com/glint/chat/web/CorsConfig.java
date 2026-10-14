package com.glint.chat.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** DEVELOPMENT: the page is served by the gateway (8081) and reads history from here (8080). */
@Configuration
public class CorsConfig implements WebMvcConfigurer {
    private final String allowedOrigin;

    public CorsConfig(@Value("${chat.cors.allowed-origin}") String allowedOrigin) { this.allowedOrigin = allowedOrigin; }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/conversations/**").allowedOrigins(allowedOrigin).allowedMethods("GET").allowedHeaders("*");
    }
}
