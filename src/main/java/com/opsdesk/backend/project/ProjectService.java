package com.opsdesk.backend.project;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

// The rules for reading projects. Projects are read-only in v1: the contract
// has no endpoints to create, change or delete them.
@Service
public class ProjectService {

    private final ProjectRepository projectRepository;

    public ProjectService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    // Sorted by id, as the contract says for every list. Asked for explicitly,
    // because findAll() alone has no ORDER BY (NOTES.md, decision 7).
    public List<Project> listProjects() {
        return projectRepository.findAll(Sort.by("id"));
    }

    public Project getProject(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException(id));
    }
}
