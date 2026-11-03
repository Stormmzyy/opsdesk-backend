package com.opsdesk.backend.error;

// A body field that passed @Valid but breaks a rule only the database can
// check, such as an assignee id that matches no user. Answered as the same
// 400 VALIDATION_ERROR, naming the field, as any other invalid field, so a
// client handles it in the same place.
//
// The message goes to the client as it is, so it must only describe what the
// caller sent.
public class InvalidFieldException extends RuntimeException {

    private final String field;

    public InvalidFieldException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
