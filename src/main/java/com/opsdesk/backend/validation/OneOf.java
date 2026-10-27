package com.opsdesk.backend.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

// A field with a fixed set of values (priority, status, team). The value must
// be a JSON string that matches one of the values exactly: same case, and no
// trimming, so " Support " and "support" are both rejected, as the contract
// says.
//
// Used instead of making the field a Java enum, because Jackson rejects an
// unknown enum value while it's still reading the body. The client would get a
// BAD_REQUEST that doesn't say which field was wrong, instead of the
// contract's VALIDATION_ERROR naming the field.
@Documented
@Constraint(validatedBy = OneOfValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OneOf {

    // The contract uses one message for both "missing" and "not one of these".
    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    // The exact strings the contract allows. These repeat the enum constants,
    // because an annotation can only hold constant values, so it can't ask the
    // enum for them. If an enum gains a value but this list doesn't, the new
    // value is rejected: it fails safe, not open.
    String[] values();

    boolean required();
}
