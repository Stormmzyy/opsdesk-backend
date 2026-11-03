package com.opsdesk.backend.validation;

import java.util.regex.Pattern;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

// The check behind @PatternField: present, text, and matching the pattern
// from start to end. Any failure is the field's one message, which Bean
// Validation takes from the annotation's message().
public class PatternFieldValidator implements ConstraintValidator<PatternField, Object> {

    private Pattern pattern;

    // Compiled once per annotation, not on every request.
    @Override
    public void initialize(PatternField annotation) {
        pattern = Pattern.compile(annotation.regexp());
    }

    // matches() checks the whole value, so "ama.owusu!" fails the username
    // pattern even though "ama.owusu" inside it would match.
    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        return value instanceof String text && pattern.matcher(text).matches();
    }
}
