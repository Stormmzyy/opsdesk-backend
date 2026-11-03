package com.opsdesk.backend.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.opsdesk.backend.user.AppUser;
import com.opsdesk.backend.user.AppUserRepository;

// The lost update (NOTES.md, decision 38), reproduced on purpose, every time.
//
// The bug: request A reads a ticket, request B changes its status and
// commits, then A writes its stale copy. A only meant to change the title,
// but before the fix its UPDATE listed every column, so it wrote back the
// status it had read and B's change vanished, with no error anywhere.
//
// Instead of two threads and hoping they interleave badly, each test runs
// the two requests' transactions in a fixed order: A reads, B commits, A
// writes. No timing luck, so the result is the same on every run.
//
// DELIBERATELY NOT @Transactional. A transactional test method runs
// everything inside ONE transaction and rolls it back at the end. B's
// "commit" would then commit nothing, the version would never move under A,
// and the test would pass while proving nothing. Here every transaction is
// started and committed explicitly, so B's change is really in the database
// before A tries to write.
//
// It runs on the test profile's in-memory H2 (NOTES.md, decision 62), with
// the table built from the Ticket class under test, whatever that class
// looks like. Until decision 62 it ran in a throwaway schema inside the dev
// PostgreSQL; the in-memory database is throwaway as a whole, so that trick
// is gone.
//
// It still proves the fix on H2 because the check is Hibernate's, not the
// database's: Hibernate adds "and version = ?" to the UPDATE and treats 0
// rows changed as a conflict. The database only has to count rows, and to
// show A's UPDATE what B committed, which H2 does at its default isolation,
// READ COMMITTED, the same as PostgreSQL's.
//
// The two transactions are two connections from the pool, both to the same
// in-memory database: the pool opens every connection with the one URL its
// context resolved at startup.
@SpringBootTest
@ActiveProfiles("test")
class TicketLostUpdateTest {

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long ticketId;

    // A fresh OPEN ticket for each test, created by any seeded user. The id
    // is taken from the saved ticket, never assumed. It's deleted afterwards
    // because the database is shared with every other test in the same
    // Spring context (contextLoads, today), and they expect decision 27's
    // twelve tickets and nothing else.
    @BeforeEach
    void createTicket() {
        AppUser creator = appUserRepository.findAll(Sort.by("id")).getFirst();
        Ticket ticket = new Ticket("Projector flickers", "Meeting room 2.",
                Priority.LOW, TicketStatus.OPEN, Team.OPERATIONS);
        ticket.setCreatedBy(creator);
        ticketId = ticketRepository.save(ticket).getId();
    }

    @AfterEach
    void deleteTicket() {
        ticketRepository.deleteById(ticketId);
    }

    // The shape updateTicket has now: one transaction loads the ticket,
    // changes it while it's still managed, and writes it at commit.
    //
    // Two real transactions, because of PROPAGATION_REQUIRES_NEW: when B
    // starts, Spring suspends A's transaction and opens a new one, on a
    // second connection with its own EntityManager. B commits before
    // execute() returns, so its change is in the database while A is
    // still open and holding the copy it read before.
    @Test
    void aStaleWriteInsideOneTransactionIsRefusedAndTheOtherChangeSurvives() {
        TransactionTemplate requestA = new TransactionTemplate(transactionManager);
        TransactionTemplate requestB = new TransactionTemplate(transactionManager);
        requestB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        RuntimeException refusal = refusalOf(() ->
                requestA.executeWithoutResult(a -> {
                    // A reads the ticket: OPEN.
                    Ticket aCopy = ticketRepository.findById(ticketId).orElseThrow();

                    // B moves it to IN_PROGRESS and commits.
                    requestB.executeWithoutResult(b ->
                            ticketRepository.findById(ticketId).orElseThrow()
                                    .setStatus(TicketStatus.IN_PROGRESS));

                    // A changes only the title. Its UPDATE runs when this
                    // callback returns and A commits.
                    aCopy.setTitle("Projector flickers in room 2");
                }));

        assertBsChangeSurvived();
        assertRefusedLoudly(refusal);
    }

    // The shape assignTicket had (and updateTicket before decision 38): the
    // ticket comes back from findById DETACHED, because the repository's own
    // transaction has already ended, and save() has to merge it.
    //
    // Two real transactions without any template around them: each
    // repository call outside a transaction runs in its own, which commits
    // when the call returns.
    @Test
    void aStaleDetachedCopyIsRefusedOnSaveAndTheOtherChangeSurvives() {
        // A reads the ticket: OPEN. Its transaction has already committed,
        // so this copy is detached.
        Ticket aCopy = ticketRepository.findById(ticketId).orElseThrow();

        // B moves it to IN_PROGRESS and commits, on its own.
        new TransactionTemplate(transactionManager).executeWithoutResult(b ->
                ticketRepository.findById(ticketId).orElseThrow().setStatus(TicketStatus.IN_PROGRESS));

        // A changes only the title, then saves its stale copy.
        aCopy.setTitle("Projector flickers in room 2");
        RuntimeException refusal = refusalOf(() -> ticketRepository.save(aCopy));

        assertBsChangeSurvived();
        assertRefusedLoudly(refusal);
    }

    // Runs A's write and returns the optimistic lock failure it ended in, or
    // null if it went through. Caught rather than asserted straight away, so
    // the stored row is checked FIRST: if this ever regresses, the failure
    // reads "B's status change was overwritten", which says what was lost,
    // instead of only "nothing was thrown".
    private static RuntimeException refusalOf(Runnable write) {
        try {
            write.run();
            return null;
        } catch (ObjectOptimisticLockingFailureException refused) {
            return refused;
        }
    }

    // Loudly: the stale write must not just be dropped, it has to fail, so
    // GlobalExceptionHandler can answer 409 and the client knows to reload.
    private static void assertRefusedLoudly(RuntimeException refusal) {
        assertNotNull(refusal, "A's stale write was not refused with an optimistic lock failure");
    }

    // Read fresh from the database, in a new transaction: B's status is still
    // there and A's title never landed. The exception alone isn't enough; the
    // point of the fix is that the other person's change is not lost.
    private void assertBsChangeSurvived() {
        Ticket stored = ticketRepository.findById(ticketId).orElseThrow();
        assertEquals(TicketStatus.IN_PROGRESS, stored.getStatus(), "B's status change was overwritten");
        assertEquals("Projector flickers", stored.getTitle(), "A's stale write landed");
    }
}
