package com.opsdesk.backend.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

// Unit tests for TicketService, with the repository replaced by a mock.
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
//   - The service's own logic, like "a missing ticket is a
//     TicketNotFoundException with the contract's message", can be checked on
//     its own. A failure points straight at the service, not at PostgreSQL,
//     Docker or the seed data.
//   - It's fast (milliseconds, no Spring, no database) and doesn't depend on
//     what's in the database, so it gives the same answer every time.
//   - The service is built with plain "new", passing the mock to the same
//     constructor Spring uses. That's constructor injection paying off: no
//     Spring is needed to test it.
//
// What these tests would NOT catch:
//   - Anything wrong in the real repository or the database: a wrong column, a
//     wrong derived-query name, a missing ORDER BY (decision 7), a constraint
//     violation. The mock returns whatever it's told, so it can't be wrong in
//     the ways a database can.
//   - Anything in the HTTP layer: the URL, the status code, the JSON shape,
//     validation, the global exception handler, the check order.
//   - A mock that doesn't behave like the real thing. If the real findById
//     ever behaved differently, these tests would still pass.
// Those need an integration test against a real database, and controller
// tests through MockMvc.
@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock
    private TicketRepository ticketRepository;

    private TicketService ticketService;

    @BeforeEach
    void createService() {
        ticketService = new TicketService(ticketRepository);
    }

    @Test
    void getTicketReturnsTheTicketWhenItExists() {
        Ticket ticket = new Ticket("Printer is jammed", "Error E-42.",
                Priority.HIGH, TicketStatus.OPEN, Team.OPERATIONS);
        ticket.setId(1L);
        when(ticketRepository.findById(1L)).thenReturn(Optional.of(ticket));

        Ticket result = ticketService.getTicket(1L);

        // The same object the repository returned, not a copy or a different
        // ticket.
        assertSame(ticket, result);
        verify(ticketRepository).findById(1L);
    }

    @Test
    void getTicketThrowsNotFoundWhenTheTicketIsMissing() {
        when(ticketRepository.findById(999L)).thenReturn(Optional.empty());

        TicketNotFoundException exception =
                assertThrows(TicketNotFoundException.class, () -> ticketService.getTicket(999L));

        // The exact message from the contract's 404 example. The global
        // exception handler sends this message to the client unchanged.
        assertEquals("No ticket with id \"999\".", exception.getMessage());
    }
}
