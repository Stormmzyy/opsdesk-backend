package com.opsdesk.backend.ticket;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

// Why there's no implementation: Spring Data writes it.
//
// At startup, Spring Data finds every interface that extends one of its
// repository types. For each one it creates a proxy: an object built at
// runtime that implements this interface. Calls to findAll, findById, save,
// existsById, count and deleteById are passed to SimpleJpaRepository, a class
// in Spring Data that turns them into Hibernate calls and so into SQL. The
// proxy is registered as a bean, and that bean is what TicketService's
// constructor receives.
//
// The two type parameters say what it stores: Ticket entities, whose @Id is
// a Long.
//
// Why there's no @Repository: that annotation tells component scanning "make
// a bean of this class", and component scanning skips interfaces, because an
// interface can't be created with "new". Spring Data finds repositories
// through its own scan, which Spring Boot switches on automatically when
// spring-boot-starter-data-jpa is on the classpath. It also applies the
// database-exception translation @Repository used to give us. Adding
// @Repository here would be harmless but would do nothing.
//
// Yesterday's List, id counter and synchronized blocks are gone. PostgreSQL
// now does all three jobs: it stores the rows, a sequence hands out ids, and
// transactions keep concurrent requests from corrupting each other.
//
// JpaSpecificationExecutor adds findAll(Specification, Pageable) and
// count(Specification): queries whose WHERE clause is built at runtime from
// optional parts, used by GET /tickets with filters (see TicketSpecifications).
public interface TicketRepository extends JpaRepository<Ticket, Long>, JpaSpecificationExecutor<Ticket> {

    // A derived query: no SQL and no implementation, just a method name.
    //
    // At startup Spring Data takes the name apart:
    //   "find...By" -> this is a query that returns entities
    //   "Status"    -> the Ticket field "status", which becomes the WHERE clause
    //   the parameter -> the value it's compared to
    // From that it builds the JPQL query
    //   select t from Ticket t where t.status = ?1
    // and Hibernate turns that into SQL for PostgreSQL, using the real column
    // name and storing the enum as its name ("OPEN"), as @Enumerated says.
    // Longer names combine the same way: findByStatusAndTeam,
    // findByTitleContainingIgnoreCase, findByStatusOrderByIdAsc.
    //
    // A misspelt field name, such as findByStatuss, doesn't fail when the
    // method is called. It fails when the app starts: Spring Data can't find a
    // field called "statuss" on Ticket, so the repository can't be created and
    // the app refuses to start. That's the advantage over a hand-written query
    // string: a typo can't hide until a user happens to hit that code path.
    //
    // No ORDER BY: this returns tickets in whatever order PostgreSQL finds
    // them. Anything that turns this into an API response must ask for id
    // order, as decision 7 in NOTES.md explains.
    List<Ticket> findByStatus(TicketStatus status);
}
