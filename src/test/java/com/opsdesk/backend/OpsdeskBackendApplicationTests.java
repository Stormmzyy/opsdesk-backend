package com.opsdesk.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// Starts the whole application, seeder included, against the test profile's
// in-memory H2 (src/test/resources/application-test.yml), so it passes with
// no PostgreSQL running.
@SpringBootTest
@ActiveProfiles("test")
class OpsdeskBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
