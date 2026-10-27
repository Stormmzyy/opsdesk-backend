package com.opsdesk.backend.ticket;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

// Loads the frontend's 12 sample tickets (opsdesk-frontend/src/data/tickets.ts)
// so the real backend serves the same data the frontend's mock API does.
//
// Spring calls run() once, after the app has started. Seeding lives in its own
// class, not in the repository's constructor, because tomorrow the repository
// becomes an interface that has no constructor to put it in.
@Component
public class TicketSeeder implements ApplicationRunner {

    private final TicketRepository ticketRepository;

    public TicketSeeder(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Today the list always starts empty, so this always seeds. Tomorrow
        // the tickets live in PostgreSQL and survive a restart; without this
        // check, every restart would add another 12 copies.
        if (ticketRepository.count() > 0) {
            return;
        }

        // Saved through the repository, not the service, because the service
        // makes every new ticket OPEN, and some samples start further along.
        // Saved in this order, they get ids 1 to 12, the same ids the frontend
        // uses for them.
        List.of(
                new Ticket("Login page times out on slow networks",
                        "Users on mobile data report that the login page spins for over 30 seconds and then shows a timeout error. It works fine on office Wi-Fi.",
                        Priority.URGENT, TicketStatus.OPEN, Team.ENGINEERING),
                new Ticket("Order new laptops for the March starters",
                        "Four new starters join on 3 March. Order laptops with the standard developer setup and have them ready the Friday before.",
                        Priority.MEDIUM, TicketStatus.OPEN, Team.OPERATIONS),
                new Ticket("Customer cannot reset their password",
                        "A customer says the password reset email never arrives. We checked their spam folder with them. Their account email looks correct.",
                        Priority.HIGH, TicketStatus.OPEN, Team.SUPPORT),
                new Ticket("Update the holiday policy document",
                        "The holiday policy still lists last year's public holidays. Update the dates and the carry-over rules agreed at the last meeting.",
                        Priority.LOW, TicketStatus.OPEN, Team.HR),
                new Ticket("Fix rounding error in monthly invoices",
                        "Some invoices are off by one cent because tax is rounded per line instead of on the total. Finance needs this fixed before month end.",
                        Priority.HIGH, TicketStatus.IN_PROGRESS, Team.FINANCE),
                new Ticket("Add dark mode to the admin panel",
                        "Several staff have asked for a dark theme in the admin panel. Reuse the colour tokens from the main app where possible.",
                        Priority.LOW, TicketStatus.IN_PROGRESS, Team.ENGINEERING),
                new Ticket("Office air conditioning is broken",
                        "The air conditioning on the second floor stopped working this morning and the room is over 30°C. The repair company has been called.",
                        Priority.URGENT, TicketStatus.IN_PROGRESS, Team.OPERATIONS),
                new Ticket("Reply to the backlog of refund emails",
                        "Around 40 refund emails built up over the holiday weekend. Reply to each one and log any refunds that still need approval.",
                        Priority.MEDIUM, TicketStatus.RESOLVED, Team.SUPPORT),
                new Ticket("Schedule onboarding for new hires",
                        "Book onboarding sessions for this month's new hires, covering IT setup, security training and a team introduction.",
                        Priority.MEDIUM, TicketStatus.RESOLVED, Team.HR),
                new Ticket("Rotate the expired API keys",
                        "The payment provider API keys expired on Friday. Generate new keys, update the server settings and revoke the old keys.",
                        Priority.HIGH, TicketStatus.RESOLVED, Team.ENGINEERING),
                new Ticket("Close the Q3 expense reports",
                        "Check the remaining Q3 expense reports against their receipts, approve them, and send the summary to the finance director.",
                        Priority.MEDIUM, TicketStatus.CLOSED, Team.FINANCE),
                new Ticket("Replace the broken meeting room screen",
                        "The screen in the large meeting room has a cracked panel. Order a replacement of the same size and book a time to mount it.",
                        Priority.LOW, TicketStatus.CLOSED, Team.OPERATIONS)
        ).forEach(ticketRepository::save);
    }
}
