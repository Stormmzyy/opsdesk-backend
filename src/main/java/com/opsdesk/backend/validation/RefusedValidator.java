package com.opsdesk.backend.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// The check behind @Refused: valid only when the field wasn't sent. On a
// JsonNode field, "sent as null" arrives as a NullNode, not null, so it
// fails here too (see @Refused).
public class RefusedValidator implements ConstraintValidator<Refused, Object> {

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        return value == null;
    }
}
