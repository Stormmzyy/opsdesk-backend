package com.opsdesk.backend.ticket;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.opsdesk.backend.user.AppUser;

// The twin of TicketRepositoryShapeTest, one layer up (NOTES.md, decision 61).
//
// TicketRepositoryShapeTest makes sure no repository method can LIST tickets
// without the caller. Single tickets are different: findById takes no caller,
// because the visibility check for one ticket has to turn "not allowed" into
// the same 404 as "doesn't exist", which a query can't do. That check lives
// in TicketService.getVisibleTicket. So the guarantee for single tickets is
// one layer up, in the service: every public service method that hands out a
// ticket must take the caller, so it can't return one without asking who's
// looking.
//
// That was a convention until this test. TicketService.getTicket(Long), a
// public lookup with no caller and no visibility check, sat unused for a
// week, ready for the next endpoint to pick up and leak a hidden ticket. This
// test fails if anything like it comes back.
//
// It checks every public method declared on TicketService that returns a
// Ticket or a TicketResponse, or a collection or page of them (List<Ticket>,
// Page<Ticket>).
class TicketServiceShapeTest {

    private static final Set<Class<?>> TICKET_TYPES = Set.of(Ticket.class, TicketResponse.class);

    @Test
    void everyPublicMethodThatReturnsATicketTakesTheCaller() {
        List<String> problems = new ArrayList<>();
        for (Method method : TicketService.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || !returnsTickets(method)) {
                continue;
            }
            if (!Arrays.asList(method.getParameterTypes()).contains(AppUser.class)) {
                problems.add(method + " returns a ticket without taking the caller, so it can't check visibility");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    // Ticket or TicketResponse itself, or a generic type with one of them as
    // a type argument: List<Ticket>, Page<Ticket>, Optional<Ticket>.
    private static boolean returnsTickets(Method method) {
        if (TICKET_TYPES.contains(method.getReturnType())) {
            return true;
        }
        Type type = method.getGenericReturnType();
        return type instanceof ParameterizedType generic
                && Arrays.stream(generic.getActualTypeArguments()).anyMatch(TICKET_TYPES::contains);
    }
}
