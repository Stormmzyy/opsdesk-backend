package com.opsdesk.backend.error;

import java.util.Map;

// Boot 3 used Jackson 2; Boot 4 uses Jackson 3, but the annotations kept their com.fasterxml package.
import com.fasterxml.jackson.annotation.JsonInclude;

// The contract's error body, used by every failing request on every endpoint:
//   { "error": "VALIDATION_ERROR", "message": "...", "fields": { ... } }
//
// "fields" is present ONLY for VALIDATION_ERROR. Every other error leaves it
// null, and NON_NULL tells Jackson to leave a null field out of the JSON
// altogether, so a 404 is just { "error": ..., "message": ... }, with no
// "fields": null.
//
// Not called ErrorResponse, because Spring already has an interface with that
// name, and importing the wrong one by accident would be easy.
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String error, String message, Map<String, String> fields) {

    // One factory per error code in the contract, so the code strings live in
    // one place and nothing else can invent a new one.

    public static ApiError notFound(String message) {
        return new ApiError("NOT_FOUND", message, null);
    }

    public static ApiError badRequest() {
        return new ApiError("BAD_REQUEST", "The request body must be a JSON object.", null);
    }

    // The contract: "message repeats the field messages joined into one
    // sentence", so a client that ignores "fields" can still show something
    // useful. The field messages already end with a full stop, so a space is
    // the only joiner needed.
    public static ApiError validation(Map<String, String> fields) {
        return new ApiError("VALIDATION_ERROR", String.join(" ", fields.values()), fields);
    }

    // Deliberately says nothing about what went wrong: no exception text, no
    // SQL, no stack trace. Those are logged on the server instead.
    public static ApiError internal() {
        return new ApiError("INTERNAL_ERROR", "Something went wrong on the server. Please try again.", null);
    }
}
