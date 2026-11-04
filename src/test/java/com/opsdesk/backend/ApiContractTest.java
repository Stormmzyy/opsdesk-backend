package com.opsdesk.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.isA;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
// Boot 3: org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

// Pins the contract's captured responses (NOTES.md, decisions 60 and 66).
//
// The contract lives in the frontend repo:
//   https://github.com/Stormmzyy/opsdesk-frontend/blob/main/API_CONTRACT.md
// Its examples were captured from the running backend on Thursday. A
// capture is true when it's taken and goes stale without a sound; a test
// fails the day the code drifts. Each test below names the contract section
// and the capture it pins, and each pinned capture in API_CONTRACT.md has a
// "Pinned by:" line naming the test, so whoever edits either side finds the
// other. Change a pinned response and both must change together.
//
// Not every capture is pinned. Decision 66 lists the 14 left out and why:
// ids and versions that depend on what ran before, and shapes or messages
// already pinned elsewhere. Login's 200 is pinned by shape, since its token
// differs on every run.
//
// STRICT comparison throughout: exact keys, exact values, no extra fields.
// The strictness is the point. A field that appears (a password hash on a
// user) fails as surely as one that disappears.
//
// The contract's examples use the seeded ids (/tickets/1, /2, /4), because
// they were taken on a freshly seeded database, and the test database is
// one. Strict bodies include each ticket's title, so if the seed ever
// numbered differently, the failure would say so at once. These tests share
// their Spring context, and so their database, with TicketEndpointsTest and
// TicketOutputOnlyFieldsTest; none of the three changes a seeded ticket, so
// every version here is still 0.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiContractTest {

    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    private static final String FORBIDDEN = """
            {"error": "FORBIDDEN", "message": "You don't have permission to do this."}""";

    // Kwame, as the User object. Pinned on GET /auth/me, and reused for the
    // user inside login's envelope, so the two can't drift apart.
    private static final String KWAME = """
            {"id": 3, "name": "Kwame Asante", "username": "kwame.asante",
             "email": "kwame.asante@opsdesk.com", "role": "MANAGER", "phone": "",
             "address": {"street": "", "suite": "", "city": "", "zipcode": ""},
             "company": {"name": ""}}""";

    @Autowired
    private MockMvc mockMvc;

    // Pins "## Signing in and the token":
    // GET /api/v1/tickets with no token → 401
    @Test
    void aRequestWithoutATokenGetsThe401Body() throws Exception {
        exactly(mockMvc.perform(get("/api/v1/tickets")), 401, """
                {"error": "UNAUTHORIZED",
                 "message": "You need to log in first. Send your token as \\"Authorization: Bearer <token>\\"."}""");
    }

    // Pins "### The error shape":
    // POST /api/v1/tickets as priya with {"title": "", ...} → 400
    @Test
    void aValidationErrorNamesEachFieldAndJoinsTheirMessages() throws Exception {
        exactly(send("priya", post("/api/v1/tickets"),
                        "{\"title\": \"\", \"priority\": \"HIGH\", \"team\": \"Operations\", \"description\": \"Toner\"}"),
                400, """
                {"error": "VALIDATION_ERROR", "message": "Title is required.",
                 "fields": {"title": "Title is required."}}""");
    }

    // Pins "### Error codes" (DELETE /api/v1/tickets/1 as daniel → 403),
    // "#### Send only the fields being changed" (PATCH /api/v1/tickets/4 as
    // fatima with {"status": "CLOSED"} → 403) and "### GET /users"
    // (GET /api/v1/users as fatima → 403).
    //
    // One fixed body from three different code paths: @PreAuthorize on
    // delete, the USER's field rule in the service, and the users endpoint's
    // role gate. If any one of them built its own body, "one 403" would quietly
    // become several.
    @Test
    void everyRefusalIsTheSameFixed403Body() throws Exception {
        exactly(send("daniel", delete("/api/v1/tickets/1"), null), 403, FORBIDDEN);
        exactly(send("fatima", patch("/api/v1/tickets/4"), "{\"status\": \"CLOSED\"}"), 403, FORBIDDEN);
        exactly(send("fatima", get("/api/v1/users"), null), 403, FORBIDDEN);
    }

    // Pins "## The order of the checks":
    // DELETE /api/v1/tickets/999 as kwame → 403, and as priya → 404
    @Test
    void theRoleGateRefusesBeforeTheTicketIsLookedUp() throws Exception {
        exactly(send("kwame", delete("/api/v1/tickets/999"), null), 403, FORBIDDEN);
        exactly(send("priya", delete("/api/v1/tickets/999"), null), 404, """
                {"error": "NOT_FOUND", "message": "No ticket with id \\"999\\"."}""");
    }

    // Pins "## Status codes": PUT /api/v1/tickets/1 as priya with {} → 404.
    // A 404 naming the method and path, not a 405. That answer is the
    // framework's as much as ours, the kind of thing an upgrade changes.
    @Test
    void anUnsupportedMethodIsA404NamingIt() throws Exception {
        exactly(send("priya", put("/api/v1/tickets/1"), "{}"), 404, """
                {"error": "NOT_FOUND", "message": "Nothing exists at PUT /api/v1/tickets/1."}""");
    }

    // Pins "### The Ticket object": GET /api/v1/tickets/1 as priya → 200.
    // All eleven fields, and nothing else.
    @Test
    void theTicketHasExactlyTheContractsElevenFields() throws Exception {
        exactly(send("priya", get("/api/v1/tickets/1"), null), 200, """
                {"id": 1,
                 "title": "Login page times out on slow networks",
                 "description": "Users on mobile data report that the login page spins for over 30 seconds and then shows a timeout error. It works fine on office Wi-Fi.",
                 "priority": "URGENT", "status": "OPEN", "team": "Engineering",
                 "assigneeId": 2, "assigneeName": "Daniel Mensah",
                 "createdById": 1, "createdByName": "Priya Sharma",
                 "version": 0}""");
    }

    // Pins "### Visibility: a ticket you can't see is a 404":
    // GET /api/v1/tickets/2 as fatima → 404, and /999 as fatima → 404.
    // The same body for a ticket that exists but is hidden as for one that
    // doesn't exist (decision 23).
    @Test
    void aHiddenTicketIsTheSame404AsAMissingOne() throws Exception {
        exactly(send("fatima", get("/api/v1/tickets/2"), null), 404, """
                {"error": "NOT_FOUND", "message": "No ticket with id \\"2\\"."}""");
        exactly(send("fatima", get("/api/v1/tickets/999"), null), 404, """
                {"error": "NOT_FOUND", "message": "No ticket with id \\"999\\"."}""");
    }

    // Pins "### GET /tickets/{id}": GET /api/v1/tickets/abc as priya → 404,
    // quoting the id exactly as it was sent.
    @Test
    void aNonNumericIdIsA404QuotingWhatWasSent() throws Exception {
        exactly(send("priya", get("/api/v1/tickets/abc"), null), 404, """
                {"error": "NOT_FOUND", "message": "No ticket with id \\"abc\\"."}""");
    }

    // Pins "### GET /tickets": GET /api/v1/tickets as fatima → 200.
    // No parameters: the plain v1 array, not the page envelope, holding only
    // her own two tickets.
    @Test
    void withNoParametersTheListIsAPlainArrayOfTheCallersTickets() throws Exception {
        exactly(send("fatima", get("/api/v1/tickets"), null), 200, """
                [{"id": 4,
                  "title": "Update the holiday policy document",
                  "description": "The holiday policy still lists last year's public holidays. Update the dates and the carry-over rules agreed at the last meeting.",
                  "priority": "LOW", "status": "OPEN", "team": "HR",
                  "assigneeId": null, "assigneeName": null,
                  "createdById": 5, "createdByName": "Fatima Bello", "version": 0},
                 {"id": 9,
                  "title": "Schedule onboarding for new hires",
                  "description": "Book onboarding sessions for this month's new hires, covering IT setup, security training and a team introduction.",
                  "priority": "MEDIUM", "status": "RESOLVED", "team": "HR",
                  "assigneeId": null, "assigneeName": null,
                  "createdById": 5, "createdByName": "Fatima Bello", "version": 0}]""");
    }

    // Pins "#### Filtering, sorting and pagination":
    // GET /api/v1/tickets?status=OPEN&sort=priority,desc&size=2&page=2 as
    // priya → 200. The envelope's five keys exactly, with a filter, a CASE
    // sort and the second page in one request.
    @Test
    void aPageHasExactlyTheEnvelopesFiveKeys() throws Exception {
        exactly(send("priya", get("/api/v1/tickets")
                        .param("status", "OPEN").param("sort", "priority,desc").param("size", "2").param("page", "2"),
                null), 200, """
                {"items": [
                   {"id": 2,
                    "title": "Order new laptops for the March starters",
                    "description": "Four new starters join on 3 March. Order laptops with the standard developer setup and have them ready the Friday before.",
                    "priority": "MEDIUM", "status": "OPEN", "team": "Operations",
                    "assigneeId": null, "assigneeName": null,
                    "createdById": 3, "createdByName": "Kwame Asante", "version": 0},
                   {"id": 4,
                    "title": "Update the holiday policy document",
                    "description": "The holiday policy still lists last year's public holidays. Update the dates and the carry-over rules agreed at the last meeting.",
                    "priority": "LOW", "status": "OPEN", "team": "HR",
                    "assigneeId": null, "assigneeName": null,
                    "createdById": 5, "createdByName": "Fatima Bello", "version": 0}],
                 "page": 2, "size": 2, "totalItems": 4, "totalPages": 2}""");
    }

    // Pins "#### Filtering, sorting and pagination":
    // GET /api/v1/tickets?size=500&status=OPEN&status=CLOSED&stauts=OPEN as
    // priya → 400. Three different checks (bean validation on size, the
    // sent-once check, the unknown-parameter check) reported together in one
    // envelope. param() feeds the same parameter map a real query string does.
    @Test
    void queryStringProblemsAreReportedTogetherInOneEnvelope() throws Exception {
        exactly(send("priya", get("/api/v1/tickets")
                        .param("size", "500").param("status", "OPEN", "CLOSED").param("stauts", "OPEN"),
                null), 400, """
                {"error": "VALIDATION_ERROR",
                 "message": "Size must be a whole number from 1 to 100. Status can only be sent once. There is no query parameter called \\"stauts\\".",
                 "fields": {"size": "Size must be a whole number from 1 to 100.",
                            "status": "Status can only be sent once.",
                            "stauts": "There is no query parameter called \\"stauts\\"."}}""");
    }

    // Pins "### POST /tickets": POST /api/v1/tickets as fatima with [] → 400.
    @Test
    void aBodyThatIsNotAJsonObjectIsABadRequest() throws Exception {
        exactly(send("fatima", post("/api/v1/tickets"), "[]"), 400, """
                {"error": "BAD_REQUEST", "message": "The request body must be a JSON object."}""");
    }

    // Pins "### POST /tickets": POST /api/v1/tickets as fatima with
    // {"title": "  ", ..., "team": "Marketing", "assigneeId": 2} → 400.
    // Create refuses the assignee too (decision 39). Until this test only
    // the PATCH side of that was tested, by TicketOutputOnlyFieldsTest.
    @Test
    void createRefusesAnAssigneeAndReportsEveryBadField() throws Exception {
        exactly(send("fatima", post("/api/v1/tickets"),
                        "{\"title\": \"  \", \"description\": \"x\", \"priority\": \"HIGH\", \"team\": \"Marketing\", \"assigneeId\": 2}"),
                400, """
                {"error": "VALIDATION_ERROR",
                 "message": "Title is required. Team must be one of Engineering, Finance, HR, Operations or Support. The assignee can't be set here. Use PATCH /tickets/{id}/assignee.",
                 "fields": {"title": "Title is required.",
                            "team": "Team must be one of Engineering, Finance, HR, Operations or Support.",
                            "assigneeId": "The assignee can't be set here. Use PATCH /tickets/{id}/assignee."}}""");
    }

    // Pins "### PATCH /tickets/{id}/assignee":
    // PATCH /api/v1/tickets/2/assignee as kwame with {"assigneeId": 5} → 400.
    // A rule with no other guard: neither the suite nor the walk tried
    // assigning a ticket to a USER before this.
    @Test
    void aUserCannotBeAssignedATicket() throws Exception {
        exactly(send("kwame", patch("/api/v1/tickets/2/assignee"), "{\"assigneeId\": 5}"), 400, """
                {"error": "VALIDATION_ERROR", "message": "A USER can't be assigned tickets.",
                 "fields": {"assigneeId": "A USER can't be assigned tickets."}}""");
    }

    // Pins "### The User object": GET /api/v1/auth/me as kwame → 200.
    // Strict, so the day a password hash (or anything else) is added to the
    // user shape, this fails.
    @Test
    void theUserShapeHasExactlyItsFieldsAndNoPassword() throws Exception {
        exactly(send("kwame", get("/api/v1/auth/me"), null), 200, KWAME);
    }

    // Pins "### POST /auth/login": the 200, by shape. The token differs on
    // every login, so its value can't be pinned; the envelope doesn't, and
    // it's the first call every client makes. The token must be a non-empty
    // string. It's then replaced by a placeholder, and the whole body is
    // compared strictly: exactly "token" and "user" at the top, and the user
    // exactly the shape GET /auth/me pins (the same KWAME constant).
    //
    // The contract's example is Ama's login after she registered; this
    // database has no Ama, so it's Kwame's. The shape is the same for any user.
    @Test
    void loginReturnsExactlyATokenAndTheUser() throws Exception {
        String body = login("kwame.asante@opsdesk.com", "devpassword123")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", isA(String.class)))
                .andExpect(jsonPath("$.token", not(emptyString())))
                .andReturn().getResponse().getContentAsString();
        String withPlaceholder = TOKEN.matcher(body).replaceFirst("\"token\":\"<token>\"");
        JSONAssert.assertEquals("{\"token\": \"<token>\", \"user\": " + KWAME + "}", withPlaceholder,
                JSONCompareMode.STRICT);
    }

    // Pins "### The Project object": GET /api/v1/projects/2 as fatima → 200.
    // Projects carry no permission logic past signing in, but that's an
    // argument about permissions; this guards the shape, which nothing else
    // would notice drifting.
    @Test
    void theProjectHasExactlyItsFields() throws Exception {
        exactly(send("fatima", get("/api/v1/projects/2"), null), 200, """
                {"id": 2, "name": "New office move",
                 "description": "Plan and run the move to the new Accra office, including desks and network.",
                 "status": "PLANNING", "team": "Operations", "lead": "Kwame Asante"}""");
    }

    // Pins "### POST /auth/login": both 401 captures, a seeded user's wrong
    // password and an email with no account. They get the same body, so the
    // response doesn't say which emails have accounts. Re-captured with
    // these exact requests so the document is reproducible on a fresh seed
    // (it was first captured with Ama's account, which only exists after
    // the register examples run).
    @Test
    void aWrongPasswordAndAnUnknownEmailGetTheSame401() throws Exception {
        String refused = """
                {"error": "UNAUTHORIZED", "message": "The email or password is incorrect."}""";
        exactly(login("fatima.bello@opsdesk.com", "wrong-password"), 401, refused);
        exactly(login("nobody@example.com", "wrong-password"), 401, refused);
    }

    private ResultActions exactly(ResultActions response, int status, String body) throws Exception {
        return response.andExpect(status().is(status)).andExpect(content().json(body, JsonCompareMode.STRICT));
    }

    // A request as one of decision 27's users, logged in for real.
    private ResultActions send(String who, MockHttpServletRequestBuilder request, String body) throws Exception {
        request.header("Authorization", "Bearer " + tokenFor(who)).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            request.content(body);
        }
        return mockMvc.perform(request);
    }

    private ResultActions login(String email, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"" + email + "\", \"password\": \"" + password + "\"}"));
    }

    private String tokenFor(String who) throws Exception {
        String email = switch (who) {
            case "priya" -> "priya.sharma@opsdesk.com";
            case "daniel" -> "daniel.mensah@opsdesk.com";
            case "kwame" -> "kwame.asante@opsdesk.com";
            case "fatima" -> "fatima.bello@opsdesk.com";
            default -> throw new IllegalArgumentException(who);
        };
        String body = login(email, "devpassword123").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher token = TOKEN.matcher(body);
        if (!token.find()) {
            throw new IllegalStateException("No token in the login response: " + body);
        }
        return token.group(1);
    }
}
