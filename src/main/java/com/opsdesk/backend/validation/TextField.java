package com.opsdesk.backend.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

// A free-text field (a ticket's title or description), checked the way the
// contract describes: required or not, must be a JSON string, and at least
// minLength characters. Each problem has its own message.
//
// Why not the standard @NotBlank and @Size? Two reasons:
//   1. They only work on String fields, and the request fields are Object so
//      that a number or an array can be reported as "Title must be text."
//      instead of breaking the whole request.
//   2. They each add their own message, so an empty title would break both
//      and get two messages. The contract's "fields" object holds exactly one
//      message per field. This constraint stops at the first problem.
//
// It expects the value to be trimmed already. The request records trim in
// their constructors, before validation runs.
//
// FIELD only: on a record, an annotation on a component is copied to every
// place its @Target allows. Limiting it to the field means it's checked
// exactly once.
@Documented
@Constraint(validatedBy = TextFieldValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface TextField {

    // Required by Bean Validation, but unused: the validator always reports
    // one of the three specific messages below instead.
    String message() default "";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    // false for PATCH, where leaving a field out means "don't change it".
    boolean required();

    int minLength() default 1;

    // Used when the field is required but missing, or empty after trimming.
    String requiredMessage();

    // Used when the value is shorter than minLength.
    String tooShortMessage() default "";

    // Used when the value is a number, boolean, object or array.
    String notTextMessage();
}
