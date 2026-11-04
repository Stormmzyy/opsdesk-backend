package com.opsdesk.backend.ticket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import com.opsdesk.backend.user.AppUser;
import com.opsdesk.backend.user.AppUserRepository;
import com.opsdesk.backend.user.Role;

// Where the shape tests are blind. TicketRepositoryShapeTest and
// TicketServiceShapeTest prove a method TAKES the caller; neither can prove
// it USES her. A method that accepts an AppUser and ignores her passes both.
// These three go where ignoring her would do the most damage while looking
// the most normal (NOTES.md, decision 63, says why these three).
//
// Strict stubs, kept strict. MockitoExtension fails a test whose stub is
// never used. In a permission test the tempting mistake is to stub the path
// that must NOT run (a save, an assignee lookup) "just in case"; that stub
// then goes unused and fails the test. So:
//   - the only stubs are what the path under test must read. If one goes
//     unused, the method never reached the lookup, and the test should fail.
//   - what must not happen isn't stubbed, it's verified: verify(..., never()).
//   - where the edit is allowed, save() stays unstubbed (it returns null),
//     and the test reads the ticket object the service changed, rather than
//     stubbing save() only to hand its argument back.
// No shared stubs in @BeforeEach, no lenient(), no strictness setting.
@ExtendWith(MockitoExtension.class)
class TicketPermissionTest {

    @Mock
    private TicketRepository ticketRepository;

    // The constructor asks for it; none of these paths may touch it.
    @Mock
    private AppUserRepository appUserRepository;

    private TicketService ticketService;

    // Decision 27's people, with their seeded ids.
    private final AppUser daniel = user(2L, "Daniel Mensah", Role.AGENT);
    private final AppUser kwame = user(3L, "Kwame Asante", Role.MANAGER);
    private final AppUser liam = user(4L, "Liam Carter", Role.AGENT);
    private final AppUser fatima = user(5L, "Fatima Bello", Role.USER);

    @BeforeEach
    void createService() {
        ticketService = new TicketService(ticketRepository, appUserRepository);
    }

    // The view rule's 404, on a write path. getVisibleTicket's own 404 is
    // pinned by TicketServiceTest. What isn't is that updateTicket goes
    // through it. If updateTicket looked the ticket up with findById directly
    // (the shape of the getTicket(Long) decision 61 deleted), it would still
    // take the caller and pass the shape test, and the request would still be
    // refused: canEdit's USER rule throws. But as a 403, which tells Fatima
    // that ticket 2 exists and is someone else's (decision 23). Everything
    // still says no, just with the wrong answer, so only a test that asserts
    // the exact refusal can see it.
    @Test
    void aUserEditingATicketSheCannotSeeGetsTheNotFoundAndNothingIsWritten() {
        Ticket kwamesTicket = ticket(2L, kwame, null, TicketStatus.OPEN);
        when(ticketRepository.findById(2L)).thenReturn(Optional.of(kwamesTicket));

        TicketNotFoundException refusal = assertThrows(TicketNotFoundException.class,
                () -> ticketService.updateTicket("2", fatima, "Taken over", null, null, null, null));

        // The same message as a ticket that doesn't exist, quoting the URL's id.
        assertEquals("No ticket with id \"2\".", refusal.getMessage());
        assertEquals("Order new laptops", kwamesTicket.getTitle(), "the title changed despite the 404");
        verify(ticketRepository, never()).save(any());
    }

    // canEdit's AGENT rule: only a ticket assigned to HIM. This comparison is
    // the only thing standing between one agent and another's work. If it
    // ignored the caller ("any assigned ticket will do"), nothing anyone
    // normally sees would change: agents edit their own tickets, and those
    // stay allowed. So the refusal is checked by its reason as well, to be
    // sure it's this rule refusing and not the "assigned to nobody" one, and
    // the allowed case is checked too, so a rule that refused every AGENT
    // can't pass either. canEdit reads nothing, so there's nothing to stub.
    @Test
    void anAgentMayEditHisOwnAssignedTicketButNotAnotherAgents() {
        Ticket danielsTicket = ticket(1L, kwame, daniel, TicketStatus.OPEN);
        Ticket liamsTicket = ticket(3L, kwame, liam, TicketStatus.OPEN);

        assertDoesNotThrow(() -> ticketService.canEdit(daniel, danielsTicket));

        AccessDeniedException refusal = assertThrows(AccessDeniedException.class,
                () -> ticketService.canEdit(daniel, liamsTicket));
        assertTrue(refusal.getMessage().contains("assigned to someone else"),
                "refused for the wrong reason: " + refusal.getMessage());
    }

    // The USER's edit rule, the part HTTP can reach. (Her other part, "only
    // a ticket she created", can't be reached over HTTP: getVisibleTicket
    // runs first everywhere, so a ticket she didn't create is a 404 before
    // canEdit is asked.) On her own OPEN ticket she may change the title and
    // description, nothing else. The field check decides by the caller's role;
    // if it ignored her, she could move her ticket out of OPEN and lock
    // herself out (decision 27), and every such request would answer 200.
    //
    // Both halves matter. The real frontend sends the whole form on every
    // save, so a rule that refused a field merely for being sent would
    // refuse every edit she makes. Refused first, so the allowed save after
    // it starts from an untouched ticket; one findById stub serves both
    // calls, and save() must happen exactly once, for the allowed one.
    @Test
    void aUserMayRetitleHerOpenTicketButNotChangeItsStatus() {
        Ticket fatimasTicket = ticket(4L, fatima, null, TicketStatus.OPEN);
        when(ticketRepository.findById(4L)).thenReturn(Optional.of(fatimasTicket));

        assertThrows(AccessDeniedException.class, () -> ticketService.updateTicket("4", fatima,
                "Update the holiday policy", null, null, TicketStatus.IN_PROGRESS, null));
        assertEquals(TicketStatus.OPEN, fatimasTicket.getStatus(), "the status changed despite the 403");
        assertEquals("Order new laptops", fatimasTicket.getTitle(), "the title was applied despite the 403");
        verify(ticketRepository, never()).save(any());

        // The whole form, as the frontend sends it: a new title, and priority,
        // status and team exactly as they are.
        ticketService.updateTicket("4", fatima, "Update the holiday policy", null,
                Priority.MEDIUM, TicketStatus.OPEN, Team.OPERATIONS);
        assertEquals("Update the holiday policy", fatimasTicket.getTitle());
        verify(ticketRepository).save(fatimasTicket);
    }

    // AppUser has no id setter: the database assigns ids. The rules compare
    // ids, so a unit test sets one directly, the way Hibernate does.
    private static AppUser user(Long id, String name, Role role) {
        String handle = name.toLowerCase().replace(' ', '.');
        AppUser user = new AppUser(name, handle, handle + "@opsdesk.com", "not-a-real-hash", role);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private static Ticket ticket(Long id, AppUser createdBy, AppUser assignee, TicketStatus status) {
        Ticket ticket = new Ticket("Order new laptops", "For the March starters.",
                Priority.MEDIUM, status, Team.OPERATIONS);
        ticket.setId(id);
        ticket.setCreatedBy(createdBy);
        ticket.setAssignee(assignee);
        return ticket;
    }
}
