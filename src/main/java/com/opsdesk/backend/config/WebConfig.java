package com.opsdesk.backend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.opsdesk.backend.ticket.TicketEditAccessInterceptor;

// Spring MVC setup beyond the defaults: the interceptors.
// (WebMvcConfigurer is the same in Boot 3.)
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TicketEditAccessInterceptor ticketEditAccessInterceptor;

    public WebConfig(TicketEditAccessInterceptor ticketEditAccessInterceptor) {
        this.ticketEditAccessInterceptor = ticketEditAccessInterceptor;
    }

    // Only on single-ticket paths. "*" matches exactly one path segment, so
    // this is /api/v1/tickets/{id} and not the list; the interceptor itself
    // only acts on PATCH.
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(ticketEditAccessInterceptor).addPathPatterns("/api/v1/tickets/*");
    }
}
