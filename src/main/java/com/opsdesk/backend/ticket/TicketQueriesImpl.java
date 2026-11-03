package com.opsdesk.backend.ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.opsdesk.backend.user.AppUser;
import com.opsdesk.backend.user.Role;

import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Order;

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
    // The order goes in through the Specification (orderedBy, below), not as
    // a Spring Data Sort, so Sort.unsorted() and an unsorted page request are
    // passed on purpose. SimpleJpaRepository only replaces the ORDER BY when
    // it's given a Sort that is sorted, so the one set by orderedBy stands.
    // For the COUNT behind a page's totals, it clears any ORDER BY a
    // Specification set, so the count is unaffected.
    @Override
    @Transactional(readOnly = true)
    public List<Ticket> findVisible(AppUser actor, TicketFilters filters, TicketOrder order) {
        return executor.findAll(where(actor, filters).and(orderedBy(order)).and(withPeople()), Sort.unsorted());
    }

    @Override
    @Transactional(readOnly = true)
    public Page<Ticket> findVisible(AppUser actor, TicketFilters filters, TicketOrder order, Pageable pageable) {
        // Only the number and size are kept: a Sort that arrived inside the
        // Pageable would otherwise override orderedBy's ORDER BY.
        Pageable unsorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        return executor.findAll(where(actor, filters).and(orderedBy(order)).and(withPeople()), unsorted);
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

    // Loads each ticket's assignee and creator in the page query itself, with
    // two left joins, because every ticket in a response includes their
    // names (NOTES.md, the N+1 decision). Without it, the names are lazy
    // proxies read after the session has closed: a 500 for every list.
    // Before the fix, with a session left open, the same request ran one
    // query per distinct person on the page.
    //
    // LEFT, because a ticket with no assignee must still be listed. Both are
    // to-one, so the join adds columns, never rows: paging stays in SQL, and
    // Hibernate has no reason to page in memory.
    //
    // THE COUNT-QUERY TRAP. SimpleJpaRepository runs this same Specification
    // for the COUNT behind a page's totals. A fetch there is invalid: the
    // count selects count(t), not the ticket, so the fetched assignee has no
    // owner to attach to. Unguarded, a full first page or any later page
    // failed with:
    //   SemanticException: Query specified join fetching, but the owner of
    //   the fetched association was not present in the select list
    // and a 500. A first page that wasn't full still worked, because Spring
    // Data skips the COUNT when it already knows the total, so the bug hid
    // from any test with a large page size. The guard: the count query's
    // result type is Long, so skip the fetch there. The count needs no
    // users anyway; it still gets the visibility condition from where().
    private static Specification<Ticket> withPeople() {
        return (root, query, cb) -> {
            if (!isCountQuery(query)) {
                root.fetch("assignee", JoinType.LEFT);
                root.fetch("createdBy", JoinType.LEFT);
            }
            return null;
        };
    }

    private static boolean isCountQuery(CriteriaQuery<?> query) {
        return Long.class.equals(query.getResultType()) || long.class.equals(query.getResultType());
    }

    // The ORDER BY for a TicketOrder, then id ascending as the tiebreaker
    // (NOTES.md, the sorting decision). Each field maps to a fixed
    // expression; nothing from the request becomes a property path.
    //
    // A Specification that only orders: it sets the query's ORDER BY and
    // returns no condition (null, which and() leaves out of the WHERE).
    private static Specification<Ticket> orderedBy(TicketOrder order) {
        return (root, query, cb) -> {
            Expression<?> key = switch (order.field()) {
                case ID -> root.get("id");
                case TITLE -> cb.lower(root.get("title"));
                case STATUS -> cb.selectCase()
                        .when(cb.equal(root.get("status"), TicketStatus.OPEN), 0)
                        .when(cb.equal(root.get("status"), TicketStatus.IN_PROGRESS), 1)
                        .when(cb.equal(root.get("status"), TicketStatus.RESOLVED), 2)
                        .otherwise(3);
                case PRIORITY -> cb.selectCase()
                        .when(cb.equal(root.get("priority"), Priority.LOW), 0)
                        .when(cb.equal(root.get("priority"), Priority.MEDIUM), 1)
                        .when(cb.equal(root.get("priority"), Priority.HIGH), 2)
                        .otherwise(3);
                case TEAM -> root.get("team");
            };
            List<Order> orders = new ArrayList<>();
            orders.add(order.descending() ? cb.desc(key) : cb.asc(key));
            if (order.field() != TicketOrder.Field.ID) {
                orders.add(cb.asc(root.get("id")));
            }
            query.orderBy(orders);
            return null;
        };
    }

    // The contract: title OR description contains the text, ignoring case.
    //
    // CLOSED LIST: search covers title and description, and nothing else
    // (NOTES.md, the filtering decision). Both appear in every response the
    // caller gets for these tickets, and the search only ever runs inside
    // her visible rows. Adding a field she can't read in a response (a
    // creator's email, a password hash, an internal note) would make search
    // an oracle: the row count would reveal whether a value exists without
    // the value ever being shown. Any field added here must already be in
    // the response for the same tickets.
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
