package com.opsdesk.backend.ticket;

import com.opsdesk.backend.validation.OneOf;
import com.opsdesk.backend.validation.TextField;

// The body of POST /tickets. Every message below is copied exactly from the
// contract's validation table.
//
// No id and no status: the contract says the server picks the id and every
// new ticket starts as OPEN. If a client sends either one, Jackson finds no
// matching field here and ignores it (Spring Boot turns off "fail on unknown
// properties"), so the request is accepted, not rejected.
//
// Every field is Object, not String or an enum, so Jackson accepts whatever
// JSON type the client sent and validation can name the problem: a number for
// a title gets "Title must be text.", and "SUPER" for a priority gets the
// priority message, instead of the whole body failing to read.
public record CreateTicketRequest(

        @TextField(required = true, minLength = 3,
                requiredMessage = "Title is required.",
                tooShortMessage = "Title must be at least 3 characters.",
                notTextMessage = "Title must be text.")
        Object title,

        @TextField(required = true,
                requiredMessage = "Description is required.",
                notTextMessage = "Description must be text.")
        Object description,

        @OneOf(required = true, values = {"LOW", "MEDIUM", "HIGH", "URGENT"},
                message = "Priority must be one of LOW, MEDIUM, HIGH or URGENT.")
        Object priority,

        @OneOf(required = true, values = {"Engineering", "Finance", "HR", "Operations", "Support"},
                message = "Team must be one of Engineering, Finance, HR, Operations or Support.")
        Object team) {

    // Trimming happens here, in the compact constructor, because the contract
    // says title and description are trimmed BEFORE they're validated:
    //   - The constructor runs while Jackson builds the record, and @Valid runs
    //     after that, so validation only ever sees the trimmed value. "   " is
    //     "Title is required.", and "  ab  " is too short.
    //   - Nothing can read the untrimmed value afterwards, because it never
    //     exists on the record. The service and the database get the same
    //     trimmed text that was validated.
    //   - Validating first and trimming later (in the service, say) would
    //     check one value and save another, so "  ab  " would pass as five
    //     characters and be saved as two.
    //   - A global Jackson setting that trims every string would also trim
    //     priority and team, and the contract says " Support " must be
    //     rejected.
    // priority and team are deliberately left alone.
    public CreateTicketRequest {
        title = RequestText.trimIfText(title);
        description = RequestText.trimIfText(description);
    }

    // Typed values for the service. Only call these after validation has
    // passed, which @Valid guarantees in the controller. By then each field is
    // known to be a valid string, so the casts can't fail.
    String titleText() {
        return (String) title;
    }

    String descriptionText() {
        return (String) description;
    }

    Priority priorityValue() {
        return Priority.valueOf((String) priority);
    }

    Team teamValue() {
        return Team.fromLabel((String) team);
    }
}
