package com.opsdesk.backend.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
// Boot 3: org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.opsdesk.backend.user.AppUser;
import com.opsdesk.backend.user.AppUserRepository;

// The ticket endpoints through the real HTTP stack (NOTES.md, decision 64).
//
// FULL CONTEXT, NOT A SLICE, for every test here. A @WebMvcTest slice builds
// only the web layer: it doesn't register TicketEditAccessInterceptor's
// wiring or method security unless both are imported by hand, and it mocks
// TicketService. That's a different application from the one that runs:
//   - without the interceptor and @PreAuthorize, the 403s and 404s that come
//     before the body is read turn into 200s and 400s, and the slice reports
//     green on requests the real app refuses.
//   - with the service mocked, nothing is ever loaded from a database, so a
//     missing fetch can't happen. Mapping to TicketResponse runs in the
//     controller, outside the transaction (open-in-view is off, decision 53),
//     and a ticket loaded without its people fails there with a 500 every
//     time. Only the real repository, Hibernate and transaction boundaries
//     can produce that 500, so only a full context can catch it.
//
// Between them the first three tests call every endpoint that returns a ticket,
// all seven responses, which is the condition that makes mapping in the
// controller safe (decision 51): the missing-fetch 500 is deterministic, so
// it only reaches production on an endpoint no test calls. Every response is
// checked for the people's NAMES, since reading a name is what touches the
// lazy link: createdByName always, and assigneeName on an assigned ticket.
//
// Same context, and so the same in-memory database, as
// TicketOutputOnlyFieldsTest (the annotations are identical, so Spring
// reuses it). Nothing here changes a seeded ticket: the write and search
// tests work on one they create and delete. The pinned contract captures
// (ApiContractTest) share the context too, and expect the seed untouched. Seeded tickets and users are found by title and
// email, never by an assumed id.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TicketEndpointsTest {

    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    private static final String PRIYA = "priya.sharma@opsdesk.com";
    private static final String DANIEL = "daniel.mensah@opsdesk.com";
    private static final String KWAME = "kwame.asante@opsdesk.com";
    private static final String FATIMA = "fatima.bello@opsdesk.com";

    // Decision 27's rows: #1 is Priya's, assigned to Daniel; #2 is Kwame's,
    // unassigned; #6 is Priya's, unassigned.
    private static final String PRIYAS_ASSIGNED_TO_DANIEL = "Login page times out on slow networks";
    private static final String KWAMES = "Order new laptops for the March starters";
    private static final String UNASSIGNED = "Add dark mode to the admin panel";
    // #4: Fatima's own, OPEN, unassigned.
    private static final String FATIMAS = "Update the holiday policy document";

    // No seeded ticket has this id (there are twelve).
    private static final long MISSING = 999;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    private Long createdId;

    // The write test deletes its ticket over HTTP. This is the safety net if
    // it failed before getting there, so the shared database still holds
    // only decision 27's twelve.
    @AfterEach
    void deleteTheCreatedTicket() {
        if (createdId != null && ticketRepository.findById(createdId).isPresent()) {
            ticketRepository.deleteById(createdId);
        }
    }

    // Both shapes of GET /tickets, which run different queries: the plain
    // array (no parameters) and the page (any parameter). Each page here is
    // SMALLER than what matches, so the first page is full and Spring Data
    // must run the COUNT query to know the total. That's the query that broke
    // on Thursday when a fetch was added to it (decision 52), and it hid at
    // the default size because twelve tickets fitted on one page and the
    // count was skipped.
    @Test
    void theListInBothShapesNamesThePeopleAndCountsOnlyWhatTheCallerSees() throws Exception {
        String kwame = tokenFor(KWAME);

        mockMvc.perform(as(kwame, get("/api/v1/tickets")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(12)))
                .andExpect(jsonPath("$[*].createdByName", everyItem(notNullValue())))
                .andExpect(jsonPath("$[?(@.title == '" + PRIYAS_ASSIGNED_TO_DANIEL + "')].assigneeName",
                        hasItem("Daniel Mensah")));

        mockMvc.perform(as(kwame, get("/api/v1/tickets?size=5")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(5)))
                .andExpect(jsonPath("$.totalItems").value(12))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.items[*].createdByName", everyItem(notNullValue())))
                .andExpect(jsonPath("$.items[?(@.title == '" + PRIYAS_ASSIGNED_TO_DANIEL + "')].assigneeName",
                        hasItem("Daniel Mensah")));

        // A USER's page: the COUNT has to carry her visibility too, so her
        // totals are her two tickets, not the table's twelve.
        mockMvc.perform(as(tokenFor(FATIMA), get("/api/v1/tickets?size=1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].createdByName").value("Fatima Bello"))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    // GET /tickets/{id}, and the refusals in decision 22's order:
    //   401 no token, before anything else
    //   403 a role gate (@PreAuthorize, or the interceptor's assign gate),
    //       before the ticket is looked up
    //   404 the ticket is missing or hidden, before the body is read
    //   403 the ticket rule (canEdit, in the interceptor), before the body
    //   400 the body itself, last
    // The two body cases send an invalid body on purpose: if the 404 or the
    // 403 came after body validation, they'd be 400s.
    @Test
    void aTicketIsReadWithItsPeopleAndRefusalsComeInTheContractsOrder() throws Exception {
        long assigned = seeded(PRIYAS_ASSIGNED_TO_DANIEL);
        long kwames = seeded(KWAMES);
        long unassigned = seeded(UNASSIGNED);
        String priya = tokenFor(PRIYA);
        String daniel = tokenFor(DANIEL);
        String kwame = tokenFor(KWAME);
        String fatima = tokenFor(FATIMA);

        mockMvc.perform(as(daniel, get("/api/v1/tickets/" + assigned)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.createdByName").value("Priya Sharma"))
                .andExpect(jsonPath("$.assigneeName").value("Daniel Mensah"));

        mockMvc.perform(get("/api/v1/tickets/" + assigned))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));

        // Hidden is the same 404 as missing, with the same message for the id
        // as sent (decision 23).
        mockMvc.perform(as(fatima, get("/api/v1/tickets/" + kwames)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No ticket with id \"" + kwames + "\"."));
        mockMvc.perform(as(fatima, get("/api/v1/tickets/" + MISSING)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No ticket with id \"" + MISSING + "\"."));

        // 404 before 400: a hidden ticket with an invalid body.
        mockMvc.perform(as(fatima, patch("/api/v1/tickets/" + kwames)).content("{\"title\": 5}"))
                .andExpect(status().isNotFound());

        // The ticket rule's 403 before 400: an AGENT, a ticket assigned to
        // nobody, an invalid body.
        mockMvc.perform(as(daniel, patch("/api/v1/tickets/" + unassigned)).content("{\"title\": 5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        // Role gates before the lookup: a ticket that doesn't exist is a 403
        // for the wrong role and a 404 only for the right one.
        mockMvc.perform(as(kwame, delete("/api/v1/tickets/" + MISSING)))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(priya, delete("/api/v1/tickets/" + MISSING)))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(fatima, patch("/api/v1/tickets/" + MISSING + "/assignee")).content("{\"assigneeId\": 2}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(kwame, patch("/api/v1/tickets/" + MISSING + "/assignee")).content("{\"assigneeId\": 2}"))
                .andExpect(status().isNotFound());
    }

    // The four writes that return a ticket, on one ticket created here:
    // create, assign, edit, unassign. The edit comes while it's assigned,
    // so its response has to read the assignee through the ticket that
    // findById loaded, which is the lookup @EntityGraph fetches for.
    @Test
    void everyWriteThatReturnsATicketNamesThePeople() throws Exception {
        String kwame = tokenFor(KWAME);
        long danielId = appUserRepository.findByEmail(DANIEL).map(AppUser::getId).orElseThrow();

        String created = mockMvc.perform(as(kwame, post("/api/v1/tickets"))
                        .content("{\"title\": \"Endpoint test\", \"description\": \"Created by a test.\","
                                + " \"priority\": \"LOW\", \"team\": \"HR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.createdByName").value("Kwame Asante"))
                .andExpect(jsonPath("$.assigneeName").value(nullValue()))
                .andReturn().getResponse().getContentAsString();
        createdId = ((Number) JsonPath.read(created, "$.id")).longValue();
        String path = "/api/v1/tickets/" + createdId;

        mockMvc.perform(as(kwame, patch(path + "/assignee")).content("{\"assigneeId\": " + danielId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeName").value("Daniel Mensah"))
                .andExpect(jsonPath("$.createdByName").value("Kwame Asante"));

        mockMvc.perform(as(kwame, patch(path)).content("{\"title\": \"Endpoint test, edited\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Endpoint test, edited"))
                .andExpect(jsonPath("$.assigneeName").value("Daniel Mensah"))
                .andExpect(jsonPath("$.createdByName").value("Kwame Asante"));

        mockMvc.perform(as(kwame, delete(path + "/assignee")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigneeName").value(nullValue()))
                .andExpect(jsonPath("$.createdByName").value("Kwame Asante"));

        mockMvc.perform(as(tokenFor(PRIYA), delete(path)))
                .andExpect(status().isNoContent());
    }

    // Decision 65, finding 2: with every assign check removed, the suite
    // used to fail on one assertion only, about a ticket that doesn't exist.
    // No test had an AGENT or a USER try to assign a REAL ticket.
    //
    // Each attempt is on a ticket the caller CAN see. On a hidden one the
    // view check answers 404 first and the role rule never runs, which is
    // exactly how the old assertion could pass for the wrong reason. Daniel
    // sees every ticket; Fatima sees her own, so she tries her own #4. Each
    // row is read back: a 403 that still changed the assignee would be worse
    // than no check.
    @Test
    void anAgentAndAUserCannotAssignOrUnassignARealTicket() throws Exception {
        long kwames = seeded(KWAMES);
        long fatimas = seeded(FATIMAS);
        long danielsAssigned = seeded(PRIYAS_ASSIGNED_TO_DANIEL);
        long danielId = appUserRepository.findByEmail(DANIEL).map(AppUser::getId).orElseThrow();
        String daniel = tokenFor(DANIEL);
        String fatima = tokenFor(FATIMA);

        mockMvc.perform(as(daniel, patch("/api/v1/tickets/" + kwames + "/assignee"))
                        .content("{\"assigneeId\": " + danielId + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        assertNull(ticketRepository.findById(kwames).orElseThrow().getAssignee(),
                "the AGENT assigned the ticket despite the 403");

        mockMvc.perform(as(daniel, delete("/api/v1/tickets/" + danielsAssigned + "/assignee")))
                .andExpect(status().isForbidden());
        assertEquals(danielId, ticketRepository.findById(danielsAssigned).orElseThrow().getAssignee().getId(),
                "the AGENT unassigned the ticket despite the 403");

        mockMvc.perform(as(fatima, patch("/api/v1/tickets/" + fatimas + "/assignee"))
                        .content("{\"assigneeId\": " + danielId + "}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
        assertNull(ticketRepository.findById(fatimas).orElseThrow().getAssignee(),
                "the USER assigned her ticket despite the 403");
    }

    // Decision 65, finding 3: the LIKE escape was guarded only by the walk.
    // Escaping isn't specific to PostgreSQL, so it belongs here too. No
    // seeded title or description contains % or _, so an unescaped "%"
    // (the pattern %%%) would match every ticket; escaped, only this one.
    //
    // Sent with param(), not in the URL: MockMvc encodes the URL template,
    // so a "%25" written there would arrive double-encoded and search for
    // something else.
    @Test
    void aSearchForAPercentSignFindsOnlyTheTicketContainingOne() throws Exception {
        String kwame = tokenFor(KWAME);
        String created = mockMvc.perform(as(kwame, post("/api/v1/tickets"))
                        .content("{\"title\": \"a 100% probe\", \"description\": \"x\","
                                + " \"priority\": \"LOW\", \"team\": \"HR\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        createdId = ((Number) JsonPath.read(created, "$.id")).longValue();

        mockMvc.perform(as(kwame, get("/api/v1/tickets")).param("search", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(createdId))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    // A seeded ticket's id, found by its title through the ADMIN's view.
    private long seeded(String title) {
        AppUser priya = appUserRepository.findByEmail(PRIYA).orElseThrow();
        return ticketRepository.findVisible(priya, TicketFilters.NONE, TicketOrder.DEFAULT).stream()
                .filter(t -> t.getTitle().equals(title))
                .findFirst().orElseThrow().getId();
    }

    private static MockHttpServletRequestBuilder as(String token, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON);
    }

    // A real login, so the token is exactly what a client would hold.
    private String tokenFor(String email) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"" + email + "\", \"password\": \"devpassword123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Matcher token = TOKEN.matcher(body);
        if (!token.find()) {
            throw new IllegalStateException("No token in the login response: " + body);
        }
        return token.group(1);
    }
}
