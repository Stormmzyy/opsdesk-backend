package com.opsdesk.backend.ticket;

import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.opsdesk.backend.validation.OneOf;
import com.opsdesk.backend.validation.WholeNumber;

// The query parameters of GET /tickets: ?page=2&size=10&status=OPEN&search=printer
//
// Spring builds this record from the query string, matching parameter names
// to field names, and @Valid in the controller checks it like a request body.
// A bad value therefore reaches GlobalExceptionHandler as a field error, and
// the client gets a VALIDATION_ERROR with the parameter's name as the key in
// "fields", using the contract's messages.
//
// Every field is a String, and null means "not sent". Any other query
// parameter has no field here, so it's ignored, as the contract says.
//
// The four edge cases (NOTES.md, the filtering decision):
//   - an unknown value (status=BANANA, team=Marketing): 400, the same
//     message and exact spelling as the same field in a request body. Case
//     counts, so status=open is refused, and team takes its label
//     ("Operations"), as everywhere on the wire. A dropped bad value would
//     quietly become "no filter" and return MORE than the client asked for.
//   - an unknown parameter (?stauts=OPEN): ignored, as the contract says.
//     Visibility never depends on a parameter, so a typo can widen the
//     filter but never who sees what.
//   - a parameter sent twice: 400 "... can only be sent once.", checked in
//     TicketController before these checks run. Spring would otherwise join
//     the values with a comma: search=printer&search=laptop silently
//     searched for "printer,laptop".
//   - an empty value: for page, size, status, priority and team it's a 400,
//     because "" isn't one of the allowed values, and treating it as "not
//     sent" would return everything to a client that blanked it by mistake.
//     An empty search keeps every ticket, because every text contains "".
//     Either way the parameter was sent, so the answer is the envelope.
//
// The order of the fields is the order of the messages in a 400.
public record TicketListParams(

        @WholeNumber(min = 1, message = "Page must be a whole number, 1 or more.")
        String page,

        @WholeNumber(min = 1, max = 100, message = "Size must be a whole number from 1 to 100.")
        String size,

        @OneOf(required = false, values = {"OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"},
                message = "Status must be one of OPEN, IN_PROGRESS, RESOLVED or CLOSED.")
        String status,

        // The same values and message as priority in a request body.
        @OneOf(required = false, values = {"LOW", "MEDIUM", "HIGH", "URGENT"},
                message = "Priority must be one of LOW, MEDIUM, HIGH or URGENT.")
        String priority,

        // The labels, as in a request body and every response.
        @OneOf(required = false, values = {"Engineering", "Finance", "HR", "Operations", "Support"},
                message = "Team must be one of Engineering, Finance, HR, Operations or Support.")
        String team,

        // Any text is a valid search, so there's nothing to check.
        String search) {

    private static final int DEFAULT_SIZE = 10;

    // Each parameter's name and the word its messages start with. The keys
    // are the query string's names, in field order; TicketController uses them
    // to refuse a parameter sent twice.
    static final Map<String, String> LABELS = labels();

    private static Map<String, String> labels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("page", "Page");
        labels.put("size", "Size");
        labels.put("status", "Status");
        labels.put("priority", "Priority");
        labels.put("team", "Team");
        labels.put("search", "Search");
        return Collections.unmodifiableMap(labels);
    }

    // The contract's rule: if none of them is sent, GET /tickets returns the
    // plain v1 array, so existing clients keep working. Sending any one of
    // them, even "?page=1", asks for the paged envelope.
    boolean anySent() {
        return page != null || size != null || status != null || priority != null
                || team != null || search != null;
    }

    // The filters, as data for TicketService. Only call after @Valid passed.
    TicketFilters filters() {
        return new TicketFilters(
                status == null ? null : TicketStatus.valueOf(status),
                priority == null ? null : Priority.valueOf(priority),
                team == null ? null : Team.fromLabel(team),
                searchText());
    }

    // 1-based, as the contract counts. Only call these after @Valid has passed.
    // A page too big for a long is still a valid page number (just past the
    // last page), so it's capped rather than rejected.
    long pageNumber() {
        if (page == null) {
            return 1;
        }
        return new BigInteger(page).min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }

    int pageSize() {
        return size == null ? DEFAULT_SIZE : Integer.parseInt(size);
    }

    // The contract: search ignores leading and trailing spaces.
    private String searchText() {
        return search == null ? null : search.strip();
    }
}
