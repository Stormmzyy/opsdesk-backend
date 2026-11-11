package com.opsdesk.backend.config;

// A setting the app can't run without is missing or unusable, found at
// startup (decision 68). It carries the three things a person needs, kept
// apart so MissingSettingFailureAnalyzer can lay them out as Spring Boot's
// "APPLICATION FAILED TO START" report:
//   - setting: the environment variable, e.g. JWT_SECRET
//   - problem: what's wrong with it, e.g. "is not set"
//   - action:  what to do about it
//
// Without the analyzer this is still a normal exception with a readable
// message, so nothing depends on the report being shown.
public class MissingSettingException extends IllegalStateException {

    private final String setting;
    private final String problem;
    private final String action;

    public MissingSettingException(String setting, String problem, String action) {
        super(setting + " " + problem + ". " + action);
        this.setting = setting;
        this.problem = problem;
        this.action = action;
    }

    public String getSetting() {
        return setting;
    }

    public String getProblem() {
        return problem;
    }

    public String getAction() {
        return action;
    }
}
