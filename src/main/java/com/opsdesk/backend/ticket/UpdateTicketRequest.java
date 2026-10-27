package com.opsdesk.backend.ticket;

import com.opsdesk.backend.validation.OneOf;
import com.opsdesk.backend.validation.TextField;

// The body of PATCH /tickets/{id}. The same rules and messages as a create,
// but every field is optional: a field that wasn't sent arrives as null, and
// null means "leave this one as it is". That's what lets the frontend send
// just { "status": "RESOLVED" } to move a ticket on the board. An empty body {}
// is valid and changes nothing. There's no id field, so an id is ignored.
//
// Object fields and trimming in the constructor, for the same reasons as
// CreateTicketRequest.
public record UpdateTicketRequest(

        @TextField(required = false, minLength = 3,
                requiredMessage = "Title is required.",
                tooShortMessage = "Title must be at least 3 characters.",
                notTextMessage = "Title must be text.")
        Object title,

        @TextField(required = false,
                requiredMessage = "Description is required.",
                notTextMessage = "Description must be text.")
        Object description,

        @OneOf(required = false, values = {"LOW", "MEDIUM", "HIGH", "URGENT"},
                message = "Priority must be one of LOW, MEDIUM, HIGH or URGENT.")
        Object priority,

        @OneOf(required = false, values = {"OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"},
                message = "Status must be one of OPEN, IN_PROGRESS, RESOLVED or CLOSED.")
        Object status,

        @OneOf(required = false, values = {"Engineering", "Finance", "HR", "Operations", "Support"},
                message = "Team must be one of Engineering, Finance, HR, Operations or Support.")
        Object team) {

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
