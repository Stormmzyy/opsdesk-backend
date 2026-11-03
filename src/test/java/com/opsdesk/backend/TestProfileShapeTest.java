package com.opsdesk.backend;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.util.ClassUtils;

// The third shape test (NOTES.md, decision 62), next to TicketRepositoryShapeTest
// and TicketServiceShapeTest.
//
// Every test that starts a Spring context must run on the "test" profile,
// the in-memory H2. One that forgets @ActiveProfiles("test") reads
// application.yml instead and connects to the dev PostgreSQL. With the
// database stopped that fails loudly; with it running, the test silently
// reads and writes the dev data. This test makes the forgetting a red build.
//
// "Starts a Spring context" is decided by what JUnit will run it with, not by
// a list of annotation names: every Spring test annotation (@SpringBootTest,
// @DataJpaTest, @WebMvcTest, @SpringJUnitConfig, ...) carries
// @ExtendWith(SpringExtension.class), and SpringExtension is what builds the
// context. MergedAnnotations follows those meta-annotations the way JUnit
// does, so a Spring test annotation nobody has used here yet is caught too.
class TestProfileShapeTest {

    private static final String BASE_PACKAGE = "com.opsdesk.backend";

    // The Spring tests that exist today. If the scan doesn't see them, it's
    // looking in the wrong place, and an empty scan would pass while proving
    // nothing.
    private static final Set<String> KNOWN_SPRING_TESTS = Set.of(
            "OpsdeskBackendApplicationTests", "TicketLostUpdateTest", "TicketOutputOnlyFieldsTest");

    @Test
    void everySpringTestRunsOnTheTestProfile() {
        List<Class<?>> springTests = springTestClasses();

        List<String> seen = springTests.stream().map(Class::getSimpleName).toList();
        assertTrue(seen.containsAll(KNOWN_SPRING_TESTS),
                "the scan found " + seen + ", which is missing some of " + KNOWN_SPRING_TESTS);

        List<String> problems = new ArrayList<>();
        for (Class<?> test : springTests) {
            // Spring's own lookup: finds @ActiveProfiles on the class, on a
            // meta-annotation, or inherited from a superclass, as the test
            // framework itself would.
            ActiveProfiles profiles = AnnotatedElementUtils.findMergedAnnotation(test, ActiveProfiles.class);
            if (profiles == null || !Arrays.asList(profiles.profiles()).contains("test")) {
                problems.add(test.getName() + " starts a Spring context without @ActiveProfiles(\"test\"),"
                        + " so it would connect to the dev PostgreSQL");
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    // Every concrete top-level class under the base package, from the
    // compiled main and test classes, kept if JUnit would run it with
    // SpringExtension.
    private static List<Class<?>> springTestClasses() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) -> true);
        List<Class<?>> springTests = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents(BASE_PACKAGE)) {
            Class<?> type = ClassUtils.resolveClassName(candidate.getBeanClassName(), null);
            if (runsWithSpringExtension(type)) {
                springTests.add(type);
            }
        }
        return springTests;
    }

    private static boolean runsWithSpringExtension(Class<?> type) {
        return MergedAnnotations.from(type, MergedAnnotations.SearchStrategy.TYPE_HIERARCHY)
                .stream(ExtendWith.class)
                .flatMap(extendWith -> Arrays.stream(extendWith.getClassArray("value")))
                .anyMatch(SpringExtension.class::equals);
    }
}
