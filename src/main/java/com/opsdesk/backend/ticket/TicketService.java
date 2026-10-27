package com.opsdesk.backend.ticket;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

// @Service marks this class as the business-logic layer. Like @Repository, it
// tells Spring to create one TicketService at startup and hand it to anything
// that asks for one. Technically @Service, @Repository and @Controller are all
// specialised forms of @Component and register the class the same way;
// @Service adds no extra behaviour. It exists so the code says which layer a
// class belongs to.
//
// Leave it off and Spring never creates a TicketService, so the app refuses to
// start: TicketController's constructor asks for one and there isn't one.
//
// The rules of the ticket workflow live here (a new ticket starts as OPEN, a
// missing ticket is a not-found error), not in the controller, so they hold
// however a ticket gets changed: through HTTP today, or a scheduled job or a
// test tomorrow.
@Service
public class TicketService {

    // final: set once in the constructor and never swapped afterwards, so the
    // service can never be left without a repository.
    private final TicketRepository ticketRepository;

    // A class with a single constructor needs no @Autowired: Spring uses that
    // constructor and passes in a TicketRepository it has already created.
    public TicketService(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    // The contract says the list is sorted by id, so the service asks for that
    // order explicitly. A plain findAll() has no ORDER BY, and PostgreSQL then
    // returns rows in whatever order they sit on disk. (NOTES.md, decision 7.)
    public List<Ticket> listTickets() {
        return ticketRepository.findAll(Sort.by("id"));
    }

    // orElseThrow turns "no such ticket" into an exception right here, so every
    // method that needs an existing ticket gets the same 404 for free.
    public Ticket getTicket(Long id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException(id));
    }

    // Takes plain values, not the CreateTicketRequest, so the service doesn't
    // depend on the shape of an HTTP request body.
    public Ticket createTicket(String title, String description, Priority priority, Team team) {
        // Every new ticket starts as OPEN, whatever the client asked for.
        Ticket ticket = new Ticket(trim(title), trim(description), priority, TicketStatus.OPEN, team);
        return ticketRepository.save(ticket);
    }

    // A null argument means "not sent, leave it alone". That's the difference
    // between PATCH (change some fields) and PUT (replace the whole ticket).
    public Ticket updateTicket(Long id, String title, String description,
                               Priority priority, TicketStatus status, Team team) {
        // Looked up first, so a missing ticket is a 404 before anything else
        // happens to it.
        Ticket ticket = getTicket(id);
        if (title != null) {
            ticket.setTitle(trim(title));
        }
        if (description != null) {
            ticket.setDescription(trim(description));
        }
        if (priority != null) {
            ticket.setPriority(priority);
        }
        if (status != null) {
            ticket.setStatus(status);
        }
        if (team != null) {
            ticket.setTeam(team);
        }
        // With today's List, the ticket was already changed in place, so this
        // save does nothing new. With JPA tomorrow, save is what writes the
        // change to the database, so it stays.
        return ticketRepository.save(ticket);
    }

    public void deleteTicket(Long id) {
        // Deleting a missing ticket is a 404 in the contract, not a silent
        // success, so check before deleting.
        if (!ticketRepository.existsById(id)) {
            throw new TicketNotFoundException(id);
        }
        ticketRepository.deleteById(id);
    }

    // The contract trims free-text fields before saving them. strip() rather
    // than trim(), because trim() only removes ASCII spaces and control
    // characters, while strip() removes all Unicode whitespace, the same as
    // the frontend's JavaScript trim(). Null stays null, so a missing field
    // can't crash here. (Rejecting missing fields is Thursday's validation.)
    private static String trim(String value) {
        return value == null ? null : value.strip();
    }
}
