package com.opsdesk.backend.user;

import org.springframework.data.jpa.repository.JpaRepository;

// Like TicketRepository: Spring Data supplies the implementation at startup.
public interface AppUserRepository extends JpaRepository<AppUser, Long> {
}
