package com.opsdesk.backend.error;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.opsdesk.backend.ticket.TicketIds;
import com.opsdesk.backend.ticket.TicketNotFoundException;
import com.opsdesk.backend.ticket.TicketService;

import jakarta.servlet.http.HttpServletRequest;

// Turns every exception into the contract's error body ({ error, message,
// fields }), for every controller.
//
// @RestControllerAdvice: Spring applies the @ExceptionHandler methods below to
// every controller, and also to errors Spring raises before a controller method
// runs: a body it can't read, a body that fails @Valid, a URL that matches
// nothing. For each exception, Spring picks the handler for the most specific
// matching type, and that method's return value becomes the response.
//
// The contract's check order (NOTES.md, decision 6): when a request has more
// than one problem, the answer is the first of
//   1. 404 NOT_FOUND          the ticket doesn't exist
//   2. 400 BAD_REQUEST        the body can't be read
//   3. 400 VALIDATION_ERROR   the body was read but fields are invalid
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // The one URL that takes both an id and a body: PATCH /api/v1/tickets/{id}.
    private static final Pattern TICKET_PATH = Pattern.compile("^/api/v1/tickets/([^/]+)$");

    private final TicketService ticketService;

    public GlobalExceptionHandler(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    // 404: no ticket has that id, or the id isn't a valid id at all.
    @ExceptionHandler(TicketNotFoundException.class)
    public ResponseEntity<ApiError> handleTicketNotFound(TicketNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, ApiError.notFound(exception.getMessage()));
    }

    // 400 BAD_REQUEST: the body isn't valid JSON, isn't a JSON object ([] or
    // "hello"), or is missing altogether. Jackson failed before validation
    // could even start.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpServletRequest request) {
        return missingTicketOnPatch(request)
                .orElseGet(() -> respond(HttpStatus.BAD_REQUEST, ApiError.badRequest()));
    }

    // 400 VALIDATION_ERROR: the body was read, but @Valid found invalid fields.
    // Spring collects every failing field before throwing, so every one is
    // listed together, not just the first.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleInvalidFields(MethodArgumentNotValidException exception,
                                                        HttpServletRequest request) {
        return missingTicketOnPatch(request)
                .orElseGet(() -> respond(HttpStatus.BAD_REQUEST, ApiError.validation(fieldMessages(exception))));
    }

    // Why an error handler has to look a ticket up:
    //
    // On PATCH /tickets/{id}, Spring reads the body and runs @Valid BEFORE the
    // controller method is called. If the body is broken, Spring throws
    // straight away and the controller never runs, so the check that would
    // have found "ticket 999 doesn't exist" never happens. Without this,
    // PATCH /tickets/999 with a broken body would answer 400, but the contract
    // says the 404 comes first.
    //
    // So before answering 400, the handler asks the question the controller
    // never got to: read the id from the URL, and check that ticket exists. If
    // the id isn't valid or there's no such ticket, the answer is the 404.
    //
    // The 404 is returned from here, not thrown. An exception thrown inside an
    // @ExceptionHandler is not handled a second time: Spring logs "Failure in
    // @ExceptionHandler" and falls back to its own default 400 body. (That was
    // the first version of this method, and it did exactly that.)
    //
    // Only PATCH needs this. It's the only request with both an id and a body:
    // POST has no id, and GET and DELETE have no body.
    private Optional<ResponseEntity<ApiError>> missingTicketOnPatch(HttpServletRequest request) {
        if (!"PATCH".equals(request.getMethod())) {
            return Optional.empty();
        }
        Matcher matcher = TICKET_PATH.matcher(request.getRequestURI());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            ticketService.getTicket(TicketIds.parse(matcher.group(1)));
            return Optional.empty();
        } catch (TicketNotFoundException missing) {
            return Optional.of(handleTicketNotFound(missing));
        }
    }

    // The URL matches no endpoint at all, such as /api/v1/tickets/ with a
    // trailing slash. Spring reports this as "no resource found"; the contract
    // calls it NOT_FOUND.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleUnknownPath(HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND,
                ApiError.notFound("Nothing exists at " + request.getRequestURI() + "."));
    }

    // A real path, but a method it doesn't support (PUT /tickets/1). The
    // contract has no 405 and says the list of error codes is complete, so
    // this is NOT_FOUND: no endpoint exists for that method and path. The
    // frontend's mock answers the same.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleWrongMethod(HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, ApiError.notFound(
                "Nothing exists at " + request.getMethod() + " " + request.getRequestURI() + "."));
    }

    // The body isn't sent as JSON (no "Content-Type: application/json"), so it
    // can't be read as a JSON object: BAD_REQUEST, not Spring's 415.
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleNotJson(HttpServletRequest request) {
        return missingTicketOnPatch(request)
                .orElseGet(() -> respond(HttpStatus.BAD_REQUEST, ApiError.badRequest()));
    }

    // Anything else. Two kinds land here:
    //   - Spring's other "the client got it wrong" errors (they implement
    //     ErrorResponse and carry a 4xx status). They're the client's fault, so
    //     they must not be reported as a 500: they become BAD_REQUEST.
    //   - Everything unexpected: a bug, the database being down, a constraint
    //     violation. These become 500 INTERNAL_ERROR with a fixed message.
    //
    // The real exception, with its stack trace and any SQL, is logged here on
    // the server and never sent to the client. Before this handler existed, a
    // missing required field reached PostgreSQL and the client saw the
    // NOT NULL constraint error.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        if (exception instanceof ErrorResponse springError
                && springError.getStatusCode().is4xxClientError()) {
            return respond(HttpStatus.BAD_REQUEST, ApiError.badRequest());
        }
        log.error("Unhandled error on {} {}", request.getMethod(), request.getRequestURI(), exception);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.internal());
    }

    // One message per field, in the order the fields are declared in the
    // request record (title, description, priority, status, team), the same
    // order as the contract's tables and examples.
    //
    // Spring doesn't promise any order for field errors, so without sorting,
    // "message" could come out in a different order on each request.
    private static Map<String, String> fieldMessages(MethodArgumentNotValidException exception) {
        List<String> declared = declaredFieldOrder(exception.getBindingResult().getTarget());
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparingInt(error -> positionOf(declared, error.getField())))
                .forEach(error -> fields.putIfAbsent(error.getField(), messageOf(error)));
        return fields;
    }

    private static List<String> declaredFieldOrder(Object target) {
        if (target == null || !target.getClass().isRecord()) {
            return List.of();
        }
        return Arrays.stream(target.getClass().getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }

    // A field missing from the list (which shouldn't happen) sorts last.
    private static int positionOf(List<String> declared, String field) {
        int position = declared.indexOf(field);
        return position >= 0 ? position : Integer.MAX_VALUE;
    }

    private static String messageOf(FieldError error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage() : "This field is invalid.";
    }

    private static ResponseEntity<ApiError> respond(HttpStatus status, ApiError body) {
        return ResponseEntity.status(status).body(body);
    }
}
