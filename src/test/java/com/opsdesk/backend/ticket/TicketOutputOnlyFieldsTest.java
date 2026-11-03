package com.opsdesk.backend.ticket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
// Boot 3: org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.opsdesk.backend.user.AppUser;
import com.opsdesk.backend.user.AppUserRepository;

// Decision 39's tests: the ticket shape's output-only fields are refused on
// PATCH /tickets/{id}, so the edit endpoint can't be used to do what only the
// assign endpoint may (NOTES.md, decisions 39 and 40).
//
// The one that matters most is assigneeId. If the edit endpoint bound it, an
// AGENT, who may edit a ticket assigned to him, could hand it to someone
// else, or take one, without ever passing the ADMIN-or-MANAGER gate on
// PATCH /tickets/{id}/assignee.
//
// Through the real HTTP stack, with a real token from POST /auth/login, so
// the security filter, the interceptor's check order (decision 22) and
// validation all run as in production. Against the dev PostgreSQL, in a
// schema of its own that is created, seeded and dropped by this test, so
// the dev data is never touched.
//
// Every test also reads the row back afterwards: a 400 that still let the
// change through would be worse than no check at all.
@SpringBootTest(properties = {
        "spring.jpa.properties.hibernate.default_schema=output_fields_test",
        "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
class TicketOutputOnlyFieldsTest {

    private static final String DANIEL = "daniel.mensah@opsdesk.com";
    private static final String LIAM = "liam.carter@opsdesk.com";
    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    private AppUser daniel;
    private Ticket danielsTicket;
    private Ticket unassignedTicket;

    // Ids are looked up, never assumed: which ticket is Daniel's comes from
    // the seeded rows, the same way Friday's tests should take them.
    @BeforeEach
    void findTheTickets() {
        daniel = appUserRepository.findByEmail(DANIEL).orElseThrow();
        var tickets = ticketRepository.findVisible(daniel, TicketFilters.NONE, TicketOrder.DEFAULT);
        danielsTicket = tickets.stream()
                .filter(t -> t.getAssignee() != null && t.getAssignee().getId().equals(daniel.getId()))
                .findFirst().orElseThrow();
        unassignedTicket = tickets.stream()
                .filter(t -> t.getAssignee() == null)
                .findFirst().orElseThrow();
    }

    // Decision 39, first case: an AGENT sends assigneeId on a ticket assigned
    // to him, to hand it to another agent. The interceptor lets him through
    // (he may edit it), so the refusal must come from the body: 400.
    @Test
    void anAgentCannotHandOffHisTicketThroughTheEditEndpoint() throws Exception {
        Long liamId = appUserRepository.findByEmail(LIAM).orElseThrow().getId();

        patchAs(DANIEL, danielsTicket, "{\"assigneeId\": " + liamId + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.assigneeId")
                        .value("The assignee can't be changed here. Use PATCH /tickets/{id}/assignee."));

        assertEquals(daniel.getId(), reload(danielsTicket).getAssignee().getId(),
                "the assignee changed despite the 400");
    }

    // "assigneeId": null is how a client would try to UNassign. It must be
    // refused too, not read as "not sent". This is why the field is a
    // JsonNode (an explicit null arrives as a NullNode) and not an Object.
    @Test
    void anExplicitNullAssigneeIsRefusedToo() throws Exception {
        patchAs(DANIEL, danielsTicket, "{\"assigneeId\": null}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fields.assigneeId").exists());

        assertEquals(daniel.getId(), reload(danielsTicket).getAssignee().getId(),
                "an explicit null unassigned the ticket despite the 400");
    }

    // Decision 39, second case: the same AGENT claims a ticket assigned to
    // nobody. He may not edit that ticket at all, and the interceptor
    // decides that before the body is read (decision 22: the ticket rule's
    // 403 comes before any 400), so this is a 403, not the 400 above.
    @Test
    void anAgentCannotClaimAnUnassignedTicket() throws Exception {
        patchAs(DANIEL, unassignedTicket, "{\"assigneeId\": " + daniel.getId() + "}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        assertNull(reload(unassignedTicket).getAssignee(), "the ticket was claimed despite the 403");
    }

    // version is output only (decision 40). Sent with a real change, the whole
    // request is refused: the title isn't applied either, and the version
    // doesn't move.
    @Test
    void versionIsRefusedAndNothingInTheRequestIsApplied() throws Exception {
        Ticket before = reload(danielsTicket);

        patchAs(DANIEL, danielsTicket, "{\"title\": \"Changed by a stale client\", \"version\": 0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fields.version")
                        .value("The version is set by the server and can't be sent."));

        Ticket after = reload(danielsTicket);
        assertEquals(before.getTitle(), after.getTitle(), "the title was applied despite the 400");
        assertEquals(before.getVersion(), after.getVersion(), "the row was written despite the 400");
    }

    private ResultActions patchAs(String email, Ticket ticket, String body) throws Exception {
        return mockMvc.perform(patch("/api/v1/tickets/" + ticket.getId())
                .header("Authorization", "Bearer " + tokenFor(email))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
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

    // Read fresh from the database, with the assignee and creator joined.
    private Ticket reload(Ticket ticket) {
        return ticketRepository.findById(ticket.getId()).orElseThrow();
    }
}
