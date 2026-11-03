package com.opsdesk.backend.config;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import com.opsdesk.backend.error.ApiError;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
// Boot 3: com.fasterxml.jackson.databind.ObjectMapper (Jackson 2). Boot 4
// uses Jackson 3, whose classes moved to the tools.jackson package, and
// configures a JsonMapper bean.
import tools.jackson.databind.json.JsonMapper;

// The 401 for a request that needs a signed-in user and doesn't have one: no
// Authorization header, or a token that's invalid, expired, or for an account
// that no longer exists. Spring Security calls this when the chain refuses
// such a request.
//
// Why this is needed: with no entry point set, Spring Security answers an
// API request that isn't signed in with an empty 403, which says "signed in,
// but not allowed", the wrong thing (NOTES.md, decision 21). This answers 401
// UNAUTHORIZED in the contract's error shape instead.
//
// Why here and not in GlobalExceptionHandler: this happens inside the
// security filters, before the request reaches Spring MVC, so no
// @ExceptionHandler ever sees it (decision 22, step 1). The body is still an
// ApiError, written with the application's own JsonMapper, so it's
// byte-for-byte the same shape as every other error.
//
// One message for every case, on purpose: the caller isn't told whether the
// token was missing, expired or forged.
@Component
public class UnauthorizedEntryPoint implements AuthenticationEntryPoint {

    private static final ApiError BODY =
            ApiError.unauthorized("You need to log in first. Send your token as \"Authorization: Bearer <token>\".");

    private final JsonMapper jsonMapper;

    public UnauthorizedEntryPoint(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException reason) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        // Tells HTTP clients that the expected credential is a Bearer token,
        // as RFC 6750 says a 401 should.
        response.setHeader("WWW-Authenticate", "Bearer");
        jsonMapper.writeValue(response.getOutputStream(), BODY);
    }
}
