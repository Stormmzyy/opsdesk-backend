package com.opsdesk.backend.user;

import java.util.Locale;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
// address and company; they arrive with GET /users. The password hash and
// role arrived in Week 7.
@Entity
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // unique = true creates a unique constraint, so the database refuses a
    // second account with the same username even if two requests race.
    //
    // Always stored in lowercase (NOTES.md, decision 30), for the same
    // reason as email: the constraint is case-sensitive, and @Ama.Owusu
    // next to @ama.owusu would invite impersonation. The constructor
    // lowercases it.
    @Column(nullable = false, unique = true)
    private String username;

    // Unique because the contract says so (registering a taken email is a
    // 409 CONFLICT), and it's what login and the seeder look users up by.
    //
    // Always stored in lowercase (NOTES.md, decision 26), so this
    // case-sensitive unique constraint still refuses Ama@example.com when
    // ama@example.com exists. The constructor lowercases it, so no code path
    // can save another form.
    @Column(nullable = false, unique = true)
    private String email;

    // The BCrypt hash of the password, never the password itself. It comes
    // from the PasswordEncoder bean, never from a literal in the source.
    //
    // It must never appear in any response. UserResponse lists the fields it
    // returns one by one, and this one isn't among them, so serialising a
    // user can't leak it.
    @Column(nullable = false)
    private String passwordHash;

    // Never taken from a request body. Registering always creates a USER; the
    // only other way to get a role is the seeder or SQL (README.md, "Making a
    // user an admin").
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    protected AppUser() {
    }

    public AppUser(String name, String username, String email, String passwordHash, Role role) {
        this.name = name;
        this.username = username.toLowerCase(Locale.ROOT);
        // Locale.ROOT, not the default locale: in a Turkish locale "I" would
        // lowercase to a dotless "ı", and the stored value would stop
        // matching.
        this.email = email.toLowerCase(Locale.ROOT);
        this.passwordHash = passwordHash;
        this.role = role;
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

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }
}
