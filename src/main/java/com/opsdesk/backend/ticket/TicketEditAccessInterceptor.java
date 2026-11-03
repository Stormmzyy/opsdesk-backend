package com.opsdesk.backend.ticket;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import com.opsdesk.backend.user.AppUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Runs decision 22's checks that come before the body, BEFORE Spring reads
// the request body:
//   PATCH /api/v1/tickets/{id}
//     step 3, 404: the ticket doesn't exist, or the caller may not see it
//     step 4, 403: the caller may see it but may not edit it
//   PATCH and DELETE /api/v1/tickets/{id}/assignee
//     step 2, 403: the caller's role may never assign
//     step 3, 404: the ticket doesn't exist, or the caller may not see it
// so they come before step 5 (400 BAD_REQUEST) and step 6 (400
// VALIDATION_ERROR), as the check order says.
//
// Why an interceptor: Spring reads @RequestBody and runs @Valid while it
// prepares the controller method's arguments, and a broken body throws
// there, so the controller never runs. preHandle runs after Spring has
// chosen the controller method but before those arguments are prepared.
// Everything steps 3 and 4 need is already available by then, and none of it
// comes from the body: the id is in the URL, the caller in the token, and the
// creator, assignee and status on the ticket's row. So a malformed body gets
// the same 404 or 403 as a well-formed one, with no special case anywhere.
//
// Before this, GlobalExceptionHandler looked the ticket up itself when a
// body failed (decision 6), and then also had to know the edit rule. That
// put authorization in the error path, in a second file that had to agree
// with the service. This class decides nothing either: it calls the same
// TicketService methods the controller's call will use.
//
// It deliberately checks only the ticket-level rule. Which fields a USER may
// change depends on the body, so that part stays in TicketService.updateTicket,
// after the body has been read and validated. The service also repeats the
// checks below, so the rules still hold for any caller that doesn't come
// through this interceptor.
//
// An exception thrown here is handled by GlobalExceptionHandler exactly like
// one thrown by a controller, so the 404 and 403 bodies are the usual ones.
//
// The cost: a PATCH loads the ticket twice, here and in the service.
@Component
public class TicketEditAccessInterceptor implements HandlerInterceptor {

    private final TicketService ticketService;

    public TicketEditAccessInterceptor(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    // The two controller paths it acts on, exactly as declared in
    // TicketController's mappings.
    private static final String TICKET = "/api/v1/tickets/{id}";
    private static final String ASSIGNEE = "/api/v1/tickets/{id}/assignee";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // Which mapping Spring chose for this request, and the {id} it read
        // from the URL. Using the chosen pattern rather than matching the URL
        // again means this can't disagree with the controller about which
        // endpoint the request is for.
        String pattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        @SuppressWarnings("unchecked")
        Map<String, String> pathVariables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String rawId = pathVariables == null ? null : pathVariables.get("id");
        AppUser actor = signedInUser();
        if (pattern == null || rawId == null || actor == null) {
            // Not a single-ticket path, or (which the security chain prevents)
            // no signed-in user: nothing to check here.
            return true;
        }
        String method = request.getMethod();

        if (TICKET.equals(pattern) && "PATCH".equals(method)) {
            // Edit: step 3 (404), then step 4 (403).
            Ticket ticket = ticketService.getVisibleTicket(rawId, actor);
            ticketService.canEdit(actor, ticket);
        } else if (ASSIGNEE.equals(pattern) && ("PATCH".equals(method) || "DELETE".equals(method))) {
            // Assign or unassign: step 2 (the role gate, 403) before step 3
            // (404). The role gate comes first and needs no ticket, so a USER
            // gets the same 403 for /tickets/999/assignee as for
            // /tickets/2/assignee, and learns nothing about which ids exist.
            //
            // This is also what makes the role gate run before the body is
            // read. @PreAuthorize on the controller method can't: it wraps the
            // method call, and Spring has to read and validate the body to
            // build the method's arguments before it can call it. Measured:
            // without this, a USER sending a broken body got 400, not 403.
            ticketService.canAssign(actor, null);
            ticketService.getVisibleTicket(rawId, actor);
        }
        return true;
    }

    private static AppUser signedInUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AppUser user ? user : null;
    }
}
