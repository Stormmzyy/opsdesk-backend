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

// Runs decision 22's ticket checks for PATCH /api/v1/tickets/{id} BEFORE
// Spring reads the request body:
//   step 3, 404: the ticket doesn't exist, or the caller may not see it
//   step 4, 403: the caller may see it but may not edit it
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

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"PATCH".equals(request.getMethod())) {
            return true;
        }
        // The {id} from the URL, as Spring matched it against the controller's
        // @PatchMapping("/{id}"). Absent on any path without an {id}.
        @SuppressWarnings("unchecked")
        Map<String, String> pathVariables =
                (Map<String, String>) request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String rawId = pathVariables == null ? null : pathVariables.get("id");
        AppUser actor = signedInUser();
        if (rawId == null || actor == null) {
            // No ticket in the path, or (which the security chain prevents) no
            // signed-in user: nothing to check here.
            return true;
        }
        Ticket ticket = ticketService.getVisibleTicket(rawId, actor);
        ticketService.canEdit(actor, ticket);
        return true;
    }

    private static AppUser signedInUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AppUser user ? user : null;
    }
}
