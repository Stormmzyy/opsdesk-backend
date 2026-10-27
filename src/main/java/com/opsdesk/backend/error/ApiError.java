package com.opsdesk.backend.error;

// The contract's error body: { "error": "NOT_FOUND", "message": "..." }.
// Its own package because every feature will answer errors in this shape,
// not just tickets. The contract's optional "fields" map (validation errors
// only) arrives on Thursday with the global exception handler.
//
// Not called ErrorResponse, because Spring already has an interface with that
// name, and importing the wrong one by accident would be easy.
public record ApiError(String error, String message) {
}
