package com.opsdesk.backend.ticket;

import com.opsdesk.backend.project.Project;
import com.opsdesk.backend.user.AppUser;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;

// @Entity: Hibernate maps this class to a table (named "ticket", from the
// class name) and each Ticket object to one row in it.
//
// A plain class with setters rather than a record, because Hibernate creates
// an empty object and then fills in its fields one at a time when it reads a
// row. A record can't be changed after it's created.
@Entity
public class Ticket {

    // @GeneratedValue(IDENTITY): PostgreSQL picks the id when the row is
    // inserted, from a sequence that only goes up. That replaces yesterday's
    // nextId counter and keeps the same promise: an id is never reused, even
    // after a delete. (NOTES.md, decision 1.)
    //
    // Long (the object), not long, so a ticket that hasn't been saved yet can
    // have no id at all. Spring Data treats a null id as "new, so INSERT it"
    // and a non-null id as "already exists, so UPDATE it".
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // nullable = false puts NOT NULL on the column, so the database itself
    // refuses a ticket without a title, even if a bug lets one past the code.
    @Column(nullable = false)
    private String title;

    // TEXT rather than the default VARCHAR(255): the contract sets no maximum
    // length on a description, and the samples are already over 120
    // characters. A long description would otherwise fail to save.
    @Column(nullable = false, columnDefinition = "text")
    private String description;

    // EnumType.STRING stores the constant's name ("URGENT") in the column.
    //
    // The default, EnumType.ORDINAL, would store its position in the enum
    // instead: LOW = 0, MEDIUM = 1, HIGH = 2, URGENT = 3. Two things go wrong:
    //   1. The data is unreadable. In psql, "priority = 3" means nothing
    //      without the Java source open next to it.
    //   2. Reordering the enum silently corrupts every existing row. Add a
    //      CRITICAL between HIGH and URGENT, and URGENT becomes 4. Every row
    //      that stored 3 now reads back as CRITICAL. Nothing fails; the data
    //      is just wrong.
    // STRING only breaks if a constant is renamed, and then it fails loudly
    // on read instead of quietly returning the wrong value.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TicketStatus status;

    // Stores ENGINEERING, not "Engineering". @JsonValue only changes the JSON;
    // in the database, EnumType.STRING always uses the constant's name.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Team team;

    // Many tickets belong to one project. In the database this is a
    // project_id column on the ticket table, with a foreign key to
    // project(id), so it can only hold the id of a project that exists.
    // Optional: none of the frontend's tickets has a project.
    //
    // FetchType.LAZY: loading a ticket doesn't load its project. Hibernate puts
    // a stand-in object (a proxy) here instead, and only runs a query for the
    // project if something reads one of its fields.
    //
    // EAGER (the default for @ManyToOne) would load the project every time a
    // ticket is loaded, whether anyone needs it or not. GET /tickets would then
    // run 1 query for the tickets plus up to 1 more per ticket for its project
    // (the "N+1 queries" problem), and the same again for the assignee, just to
    // return six fields that use neither. EAGER also can't be switched off for
    // one query, while LAZY can be switched on for one query when a page really
    // needs the project.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    // The person working on the ticket: many tickets can be assigned to one
    // user. An assignee_id column with a foreign key to app_user(id). Optional,
    // because a new ticket has no assignee. LAZY for the same reasons as above.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private AppUser assignee;

    // Hibernate needs a no-argument constructor to create the empty object it
    // fills from a row. Protected, not public, so application code uses the
    // full constructor below and can't create a half-empty ticket by accident.
    protected Ticket() {
    }

    public Ticket(String title, String description, Priority priority, TicketStatus status, Team team) {
        this.title = title;
        this.description = description;
        this.priority = priority;
        this.status = status;
        this.team = team;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
    }

    public TicketStatus getStatus() {
        return status;
    }

    public void setStatus(TicketStatus status) {
        this.status = status;
    }

    public Team getTeam() {
        return team;
    }

    public void setTeam(Team team) {
        this.team = team;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public AppUser getAssignee() {
        return assignee;
    }

    public void setAssignee(AppUser assignee) {
        this.assignee = assignee;
    }
}
