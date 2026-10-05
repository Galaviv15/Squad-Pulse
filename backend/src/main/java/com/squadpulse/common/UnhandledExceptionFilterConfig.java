package com.squadpulse.common;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import tools.jackson.databind.json.JsonMapper;

/**
 * Registers {@link UnhandledExceptionFilter} with the servlet container.
 *
 * <p>Order {@link #ORDER}: right after Boot's {@code characterEncodingFilter} ({@code
 * HIGHEST_PRECEDENCE}, which only sets the request encoding), and so before every other filter —
 * Spring Security's chain ({@code SecurityFilterProperties.DEFAULT_FILTER_ORDER}, -100) included.
 * {@code ErrorRenderingIntegrationTest} proves that order against the filters the running server
 * actually has.
 *
 * <p>{@code REQUEST} dispatches only: the {@code /error} dispatch is rendered by {@link
 * ApiErrorController}, and if even that failed, there'd be nothing better to answer with.
 *
 * <p>Only in a web application: the {@code bootstrap} profile runs without a web server.
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class UnhandledExceptionFilterConfig {

  static final String FILTER_NAME = "unhandledExceptionFilter";

  static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

  @Bean
  FilterRegistrationBean<UnhandledExceptionFilter> unhandledExceptionFilter(JsonMapper jsonMapper) {
    FilterRegistrationBean<UnhandledExceptionFilter> registration =
        new FilterRegistrationBean<>(new UnhandledExceptionFilter(jsonMapper));
    registration.setName(FILTER_NAME);
    registration.setOrder(ORDER);
    registration.setDispatcherTypes(DispatcherType.REQUEST);
    return registration;
  }
}
