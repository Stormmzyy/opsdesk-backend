package com.opsdesk.backend.project;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.opsdesk.backend.common.PathIds;

// The contract's two project endpoints. Read-only, so there's no request body
// and nothing to validate beyond the id.
//
// Same layering and rules as TicketController: HTTP only here, rules in the
// service, and every project leaves as a ProjectResponse, never the entity.
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    // GET /api/v1/projects -> 200 with a plain array sorted by id; [] when
    // there are none. (Pagination exists only for tickets.)
    @GetMapping
    public List<ProjectResponse> listProjects() {
        return projectService.listProjects().stream()
                .map(ProjectResponse::from)
                .toList();
    }

    // GET /api/v1/projects/{id} -> 200, or 404 NOT_FOUND for a missing id or
    // one that isn't a whole number, the same rule as tickets.
    @GetMapping("/{id}")
    public ProjectResponse getProject(@PathVariable String id) {
        Long projectId = PathIds.parse(id).orElseThrow(() -> new ProjectNotFoundException(id));
        return ProjectResponse.from(projectService.getProject(projectId));
    }
}
