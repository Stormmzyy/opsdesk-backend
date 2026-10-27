package com.opsdesk.backend.validation;

import java.util.Set;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// The check behind @OneOf. Its message comes straight from the annotation,
// because the contract uses one message per field for every problem.
public class OneOfValidator implements ConstraintValidator<OneOf, Object> {

    private Set<String> allowed;
    private boolean required;

    @Override
    public void initialize(OneOf annotation) {
        allowed = Set.of(annotation.values());
        required = annotation.required();
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return !required;
        }
        // A number or boolean is never one of the allowed strings. Exact
        // match: no trimming, no case-folding.
        return value instanceof String text && allowed.contains(text);
    }
}
