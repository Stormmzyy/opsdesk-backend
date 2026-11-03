package com.opsdesk.backend.seed;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.opsdesk.backend.project.Project;
import com.opsdesk.backend.project.ProjectRepository;
import com.opsdesk.backend.project.ProjectStatus;
import com.opsdesk.backend.ticket.Priority;
import com.opsdesk.backend.ticket.Team;
import com.opsdesk.backend.ticket.Ticket;
import com.opsdesk.backend.ticket.TicketRepository;
import com.opsdesk.backend.ticket.TicketStatus;
import com.opsdesk.backend.user.AppUser;
import com.opsdesk.backend.user.AppUserRepository;
import com.opsdesk.backend.user.Role;

// Loads the frontend's sample data so the real backend serves what the
// frontend's mock API does:
//   - the 6 projects from opsdesk-frontend/src/data/projects.ts
//   - the 6 project leads from opsdesk-frontend/src/data/employees.ts, as users
//   - the 12 tickets from opsdesk-frontend/src/data/tickets.ts
//
// The roles, creators and assignments follow NOTES.md, decision 27, which
// lays them out so every cell of the permission table has a seeded ticket to
// test it against. If this class and decision 27 disagree, this class is
// wrong.
//
// One seeder for all three, rather than one per entity, because the order
// matters: a ticket can only point at a project and a user that already exist.
//
// Safe to run on every startup. Each table is seeded only if it's empty, so
// restarting against an existing database adds nothing. Seeding each table
// separately (rather than "only if everything is empty") means a database that
// already has some of the data gets the rest filled in, not skipped.
@Component
public class SampleDataSeeder implements ApplicationRunner {

    // DEVELOPMENT ONLY. The password of every seeded user, so anyone running
    // the app locally can log in as any role (README.md, "Development
    // password"). It only ever exists in a seeded development database.
    //
    // Only the plain password is written here. It goes through the
    // PasswordEncoder like any other password, so what's stored is a BCrypt
    // hash, and each user's hash is different because each gets its own salt.
    private static final String DEV_PASSWORD = "devpassword123";

    private final ProjectRepository projectRepository;
    private final AppUserRepository appUserRepository;
    private final TicketRepository ticketRepository;
    private final PasswordEncoder passwordEncoder;

    public SampleDataSeeder(ProjectRepository projectRepository,
                            AppUserRepository appUserRepository,
                            TicketRepository ticketRepository,
                            PasswordEncoder passwordEncoder) {
        this.projectRepository = projectRepository;
        this.appUserRepository = appUserRepository;
        this.ticketRepository = ticketRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedProjects();
        seedUsers();
        seedTickets();
    }

    private void seedProjects() {
        if (projectRepository.count() > 0) {
            return;
        }
        projectRepository.saveAll(List.of(
                new Project("Customer portal redesign",
                        "A faster, mobile-friendly customer portal with a simpler login.",
                        ProjectStatus.ACTIVE, Team.ENGINEERING, "Priya Sharma"),
                new Project("New office move",
                        "Plan and run the move to the new Accra office, including desks and network.",
                        ProjectStatus.PLANNING, Team.OPERATIONS, "Kwame Asante"),
                new Project("Help centre articles",
                        "Write clear guides for the twenty most common customer questions.",
                        ProjectStatus.ACTIVE, Team.SUPPORT, "Liam Carter"),
                new Project("Onboarding checklist",
                        "One shared checklist so every new hire gets the same first week.",
                        ProjectStatus.COMPLETED, Team.HR, "Fatima Bello"),
                new Project("Automated invoicing",
                        "Send monthly invoices automatically instead of preparing them by hand.",
                        ProjectStatus.ON_HOLD, Team.FINANCE, "Marco Silva"),
                new Project("API monitoring",
                        "Alerts that warn the team before the public API slows down or fails.",
                        ProjectStatus.PLANNING, Team.ENGINEERING, "Daniel Mensah")));
    }

    // The username is the part of the email before the @, which already fits
    // the contract's username rule (letters, dots, 3 to 30 characters).
    //
    // Roles from decision 27: 1 ADMIN, 1 MANAGER, 3 AGENT, 1 USER.
    private void seedUsers() {
        if (appUserRepository.count() > 0) {
            return;
        }
        appUserRepository.saveAll(List.of(
                user("Priya Sharma", "priya.sharma", "priya.sharma@opsdesk.com", Role.ADMIN),
                user("Daniel Mensah", "daniel.mensah", "daniel.mensah@opsdesk.com", Role.AGENT),
                user("Kwame Asante", "kwame.asante", "kwame.asante@opsdesk.com", Role.MANAGER),
                user("Liam Carter", "liam.carter", "liam.carter@opsdesk.com", Role.AGENT),
                user("Fatima Bello", "fatima.bello", "fatima.bello@opsdesk.com", Role.USER),
                user("Marco Silva", "marco.silva", "marco.silva@opsdesk.com", Role.AGENT)));
    }

    // Encodes the password once per user, so each user gets their own salt.
    private AppUser user(String name, String username, String email, Role role) {
        return new AppUser(name, username, email, passwordEncoder.encode(DEV_PASSWORD), role);
    }

    private void seedTickets() {
        if (ticketRepository.count() > 0) {
            return;
        }

        // Projects are matched by name and users by email, not by id, because
        // ids depend on what the database handed out. These lookups work
        // whether the projects and users were created a moment ago or on an
        // earlier run.
        Map<String, Project> projects = projectRepository.findAll().stream()
                .collect(Collectors.toMap(Project::getName, Function.identity()));
        Map<String, AppUser> users = appUserRepository.findAll().stream()
                .collect(Collectors.toMap(AppUser::getEmail, Function.identity()));

        AppUser priya = users.get("priya.sharma@opsdesk.com");
        AppUser kwame = users.get("kwame.asante@opsdesk.com");
        AppUser daniel = users.get("daniel.mensah@opsdesk.com");
        AppUser liam = users.get("liam.carter@opsdesk.com");
        AppUser marco = users.get("marco.silva@opsdesk.com");
        AppUser fatima = users.get("fatima.bello@opsdesk.com");

        // Saved in this order, the tickets get ids 1 to 12 on an empty
        // database, the same ids the frontend uses. Saved through the
        // repository, not the service, because the service makes every new
        // ticket OPEN and some samples start further along.
        //
        // The project and assignee links are new: the frontend's tickets have
        // neither. A few tickets get them so the relationships hold real data;
        // the rest are left empty, as most new tickets would be.
        //
        // The last two arguments are the creator (always set: createdBy is
        // required) and the assignee (null for none). The comment above each
        // ticket is its row in decision 27's table.
        ticketRepository.saveAll(List.of(
                // #1  OPEN, created by Priya, assigned to Daniel
                ticket("Login page times out on slow networks",
                        "Users on mobile data report that the login page spins for over 30 seconds and then shows a timeout error. It works fine on office Wi-Fi.",
                        Priority.URGENT, TicketStatus.OPEN, Team.ENGINEERING,
                        projects.get("Customer portal redesign"), priya, daniel),
                // #2  OPEN, created by Kwame, unassigned
                ticket("Order new laptops for the March starters",
                        "Four new starters join on 3 March. Order laptops with the standard developer setup and have them ready the Friday before.",
                        Priority.MEDIUM, TicketStatus.OPEN, Team.OPERATIONS,
                        null, kwame, null),
                // #3  OPEN, created by Liam, assigned to Liam
                ticket("Customer cannot reset their password",
                        "A customer says the password reset email never arrives. We checked their spam folder with them. Their account email looks correct.",
                        Priority.HIGH, TicketStatus.OPEN, Team.SUPPORT,
                        null, liam, liam),
                // #4  OPEN, created by Fatima, unassigned
                ticket("Update the holiday policy document",
                        "The holiday policy still lists last year's public holidays. Update the dates and the carry-over rules agreed at the last meeting.",
                        Priority.LOW, TicketStatus.OPEN, Team.HR,
                        null, fatima, null),
                // #5  IN_PROGRESS, created by Kwame, assigned to Marco
                ticket("Fix rounding error in monthly invoices",
                        "Some invoices are off by one cent because tax is rounded per line instead of on the total. Finance needs this fixed before month end.",
                        Priority.HIGH, TicketStatus.IN_PROGRESS, Team.FINANCE,
                        projects.get("Automated invoicing"), kwame, marco),
                // #6  IN_PROGRESS, created by Priya, unassigned
                ticket("Add dark mode to the admin panel",
                        "Several staff have asked for a dark theme in the admin panel. Reuse the colour tokens from the main app where possible.",
                        Priority.LOW, TicketStatus.IN_PROGRESS, Team.ENGINEERING,
                        projects.get("Customer portal redesign"), priya, null),
                // #7  IN_PROGRESS, created by Liam, assigned to Kwame
                ticket("Office air conditioning is broken",
                        "The air conditioning on the second floor stopped working this morning and the room is over 30°C. The repair company has been called.",
                        Priority.URGENT, TicketStatus.IN_PROGRESS, Team.OPERATIONS,
                        null, liam, kwame),
                // #8  RESOLVED, created by Liam, unassigned
                ticket("Reply to the backlog of refund emails",
                        "Around 40 refund emails built up over the holiday weekend. Reply to each one and log any refunds that still need approval.",
                        Priority.MEDIUM, TicketStatus.RESOLVED, Team.SUPPORT,
                        null, liam, null),
                // #9  RESOLVED, created by Fatima, unassigned. It was assigned
                //     to Fatima until Week 7; a USER doesn't work tickets.
                ticket("Schedule onboarding for new hires",
                        "Book onboarding sessions for this month's new hires, covering IT setup, security training and a team introduction.",
                        Priority.MEDIUM, TicketStatus.RESOLVED, Team.HR,
                        projects.get("Onboarding checklist"), fatima, null),
                // #10 RESOLVED, created by Daniel, assigned to Daniel
                ticket("Rotate the expired API keys",
                        "The payment provider API keys expired on Friday. Generate new keys, update the server settings and revoke the old keys.",
                        Priority.HIGH, TicketStatus.RESOLVED, Team.ENGINEERING,
                        projects.get("API monitoring"), daniel, daniel),
                // #11 CLOSED, created by Marco, unassigned
                ticket("Close the Q3 expense reports",
                        "Check the remaining Q3 expense reports against their receipts, approve them, and send the summary to the finance director.",
                        Priority.MEDIUM, TicketStatus.CLOSED, Team.FINANCE,
                        null, marco, null),
                // #12 CLOSED, created by Kwame, unassigned
                ticket("Replace the broken meeting room screen",
                        "The screen in the large meeting room has a cracked panel. Order a replacement of the same size and book a time to mount it.",
                        Priority.LOW, TicketStatus.CLOSED, Team.OPERATIONS,
                        null, kwame, null)));
    }

    private static Ticket ticket(String title, String description, Priority priority,
                                 TicketStatus status, Team team, Project project,
                                 AppUser createdBy, AppUser assignee) {
        Ticket ticket = new Ticket(title, description, priority, status, team);
        ticket.setProject(project);
        ticket.setCreatedBy(createdBy);
        ticket.setAssignee(assignee);
        return ticket;
    }
}
