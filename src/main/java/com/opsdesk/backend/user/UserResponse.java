package com.opsdesk.backend.user;

// The contract's User object, in the contract's field order:
//   { "id", "name", "username", "email", "phone",
//     "address": { "street", "suite", "city", "zipcode" },
//     "company": { "name" } }
//
// A User response never includes a password or a password hash, in any
// response. AppUser doesn't have one yet (Week 8), and when it does, it won't
// be added here.
//
// AppUser stores only name, username and email. The contract's other fields
// (phone, address, company) come back as empty strings, the same convention
// the contract uses for a newly registered user ("fields the register form
// doesn't collect come back as empty strings"). So the shape is always the
// contract's, and nothing is invented.
public record UserResponse(
        Long id,
        String name,
        String username,
        String email,
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
                "",
                new Address("", "", "", ""),
                new Company(""));
    }
}
