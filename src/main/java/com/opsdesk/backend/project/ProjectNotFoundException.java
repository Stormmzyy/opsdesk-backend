package com.opsdesk.backend.project;

import com.opsdesk.backend.error.NotFoundException;

// No project has that id, or the id isn't a valid id at all. The message
// matches the contract's 404 example, quoting the id exactly as it appeared
// in the URL: No project with id "999".
public class ProjectNotFoundException extends NotFoundException {

    public ProjectNotFoundException(String rawId) {
        super("No project with id \"" + rawId + "\".");
    }

    public ProjectNotFoundException(Long id) {
        this(String.valueOf(id));
    }
}
