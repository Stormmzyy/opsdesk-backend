package com.opsdesk.backend.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

// A field a request body must not send at all (NOTES.md, decisions 39 and
// 40): the ticket shape's output-only fields, such as assigneeId and
// version. Present with any value, null included, is a 400 VALIDATION_ERROR
// on that field with this message. Absent is fine.
//
// Why refuse instead of ignore: a client that sends one is trying to do
// something (assign a ticket, ask for a conflict check), and answering 200
// without doing it would pretend. Refusing is also forward compatible: a
// 400 can be relaxed into an accepted field later without breaking anyone.
//
// Put it on a JsonNode field, not Object. Jackson gives a JsonNode field a
// NullNode for an explicit "field": null, and leaves it null only when the
// field is absent, so "sent as null" is still caught. An Object field gets
// null for both, and {"assigneeId": null}, which is how a client would try to
// unassign, would slip through as "not sent".
@Documented
@Constraint(validatedBy = RefusedValidator.class)
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Refused {

    // Says what to do instead, where there is a right way.
    String message();

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
