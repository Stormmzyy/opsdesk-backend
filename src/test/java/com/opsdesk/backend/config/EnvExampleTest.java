package com.opsdesk.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

// .env.example is the list a deployment works from, so it must name exactly
// the settings the application requires: one missing, and a deployment fails
// at startup with nothing telling it the variable exists; one extra, and
// someone hunts for a setting nothing reads.
//
// "Required" has one definition here: an empty default, ${NAME:}, in
// application.yml (decision 68). That's what makes RequiredSettingsCheck and
// JwtService refuse a start without it. Three lists must agree, and this test
// compares all three rather than trusting anyone to keep them in step:
//   - the ${NAME:} placeholders in src/main/resources/application.yml,
//   - ProductionSettingsTest.ALL_SETTINGS, whose keys that test proves are
//     each refused when missing,
//   - the NAME= lines in .env.example.
// SPRING_PROFILES_ACTIVE is in .env.example too but isn't required (unset
// means production), so it's the one expected difference.
//
// Maven runs tests from the project folder, so the paths are relative to it.
class EnvExampleTest {

    private static final Pattern EMPTY_DEFAULT = Pattern.compile("\\$\\{([A-Z][A-Z0-9_]*):}");
    private static final Pattern ASSIGNMENT = Pattern.compile("(?m)^([A-Z][A-Z0-9_]*)=");

    @Test
    void envExampleNamesExactlyTheRequiredSettingsPlusTheProfile() throws IOException {
        Set<String> requiredByYaml = names(EMPTY_DEFAULT, read("src/main/resources/application.yml"));
        Set<String> provenByTest = new TreeSet<>(ProductionSettingsTest.ALL_SETTINGS.keySet());
        Set<String> inEnvExample = names(ASSIGNMENT, read(".env.example"));

        assertThat(provenByTest)
                .as("ProductionSettingsTest.ALL_SETTINGS against application.yml's empty defaults")
                .isEqualTo(requiredByYaml);

        Set<String> expected = new TreeSet<>(requiredByYaml);
        expected.add("SPRING_PROFILES_ACTIVE");
        assertThat(inEnvExample)
                .as(".env.example against application.yml's empty defaults plus SPRING_PROFILES_ACTIVE")
                .isEqualTo(expected);
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    private static Set<String> names(Pattern pattern, String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
