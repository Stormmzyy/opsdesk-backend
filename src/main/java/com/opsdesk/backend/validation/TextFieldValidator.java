package com.opsdesk.backend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// The checks behind @TextField, in the order the contract's messages imply:
// missing, then not text, then empty, then too short. It reports only the
// first problem it finds, so each field gets one message.
public class TextFieldValidator implements ConstraintValidator<TextField, Object> {

    private boolean required;
    private int minLength;
    private String requiredMessage;
    private String tooShortMessage;
    private String notTextMessage;

    @Override
    public void initialize(TextField annotation) {
        required = annotation.required();
        minLength = annotation.minLength();
        requiredMessage = annotation.requiredMessage();
        tooShortMessage = annotation.tooShortMessage();
        notTextMessage = annotation.notTextMessage();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            // Not sent. Fine on a PATCH ("leave it alone"), an error on a create.
            return !required || fail(context, requiredMessage);
        }
        if (!(value instanceof String text)) {
            return fail(context, notTextMessage);
        }
        // Already trimmed by the request record, so "   " has arrived as "".
        if (text.isEmpty()) {
            return fail(context, requiredMessage);
        }
        // length() counts UTF-16 units, the same as JavaScript's .length, so
        // the backend and the frontend's form agree on what "3 characters" is.
        if (text.length() < minLength) {
            return fail(context, tooShortMessage);
        }
        return true;
    }

    // Replaces the annotation's (unused) default message with the specific
    // one. The messages are fixed strings from the contract, never user input,
    // so it's safe to pass them as a message template.
    private static boolean fail(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
