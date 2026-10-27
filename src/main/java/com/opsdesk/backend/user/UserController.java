package com.opsdesk.backend.user;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// GET /api/v1/users, the contract's user directory. POST /users (register)
// arrives with authentication in Week 8, and from then this list will need a
// signed-in user.
//
// The path is /users, not /app_users or /appUsers: the entity is called
// AppUser only because "user" is reserved in PostgreSQL (see AppUser). That's
// a database detail, and it stays out of the API.
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // 200 with a plain array sorted by id; [] when there are none.
    @GetMapping
    public List<UserResponse> listUsers() {
        return userService.listUsers().stream()
                .map(UserResponse::from)
                .toList();
    }
}
