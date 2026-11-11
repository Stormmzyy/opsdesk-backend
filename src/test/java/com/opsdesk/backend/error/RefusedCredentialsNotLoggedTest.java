package com.opsdesk.backend.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// A refused registration or login must not write the password, or the email,
// to the log, even with Spring's web logging turned up to DEBUG (decision
// 72's never-log list).
//
// The risk isn't in our code: GlobalExceptionHandler never logs a 400. It's
// Spring's ExceptionHandlerExceptionResolver, which logs "Resolved
// [<exception>]" at DEBUG before our handler runs, and a validation failure's
// exception prints "rejected value [...]" for every failing field. Week 8
// measured it: with org.springframework.web=DEBUG, a refused registration
// logged the password in full. application.yml pins that one logger at INFO;
// this test fails if the pin is removed.
//
// The DEBUG level is set here, for this context only, because that's the
// situation being guarded: someone turns web logging up to chase a problem.
// The test also checks that DEBUG really was on (the handler mapping's
// "Mapped to ..." line for the request), so it can't pass just because
// nothing was logged. MockMvc doesn't log DispatcherServlet's "Completed
// 400", so that line can't serve.
@SpringBootTest(properties = "logging.level.org.springframework.web=DEBUG")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class RefusedCredentialsNotLoggedTest {

    // Seven characters, one short of the minimum, so registration refuses it.
    // Distinctive enough that finding it in the output can only mean a leak.
    private static final String REFUSED_PASSWORD = "Zq7-kXv";
    private static final String REFUSED_EMAIL = "refused-email-marker";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aRefusedRegistrationLogsNeitherThePasswordNorTheEmail(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Refused","username":"refused_user",
                                 "email":"%s","password":"%s"}
                                """.formatted(REFUSED_EMAIL, REFUSED_PASSWORD)))
                .andExpect(status().isBadRequest());

        assertThat(output.getAll()).as("web DEBUG logging was on")
                .contains("Mapped to com.opsdesk.backend.auth.AuthController#register");
        assertThat(output.getAll()).doesNotContain(REFUSED_PASSWORD).doesNotContain(REFUSED_EMAIL);
    }

    // Login validates presence and type only, so the way to make the password
    // a failing field is to send it as something other than text.
    @Test
    void aRefusedLoginDoesNotLogThePassword(CapturedOutput output) throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"ama@example.com","password":{"value":"%s"}}
                                """.formatted(REFUSED_PASSWORD)))
                .andExpect(status().isBadRequest());

        assertThat(output.getAll()).as("web DEBUG logging was on")
                .contains("Mapped to com.opsdesk.backend.auth.AuthController#login");
        assertThat(output.getAll()).doesNotContain(REFUSED_PASSWORD);
    }
}
