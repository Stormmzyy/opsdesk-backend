package com.opsdesk.backend.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

// A query parameter that must be a whole number in a range, such as page
// (1 or more) or size (1 to 100). Optional: a missing value is valid, and the
// default applies.
//
// The value arrives as text, not an int, for the same reason the request
// bodies use Object fields: if Spring converted "abc" to an int itself, it
// would fail before validation and the client would get a BAD_REQUEST, not
// the contract's VALIDATION_ERROR naming the parameter.
@Documented
@Constraint(validatedBy = WholeNumberValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface WholeNumber {

    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    long min();

    // The default means "no upper limit", not "up to Long.MAX_VALUE": a page
    // number of twenty digits is still a whole number of 1 or more.
    long max() default Long.MAX_VALUE;
}
