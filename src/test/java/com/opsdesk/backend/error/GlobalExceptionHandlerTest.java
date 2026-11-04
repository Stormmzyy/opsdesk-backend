package com.opsdesk.backend.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import com.opsdesk.backend.ticket.Ticket;

// The 409 a stale write gets (NOTES.md, decision 38). TicketLostUpdateTest
// proves the exception is thrown; this pins what the client sees when it is:
// the contract's error shape, with a fixed message that says nothing about
// versions, entities or ids.
//
// Pins API_CONTRACT.md (in opsdesk-frontend), "#### Send only the fields
// being changed": the 409 example, the contract's one hand-written response,
// whose "Pinned by:" line names this test. Change the two together.
class GlobalExceptionHandlerTest {

    @Test
    void anOptimisticLockFailureIsA409WithTheFixedMessage() {
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/v1/tickets/4");

        ResponseEntity<ApiError> response = new GlobalExceptionHandler().handleConcurrentChange(
                new ObjectOptimisticLockingFailureException(Ticket.class, 4L), request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        ApiError body = response.getBody();
        assertEquals("CONFLICT", body.error());
        assertEquals("Someone else changed this at the same time. Reload it and try again.", body.message());
        assertNull(body.fields());
    }
}
