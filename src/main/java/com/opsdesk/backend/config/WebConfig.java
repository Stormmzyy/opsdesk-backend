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
    // "/api/v1/tickets/*" is /tickets/{id} and NOT /tickets/{id}/assignee:
    // that one needs its own pattern (measured: with only the first, the
    // assignee endpoint skipped the interceptor). The interceptor itself
    // decides which methods it acts on.
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(ticketEditAccessInterceptor)
                .addPathPatterns("/api/v1/tickets/*", "/api/v1/tickets/*/assignee");
    }
}
