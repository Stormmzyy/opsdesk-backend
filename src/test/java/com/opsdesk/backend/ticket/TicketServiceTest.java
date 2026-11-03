package com.opsdesk.backend.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.opsdesk.backend.user.AppUser;
import com.opsdesk.backend.user.AppUserRepository;
import com.opsdesk.backend.user.Role;

// Unit tests for TicketService's single-ticket lookup, getVisibleTicket, with
// the repository replaced by a mock.
//
// What Mockito is doing:
//   - @Mock makes a mock TicketRepository: an object Mockito builds at runtime
//     that implements the interface, but has no real behaviour. Every method
//     returns an "empty" answer (null, 0, false, an empty Optional, an empty
//     list) until the test says otherwise.
//   - when(...).thenReturn(...) is "stubbing": the test decides what one call
//     returns. "When findById(1) is called, return this ticket."
//   - verify(...) checks afterwards that the service really did make a call,
//     with the arguments expected.
//   - MockitoExtension creates the @Mock fields before each test, and fails a
//     test that stubs a call the code never makes, which catches tests that
//     set up something irrelevant.
//
// Why test the service without a database:
//   - The service's own logic, like "a missing ticket and a hidden ticket get
//     the same 404, with the contract's message", can be checked on its own.
//     A failure points straight at the service, not at PostgreSQL, Docker or
//     the seed data.
//   - It's fast (milliseconds, no Spring, no database) and doesn't depend on
//     what's in the database, so it gives the same answer every time.
//   - The service is built with plain "new", passing the mocks to the same
//     constructor Spring uses. That's constructor injection paying off: no
//     Spring is needed to test it.
//
// What these tests would NOT catch:
//   - Anything wrong in the real repository or the database: a wrong column, a
//     missing fetch (decision 51), a constraint violation. The mock returns
//     whatever it's told, so it can't be wrong in the ways a database can.
//   - Anything in the HTTP layer: the URL, the status code, the JSON shape,
//     validation, the global exception handler, the check order.
//   - A mock that doesn't behave like the real thing. If the real findById
//     ever behaved differently, these tests would still pass.
// Those need an integration test against a real database, and controller
// tests through MockMvc, like TicketOutputOnlyFieldsTest.
//
// These used to test getTicket(Long), a lookup with no caller and no
// visibility check. It was deleted (any future endpoint could have picked it
// up and leaked a hidden ticket), and TicketServiceShapeTest now fails if a
// method like it comes back. getVisibleTicket is the lookup every endpoint
// uses, so it's the one tested here.
@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    // Only needed for assigning, which these tests don't cover, but the
    // constructor asks for it.
    @Mock
    private AppUserRepository appUserRepository;

    private TicketService ticketService;

    private final AppUser priya = user(1L, "Priya Sharma", Role.ADMIN);
    private final AppUser kwame = user(3L, "Kwame Asante", Role.MANAGER);
    private final AppUser fatima = user(5L, "Fatima Bello", Role.USER);

    @BeforeEach
    void createService() {
        ticketService = new TicketService(ticketRepository, appUserRepository);
    }

    @Test
    void anAdminGetsTheTicketTheRepositoryReturned() {
        Ticket ticket = ticket(2L, kwame);
        when(ticketRepository.findById(2L)).thenReturn(Optional.of(ticket));

        Ticket result = ticketService.getVisibleTicket("2", priya);

        // The same object the repository returned, not a copy or a different
        // ticket.
        assertSame(ticket, result);
        verify(ticketRepository).findById(2L);
    }

    @Test
    void aMissingTicketIsNotFoundWithTheContractsMessage() {
        when(ticketRepository.findById(999L)).thenReturn(Optional.empty());

        TicketNotFoundException exception =
                assertThrows(TicketNotFoundException.class, () -> ticketService.getVisibleTicket("999", priya));

        // The exact message from the contract's 404 example. The global
        // exception handler sends this message to the client unchanged.
        assertEquals("No ticket with id \"999\".", exception.getMessage());
    }

    // Decision 23: a ticket the caller may not see is the SAME 404 as one that
    // doesn't exist, with the same message for the same id. Ticket 2 exists,
    // but Kwame created it, so Fatima (a USER) may not see it.
    @Test
    void aTicketAUserMayNotSeeIsTheSameNotFoundAsAMissingOne() {
        when(ticketRepository.findById(2L)).thenReturn(Optional.of(ticket(2L, kwame)));

        TicketNotFoundException exception =
                assertThrows(TicketNotFoundException.class, () -> ticketService.getVisibleTicket("2", fatima));

        assertEquals("No ticket with id \"2\".", exception.getMessage());
    }

    // An id that isn't a whole number is a 404 quoting what was sent, and the
    // database is never asked.
    @Test
    void anIdThatIsntANumberIsNotFoundWithoutAQuery() {
        TicketNotFoundException exception =
                assertThrows(TicketNotFoundException.class, () -> ticketService.getVisibleTicket("abc", priya));

        assertEquals("No ticket with id \"abc\".", exception.getMessage());
        verifyNoInteractions(ticketRepository);
    }

    // AppUser has no id setter: the database assigns ids. Visibility compares
    // ids, so a unit test sets one directly, the way Hibernate does.
    private static AppUser user(Long id, String name, Role role) {
        AppUser user = new AppUser(name, name.toLowerCase().replace(' ', '.'),
                name.toLowerCase().replace(' ', '.') + "@opsdesk.com", "not-a-real-hash", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Ticket ticket(Long id, AppUser createdBy) {
        Ticket ticket = new Ticket("Order new laptops", "For the March starters.",
                Priority.MEDIUM, TicketStatus.OPEN, Team.OPERATIONS);
        ticket.setId(id);
        ticket.setCreatedBy(createdBy);
        return ticket;
    }
}
