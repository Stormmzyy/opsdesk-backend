package com.opsdesk.backend.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import com.opsdesk.backend.auth.LoginRequest;
import com.opsdesk.backend.auth.RegisterRequest;
import com.opsdesk.backend.ticket.AssignTicketRequest;
import com.opsdesk.backend.ticket.CreateTicketRequest;
import com.opsdesk.backend.ticket.TicketListParams;
import com.opsdesk.backend.ticket.UpdateTicketRequest;

import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.json.JsonMapper;

// The request records say each rule twice: once in a validator annotation
// (@TextField, @OneOf, ...), which is what the server enforces, and once in
// @Schema, which is what Swagger shows (NOTES.md, decision 73). Nothing at
// runtime connects the two, so this test does: change one without the other
// and it fails, naming the field and both values.
//
// What it compares, per validator:
//   @TextField    type string, the same minLength, the same "required", and a
//                 byte limit (which OpenAPI can't express) named in the
//                 description
//   @OneOf        the same set of values: from the enum named by
//                 @Schema(implementation = ...), as Jackson writes them
//                 (Team is "Engineering", not ENGINEERING), or else from
//                 allowableValues; and the same "required"
//   @PatternField the same regular expression; required
//   @WholeNumber  type integer, the same minimum and maximum
//   @PositiveId   described as a Long, minimum 1, required
//   @Refused      hidden, so Swagger doesn't offer a field the server refuses
//
// It also checks the @OneOf lists against the enums they repeat, a copy that
// predates Swagger and that nothing checked before.
//
// Annotations are read from the record's fields: neither @Schema nor the
// validators target record components, so Java puts them on the field.
class SchemaMatchesValidationTest {

    private static final List<Class<? extends Record>> REQUEST_RECORDS = List.of(
            CreateTicketRequest.class, UpdateTicketRequest.class, AssignTicketRequest.class,
            TicketListParams.class, RegisterRequest.class, LoginRequest.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void everyValidatedFieldIsDescribedTheSameWayInItsSchema() throws Exception {
        List<String> problems = new ArrayList<>();
        int checked = 0;
        for (Class<? extends Record> record : REQUEST_RECORDS) {
            for (RecordComponent component : record.getRecordComponents()) {
                Field field = record.getDeclaredField(component.getName());
                String where = record.getSimpleName() + "." + field.getName();
                Schema schema = field.getAnnotation(Schema.class);
                if (hasValidator(field) && schema == null) {
                    problems.add(where + ": validated, but has no @Schema, so Swagger can't describe it");
                    continue;
                }
                checked += compare(where, field, schema, problems);
            }
        }
        assertThat(checked).as("fields compared").isGreaterThan(20);
        assertThat(problems).as("validator and @Schema disagree").isEmpty();
    }

    private static int compare(String where, Field field, Schema schema, List<String> problems)
            throws Exception {
        int checked = 0;
        if (field.getAnnotation(TextField.class) instanceof TextField text) {
            checked++;
            same(problems, where, "type", "string", schema.type());
            same(problems, where, "minLength", text.minLength(), schema.minLength());
            same(problems, where, "required", text.required(), isRequired(schema));
            if (text.maxUtf8Bytes() != Integer.MAX_VALUE
                    && !schema.description().contains(text.maxUtf8Bytes() + " bytes")) {
                problems.add(where + ": @TextField allows at most " + text.maxUtf8Bytes()
                        + " bytes, but @Schema's description doesn't say \"" + text.maxUtf8Bytes() + " bytes\"");
            }
        }
        if (field.getAnnotation(OneOf.class) instanceof OneOf oneOf) {
            checked++;
            same(problems, where, "allowed values", new TreeSet<>(List.of(oneOf.values())), allowedValues(schema));
            same(problems, where, "required", oneOf.required(), isRequired(schema));
        }
        if (field.getAnnotation(PatternField.class) instanceof PatternField pattern) {
            checked++;
            same(problems, where, "pattern", pattern.regexp(), schema.pattern());
            same(problems, where, "required", true, isRequired(schema));
        }
        if (field.getAnnotation(WholeNumber.class) instanceof WholeNumber number) {
            checked++;
            same(problems, where, "type", "integer", schema.type());
            same(problems, where, "minimum", String.valueOf(number.min()), schema.minimum());
            same(problems, where, "maximum",
                    number.max() == Long.MAX_VALUE ? "" : String.valueOf(number.max()), schema.maximum());
        }
        if (field.getAnnotation(PositiveId.class) != null) {
            checked++;
            // Described by class (Long), because springdoc turned
            // type = "integer" into "string" on an Object body field.
            same(problems, where, "implementation", Long.class, schema.implementation());
            same(problems, where, "minimum", "1", schema.minimum());
            same(problems, where, "required", true, isRequired(schema));
        }
        if (field.getAnnotation(Refused.class) != null) {
            checked++;
            same(problems, where, "hidden", true, schema.hidden());
        }
        return checked;
    }

    // The values Swagger will list: the enum's, as JSON writes them, when
    // @Schema points at an enum; otherwise allowableValues.
    private static Set<String> allowedValues(Schema schema) throws Exception {
        Set<String> values = new TreeSet<>();
        if (schema.implementation().isEnum()) {
            for (Object constant : schema.implementation().getEnumConstants()) {
                values.add(JSON.readTree(JSON.writeValueAsString(constant)).asString());
            }
        } else {
            values.addAll(Arrays.asList(schema.allowableValues()));
        }
        return values;
    }

    private static boolean isRequired(Schema schema) {
        return schema.requiredMode() == Schema.RequiredMode.REQUIRED;
    }

    private static boolean hasValidator(Field field) {
        return field.getAnnotation(TextField.class) != null || field.getAnnotation(OneOf.class) != null
                || field.getAnnotation(PatternField.class) != null || field.getAnnotation(WholeNumber.class) != null
                || field.getAnnotation(PositiveId.class) != null || field.getAnnotation(Refused.class) != null;
    }

    private static void same(List<String> problems, String where, String what, Object validator, Object schema) {
        if (!validator.equals(schema)) {
            problems.add(where + ": " + what + " is " + validator + " in the validator but " + schema + " in @Schema");
        }
    }
}
