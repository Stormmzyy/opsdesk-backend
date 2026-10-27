package com.opsdesk.backend.ticket;

// Boot 3 used Jackson 2; Boot 4 uses Jackson 3, but the annotations kept their com.fasterxml package.
import com.fasterxml.jackson.annotation.JsonValue;

// Unlike priority and status, the contract's team values are mixed case
// ("Engineering", not "ENGINEERING"). Java constants are upper case by
// convention, so each one carries the exact text the contract uses.
public enum Team {
    ENGINEERING("Engineering"),
    FINANCE("Finance"),
    HR("HR"),
    OPERATIONS("Operations"),
    SUPPORT("Support");

    private final String label;

    Team(String label) {
        this.label = label;
    }

    // Without this, Jackson would write the constant name ("ENGINEERING") into
    // the JSON, and the frontend expects "Engineering".
    @JsonValue
    public String getLabel() {
        return label;
    }
}
