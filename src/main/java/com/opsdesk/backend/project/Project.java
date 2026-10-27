package com.opsdesk.backend.project;

import com.opsdesk.backend.ticket.Team;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

// The contract's Project object: id, name, description, status, team, lead.
//
// There's no list of tickets on this side of the relationship. Ticket points
// to Project (many tickets, one project), and nothing here needs to walk the
// other way yet. A @OneToMany list would be one more thing that could be
// loaded by accident or end up in the JSON.
@Entity
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProjectStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Team team;

    // A plain name, not a link to an AppUser, because the contract's Project
    // object has "lead" as a string ("Priya Sharma"). Turning it into a
    // relationship would change what GET /projects returns.
    @Column(nullable = false)
    private String lead;

    protected Project() {
    }

    public Project(String name, String description, ProjectStatus status, Team team, String lead) {
        this.name = name;
        this.description = description;
        this.status = status;
        this.team = team;
        this.lead = lead;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public Team getTeam() {
        return team;
    }

    public String getLead() {
        return lead;
    }
}
