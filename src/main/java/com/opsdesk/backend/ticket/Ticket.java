package com.opsdesk.backend.ticket;

// A plain class with setters rather than a record, because tomorrow this
// becomes a JPA entity, and JPA needs to create an empty object and fill in
// its fields one at a time. A record can't be changed after it's created.
public class Ticket {

    // Long (the object), not long (the primitive), so a ticket that hasn't
    // been saved yet can have no id at all: null means "the repository hasn't
    // given this one an id yet".
    private Long id;
    private String title;
    private String description;
    private Priority priority;
    private TicketStatus status;
    private Team team;

    public Ticket() {
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
}
