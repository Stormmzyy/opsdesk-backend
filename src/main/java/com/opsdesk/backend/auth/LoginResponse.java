package com.opsdesk.backend.auth;

import com.opsdesk.backend.user.UserResponse;

// The body of a successful login (NOTES.md, decision 19):
//   { "token": "<JWT>", "user": { ...the contract's User object... } }
//
// "user" is exactly what the contract's login response already returns; the
// token is the only addition. The client sends the token back on every later
// request as "Authorization: Bearer <token>". The field names are fixed in
// the contract on Thursday.
//
// The user's role isn't a field here: it travels inside the token, as the
// "role" claim, as a hint for the frontend (decision 28).
public record LoginResponse(String token, UserResponse user) {
}
