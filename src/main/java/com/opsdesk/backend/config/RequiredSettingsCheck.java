package com.opsdesk.backend.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

// Refuses to start when a database setting is missing, before anything tries
// to connect (decision 68). JWT_SECRET is checked by JwtService itself.
//
// Why a check is needed at all: application.yml has no usable default for
// DB_URL, DB_USER or DB_PASSWORD, but an unset one doesn't fail clearly on its
// own. Spring Boot's property binder leaves a placeholder it can't resolve as
// literal text, so a production start without DB_PASSWORD connected to
// PostgreSQL with the password "${DB_PASSWORD}" and failed with "FATAL:
// password authentication failed for user opsdesk" (seen in Week 8). That
// tells a newcomer the password is WRONG, not that it's missing.
// application.yml now resolves each unset one to "", and this check names it.
//
// A BeanFactoryPostProcessor because those run before any bean is created, so
// the DataSource never gets a chance to try. The failure goes through
// MissingSettingFailureAnalyzer, like JWT_SECRET's.
@Component
public class RequiredSettingsCheck implements BeanFactoryPostProcessor, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        String url = environment.getProperty("spring.datasource.url", "");
        require("DB_URL", url, "the JDBC URL of the database, e.g. jdbc:postgresql://db:5432/opsdesk");
        require("DB_USER", environment.getProperty("spring.datasource.username", ""),
                "the database user's name");
        // An in-memory H2 database (the test profile's) has no password, and
        // needs none. Any real database does.
        if (!url.startsWith("jdbc:h2:mem:")) {
            require("DB_PASSWORD", environment.getProperty("spring.datasource.password", ""),
                    "the database user's password");
        }
    }

    private static void require(String variable, String value, String whatItIs) {
        if (value.isBlank()) {
            throw new MissingSettingException(variable, "is not set",
                    "Set the " + variable + " environment variable to " + whatItIs
                            + ", or, on a development machine, run with SPRING_PROFILES_ACTIVE=dev.");
        }
    }
}
