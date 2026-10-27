package com.opsdesk.backend.ticket;

import org.springframework.data.jpa.repository.JpaRepository;

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
public interface TicketRepository extends JpaRepository<Ticket, Long> {
}
