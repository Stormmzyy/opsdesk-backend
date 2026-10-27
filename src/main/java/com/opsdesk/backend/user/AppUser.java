package com.opsdesk.backend.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

// Called AppUser, not User, because "user" is a reserved word in PostgreSQL.
// An entity called User would map to a table called "user", and
// "select * from user" doesn't read that table at all: it returns the name of
// the database role you're logged in as. Every query against it would need
// the name in double quotes. AppUser maps to "app_user", which needs nothing
// special.
//
// Only the fields needed today. The contract's User object also has phone,
// address and company; they arrive with GET /users. The password hash
// arrives in Week 8, and must never appear in any response.
@Entity
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // unique = true creates a unique constraint, so the database refuses a
    // second account with the same username even if two requests race.
    @Column(nullable = false, unique = true)
    private String username;

    // Unique because the contract says so (registering a taken email is a
    // 409 CONFLICT from Week 8), and it's what the seeder matches users on.
    @Column(nullable = false, unique = true)
    private String email;

    protected AppUser() {
    }

    public AppUser(String name, String username, String email) {
        this.name = name;
        this.username = username;
        this.email = email;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }
}
