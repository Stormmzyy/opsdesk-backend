package com.opsdesk.backend.ticket;

import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.opsdesk.backend.user.AppUser;
import com.opsdesk.backend.user.Role;

import jakarta.persistence.EntityManager;

// The one place a ticket list query is built and run (NOTES.md, the
// filtering decision).
//
// The danger this class exists to remove: moving filters to Specifications
// makes it easy to build a WHERE clause from the filters and forget the
// visibility condition. A USER sending ?status=OPEN would get every open
// ticket, everyone's, and the response would look perfectly normal. Nothing
// fails; the data is just wrong. So the unsafe version is made impossible to
// write, not just avoided:
//
//   1. Nothing else can RUN a Specification on tickets. TicketRepository no
//      longer extends JpaSpecificationExecutor (or JpaRepository, whose
//      findAll() has no caller either). The only executor is the private
//      field below, which only this class can reach.
//   2. Nothing else can BUILD one. The factories (visibility, each filter)
//      are private static methods here, and the filters arrive as plain data
//      (TicketFilters), not as query fragments.
//   3. The one method that builds the WHERE clause, where(), takes the caller
//      and starts from the caller's visibility, then ANDs the filters onto
//      it. There's no overload without the caller to call by mistake.
//   4. TicketRepositoryShapeTest fails the build if TicketRepository ever
//      gains a method that returns many tickets without taking the caller,
//      or that takes a Specification.
//
// What it can't stop is code that bypasses Spring Data altogether and writes
// JPQL against an EntityManager. That's a deliberate act that shows up in
// review, not a forgotten .and().
//
// Package-private, like the fragment interface: only Spring Data (which
// creates it) and TicketRepository's callers in this package need it.
class TicketQueriesImpl implements TicketQueries {

    // A plain Spring Data repository built by hand, used ONLY for its
    // Specification methods (findAll(spec, sort), findAll(spec, pageable),
    // count(spec)). It runs exactly the SQL the old TicketRepository did,
    // including the COUNT query behind a page's totals, so moving the queries
    // here changes no behaviour. Built by hand, rather than injected, so it is
    // not a bean anyone else could ask Spring for.
    private final SimpleJpaRepository<Ticket, Long> executor;

    // EntityManager here is Spring's shared, thread-safe proxy, the same one
    // the generated repositories use.
    TicketQueriesImpl(EntityManager entityManager) {
        this.executor = new SimpleJpaRepository<>(Ticket.class, entityManager);
    }

    // readOnly, like SimpleJpaRepository's own read methods: one read-only
    // transaction around the page query and its count.
    @Override
    @Transactional(readOnly = true)
    public List<Ticket> findVisible(AppUser actor, TicketFilters filters, Sort sort) {
        return executor.findAll(where(actor, filters), sort);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Ticket> findVisible(AppUser actor, TicketFilters filters, Pageable pageable) {
        return executor.findAll(where(actor, filters), pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public long countVisible(AppUser actor, TicketFilters filters) {
        return executor.count(where(actor, filters));
    }

    // The WHERE clause, always in this order: who may see what, THEN what was
    // asked for. Starting from visibility means a new filter added below can
    // only ever narrow what the caller sees, never widen it.
    private static Specification<Ticket> where(AppUser actor, TicketFilters filters) {
        Specification<Ticket> spec = visibleTo(actor);
        if (filters.status() != null) {
            spec = spec.and(hasStatus(filters.status()));
        }
        if (filters.priority() != null) {
            spec = spec.and(hasPriority(filters.priority()));
        }
        if (filters.team() != null) {
            spec = spec.and(hasTeam(filters.team()));
        }
        if (filters.search() != null && !filters.search().isEmpty()) {
            spec = spec.and(titleOrDescriptionContains(filters.search()));
        }
        return spec;
    }

    // Which tickets this caller may see (README.md, Permissions; NOTES.md,
    // decision 27). ADMIN, MANAGER and AGENT see every ticket, so no
    // condition. A USER sees only the tickets she created, so the condition
    // is created_by_id = her id.
    //
    // It's a condition in the query itself, not a filter applied to the rows
    // afterwards, because the same WHERE clause feeds the COUNT that gives
    // totalItems and totalPages. Filtering a loaded page instead would count
    // tickets she can't see (totalItems 12 for a caller who can see 2), and a
    // page of 20 would come back with whatever survived the filter.
    //
    // root.get("createdBy").get("id") compares the foreign key column
    // directly; Hibernate doesn't need to join app_user for it.
    //
    // Spring Data 4's Specification.unrestricted() means "no condition"; Boot 3
    // code usually started from Specification.where(null).
    private static Specification<Ticket> visibleTo(AppUser actor) {
        if (actor.getRole() != Role.USER) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> cb.equal(root.get("createdBy").get("id"), actor.getId());
    }

    private static Specification<Ticket> hasStatus(TicketStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    private static Specification<Ticket> hasPriority(Priority priority) {
        return (root, query, cb) -> cb.equal(root.get("priority"), priority);
    }

    private static Specification<Ticket> hasTeam(Team team) {
        return (root, query, cb) -> cb.equal(root.get("team"), team);
    }

    // The contract: title OR description contains the text, ignoring case.
    // (The caller has already trimmed it.) Both sides are lower-cased, so
    // "PRINTER" finds "Printer on the second floor".
    //
    // The search text goes in as a bound parameter, never pasted into the SQL,
    // so it can't inject SQL. But inside a LIKE pattern, % and _ are wildcards.
    // Unescaped, a search for "50%" would match "50" followed by anything.
    // Escaping them makes the search literal, which is what "contains this
    // text" means.
    private static Specification<Ticket> titleOrDescriptionContains(String search) {
        String pattern = "%" + escapeLike(search.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("title")), pattern, '\\'),
                cb.like(cb.lower(root.get("description")), pattern, '\\'));
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
