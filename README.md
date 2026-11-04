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

Hibernate creates and updates the tables automatically when the app starts (`ddl-auto: update`), so there is no separate schema setup step. On first start the app also seeds sample data: 6 projects, 6 users and 12 tickets, the same as the frontend's sample data. Seeding only fills empty tables, so restarting never duplicates rows.

> **If your database was created before Thursday of Week 6,** run this once. The `title` column was created as `varchar(255)`, and `ddl-auto: update` never changes the type of a column that already exists, so a title longer than 255 characters would fail with a 500 (see [NOTES.md](NOTES.md), decision 9):
>
> ```sh
> docker exec opsdesk-db psql -U opsdesk -c "ALTER TABLE ticket ALTER COLUMN title TYPE text;"
> ```
>
> A database created fresh from today's code already has the right type. To start completely from scratch instead, remove the container (`docker rm -f opsdesk-db`, which deletes all its data) and run the `docker run` command above again.

> **If your database was created before Tuesday of Week 7, recreate it.** Week 7 adds required columns: `password_hash` and `role` to `app_user`, and `created_by_id` to `ticket`. `ddl-auto: update` can't add a required column to a table that already has rows, so it logs a warning and the app starts anyway. Then every user endpoint (register, login, `GET /users`) answers 500, while the rest looks fine (see [NOTES.md](NOTES.md), decision 25). Everything in the database is sample data, so recreating it loses nothing:
>
> ```sh
> docker rm -f opsdesk-db
> docker run --name opsdesk-db -e POSTGRES_USER=opsdesk -e POSTGRES_PASSWORD=opsdesk -e POSTGRES_DB=opsdesk -p 5433:5432 -d postgres:16
> ```
>
> Then start the app, and the seeder fills the new database. This also covers the `ALTER TABLE` above, because a fresh database already has the right `title` type.

### Development password: for the seeded development database only

**`devpassword123` is a development value.** It is the password of all six seeded users, and it only exists in the seeded development database that the app creates on your machine. It is not a real password and must never be used anywhere else.

It's there so you can log in locally as any role:

| Email | Role |
| --- | --- |
| `priya.sharma@opsdesk.com` | ADMIN |
| `kwame.asante@opsdesk.com` | MANAGER |
| `daniel.mensah@opsdesk.com` | AGENT |
| `liam.carter@opsdesk.com` | AGENT |
| `marco.silva@opsdesk.com` | AGENT |
| `fatima.bello@opsdesk.com` | USER |

The database never holds the password itself. The seeder hashes it through the app's `PasswordEncoder` (BCrypt), like any other password, so each user's `password_hash` is a different BCrypt hash starting with `$2a$`. Which user owns and is assigned which seeded ticket is laid out in [NOTES.md](NOTES.md), decision 27.

### Making a user an admin

A role is never taken from a request body. Registering always creates a plain user, and no endpoint lets anyone change their own role or anyone else's. So the first admin has to be made directly in the database, with SQL in psql:

```sh
docker exec -it opsdesk-db psql -U opsdesk
```

```sql
update app_user set role = 'ADMIN' where email = '...';
```

Use the email in lowercase, because that's how emails are stored (see [NOTES.md](NOTES.md), decision 26). For local development the seeder already gives each sample user a role, so you only need this for an account you registered yourself. In a real deployment, this is how the first admin is created.

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

### What it serves

Every path starts with `/api/v1`, and every request and response is exactly as [API_CONTRACT.md](https://github.com/Stormmzyy/opsdesk-frontend/blob/main/API_CONTRACT.md) describes, including the error body (`{ "error", "message", "fields" }`).

Every endpoint except register and login needs a token: `Authorization: Bearer <token>`, from `POST /auth/login`. Without one, every request, to any path, answers `401 UNAUTHORIZED`. Who may do what is in [Permissions](#permissions).

**New in Week 7:** `POST /auth/register`, `POST /auth/login`, `GET /auth/me`, and assigning and unassigning (`PATCH` and `DELETE /tickets/{id}/assignee`). The other endpoints existed in Week 6. This week they gained the token and the permission rules, and `GET /tickets` gained the `priority` and `team` filters and sorting, next to Week 6's paging, `status` and `search`.

| Endpoint | Who | What it does |
| --- | --- | --- |
| `POST /auth/register` | anyone | Create an account. It's always a USER. |
| `POST /auth/login` | anyone | Sign in: returns a token (valid for 60 minutes) and the user. |
| `GET /auth/me` | signed in | The signed-in user, including their `role`. |
| `GET /tickets` | signed in | The tickets the caller may see, sorted by id. With `page`, `size`, `status`, `priority`, `team`, `search` or `sort`, one page of them in the contract's envelope. |
| `GET /tickets/{id}` | signed in | One ticket, or 404 if it doesn't exist or the caller may not see it. |
| `POST /tickets` | signed in | Create a ticket, with the caller as its creator. |
| `PATCH /tickets/{id}` | depends on the ticket | Change some of a ticket's fields. |
| `DELETE /tickets/{id}` | ADMIN | Delete a ticket. |
| `PATCH /tickets/{id}/assignee` | ADMIN, MANAGER | Assign a ticket: `{"assigneeId": n}`. |
| `DELETE /tickets/{id}/assignee` | ADMIN, MANAGER | Unassign a ticket. |
| `GET /projects`, `GET /projects/{id}` | signed in | Projects (read-only). |
| `GET /users` | ADMIN, MANAGER | The user directory (read-only). |

A quick check that it's up, signing in as the seeded ADMIN and listing the tickets (the password is the development one above):

```sh
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email": "priya.sharma@opsdesk.com", "password": "devpassword123"}' \
  | sed -E 's/.*"token":"([^"]+)".*/\1/')
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/tickets
```

The same `curl http://localhost:8080/api/v1/tickets` without the header answers `401`, which also shows the app is up.

### Using it with the frontend

The frontend ([opsdesk-frontend](https://github.com/Stormmzyy/opsdesk-frontend)) runs on <http://localhost:5173> and calls this API from the browser. The backend allows browser requests from **that origin only** (CORS, configured in `CorsConfig`). If the frontend runs on any other port, the browser blocks every request with "blocked by CORS policy", so keep it on 5173. `curl` and Postman aren't affected, because only browsers enforce CORS.

## Permissions

| Action | ADMIN | MANAGER | AGENT | USER |
|---|---|---|---|---|
| View tickets | all | all | all | own only |
| Create ticket | yes | yes | yes | yes |
| Edit ticket | any | any | assigned | own, if OPEN |
| Assign ticket | yes | yes | no | no |
| Delete ticket | yes | no | no | no |
| List users | yes | yes | no | no |

Authentication answers "who are you?" by checking the caller's identity, while authorization answers "what are you allowed to do?" by checking what that identity may do. In OpsDesk, authentication is logging in at `POST /api/v1/auth/login` with an email and password and then sending the returned token on every request, while authorization is this table deciding that an AGENT who is signed in still gets `403 FORBIDDEN` when trying to delete a ticket.

## Running the tests

```sh
./mvnw test
```

On Windows Command Prompt or PowerShell, use `mvnw.cmd test`.

**41 tests, and none of them need PostgreSQL or Docker.** The ones that start the application run on the `test` profile, `src/test/resources/application-test.yml`: an in-memory H2 database, built from the entities and seeded at startup, and gone when the run ends. Every new Spring test needs `@ActiveProfiles("test")`, or it connects to `opsdesk-db` instead; `TestProfileShapeTest` fails the build if one doesn't have it.

| Tests | What they cover |
| --- | --- |
| `TicketRepositoryShapeTest`, `TicketServiceShapeTest`, `TestProfileShapeTest` | Shape: no ticket query or service method can skip the caller, and no Spring test can skip the test profile. They check signatures, not behaviour. |
| `TicketServiceTest`, `TicketPermissionTest` | Mockito, no database: the single-ticket lookup, and the permission rules where a method takes the caller and could ignore her (NOTES.md, decision 63). |
| `TicketEndpointsTest` | Full application through MockMvc: every endpoint that returns a ticket, a page smaller than the data (so the COUNT query runs), the 401, 403 and 404 order, an AGENT and a USER refused on real tickets, and the LIKE escape (decisions 64 and 65). |
| `ApiContractTest` | The contract's captured responses that clients depend on, compared strictly (decision 66). Each one has a "Pinned by:" line in [API_CONTRACT.md](https://github.com/Stormmzyy/opsdesk-frontend/blob/main/API_CONTRACT.md). |
| `TicketLostUpdateTest`, `GlobalExceptionHandlerTest` | The lost update and its 409 (decision 38). `TicketLostUpdateTest` is the only check on the lost update anywhere: the walk can't see it. |
| `TicketOutputOnlyFieldsTest`, `OpsdeskBackendApplicationTests` | Output-only fields refused on edit, and the application starting at all. |

H2 is not PostgreSQL, so the tests prove the logic, not the SQL against the real database ([NOTES.md](NOTES.md), decision 62). The one check against real PostgreSQL is the permission walk. It covers the permission table and the list's filters, sorts, search and paging. With `opsdesk-db` and the app running:

```sh
bash scripts/week7-permission-walk.sh
```

The walk assumes a schema freshly built from the entities. After changing an entity, rebuild the database (the `docker rm -f opsdesk-db` step above) before trusting a red from it.

**A change is done when `./mvnw test` is green and the walk passes.** Each catches breaks the other can't (decision 65).

## Design notes

[NOTES.md](NOTES.md) records every design decision and finding so far, and why: id handling, the error check order, why requests use custom validation, pagination, the database schema, CORS, and more.
