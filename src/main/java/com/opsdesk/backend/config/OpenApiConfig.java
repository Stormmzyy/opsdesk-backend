package com.opsdesk.backend.config;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
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

    // The {id} in /tickets/{id} and /projects/{id}. The controllers take it as
    // a String on purpose (decision 35), and springdoc reports that type,
    // which is the honest one: the server accepts any text there. Declaring
    // it an integer would make Swagger UI and generated clients refuse "abc"
    // before sending it, and turn "02" into 2, hiding both behaviours the
    // contract promises. So the type stays string, with no pattern (a pattern
    // would mark "abc" as invalid input, when the server's answer to it is a
    // defined 404), and this description says what the server does.
    @Bean
    public OpenApiCustomizer describeTheIdInAPath() {
        return openApi -> openApi.getPaths().values().forEach(item ->
                item.readOperations().forEach(operation -> {
                    if (operation.getParameters() == null) {
                        return;
                    }
                    operation.getParameters().stream()
                            .filter(parameter -> "path".equals(parameter.getIn()) && "id".equals(parameter.getName()))
                            .forEach(parameter -> {
                                parameter.setDescription(ID_IN_A_PATH);
                                parameter.setExample("1");
                            });
                }));
    }

    // Every field of every object a response contains is always sent: a
    // ticket always has its eleven fields (ApiContractTest pins exactly
    // those), null where the contract says so. springdoc marks a record's
    // fields required only when told to, so it listed none, which reads as
    // "any of these may be missing". This finds every schema reachable from a
    // response (through $ref, array items, oneOf/allOf and properties) and
    // marks all of its properties required. Found by walking the document,
    // not from a list of classes, so a new response record is covered too and
    // the request schemas, which have their own required lists, aren't.
    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    public OpenApiCustomizer everyResponseFieldIsAlwaysSent() {
        return openApi -> {
            Map<String, Schema> schemas = openApi.getComponents().getSchemas();
            Set<String> reached = new HashSet<>();
            Deque<Schema> toVisit = new ArrayDeque<>();
            openApi.getPaths().values().forEach(item -> item.readOperations().forEach(operation -> {
                if (operation.getResponses() != null) {
                    operation.getResponses().values().forEach(response -> {
                        if (response.getContent() != null) {
                            response.getContent().values().forEach(media -> {
                                if (media.getSchema() != null) {
                                    toVisit.push(media.getSchema());
                                }
                            });
                        }
                    });
                }
            }));
            while (!toVisit.isEmpty()) {
                Schema schema = toVisit.pop();
                String ref = schema.get$ref();
                if (ref != null) {
                    String name = ref.substring(ref.lastIndexOf('/') + 1);
                    if (reached.add(name) && schemas.get(name) != null) {
                        toVisit.push(schemas.get(name));
                    }
                    continue;
                }
                if (schema.getItems() != null) {
                    toVisit.push(schema.getItems());
                }
                if (schema.getOneOf() != null) {
                    schema.getOneOf().forEach(branch -> toVisit.push((Schema) branch));
                }
                if (schema.getAllOf() != null) {
                    schema.getAllOf().forEach(branch -> toVisit.push((Schema) branch));
                }
                if (schema.getProperties() != null) {
                    schema.getProperties().values().forEach(property -> toVisit.push((Schema) property));
                }
            }
            reached.forEach(name -> {
                Schema schema = schemas.get(name);
                if (schema != null && schema.getProperties() != null) {
                    schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
                }
            });
        };
    }

    private static final String ID_IN_A_PATH = """
            The id, as text. A positive whole number; leading zeros are accepted (02 is the same as 2). \
            Any other text (abc, 3.5, -1) isn't refused as invalid: it answers 404 NOT_FOUND, exactly \
            like an id that doesn't exist, and the 404 quotes the id as it was sent \
            (API_CONTRACT.md, Conventions).""";

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
