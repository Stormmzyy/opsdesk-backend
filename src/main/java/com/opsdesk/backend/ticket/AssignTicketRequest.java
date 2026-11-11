package com.opsdesk.backend.ticket;

import com.opsdesk.backend.validation.PositiveId;
import io.swagger.v3.oas.annotations.media.Schema;

// The body of PATCH /api/v1/tickets/{id}/assignee: { "assigneeId": 2 }.
//
// assigneeId is required. Unassigning is not { "assigneeId": null }: in this
// API's PATCH bodies, null means the same as "not sent" (NOTES.md, decision
// 11), so null can't also mean "remove the assignee" without {} doing it too,
// and a forgotten field would silently take a ticket away from whoever was
// working it. Unassigning is DELETE /api/v1/tickets/{id}/assignee instead.
//
// Object, not Long, for the reason given on CreateTicketRequest: Jackson
// accepts whatever JSON type was sent, and validation names the problem.
public record AssignTicketRequest(

        @PositiveId(requiredMessage = "Assignee id is required.",
                invalidMessage = "Assignee id must be a positive whole number.")
        // implementation = Long.class, not type = "integer": on an Object
        // field in a request body, springdoc 3.2.0 turned type = "integer"
        // into "string" (measured), while a class is described correctly.
        @Schema(implementation = Long.class, minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
        Object assigneeId) {

    // Only call after @Valid has passed, when it's known to be an Integer or
    // a Long.
    Long assigneeIdValue() {
        return ((Number) assigneeId).longValue();
    }
}
