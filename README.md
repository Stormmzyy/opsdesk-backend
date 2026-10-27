# OpsDesk backend

The backend for OpsDesk, an internal operations desk where staff raise support tickets, move them through a workflow, and see what each team is working on.

It is a Spring Boot REST API backed by PostgreSQL. It implements the API contract written in Week 4, which lives in the frontend repo: [opsdesk-frontend/API_CONTRACT.md](https://github.com/Stormmzyy/opsdesk-frontend/blob/main/API_CONTRACT.md). If this code and the contract disagree, the contract wins.

## Requirements

- Java 21 or later
- Docker

Maven is not needed. The project includes the Maven wrapper (`mvnw` / `mvnw.cmd`).

## Built on Spring Boot 4

This project uses Spring Boot 4.1.2. Most tutorials and Stack Overflow answers are still written for Spring Boot 3, and some names changed between the two. For example, `spring-boot-starter-web` is now `spring-boot-starter-webmvc`. Where the code uses something that was named differently in Boot 3, a comment next to it gives the Boot 3 name.

## Database

The app uses a PostgreSQL 16 database running in Docker. Start it once with:

```sh
docker run --name opsdesk-db -e POSTGRES_USER=opsdesk -e POSTGRES_PASSWORD=opsdesk -e POSTGRES_DB=opsdesk -p 5433:5432 -d postgres:16
```

After that, start and stop it with `docker start opsdesk-db` and `docker stop opsdesk-db`.

> **Note: the database is on port 5433, not the usual 5432.**
> On the original development machine, port 5432 is already used by another project's PostgreSQL container. Both databases run side by side, so opsdesk-db is mapped to port 5433 on the host. The app's connection URL (`src/main/resources/application.yml`) points at `localhost:5433` to match.
>
> The port change only applies outside the container. Inside it, Postgres still listens on 5432. Commands run inside the container work unchanged. For example, this opens a SQL console:
>
> ```sh
> docker exec -it opsdesk-db psql -U opsdesk
> ```
>
> If 5432 is free on your machine and you'd rather use it, change `-p 5433:5432` to `-p 5432:5432` in the command above and change the port in `application.yml` to match.

Hibernate creates and updates the tables automatically when the app starts (`ddl-auto: update`), so there is no separate schema setup step.

### Environment variables

| Variable | Purpose | Default |
| --- | --- | --- |
| `DB_USER` | Database username | `opsdesk` |
| `DB_PASSWORD` | Database password | `opsdesk` |

The defaults match the `docker run` command above, so for local development you don't need to set either one.

## Running the app

With the database running:

```sh
./mvnw spring-boot:run
```

On Windows Command Prompt or PowerShell, use `mvnw.cmd spring-boot:run` instead.

The API starts on <http://localhost:8080>. Stop it with Ctrl+C.

## Running the tests

```sh
./mvnw test
```

On Windows Command Prompt or PowerShell, use `mvnw.cmd test`.

Some tests start the whole application, so they need the database running.
