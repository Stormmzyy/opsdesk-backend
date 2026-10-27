package com.opsdesk.backend.ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

// @Repository marks this class as the storage layer. At startup, Spring scans
// the package for classes with this kind of annotation, creates exactly one
// TicketRepository, and keeps it to hand to anything that asks for one.
// With JPA on the classpath it also translates database-specific exceptions
// into Spring's own DataAccessException family, so the layers above don't
// depend on which database is underneath. (That part does nothing yet,
// because a List can't throw database exceptions.)
//
// Leave it off and Spring never creates a TicketRepository. The app then
// refuses to start, because TicketService's constructor asks for one and
// there isn't one: "Parameter 0 of constructor in
// com.opsdesk.backend.ticket.TicketService required a bean of type
// 'com.opsdesk.backend.ticket.TicketRepository' that could not be found."
//
// The method names (findAll, findById, save, existsById, count, deleteById) are the
// same ones Spring Data's JpaRepository uses. Tomorrow this class becomes a
// JpaRepository interface, and TicketService shouldn't need to change.
@Repository
public class TicketRepository {

    // Spring handles requests on many threads at once, and ArrayList isn't
    // safe to change from two threads at the same time. Every method below is
    // synchronized, so only one thread can be inside any of them at a time.
    private final List<Ticket> tickets = new ArrayList<>();

    // Only ever goes up, so an id is never handed out twice, even after a
    // delete. (See NOTES.md, decision 1.)
    private long nextId = 1;

    // Returns a copy, so a caller looping over the list can't be tripped up by
    // another request adding or removing a ticket mid-loop. Tickets are added
    // with ever-increasing ids, so insertion order is already id order, which
    // is the order the contract asks for.
    public synchronized List<Ticket> findAll() {
        return List.copyOf(tickets);
    }

    // Optional makes "there may be no such ticket" part of the method's type,
    // so the caller is forced to decide what happens then, instead of getting a
    // null it forgot to check.
    public synchronized Optional<Ticket> findById(Long id) {
        return tickets.stream()
                .filter(ticket -> ticket.getId().equals(id))
                .findFirst();
    }

    public synchronized boolean existsById(Long id) {
        return findById(id).isPresent();
    }

    // long, not int, to match JpaRepository's count().
    public synchronized long count() {
        return tickets.size();
    }

    // One method for both create and update, like JpaRepository: a ticket with
    // no id is new and gets the next one; a ticket with an id replaces the
    // stored ticket that has the same id.
    public synchronized Ticket save(Ticket ticket) {
        if (ticket.getId() == null) {
            ticket.setId(nextId++);
            tickets.add(ticket);
            return ticket;
        }
        for (int i = 0; i < tickets.size(); i++) {
            if (tickets.get(i).getId().equals(ticket.getId())) {
                tickets.set(i, ticket);
                return ticket;
            }
        }
        tickets.add(ticket);
        return ticket;
    }

    public synchronized void deleteById(Long id) {
        tickets.removeIf(ticket -> ticket.getId().equals(id));
    }
}
