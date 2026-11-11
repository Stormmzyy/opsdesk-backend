package com.opsdesk.backend.ticket;

import com.opsdesk.backend.validation.OneOf;
import io.swagger.v3.oas.annotations.media.Schema;
import com.opsdesk.backend.validation.Refused;
import com.opsdesk.backend.validation.TextField;

// Boot 3 used Jackson 2 (com.fasterxml.jackson.databind.JsonNode); Boot 4 uses Jackson 3.
import tools.jackson.databind.JsonNode;

// The body of PATCH /tickets/{id}. The same rules and messages as a create,
// but every field is optional: a field that wasn't sent arrives as null, and
// null means "leave this one as it is". That's what lets the frontend send
// just { "status": "RESOLVED" } to move a ticket on the board. An empty body {}
// is valid and changes nothing. There's no id field, so an id is ignored
// (the contract says so). The other output-only fields are refused; see below.
//
// Object fields and trimming in the constructor, for the same reasons as
// CreateTicketRequest.
public record UpdateTicketRequest(

        @TextField(required = false, minLength = 3,
                requiredMessage = "Title is required.",
                tooShortMessage = "Title must be at least 3 characters.",
                notTextMessage = "Title must be text.")
        @Schema(type = "string", minLength = 3)
        Object title,

        @TextField(required = false,
                requiredMessage = "Description is required.",
                notTextMessage = "Description must be text.")
        @Schema(type = "string", minLength = 1)
        Object description,

        @OneOf(required = false, values = {"LOW", "MEDIUM", "HIGH", "URGENT"},
                message = "Priority must be one of LOW, MEDIUM, HIGH or URGENT.")
        @Schema(implementation = Priority.class)
        Object priority,

        @OneOf(required = false, values = {"OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"},
                message = "Status must be one of OPEN, IN_PROGRESS, RESOLVED or CLOSED.")
        @Schema(implementation = TicketStatus.class)
        Object status,

        @OneOf(required = false, values = {"Engineering", "Finance", "HR", "Operations", "Support"},
                message = "Team must be one of Engineering, Finance, HR, Operations or Support.")
        @Schema(implementation = Team.class)
        Object team,

        // The ticket shape's output-only fields (NOTES.md, decisions 39 and
        // 40). Declared only to be refused: any of them in the body, even as
        // null, is a 400 on that field, and nothing in the request is applied.
        //
        // assigneeId matters most. If it bound here, an AGENT could assign a
        // ticket to himself, or hand his to someone else, on the edit endpoint,
        // and skip the ADMIN-or-MANAGER gate on the assign endpoint entirely:
        // the edit rules don't know the field means assigning.
        @Refused(message = "The assignee can't be changed here. Use PATCH /tickets/{id}/assignee.")
        @Schema(hidden = true)
        JsonNode assigneeId,

        @Refused(message = "The assignee can't be changed here. Use PATCH /tickets/{id}/assignee.")
        @Schema(hidden = true)
        JsonNode assigneeName,

        @Refused(message = "The creator is set by the server and can't be sent.")
        @Schema(hidden = true)
        JsonNode createdById,

        @Refused(message = "The creator is set by the server and can't be sent.")
        @Schema(hidden = true)
        JsonNode createdByName,

        @Refused(message = "The version is set by the server and can't be sent.")
        @Schema(hidden = true)
        JsonNode version) {

    // Trimmed before validation; status, priority and team are not.
    public UpdateTicketRequest {
        title = RequestText.trimIfText(title);
        description = RequestText.trimIfText(description);
    }

    // Typed values for the service, or null for "not sent, leave it alone".
    // Only valid after @Valid has passed.
    String titleText() {
        return (String) title;
    }

    String descriptionText() {
        return (String) description;
    }

    Priority priorityValue() {
        return priority == null ? null : Priority.valueOf((String) priority);
    }

    TicketStatus statusValue() {
        return status == null ? null : TicketStatus.valueOf((String) status);
    }

    Team teamValue() {
        return team == null ? null : Team.fromLabel((String) team);
    }
}
