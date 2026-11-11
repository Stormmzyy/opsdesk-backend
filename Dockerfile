# syntax=docker/dockerfile:1
#
# The OpsDesk backend as an image, in two stages:
#   build:   a full JDK, Maven (through the wrapper) and the source -> the JAR
#   runtime: a JRE and that one JAR, nothing else
# Only what the last stage contains is shipped. The JDK, Maven, the source and
# the downloaded dependencies stay behind in the build stage.
#
#   docker build -t opsdesk-backend .
#   docker run -p 8080:8080 --env-file .env opsdesk-backend
#
# Settings (DB_URL, DB_PASSWORD, JWT_SECRET, ...) are NOT baked in: they come
# from the environment when the container starts (.env.example lists them).
# With none set, the app refuses to start and names the first one missing.

# ---- build ----------------------------------------------------------------
# JDK 21, because the build itself requires it: pom.xml's enforcer rule stops
# any older JDK before compiling (Week 8, Monday).
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# The wrapper and pom.xml first, on their own, then the dependencies. Docker
# reuses a step's cached result while the files copied before it are
# unchanged, so a change to the source alone skips straight past this
# download. The cache mount keeps Maven's local repository between builds as
# well, without it ever becoming part of an image.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

# Everything else the context holds (.dockerignore keeps it to src/ and the
# build files: no target/, no .git, no .env).
COPY . .

# -DskipTests is deliberate, not an oversight. The tests run in CI (Thursday's
# workflow) on every push, before an image is built from that commit; running
# them again here would make every image build minutes slower and need the
# test setup inside Docker. -DskipTests still COMPILES the tests, so a test
# that no longer compiles still fails this build.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && cp target/opsdesk-backend-*.jar /app/app.jar

# ---- runtime --------------------------------------------------------------
# A JRE, not a JDK: it can run Java but has no compiler or build tools, and
# Alpine keeps the base small.
FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Run as an ordinary user, not root, so a compromised app can't change the
# container's own files.
RUN addgroup -S opsdesk && adduser -S opsdesk -G opsdesk
USER opsdesk

COPY --from=build /app/app.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
