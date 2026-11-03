package com.opsdesk.backend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// The checks behind @PositiveId. Jackson turns a JSON whole number into an
// Integer, or a Long if it doesn't fit; a decimal becomes a Double and a
// number too big for a Long a BigInteger. Only Integer and Long can be an id.
public class PositiveIdValidator implements ConstraintValidator<PositiveId, Object> {

    private String requiredMessage;
    private String invalidMessage;

    @Override
    public void initialize(PositiveId annotation) {
        requiredMessage = annotation.requiredMessage();
        invalidMessage = annotation.invalidMessage();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return fail(context, requiredMessage);
        }
        boolean wholeNumber = value instanceof Integer || value instanceof Long;
        if (!wholeNumber || ((Number) value).longValue() < 1) {
            return fail(context, invalidMessage);
        }
        return true;
    }

    // As in TextFieldValidator: the messages are fixed strings from the code,
    // never user input, so it's safe to pass them as a message template.
    private static boolean fail(ConstraintValidatorContext context, String message) {
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message).addConstraintViolation();
        return false;
    }
}
