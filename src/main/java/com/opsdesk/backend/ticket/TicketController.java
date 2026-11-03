package com.opsdesk.backend.ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.Validator;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import com.opsdesk.backend.user.AppUser;

import jakarta.validation.Valid;

// @RestController does two jobs:
//   1. Like @Service, it tells Spring to create one TicketController at startup.
//      Spring MVC then reads its methods and registers each one as the handler
//      for a URL and HTTP method (GET /api/v1/tickets and so on).
//   2. It makes every method's return value become the response body. Spring
//      turns the returned object into JSON with Jackson. It's shorthand for
//      @Controller plus @ResponseBody on every method.
//
// Leave it off entirely and Spring never creates the controller. The app still
// starts, but no ticket URLs are registered, so every request to them gets a
// 404. Swap it for plain @Controller and the URLs are registered, but Spring
// treats each returned value as the name of an HTML template to render
// instead of data to send, and the requests fail.
//
// @RequestMapping on the class puts the shared start of every path here once,
// so each method below only adds its own part ("/{id}" and so on).
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final TicketService ticketService;

    // Dependency injection, in plain English:
    //
    // This controller needs a TicketService to do its work, but it doesn't
    // create one. It just lists it as a constructor parameter, which is a way
    // of saying "I can't work without one of these".
    //
    // Spring does the rest when the app starts. It finds TicketRepository,
    // TicketService and TicketController (by their annotations) and works out
    // from their constructors which needs which. It creates them in that order:
    // the repository first, then the service (handing it the repository), then
    // this controller, handing it that service by calling this constructor.
    // Every class that asks for a TicketService gets the same single instance.
    //
    // Without Spring, I'd have to write that wiring myself, somewhere in main():
    //
    //     TicketRepository repository = new TicketRepository();
    //     TicketService service = new TicketService(repository);
    //     TicketController controller = new TicketController(service);
    //
    // and then register the controller's methods with a web server by hand.
    // I'd also have to keep the order right and make sure nobody creates a
    // second repository with its own separate list of tickets.
    //
    // Asking through the constructor, rather than an @Autowired field, means
    // the controller can't exist without its service, and a test can build one
    // with a fake service: new TicketController(fakeService).
    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    // Each method below only translates between HTTP and Java: it reads the
    // path and body, calls the service, and says which status code to send.
    // The rules live in the service.
    //
    // Every ticket leaves through TicketResponse.from, never as the Ticket
    // entity itself, so the JSON is always exactly the contract's six fields.
    // (NOTES.md, decisions 4 and 8.)

    // GET /api/v1/tickets -> 200. Two shapes, chosen by the query string:
    //   - none of page, size, status, priority, team, search or sort sent:
    //     the plain v1 JSON array of every ticket, by id. An empty list is
    //     still a 200 with [], not a 404.
    //   - any of them sent: the contract's paginated envelope (TicketPage).
    // The return type is Object because the two answers have different
    // shapes; Jackson writes whichever one is returned.
    //
    // @ModelAttribute builds TicketListParams from the query string, and
    // @Valid checks it. A bad or repeated parameter never reaches this
    // method: GlobalExceptionHandler answers VALIDATION_ERROR naming it.
    //
    // Both shapes contain only the tickets the caller may see: a USER's list
    // is her own tickets, and her page's totals count only those.
    @GetMapping
    public Object listTickets(@Valid @ModelAttribute TicketListParams params,
                              @AuthenticationPrincipal AppUser actor) {
        if (!params.anySent()) {
            return ticketService.listTickets(actor).stream()
                    .map(TicketResponse::from)
                    .toList();
        }
        long page = params.pageNumber();
        int size = params.pageSize();
        return TicketPage.from(
                ticketService.findTickets(page, size, params.filters(), params.order(), actor),
                page, size);
    }

    // Two checks on the query string itself, which TicketListParams can't
    // make because by the time it's built, both problems have been erased
    // (NOTES.md, the filtering decision):
    //
    // 1. A parameter sent more than once (?status=OPEN&status=CLOSED) is a
    //    400 VALIDATION_ERROR on that parameter: "Status can only be sent
    //    once." Spring binds repeated values into one String by joining them
    //    with commas. For status that fails @OneOf with a misleading message
    //    ("must be one of"); for search it silently searched for the literal
    //    text "printer,laptop", a 200 with the wrong answer. Taking the first
    //    or the last would be an arbitrary guess, and treating it as "either"
    //    would be a feature the contract doesn't have. Refusing it keeps that
    //    door open.
    //
    // 2. A parameter GET /tickets doesn't have (?stauts=OPEN) is a 400 on
    //    that name. Ignoring it, as the contract said until now, answered
    //    with every visible ticket: a superset that looks exactly like a
    //    correct answer, which no client can detect. Unknown BODY fields stay
    //    ignored, because a write that silently didn't happen CAN be detected,
    //    by reading the ticket back. The cost: a client that appends a
    //    cache-busting or tracking parameter (?_=1712345, ?utm_source=...)
    //    now gets a 400. Nothing does today; one that needs it should use
    //    Cache-Control. A reserved prefix could be let through later, and
    //    relaxing a 400 breaks nobody.
    //
    // @InitBinder("ticketListParams") runs only for listTickets' parameter
    // object, before @Valid. The check is put FIRST among the binder's
    // validators: GlobalExceptionHandler keeps the first message per field,
    // and a repeated status also fails @OneOf, so this message must come
    // first to be the one the client sees.
    @InitBinder("ticketListParams")
    void checkListQueryString(WebDataBinder binder, WebRequest request) {
        List<Validator> validators = new ArrayList<>();
        validators.add(new QueryStringValidator(request.getParameterMap()));
        validators.addAll(binder.getValidators());
        binder.replaceValidators(validators.toArray(Validator[]::new));
    }

    private static final class QueryStringValidator implements Validator {

        private final Map<String, String[]> query;

        QueryStringValidator(Map<String, String[]> query) {
            this.query = query;
        }

        @Override
        public boolean supports(Class<?> type) {
            return TicketListParams.class.equals(type);
        }

        @Override
        public void validate(Object target, Errors errors) {
            TicketListParams.LABELS.forEach((name, label) -> {
                String[] values = query.get(name);
                if (values != null && values.length > 1) {
                    errors.rejectValue(name, "sentOnce", label + " can only be sent once.");
                }
            });
            // An unknown name isn't a field of TicketListParams, so
            // rejectValue (which reads the field) can't report it. A FieldError
            // added directly can: the contract's "fields" keys are the
            // parameter names, and this one is a parameter name too. It's
            // listed after the known parameters' messages.
            if (errors instanceof BindingResult result) {
                for (String name : query.keySet()) {
                    if (!TicketListParams.LABELS.containsKey(name)) {
                        result.addError(new FieldError(result.getObjectName(), name, null, false, null, null,
                                "There is no query parameter called \"" + name + "\"."));
                    }
                }
            }
        }
    }

    // GET /api/v1/tickets/{id} -> 200, or 404 via GlobalExceptionHandler.
    // @PathVariable copies the {id} part of the URL into the id parameter as
    // text. The service parses it, so that a missing ticket and a ticket the
    // caller may not see give exactly the same 404 (TicketService,
    // getVisibleTicket).
    @GetMapping("/{id}")
    public TicketResponse getTicket(@PathVariable String id, @AuthenticationPrincipal AppUser actor) {
        return TicketResponse.from(ticketService.getVisibleTicket(id, actor));
    }

    // POST /api/v1/tickets -> 201 Created with the new ticket.
    // @RequestBody: Jackson reads the JSON body into a CreateTicketRequest.
    // @ResponseStatus: a method answers 200 unless told otherwise, and the
    // contract says a successful create is 201.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    // @Valid: after Jackson has built the CreateTicketRequest (and its
    // constructor has trimmed it), Spring runs every constraint on it. If any
    // fail, the method never runs and Spring throws
    // MethodArgumentNotValidException with every failing field.
    //
    // @AuthenticationPrincipal: the signed-in user, which JwtAuthFilter put
    // there from the token. It becomes the ticket's creator.
    //
    // The creator comes from the token and never from the request body, the
    // same rule as the role on register. CreateTicketRequest has no
    // createdBy or createdById field, so if a client sends one, Jackson
    // finds nowhere to put it and ignores it: nobody can create a ticket in
    // someone else's name. (Same annotation and package in Boot 3.)
    public TicketResponse createTicket(@Valid @RequestBody CreateTicketRequest request,
                                       @AuthenticationPrincipal AppUser actor) {
        return TicketResponse.from(ticketService.createTicket(
                request.titleText(), request.descriptionText(), request.priorityValue(), request.teamValue(),
                actor));
    }

    // PATCH /api/v1/tickets/{id} -> 200 with the whole updated ticket.
    // PATCH, not PUT, because the client sends only the fields it wants to
    // change; PUT would mean "replace the whole ticket with this body".
    //
    // No @PreAuthorize: every role may edit something, so who may edit
    // depends on the ticket. TicketService decides, after loading it.
    @PatchMapping("/{id}")
    public TicketResponse updateTicket(@PathVariable String id, @Valid @RequestBody UpdateTicketRequest request,
                                       @AuthenticationPrincipal AppUser actor) {
        return TicketResponse.from(ticketService.updateTicket(id, actor,
                request.titleText(), request.descriptionText(), request.priorityValue(),
                request.statusValue(), request.teamValue()));
    }

    // DELETE /api/v1/tickets/{id} -> 204 No Content. A void method with 204
    // sends no body at all, which is what the contract requires.
    //
    // Only an ADMIN deletes (README.md, Permissions). This is a role gate,
    // decision 22 step 2: the answer depends only on who the caller is, never
    // on which ticket, so it can be decided from the token alone, before the
    // ticket is loaded. @PreAuthorize runs before the method body, so a
    // refused caller never reaches the lookup: a MANAGER deleting ticket 999
    // gets 403, not 404. That reveals nothing, because the answer is the
    // same for every id.
    //
    // The ownership rules (an AGENT editing what's assigned to them, a USER
    // editing their own OPEN ticket) can't work this way. Their answer
    // depends on the ticket's creator, assignee and status, so the ticket has
    // to be loaded first. They run in TicketService after the lookup,
    // decision 22 step 4.
    //
    // hasRole('ADMIN') adds the ROLE_ prefix itself and checks for the
    // authority ROLE_ADMIN, which is what JwtAuthFilter grants. If the filter
    // granted a bare "ADMIN" instead, nothing would fail loudly: every role
    // gate would quietly refuse everyone with a 403.
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasRole('ADMIN')")
    public void deleteTicket(@PathVariable String id, @AuthenticationPrincipal AppUser actor) {
        ticketService.deleteTicket(id, actor);
    }

    // PATCH /api/v1/tickets/{id}/assignee -> 200 with the ticket.
    // Body: { "assigneeId": 2 }. (NOTES.md, decision 27.)
    //
    // Its own endpoint, not an "assigneeId" field in PATCH /tickets/{id},
    // for two reasons:
    //   - Assigning and editing are different rows of the permission table,
    //     with different permissions: an AGENT may edit a ticket assigned to
    //     them but may not assign it. One combined request could mix a change
    //     the caller may make with one they may not, and the server would have
    //     to refuse all of it or apply half.
    //   - The role gate depends on nothing but the path, so on its own
    //     endpoint it can be decided before the ticket is loaded or the body
    //     is read (decision 22, step 2). TicketEditAccessInterceptor is what
    //     makes "before the body is read" true; see it for why
    //     @PreAuthorize alone can't.
    //
    // @PreAuthorize is the role gate; TicketService.canAssign repeats it as
    // the rule itself. Its comment says what each one catches.
    //
    // KEEP IN STEP: the role list here ('ADMIN', 'MANAGER'), on this method
    // and on unassignTicket below, duplicates the one in
    // TicketService.canAssign. Nothing makes them agree. Change one and
    // change both, or the HTTP gate and the service rule will disagree about
    // who may assign.
    //
    // The response is the contract's ticket, which has no assignee field
    // yet, so it can't show who the ticket is now assigned to. Adding one is
    // a contract change for Thursday.
    @PatchMapping("/{id}/assignee")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public TicketResponse assignTicket(@PathVariable String id, @Valid @RequestBody AssignTicketRequest request,
                                       @AuthenticationPrincipal AppUser actor) {
        return TicketResponse.from(ticketService.assignTicket(id, actor, request.assigneeIdValue()));
    }

    // DELETE /api/v1/tickets/{id}/assignee -> 200 with the ticket, now
    // assigned to nobody. The way to unassign: in the PATCH body null means
    // "not sent" (decision 11), so it can't also mean "remove the assignee".
    // Removing someone who isn't there is a no-op, like assigning someone who
    // already is. 200 with the ticket rather than 204, so both assignee
    // endpoints answer the same way.
    @DeleteMapping("/{id}/assignee")
    @PreAuthorize("hasAnyRole('ADMIN', 'MANAGER')")
    public TicketResponse unassignTicket(@PathVariable String id, @AuthenticationPrincipal AppUser actor) {
        return TicketResponse.from(ticketService.assignTicket(id, actor, null));
    }

    // No error handlers here any more. Every error, from this controller or
    // from Spring before it, is turned into the contract's error body by
    // com.opsdesk.backend.error.GlobalExceptionHandler.
}
