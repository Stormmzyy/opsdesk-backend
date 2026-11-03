package com.opsdesk.backend.ticket;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

// Why there's no implementation: Spring Data writes it.
//
// At startup, Spring Data finds every interface that extends one of its
// repository types. For each one it creates a proxy: an object built at
// runtime that implements this interface. Calls to findById, save, count and
// so on are passed to SimpleJpaRepository, a class in Spring Data that turns
// them into Hibernate calls and so into SQL. The proxy is registered as a
// bean, and that bean is what TicketService's constructor receives.
//
// The two type parameters say what it stores: Ticket entities, whose @Id is
// a Long.
//
// Why there's no @Repository: that annotation tells component scanning "make
// a bean of this class", and component scanning skips interfaces, because an
// interface can't be created with "new". Spring Data finds repositories
// through its own scan, which Spring Boot switches on automatically when
// spring-boot-starter-data-jpa is on the classpath. It also applies the
// database-exception translation @Repository used to give us.
//
// WHY THIS EXTENDS THE BARE Repository, NOT JpaRepository (NOTES.md, the
// filtering decision). JpaRepository would hand every caller findAll(): all
// tickets, no caller, no visibility. JpaSpecificationExecutor, which this
// used to extend, would hand them findAll(spec): any WHERE clause at all,
// with or without the visibility condition. Either one makes "a USER sees
// everyone's tickets" a one-line mistake. Extending the bare Repository means
// a method exists only if it's declared below, so the list is exactly:
//   - single-ticket reads and writes, which Spring Data implements as usual
//     because their signatures match CrudRepository's (the "selectively
//     exposing CRUD methods" pattern);
//   - the multi-ticket queries from TicketQueries, every one of which takes
//     the caller and applies her visibility first.
// TicketRepositoryShapeTest checks that the list stays that way.
//
// The old findByStatus derived query is gone for the same reason: it
// returned many tickets with no caller and no visibility, and nothing used
// it.
//
// Single-ticket reads (findById) don't take the caller. Visibility for one
// ticket is TicketService.getVisibleTicket's job, because it must turn
// "not allowed" into the same 404 as "doesn't exist" (decision 23), which a
// query can't do.
public interface TicketRepository extends Repository<Ticket, Long>, TicketQueries {

    Optional<Ticket> findById(Long id);

    <S extends Ticket> S save(S ticket);

    // The seeder inserts the sample tickets in one call.
    <S extends Ticket> List<S> saveAll(Iterable<S> tickets);

    void delete(Ticket ticket);

    void deleteById(Long id);

    // All tickets, not the caller's. Only the seeder uses it, to see whether
    // the table is empty; it returns a number, not tickets.
    long count();
}
