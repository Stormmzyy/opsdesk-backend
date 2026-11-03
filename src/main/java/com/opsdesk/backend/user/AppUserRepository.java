package com.opsdesk.backend.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

// Like TicketRepository: Spring Data supplies the implementation at startup.
//
// The three methods below have no body. Spring Data works out the query from
// the method name: findByEmail becomes "where email = ?", existsByUsername
// becomes a "select 1 ... where username = ? limit 1". A typo in a field name
// stops the app at startup rather than failing on the first request.
//
// Every email passed in must already be lowercase (NOTES.md, decision 26).
// These are exact comparisons, which is what lets the unique constraint on
// email double as the index for them.
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByUsername(String username);
}
