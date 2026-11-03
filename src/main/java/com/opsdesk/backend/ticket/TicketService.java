package com.opsdesk.backend.ticket;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.opsdesk.backend.error.InvalidFieldException;
import com.opsdesk.backend.user.AppUser;
import com.opsdesk.backend.user.AppUserRepository;
import com.opsdesk.backend.user.Role;

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
    // Only to look up the user a ticket is being assigned to.
    private final AppUserRepository appUserRepository;

    // A class with a single constructor needs no @Autowired: Spring uses that
    // constructor and passes in the repositories it has already created.
    public TicketService(TicketRepository ticketRepository, AppUserRepository appUserRepository) {
        this.ticketRepository = ticketRepository;
        this.appUserRepository = appUserRepository;
    }

    // The contract says the list is sorted by id, so the service asks for that
    // order explicitly. A plain findAll() has no ORDER BY, and PostgreSQL then
    // returns rows in whatever order they sit on disk. (NOTES.md, decision 7.)
    //
    // Only the tickets this caller may see: every ticket for ADMIN, MANAGER
    // and AGENT, only her own for a USER. The repository applies that itself:
    // findVisible is the only list query it has, and it takes the caller
    // (TicketQueriesImpl).
    public List<Ticket> listTickets(AppUser actor) {
        return ticketRepository.findVisible(actor, TicketFilters.NONE, TicketOrder.DEFAULT);
    }

    // One page of tickets, filtered by any of status, priority, team and
    // search text. page is 1-based, as the contract counts.
    //
    // Visibility isn't added here, and can't be forgotten here: the service
    // has no way to run a ticket query without passing the caller.
    // TicketQueriesImpl starts every WHERE clause from her visibility, so it's
    // in both the page query and the count query, and a USER's totalItems and
    // totalPages count only her own tickets.
    public Page<Ticket> findTickets(long page, int size, TicketFilters filters, TicketOrder order,
                                    AppUser actor) {
        // A page so far out that its first row would be past row 2,147,483,647
        // can't be asked for: Hibernate's offset is an int, and the request
        // would crash with a 500. No table here is that big, so the page is
        // certainly past the last one. The contract says that's a 200 with no
        // items and the real totals, so answer exactly that with a count.
        if (page - 1 > Integer.MAX_VALUE / size) {
            return new PageImpl<>(List.of(), Pageable.unpaged(), ticketRepository.countVisible(actor, filters));
        }

        // The off-by-one: the contract's pages start at 1 ("page=1" is the
        // first page), but Spring's PageRequest starts at 0. So page 1 is
        // Spring's page 0, hence page - 1. Without it, ?page=1 would silently
        // return the second page, and the first ten tickets could never be
        // fetched.
        //
        // The order goes into the paged query itself, always ending with id
        // as the tiebreaker (NOTES.md, decision 7). Without a total order,
        // PostgreSQL may return rows in a different order for each query, so
        // page 1 and page 2 could overlap or skip tickets. The page request
        // carries no Sort: the order is the TicketOrder, and only that.
        Pageable pageable = PageRequest.of((int) (page - 1), size);
        return ticketRepository.findVisible(actor, filters, order, pageable);
    }

    // orElseThrow turns "no such ticket" into an exception right here, so every
    // method that needs an existing ticket gets the same 404 for free.
    public Ticket getTicket(Long id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException(id));
    }

    // A ticket as one caller may see it, looked up by the id exactly as it
    // appeared in the URL. Every endpoint with a ticket id in its path comes
    // through here: GET, PATCH, DELETE, and the PATCH interceptor.
    //
    // Three outcomes, two of them deliberately indistinguishable:
    //   - the id isn't a valid id, or no ticket has it: 404
    //   - the ticket exists, but this caller may not see it: the SAME 404
    //   - otherwise, the ticket
    //
    // DELIBERATE: a ticket the caller can't see answers 404, not 403
    // (NOTES.md, decision 23). A 403 would confirm that the ticket exists
    // and belongs to someone else, so a USER could count other people's
    // tickets and probe their ids one by one. With the 404, "hidden" and
    // "doesn't exist" look exactly alike.
    //
    // This looks like a bug to anyone who doesn't know it's on purpose:
    // the ticket is right there in the database, and the API says it doesn't
    // exist. Don't "fix" it to a 403. That would put the leak back.
    //
    // Identical means identical: both refusals are built here, from the same
    // raw id text. The contract quotes the id as it was in the URL, so
    // /tickets/02 must say "02" whether ticket 2 is missing or merely hidden.
    // Building the hidden one from ticket.getId() would say "2" and give it
    // away.
    public Ticket getVisibleTicket(String rawId, AppUser actor) {
        Long id = TicketIds.parse(rawId);
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new TicketNotFoundException(rawId));
        if (!canView(actor, ticket)) {
            throw new TicketNotFoundException(rawId);
        }
        return ticket;
    }

    // Takes plain values, not the CreateTicketRequest, so the service doesn't
    // depend on the shape of an HTTP request body.
    //
    // createdBy is the signed-in user, passed in by the controller from the
    // token, never anything the client sent. It's required (NOTES.md,
    // decision 27): a null here fails on insert rather than saving a ticket
    // nobody owns.
    public Ticket createTicket(String title, String description, Priority priority, Team team,
                               AppUser createdBy) {
        // Every new ticket starts as OPEN, whatever the client asked for.
        Ticket ticket = new Ticket(trim(title), trim(description), priority, TicketStatus.OPEN, team);
        ticket.setCreatedBy(createdBy);
        return ticketRepository.save(ticket);
    }

    // A null argument means "not sent, leave it alone". That's the difference
    // between PATCH (change some fields) and PUT (replace the whole ticket).
    //
    // @Transactional (NOTES.md, decision 38): the ticket is loaded, changed
    // and flushed in ONE persistence context, so it stays managed from
    // findById to commit. Without it, the repository's own transaction ended
    // right after findById, the ticket came back detached, and save() had to
    // merge it: re-read the row, copy every field from our copy onto it
    // (including a status read before someone else changed it), then write
    // them all. Managed, save() has nothing to merge, and at commit Hibernate
    // compares the ticket with the snapshot it took on load, so
    // @DynamicUpdate writes only the fields this request changed, and
    // @Version refuses the write if the row moved on in between.
    //
    // The refusals below (404, 403) throw a RuntimeException, which rolls the
    // transaction back; nothing was changed by then anyway.
    @Transactional
    public Ticket updateTicket(String rawId, AppUser actor, String title, String description,
                               Priority priority, TicketStatus status, Team team) {
        // Looked up first, so a missing or hidden ticket is a 404 before
        // anything else happens to it.
        Ticket ticket = getVisibleTicket(rawId, actor);
        // Then the ownership rules (decision 22, step 4): may this caller
        // edit this ticket at all, and if they're a USER, does the request
        // only touch the fields she may change?
        canEdit(actor, ticket);
        rejectFieldsAUserMayNotChange(actor, ticket, priority, status, team);
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
        // The ticket is managed, so this save doesn't write anything itself:
        // the UPDATE happens at commit, when the method returns. It stays so
        // the method reads correctly, and still works if it's ever called
        // without the transaction (it would merge, as before).
        return ticketRepository.save(ticket);
    }

    // Assign the ticket to a user, or (assigneeId null) unassign it. The
    // controller sends null only for DELETE /assignee: in the PATCH body
    // assigneeId is required, so a missing field can never unassign.
    //
    // The checks, in decision 22's order:
    //   404 the ticket doesn't exist or can't be seen (getVisibleTicket)
    //   403 the caller isn't an ADMIN or MANAGER (canAssign)
    //   400 VALIDATION_ERROR the assignee is no user, or is a USER
    // The 400s come last because they're about the body, and the only way to
    // check them is to look the user up.
    //
    // Assigning to whoever already has it is not an error. The request asks
    // for an end state that already holds, so it answers 200 and, because
    // nothing changed, writes nothing.
    //
    // @Transactional for the same reason as updateTicket: the ticket stays
    // managed, so save() doesn't re-read it to merge a detached copy. This is
    // a tidy-up, not a correctness fix. Without it, the detached merge was
    // already safe once Ticket had @Version: merge compares the copy's
    // version with the row's and throws if the row moved on, so a stale
    // assign failed instead of writing back an old status.
    @Transactional
    public Ticket assignTicket(String rawId, AppUser actor, Long assigneeId) {
        Ticket ticket = getVisibleTicket(rawId, actor);
        canAssign(actor, ticket);
        AppUser assignee = null;
        if (assigneeId != null) {
            // 400, not 404: the URL's resource, the ticket, exists. What's
            // wrong is a value in the body.
            //
            // Together, "No user with id 99." and "A USER can't be assigned
            // tickets." reveal which user ids exist and which of them are
            // USERs. That's safe ONLY because assigning is limited to ADMIN
            // and MANAGER, who can already list every user (GET /users). If
            // assigning is ever opened to AGENT, these two messages become a
            // user enumeration oracle for a role that can't list users, and
            // they'd have to become one message that says neither.
            assignee = appUserRepository.findById(assigneeId)
                    .orElseThrow(() -> new InvalidFieldException("assigneeId", "No user with id " + assigneeId + "."));
            // A USER raises tickets and doesn't work them (NOTES.md, decision
            // 27: the reason #9 lost its assignee). ADMIN, MANAGER and AGENT
            // can all be assigned; the seed has a MANAGER on #7.
            if (assignee.getRole() == Role.USER) {
                throw new InvalidFieldException("assigneeId", "A USER can't be assigned tickets.");
            }
        }
        ticket.setAssignee(assignee);
        // Managed, so this writes nothing itself; the UPDATE runs at commit.
        return ticketRepository.save(ticket);
    }

    public void deleteTicket(String rawId, AppUser actor) {
        // Deleting a missing ticket is a 404 in the contract, not a silent
        // success, so look it up before deleting.
        Ticket ticket = getVisibleTicket(rawId, actor);
        // The controller's @PreAuthorize already refuses everyone but an
        // ADMIN. This repeats the rule where the rules live, so it still holds
        // if something other than that controller method ever deletes.
        canDelete(actor, ticket);
        ticketRepository.delete(ticket);
    }

    // ---------------------------------------------------------------------
    // The ownership rules: README.md's permission table, as NOTES.md
    // decision 27 reads it. Each method takes the caller and the ticket,
    // returns quietly when the rule allows the action, and throws
    // AccessDeniedException when it doesn't. GlobalExceptionHandler turns
    // that into the client's 403, with one fixed message; the reason given
    // here is only logged, at WARN, for whoever reads the server log.
    //
    // They run after the ticket is loaded (decision 22, step 4), because
    // their answer depends on the ticket: who created it, who it's assigned
    // to, what its status is. That's why they live here and not in a
    // @PreAuthorize on the controller, which runs before any lookup.
    //
    // People are compared by id, never as objects. The actor comes from
    // JwtAuthFilter and the ticket's creator and assignee from this ticket's
    // row, so they're different Java objects (the ticket's are Hibernate
    // proxies) even when they're the same person.
    // ---------------------------------------------------------------------

    // View: ADMIN, MANAGER and AGENT see every ticket; a USER sees only the
    // tickets she created. The list applies the same rule as a query
    // (TicketQueriesImpl.visibleTo); this is the single-ticket form.
    //
    // The one rule that answers instead of throwing. The other three throw
    // AccessDeniedException, which becomes a 403. A view refusal must not
    // become a 403: it has to come out as the same 404 as a ticket that
    // doesn't exist (decision 23), built from the raw id in the URL, which
    // only the caller has. So canView only says yes or no, and
    // getVisibleTicket turns "no" into that 404.
    boolean canView(AppUser actor, Ticket ticket) {
        return actor.getRole() != Role.USER || sameUser(actor, ticket.getCreatedBy());
    }

    // Edit, the ticket-level part. Which fields a USER may change is checked
    // separately, by rejectFieldsAUserMayNotChange, because it needs the
    // request. This part doesn't, so TicketEditAccessInterceptor can run it
    // before the body is read (decision 22: step 4 before the 400s).
    public void canEdit(AppUser actor, Ticket ticket) {
        switch (actor.getRole()) {
            case ADMIN, MANAGER -> {
                // Any ticket.
            }
            case AGENT -> {
                // Only a ticket assigned to them. Not one assigned to nobody
                // (#6, #9): an unassigned ticket is waiting for a MANAGER to
                // hand it to someone, and an AGENT editing it would be working
                // it without being given it. Not one assigned to someone else
                // (#3 is Liam's), so two agents never work the same ticket.
                if (ticket.getAssignee() == null) {
                    throw refusal(actor, ticket, "an AGENT may only edit tickets assigned to them; it is assigned to nobody");
                }
                if (!sameUser(actor, ticket.getAssignee())) {
                    throw refusal(actor, ticket, "an AGENT may only edit tickets assigned to them; it is assigned to someone else");
                }
            }
            case USER -> {
                // Only her own ticket, and only while it's OPEN. Once someone
                // is working it, the ticket is theirs to change, not hers.
                if (!sameUser(actor, ticket.getCreatedBy())) {
                    throw refusal(actor, ticket, "a USER may only edit tickets she created");
                }
                if (ticket.getStatus() != TicketStatus.OPEN) {
                    throw refusal(actor, ticket, "a USER may only edit her own ticket while it is OPEN; it is " + ticket.getStatus());
                }
            }
        }
    }

    // Edit, the field-level part, for a USER only: on her own OPEN ticket she
    // may change title and description, and nothing else.
    //
    // Why not status too: the wider reading is a trap. If she could change
    // the status, she could move her ticket to IN_PROGRESS, and at that
    // moment it's no longer OPEN, so she has lost the right to edit it. She
    // couldn't move it back either, because reopening it would itself be an
    // edit. One request would lock her out of her own ticket for good
    // (NOTES.md, decision 27). Priority and team are refused with it: those
    // are how the people who work tickets triage them.
    //
    // "Change" means present AND different from the current value. A field
    // sent with the value it already has is not an attempt to change it.
    // That matters for the real frontend: its Edit page sends the whole form
    // (title, description, priority, team and status) on every save, so
    // refusing a field merely for being present would refuse every edit she
    // makes there, even a title-only one.
    private void rejectFieldsAUserMayNotChange(AppUser actor, Ticket ticket,
                                               Priority priority, TicketStatus status, Team team) {
        if (actor.getRole() != Role.USER) {
            return;
        }
        if (status != null && status != ticket.getStatus()) {
            throw refusal(actor, ticket, "a USER may not change status");
        }
        if (priority != null && priority != ticket.getPriority()) {
            throw refusal(actor, ticket, "a USER may not change priority");
        }
        if (team != null && team != ticket.getTeam()) {
            throw refusal(actor, ticket, "a USER may not change team");
        }
    }

    // Assign: ADMIN and MANAGER only.
    //
    // The same rule also sits in @PreAuthorize on the two assignee endpoints.
    // Both exist because they catch different things:
    //   - @PreAuthorize is the role gate at the HTTP door. It documents the
    //     rule where the endpoint is declared, and still refuses if the
    //     interceptor's path patterns ever stop matching the endpoint. It
    //     runs only after the body has been read, though, which is why
    //     TicketEditAccessInterceptor calls this method first, with no
    //     ticket, to keep the role 403 ahead of the 400s (decision 22).
    //   - this method is the rule itself, in the service, so it holds for any
    //     caller of assignTicket: a future endpoint that forgets the
    //     annotation, a scheduled job, a test. Without it, the rule would
    //     live only in an annotation that every new caller has to remember.
    void canAssign(AppUser actor, Ticket ticket) {
        if (actor.getRole() != Role.ADMIN && actor.getRole() != Role.MANAGER) {
            throw refusal(actor, ticket, "only an ADMIN or MANAGER may assign tickets");
        }
    }

    // Delete: ADMIN only.
    void canDelete(AppUser actor, Ticket ticket) {
        if (actor.getRole() != Role.ADMIN) {
            throw refusal(actor, ticket, "only an ADMIN may delete tickets");
        }
    }

    // Compared by id: see the note above canView. Reading getId() on a
    // Hibernate proxy returns the id it already holds without loading the
    // row, so this works outside a transaction too. Checked with SQL logging:
    // an AGENT refused on someone else's ticket runs exactly two SELECTs (the
    // caller by email, the ticket by id) and none for the assignee.
    private static boolean sameUser(AppUser actor, AppUser other) {
        return other != null && actor.getId().equals(other.getId());
    }

    // The message never reaches the client (ApiError.forbidden() has one
    // fixed message); GlobalExceptionHandler logs it at WARN. So it names
    // everything a reader of the log needs: who, which ticket, which rule.
    // The ticket is null when a role rule refuses before any lookup (the
    // interceptor's assign gate); the request path in the log line still
    // names it.
    private static AccessDeniedException refusal(AppUser actor, Ticket ticket, String rule) {
        String where = ticket == null ? "before the ticket was loaded" : "on ticket " + ticket.getId();
        return new AccessDeniedException(actor.getRole() + " " + actor.getEmail() + " (user " + actor.getId()
                + ") refused " + where + ": " + rule);
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
