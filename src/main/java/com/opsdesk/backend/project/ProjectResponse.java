package com.opsdesk.backend.project;

import com.opsdesk.backend.ticket.Team;

// The contract's Project object, in the contract's field order:
//   { "id", "name", "description", "status", "team", "lead" }
//
// A response record, never the Project entity, for the same reason as
// TicketResponse (NOTES.md, decision 8): the entity is the database's shape,
// and this is the API's. Team writes its contract label ("Operations") through
// @JsonValue, and status writes its constant name ("PLANNING").
public record ProjectResponse(
        Long id,
        String name,
        String description,
        ProjectStatus status,
        Team team,
        String lead) {

    // The one place a Project becomes a ProjectResponse.
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                project.getStatus(),
                project.getTeam(),
                project.getLead());
    }
}
