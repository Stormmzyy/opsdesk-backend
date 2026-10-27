package com.opsdesk.backend.project;

import org.springframework.data.jpa.repository.JpaRepository;

// Like TicketRepository: Spring Data supplies the implementation at startup.
public interface ProjectRepository extends JpaRepository<Project, Long> {
}
