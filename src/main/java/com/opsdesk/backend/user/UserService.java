package com.opsdesk.backend.user;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

// The rules for reading users. Registering and logging in arrive in Week 8.
@Service
public class UserService {

    private final AppUserRepository appUserRepository;

    public UserService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    // Sorted by id, as the contract says for every list (NOTES.md, decision 7).
    public List<AppUser> listUsers() {
        return appUserRepository.findAll(Sort.by("id"));
    }
}
