package com.opsdesk.backend.config;

import java.util.Set;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

// The parts of the Swagger document springdoc can't work out from the code
// (decision 74: Swagger is served only under the dev profile, so none of this
// is reachable in production).
//
// The bearer token is described here once, rather than with an annotation on
// every controller method: a security SCHEME (HTTP bearer, a JWT), which gives
// the Swagger page its "Authorize" button, and a security REQUIREMENT on each
// operation that needs it, which is what makes the page send the token.
@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearerToken";

    // The two operations SecurityConfig lets through without a token
    // (decision 18). KEEP IN STEP with SecurityConfig's permitAll for
    // /api/v1/auth/register and /api/v1/auth/login: everything else is marked
    // as needing the token, which is true because everything else is
    // anyRequest().authenticated().
    private static final Set<String> OPEN_OPERATIONS = Set.of(
            "POST /api/v1/auth/register",
            "POST /api/v1/auth/login");

    @Bean
    public OpenAPI opsdeskOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("OpsDesk API")
                        .version("v1")
                        .description(DESCRIPTION))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("The token from POST /api/v1/auth/login. Paste the token alone: "
                                + "Swagger adds \"Bearer \" itself. It lasts 60 minutes.")));
    }

    // Runs after springdoc has read the controllers: marks every operation
    // except the open two as needing the bearer token.
    @Bean
    public OpenApiCustomizer requireTheTokenExceptOnTheOpenOperations() {
        return openApi -> openApi.getPaths().forEach((path, item) ->
                item.readOperationsMap().forEach((method, operation) -> {
                    if (!OPEN_OPERATIONS.contains(method + " " + path)) {
                        operation.addSecurityItem(new SecurityRequirement().addList(BEARER));
                    }
                }));
    }

    // Shown at the top of the Swagger page (decision 73).
    private static final String DESCRIPTION = """
            Generated from the running code, so the paths, methods, parameters, request fields \
            (their types, allowed values and limits) and response shapes are what this server does. \
            The rules this page can't show are in \
            [API_CONTRACT.md](https://github.com/Stormmzyy/opsdesk-frontend/blob/main/API_CONTRACT.md): \
            which status codes each endpoint returns and why, the error body, who may do what, the \
            order of the checks, trimming, and refused fields. Where this page and the contract differ \
            about those, the contract wins.

            To call an endpoint that needs a token: run POST /api/v1/auth/login with a seeded account \
            (development password devpassword123), copy "token" from the answer, press Authorize and \
            paste it.""";
}
