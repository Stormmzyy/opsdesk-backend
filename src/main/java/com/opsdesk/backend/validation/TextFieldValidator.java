package com.opsdesk.backend.validation;

import java.nio.charset.StandardCharsets;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// The checks behind @TextField, in the order the contract's messages imply:
// missing, then not text, then empty, then too short, then too long. It reports only the
// first problem it finds, so each field gets one message.
public class TextFieldValidator implements ConstraintValidator<TextField, Object> {

    private boolean required;
    private int minLength;
    private String requiredMessage;
    private String tooShortMessage;
    private String notTextMessage;
    private int maxUtf8Bytes;
    private String tooLongMessage;

    @Override
    public void initialize(TextField annotation) {
        required = annotation.required();
        minLength = annotation.minLength();
        maxUtf8Bytes = annotation.maxUtf8Bytes();
        tooLongMessage = annotation.tooLongMessage();
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
        // Bytes, not characters: "é" is one character but two bytes, and an
        // emoji is four. Only checked when a limit is set, so other fields
        // don't pay for encoding the string.
        if (maxUtf8Bytes != Integer.MAX_VALUE
                && text.getBytes(StandardCharsets.UTF_8).length > maxUtf8Bytes) {
            return fail(context, tooLongMessage);
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
