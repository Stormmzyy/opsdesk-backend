package com.opsdesk.backend.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

// A required text field that must match a pattern as a whole: register's
// username and email. Missing, not text, and not matching all get the same
// message, because the contract gives each of these fields exactly one.
//
// Why not the standard @Pattern and @Email? The same two reasons as
// @TextField: they only work on String fields (the request fields are
// Object), and a missing value would need a second annotation with a second
// message, while the contract's "fields" holds one message per field.
//
// FIELD only, for the reason given on @TextField.
@Documented
@Constraint(validatedBy = PatternFieldValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PatternField {

    // The contract's message for this field.
    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    // Must match the whole value, not just part of it.
    String regexp();
}
