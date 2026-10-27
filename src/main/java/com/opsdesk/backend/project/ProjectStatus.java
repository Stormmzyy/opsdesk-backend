package com.opsdesk.backend.project;

// The contract's four project statuses. Its own enum, not TicketStatus,
// because projects and tickets move through different workflows.
public enum ProjectStatus {
    PLANNING,
    ACTIVE,
    ON_HOLD,
    COMPLETED
}
