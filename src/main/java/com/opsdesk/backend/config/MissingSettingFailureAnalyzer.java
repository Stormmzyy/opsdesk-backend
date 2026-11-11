package com.opsdesk.backend.config;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

// Turns a MissingSettingException into Spring Boot's startup failure report:
//
//   ***************************
//   APPLICATION FAILED TO START
//   ***************************
//
//   Description:
//   JWT_SECRET is not set.
//
//   Action:
//   Set JWT_SECRET to a random value ...
//
// Why: without it, the one useful sentence is the last "Caused by" of a
// 140-line stack trace that starts with "Unable to start web server" and
// "Unable to start embedded Tomcat", so someone new to the project goes
// looking for a port problem. The report is printed last, where people look,
// and it's the same shape as Spring Boot's own reports (a port already in
// use, a missing bean), so it reads as "you need to configure something",
// not "the code crashed".
//
// Registered in META-INF/spring.factories, which is how Spring Boot finds
// failure analyzers before the application context exists. Same class,
// package and registration in Boot 3.
public class MissingSettingFailureAnalyzer extends AbstractFailureAnalyzer<MissingSettingException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingSettingException cause) {
        return new FailureAnalysis(cause.getSetting() + " " + cause.getProblem() + ".", cause.getAction(), cause);
    }
}
