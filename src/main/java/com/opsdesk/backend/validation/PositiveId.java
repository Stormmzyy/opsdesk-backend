package com.opsdesk.backend.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

// A required id in a JSON body, such as an assignee's user id: present, and a
// JSON whole number of 1 or more. Each problem has its own message, and only
// the first is reported, as with @TextField.
//
// Not @WholeNumber: that one checks query-string text ("page=2"). In a JSON
// body the id arrives as a number, and the text "2" is a different JSON type,
// refused here the same way a number sent for a title is refused.
//
// FIELD only, for the reason given on @TextField.
@Documented
@Constraint(validatedBy = PositiveIdValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PositiveId {

    // Required by Bean Validation, but unused: the validator always reports
    // one of the two specific messages below instead.
    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    // Used when the field is missing or null.
    String requiredMessage();

    // Used when it's present but not a whole number of 1 or more: text, a
    // decimal, zero, a negative, or too large to be any row's id.
    String invalidMessage();
}
