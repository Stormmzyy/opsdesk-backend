package com.opsdesk.backend.ticket;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.domain.Specification;

import com.opsdesk.backend.user.AppUser;

// Keeps the unsafe ticket query impossible to write (NOTES.md, the filtering
// decision; TicketQueriesImpl explains the design).
//
// Visibility can't be forgotten today because TicketRepository has no method
// that returns many tickets without taking the caller, and none that accepts
// a Specification. Both are easy to undo without noticing: extend
// JpaRepository again for convenience, or add a
// "@Query List<Ticket> findByTeam(Team team)". Either would compile, run,
// pass every other test, and let a USER see everyone's tickets the day
// someone calls it. This test turns that into a red build.
//
// It checks every public method TicketRepository has, inherited ones
// included, so extending another Spring Data interface is caught too.
class TicketRepositoryShapeTest {

    // saveAll returns the tickets it was handed, not tickets it looked up, so
    // it can't reveal one the caller couldn't already see.
    private static final Set<String> RETURNS_ONLY_WHAT_IT_WAS_GIVEN = Set.of("saveAll");

    @Test
    void everyMultiTicketQueryTakesTheCallerAndNoneTakesASpecification() {
        List<String> problems = new ArrayList<>();
        for (Method method : TicketRepository.class.getMethods()) {
            List<Class<?>> parameters = Arrays.asList(method.getParameterTypes());
            if (parameters.stream().anyMatch(Specification.class::isAssignableFrom)) {
                problems.add(method + " takes a Specification, so it can run a WHERE clause without visibility");
            }
            if (returnsManyTickets(method)
                    && !RETURNS_ONLY_WHAT_IT_WAS_GIVEN.contains(method.getName())
                    && !parameters.contains(AppUser.class)) {
                problems.add(method + " returns many tickets without taking the caller");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    // A Page or List is Iterable; a Stream and an array are the other ways to
    // return several.
    private static boolean returnsManyTickets(Method method) {
        Class<?> type = method.getReturnType();
        return Iterable.class.isAssignableFrom(type) || Stream.class.isAssignableFrom(type) || type.isArray();
    }
}
