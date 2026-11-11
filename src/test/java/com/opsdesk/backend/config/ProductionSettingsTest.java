package com.opsdesk.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import com.opsdesk.backend.OpsdeskBackendApplication;

// The production profile refuses to start without each of its five required
// settings, and names the one that's missing (decision 68).
//
// Every one of them has an EMPTY default in application.yml, so the checks
// (RequiredSettingsCheck for the database, JwtService for the secret) are the
// only thing making a production start fail fast. A usable default put back
// into application.yml later would silently undo that: the app would start,
// or fail further on with a misleading error. These tests catch it.
//
// One case per setting, with all the others supplied. A single test that
// supplied nothing would only see the FIRST missing setting, and would keep
// passing if a default came back for that one (the failure would just move
// to the next). Leaving out exactly one at a time means restoring any one
// default fails exactly one case.
//
// Not a @SpringBootTest: each case starts the real application itself, with
// the prod profile and a hand-built environment that leaves out the machine's
// environment variables and system properties. So "not set" is true on any
// machine, including one that happens to have JWT_SECRET exported.
class ProductionSettingsTest {

    // Values that let a start get past each setting without any server: an
    // in-memory H2 database, and a secret long enough for JwtService.
    private static final Map<String, Object> ALL_SETTINGS = Map.of(
            "DB_URL", "jdbc:h2:mem:production-settings-test;DB_CLOSE_DELAY=-1",
            "DB_USER", "sa",
            "DB_PASSWORD", "not-a-real-password",
            "JWT_SECRET", "a-secret-only-for-production-settings-test",
            "ALLOWED_ORIGIN", "http://localhost:3000");

    @ParameterizedTest(name = "a prod start without {0} fails and names {0}")
    @ValueSource(strings = {"DB_URL", "DB_USER", "DB_PASSWORD", "JWT_SECRET", "ALLOWED_ORIGIN"})
    void aProdStartWithoutTheSettingFailsAndNamesIt(String missing) {
        Map<String, Object> settings = new HashMap<>(ALL_SETTINGS);
        settings.remove(missing);
        if (missing.equals("DB_PASSWORD")) {
            // RequiredSettingsCheck lets an in-memory H2 database have no
            // password (the test profile's has none), so this case needs a
            // real database's URL. Nothing listens on port 1: if the check
            // ever stopped refusing, the start would fail trying to connect,
            // not pass.
            settings.put("DB_URL", "jdbc:postgresql://localhost:1/opsdesk");
        }

        Throwable failure = startProductionWith(settings);

        assertThat(failure)
                .as("a prod start without %s should fail at startup", missing)
                .isNotNull();
        MissingSettingException cause = findMissingSetting(failure);
        assertThat(cause)
                .as("the failure should be a MissingSettingException naming %s, but was: %s", missing, failure)
                .isNotNull();
        assertThat(cause.getSetting()).isEqualTo(missing);
        assertThat(cause.getMessage()).startsWith(missing + " is not set.");
    }

    // An ALLOWED_ORIGIN that CORS would accept without complaint but that
    // can't work, or works too well: a trailing slash never equals the Origin
    // header a browser sends, so every browser request would fail as "blocked
    // by CORS policy"; "*" would let any website call the API. Both stop the
    // start with the value named.
    @ParameterizedTest(name = "a prod start with ALLOWED_ORIGIN={0} fails and names it")
    @ValueSource(strings = {"http://localhost:3000/", "*"})
    void aProdStartWithAnOriginThatCantWorkFails(String origin) {
        Map<String, Object> settings = new HashMap<>(ALL_SETTINGS);
        settings.put("ALLOWED_ORIGIN", origin);

        MissingSettingException cause = findMissingSetting(startProductionWith(settings));

        assertThat(cause).as("ALLOWED_ORIGIN=%s should stop the start", origin).isNotNull();
        assertThat(cause.getSetting()).isEqualTo("ALLOWED_ORIGIN");
        assertThat(cause.getMessage()).contains("is not a single origin");
    }

    // Starts the application with the prod profile and ONLY these settings.
    // Returns what it failed with, or null if it started (and closes it).
    private static Throwable startProductionWith(Map<String, Object> settings) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        Map<String, Object> properties = new HashMap<>(settings);
        properties.put("spring.profiles.active", "prod");
        environment.getPropertySources().addFirst(new MapPropertySource("production-settings-test", properties));

        SpringApplication application = new SpringApplication(OpsdeskBackendApplication.class);
        application.setEnvironment(environment);
        application.setWebApplicationType(WebApplicationType.NONE);
        try (ConfigurableApplicationContext started = application.run()) {
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static MissingSettingException findMissingSetting(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof MissingSettingException missingSetting) {
                return missingSetting;
            }
        }
        return null;
    }
}
