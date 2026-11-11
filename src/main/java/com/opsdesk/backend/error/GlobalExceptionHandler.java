package com.opsdesk.backend.error;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.opsdesk.backend.auth.InvalidCredentialsException;
import com.opsdesk.backend.user.AppUser;

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
// The check order (NOTES.md, decisions 6 and 22): when a request has more than
// one problem, the answer is the first of 401, 403 for a role rule, 404, 403
// for a ticket rule, then 400 BAD_REQUEST and 400 VALIDATION_ERROR.
//
// This class only translates exceptions into bodies. It never decides
// anything itself. The ordering comes from where each check runs: the 401 in
// the security filters, the role rules in @PreAuthorize, and for a PATCH the
// 404 and the ticket rule in TicketEditAccessInterceptor, all before Spring
// reads the body. By the time an exception arrives here, the first problem
// has already been found.
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 404: no ticket has that id, or the id isn't a valid id at all.
    // NotFoundException covers every resource: tickets and projects.
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, ApiError.notFound(exception.getMessage()));
    }

    // 409 CONFLICT: registering an email or username that's already taken.
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException exception) {
        return respond(HttpStatus.CONFLICT, ApiError.conflict(exception.getMessage()));
    }

    // 401 UNAUTHORIZED: a login with an unknown email or a wrong password,
    // which get this same answer (AuthService.login).
    //
    // Logged at WARN (decision 72): without it, someone guessing passwords
    // leaves no trace at all. What's logged is that a login was refused and
    // where the request came from. NOT the email that was tried (personal
    // data, and for a mistyped password usually a real person's), and never
    // the password. The client address is what lets someone reading the log
    // see many refusals from one place.
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException exception,
                                                             HttpServletRequest request) {
        log.warn("Login refused (unknown email or wrong password) from {}", request.getRemoteAddr());
        return respond(HttpStatus.UNAUTHORIZED, ApiError.unauthorized(exception.getMessage()));
    }

    // 403 FORBIDDEN (NOTES.md, decision 21).
    //   401 means "we don't know who you are": no token, or one that isn't
    //   valid. It's answered by UnauthorizedEntryPoint, inside the security
    //   filters, before a controller is ever reached.
    //   403 means "we know exactly who you are, and the answer is no": a
    //   signed-in caller whose role, or whose relationship to the ticket,
    //   doesn't allow this action.
    //
    // Two places throw AccessDeniedException: @PreAuthorize, when a role gate
    // refuses before the controller method runs (decision 22, step 2), and
    // TicketService's ownership rules (step 4). Both happen inside Spring
    // MVC's call to the controller, which is why this handler sees them.
    // Without it, the catch-all below would report a refusal as a 500.
    //
    // Spring Security 7's @PreAuthorize actually throws
    // AuthorizationDeniedException, a subclass, so this handler covers it
    // as well. (Same in Boot 3 from Spring Security 6.3; before that it threw
    // AccessDeniedException itself.)
    //
    // The client always gets the same body. The real reason is logged here
    // at WARN instead: who the caller was, the request (whose path holds the
    // ticket id), and which rule refused. TicketService's rules put all of
    // that in the exception's message; a @PreAuthorize refusal only says
    // "Access Denied", so the caller and the path are what identify it.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleForbidden(AccessDeniedException exception, HttpServletRequest request) {
        log.warn("403 FORBIDDEN on {} {} for {}: {}", request.getMethod(), request.getRequestURI(),
                describeCaller(), exception.getMessage());
        return respond(HttpStatus.FORBIDDEN, ApiError.forbidden());
    }

    // 409 CONFLICT: the optimistic lock refused a write (NOTES.md, decision
    // 38). This request read the ticket, another request changed and
    // committed it, and then this one tried to write. Ticket's @Version made
    // the UPDATE match no row, so Hibernate threw instead of overwriting the
    // other change, and Spring translated that into this exception.
    //
    // 409 and not 500: nothing is broken, the client is simply out of date.
    // Reloading and trying again is the fix, and only the client can do it,
    // because only the person knows whether their change still makes sense
    // on top of the other one. Retrying here, on the server, would just be
    // the lost update again with extra steps.
    //
    // On updateTicket and assignTicket it's thrown at commit, by the
    // @Transactional proxy, after the method body has returned. That is
    // still inside Spring MVC's call to the controller, so it lands here.
    //
    // The same pattern as the 403: one fixed message for the client, the
    // detail (which entity, which id, the caller, the request) at WARN. A
    // stream of these on one ticket is worth a look in the log: two people
    // fighting over it, or a client that retries without reloading.
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleConcurrentChange(ObjectOptimisticLockingFailureException exception,
                                                           HttpServletRequest request) {
        log.warn("409 CONFLICT on {} {} for {}: {} {} was changed by another request since it was read ({})",
                request.getMethod(), request.getRequestURI(), describeCaller(),
                exception.getPersistentClassName(), exception.getIdentifier(), exception.getMessage());
        return respond(HttpStatus.CONFLICT, ApiError.concurrentChange());
    }

    // The caller as the log names them: role and user id, never the email
    // (decision 72). The id identifies the person just as well for whoever
    // reads the log, and isn't personal data on its own; the email is.
    private static String describeCaller() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof AppUser user) {
            return user.getRole() + " user " + user.getId();
        }
        return "an unauthenticated caller";
    }

    // 400 BAD_REQUEST: the body isn't valid JSON, isn't a JSON object ([] or
    // "hello"), or is missing altogether. Jackson failed before validation
    // could even start.
    //
    // On a PATCH, the ticket's 404 and 403 have already been decided by
    // TicketEditAccessInterceptor before the body was read, so reaching here
    // means the ticket exists and the caller may edit it.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody() {
        return respond(HttpStatus.BAD_REQUEST, ApiError.badRequest());
    }

    // 400 VALIDATION_ERROR: the body was read, but @Valid found invalid fields.
    // Spring collects every failing field before throwing, so every one is
    // listed together, not just the first.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleInvalidFields(MethodArgumentNotValidException exception) {
        return respond(HttpStatus.BAD_REQUEST, ApiError.validation(fieldMessages(exception)));
    }

    // 400 VALIDATION_ERROR for a field only the database could check, such
    // as an assignee id that matches no user. Same shape as @Valid's, so the
    // client handles both in one place.
    @ExceptionHandler(InvalidFieldException.class)
    public ResponseEntity<ApiError> handleInvalidField(InvalidFieldException exception) {
        return respond(HttpStatus.BAD_REQUEST,
                ApiError.validation(Map.of(exception.getField(), exception.getMessage())));
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
    public ResponseEntity<ApiError> handleNotJson() {
        return respond(HttpStatus.BAD_REQUEST, ApiError.badRequest());
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
