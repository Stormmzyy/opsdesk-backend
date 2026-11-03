package com.opsdesk.backend.user;

// The contract's User object, in the contract's field order:
//   { "id", "name", "username", "email", "role", "phone",
//     "address": { "street", "suite", "city", "zipcode" },
//     "company": { "name" } }
//
// A User response never includes a password or a password hash, in any
// response. AppUser has a passwordHash, and it is not a field here. Every
// field is listed one by one below, so a field added to AppUser never
// reaches a response unless it's added here on purpose.
//
// role is here on purpose (NOTES.md, the role-in-responses decision). Two
// clients need it: one deciding which buttons to show the signed-in user
// (GET /auth/me), and a MANAGER's assign dropdown, which has to leave out
// USERs because the assign endpoint refuses them (GET /users). The token's
// "role" claim is not the alternative: the server never trusts it (decision
// 28: the role is read from the database on every request), and making
// every client decode a JWT to render a button would put a server detail
// into every client.
//
// AppUser stores only name, username, email and role. The contract's other
// fields (phone, address, company) come back as empty strings. The contract
// marks them deprecated: OpsDesk has no such data, and they go in the next
// version. They stay for now because the frontend's user card reads two of
// them, and removing a field breaks a client that reads it.
public record UserResponse(
        Long id,
        String name,
        String username,
        String email,
        Role role,
        String phone,
        Address address,
        Company company) {

    public record Address(String street, String suite, String city, String zipcode) {
    }

    public record Company(String name) {
    }

    // The one place an AppUser becomes a UserResponse.
    public static UserResponse from(AppUser user) {
        return new UserResponse(
                user.getId(),
                user.getName(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                "",
                new Address("", "", "", ""),
                new Company(""));
    }
}
