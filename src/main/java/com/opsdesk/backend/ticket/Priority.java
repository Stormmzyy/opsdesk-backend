package com.opsdesk.backend.ticket;

// An enum rather than a String so an invalid priority can't exist inside the
// app at all. The constant names match the contract's JSON values exactly,
// so they need no mapping.
public enum Priority {
    LOW,
    MEDIUM,
    HIGH,
    URGENT
}
