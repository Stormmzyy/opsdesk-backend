package com.opsdesk.backend.ticket;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

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
    //   - none of page, size, status or search sent: the plain v1 JSON array
    //     of every ticket. An empty list is still a 200 with [], not a 404.
    //   - any of them sent: the contract's paginated envelope (TicketPage).
    // The return type is Object because the two answers have different
    // shapes; Jackson writes whichever one is returned.
    //
    // @ModelAttribute builds TicketListParams from the query string, and
    // @Valid checks it. A bad page, size or status never reaches this method:
    // GlobalExceptionHandler answers VALIDATION_ERROR naming the parameter.
    @GetMapping
    public Object listTickets(@Valid @ModelAttribute TicketListParams params) {
        if (!params.anySent()) {
            return ticketService.listTickets().stream()
                    .map(TicketResponse::from)
                    .toList();
        }
        long page = params.pageNumber();
        int size = params.pageSize();
        return TicketPage.from(
                ticketService.findTickets(page, size, params.statusValue(), params.searchText()),
                page, size);
    }

    // GET /api/v1/tickets/{id} -> 200, or 404 via GlobalExceptionHandler.
    // @PathVariable copies the {id} part of the URL into the id parameter as
    // text; TicketIds.parse decides whether it's a valid id.
    @GetMapping("/{id}")
    public TicketResponse getTicket(@PathVariable String id) {
        return TicketResponse.from(ticketService.getTicket(TicketIds.parse(id)));
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
    @PatchMapping("/{id}")
    public TicketResponse updateTicket(@PathVariable String id, @Valid @RequestBody UpdateTicketRequest request) {
        return TicketResponse.from(ticketService.updateTicket(TicketIds.parse(id),
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
    public void deleteTicket(@PathVariable String id) {
        ticketService.deleteTicket(TicketIds.parse(id));
    }

    // No error handlers here any more. Every error, from this controller or
    // from Spring before it, is turned into the contract's error body by
    // com.opsdesk.backend.error.GlobalExceptionHandler.
}
