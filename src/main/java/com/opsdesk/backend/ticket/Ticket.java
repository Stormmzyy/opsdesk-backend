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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.DynamicUpdate;

// @Entity: Hibernate maps this class to a table (named "ticket", from the
// class name) and each Ticket object to one row in it.
//
// A plain class with setters rather than a record, because Hibernate creates
// an empty object and then fills in its fields one at a time when it reads a
// row. A record can't be changed after it's created.
//
// @DynamicUpdate: Hibernate builds each UPDATE from the columns that actually
// changed, instead of its default, a prepared UPDATE that lists every column.
// A title-only PATCH then writes only the title, so it can't write back a
// status it read earlier and someone else has since changed (NOTES.md,
// decision 38). It only works on a managed entity, where Hibernate knows what
// the row looked like when it was loaded; see TicketService.updateTicket.
//
// @Table(indexes): declared here, not as hand-written SQL, because the schema
// is built from the entities. An index created by hand vanishes the next time
// the schema is recreated, and a production schema built from these classes
// would never have it.
//
// PostgreSQL indexes primary keys and unique constraints by itself, but NOT
// foreign key columns, so created_by_id had no index at all, although it's
// in the visibility condition of every list query a USER makes.
//
// One index, (created_by_id, id), measured on 5,000 tickets (NOTES.md, the
// index decision). It serves the visibility condition AND the ORDER BY id:
// a USER's page becomes "find her first entry, read 10 in order, stop", with
// no Sort and no Filter. That stays roughly constant as the table grows,
// where the plan without it walked the primary key and filtered, which
// grows with the table and is worst for a USER with few tickets. Because
// the index holds both created_by_id and id, the COUNT behind her page's
// totals is an index-only scan (count(t.id) reads nothing else), where it
// was a full seq scan. It also serves the foreign key check on
// created_by_id when a user is deleted.
//
// Measured and rejected: created_by_id alone (leaves the Sort, and the COUNT
// must read the heap), (created_by_id, status) and (created_by_id, status,
// id) (a USER's own rows are few, so filtering her status costs about her
// ticket count, not the table's), status (four values; the planner ignored
// it for the lists and kept a seq scan for CLOSED), and assignee_id (no
// query looks tickets up by assignee).
@Entity
@DynamicUpdate
@Table(indexes = @Index(name = "ix_ticket_created_by_id_id", columnList = "created_by_id, id"))
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
    //
    // TEXT, not the default VARCHAR(255): the contract sets no maximum title
    // length, so a 300-character title is valid and passed validation, then
    // failed in PostgreSQL with a 500. (NOTES.md, decision 9.)
    @Column(nullable = false, columnDefinition = "text")
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
    // (the "N+1 queries" problem), just to return fields that don't use it.
    // EAGER also can't be switched off for one query, while LAZY can be
    // switched on for one query when a page really needs it. That's exactly
    // what happens for the assignee and creator, whose names every response
    // now includes: they stay LAZY here, and the ticket queries fetch them
    // with a join (TicketQueriesImpl.withPeople, TicketRepository.findById).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    // The person working on the ticket: many tickets can be assigned to one
    // user. An assignee_id column with a foreign key to app_user(id). Optional,
    // because a new ticket has no assignee. LAZY for the same reasons as above.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private AppUser assignee;

    // The person who raised the ticket. The ownership rules ("own only",
    // "own, if OPEN") compare it with the caller. A created_by_id column with
    // a foreign key to app_user(id), named like assignee_id and project_id,
    // and LAZY like the two above.
    //
    // Required, unlike assignee: every ticket has a creator by definition,
    // so there is no legitimate null (NOTES.md, decision 27).
    // optional = false tells Hibernate the link is always there;
    // nullable = false puts NOT NULL on the column, so a code path that
    // forgets the creator fails on insert instead of saving a ticket nobody
    // owns.
    //
    // It is always set by the server: the seeder for seeded tickets, the
    // logged-in user for new ones. Never from a request body.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by_id", nullable = false)
    private AppUser createdBy;

    // Optimistic locking. Hibernate adds "and version=?" to every UPDATE of
    // this row, with the version it read, and sets version = version + 1. If
    // another request committed in between, the row's version has moved on,
    // the UPDATE matches no row, and Hibernate throws instead of overwriting
    // that change. @DynamicUpdate narrows what a stale request can overwrite;
    // this makes the remaining conflict (two people changing the same field)
    // fail loudly instead of "last write wins".
    //
    // Long, not long, for the same reason as id: Spring Data reads a null
    // version as "new, INSERT it", and Hibernate sets 0 on insert.
    //
    // There is no setter. Only Hibernate changes it; code that set it could
    // make a stale copy look current and defeat the check.
    @Version
    private Long version;

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

    public AppUser getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(AppUser createdBy) {
        this.createdBy = createdBy;
    }

    public Long getVersion() {
        return version;
    }
}
