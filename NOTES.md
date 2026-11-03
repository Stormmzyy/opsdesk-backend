# Design notes

Decisions made while building the backend, and why. The API contract they refer to is [opsdesk-frontend/API_CONTRACT.md](https://github.com/Stormmzyy/opsdesk-frontend/blob/main/API_CONTRACT.md).

## Decisions

### 1. Ticket ids are `Long`, from a counter that never goes backwards

The contract says ids are positive whole numbers chosen by the server, and the frontend types them as `number` (`id: number` in `src/features/tickets/types.ts`). It only accepts digit-only ids in a URL (`src/utils/parseId.ts`), and rejects a ticket whose `id` isn't a number. A `Long` fits all of that with no frontend changes. A UUID or string id would break the frontend and need a new API version (`/api/v2`).

Ids come from a counter that only ever increases, so **an id is never reused**, even after a delete. This is stricter than the frontend's mock API, which gives a new ticket `max(id) + 1`. In the mock, deleting the newest ticket and then creating one hands out the deleted ticket's id again. The contract says ids are "never reused", so the backend follows the contract, not the mock. Once tickets move into PostgreSQL, the database's id sequence gives the same guarantee.

### 2. `POST /tickets/reset` is not built

The mock API has a `POST /tickets/reset` endpoint that restores the sample tickets. The contract lists it as mock-only and says the backend must not build it, so we don't. The frontend's "Reset to sample data" button goes away once the real backend is in use.

### 3. Errors are checked in the contract's order: 404, then `BAD_REQUEST`, then `VALIDATION_ERROR`

When a request has more than one problem, the contract says the server answers with the first of these that applies:

1. **404 `NOT_FOUND`**: the ticket doesn't exist.
2. **400 `BAD_REQUEST`**: the body can't be read (not JSON, or not a JSON object).
3. **400 `VALIDATION_ERROR`**: the body was read but fields break the rules (every bad field listed).

This matters when the global exception handler goes in. A `PATCH /tickets/999` with an invalid body must answer **404**, not a validation error. Spring normally reads and validates the body *before* the controller method runs, which would give the 400 first. So the existence check has to come before validation, not after it. Decision 6 says how.

## Planned changes

### 4. The controller returns a response DTO, never the `Ticket` entity (done Wednesday)

Originally planned for Thursday, because the deliverable says no entity is ever returned from a controller. It was brought forward to Wednesday, when adding relationships to the entity broke the API (decision 8).

Every endpoint that returns a ticket now returns `TicketResponse`, a record with exactly the contract's six fields in the contract's order (`id, title, description, priority, status, team`). The conversion lives in one place, `TicketResponse.from(Ticket)`, and every endpoint goes through it.

The service and repository didn't change. They still work with `Ticket` as a Java object, so the swap was a change to the controller alone. That was only possible because of the rule set on Tuesday: nothing outside the controller may depend on the JSON shape of `Ticket`.

### 5. The in-memory repository is replaced by a `JpaRepository` tomorrow, and `TicketService` should not change

`TicketRepository` is a class today: a `List`, an id counter, and `synchronized` methods. Tomorrow all of that is replaced by an interface extending `JpaRepository<Ticket, Long>`, and PostgreSQL takes over storage, id generation and concurrency.

The in-memory class deliberately uses the same method names as `JpaRepository` (`findAll`, `findById`, `save`, `existsById`, `deleteById`, `count`), so `TicketService` can stay exactly as it is. **If the switch forces any change in `TicketService`, the layering was wrong.** That gets reported and discussed, not quietly patched.

### 6. A `PATCH` to a missing ticket with an unreadable body must answer 404 (fixed on Thursday)

**The problem.** Spring reads and converts the `@RequestBody` *before* the controller method runs. If the JSON can't be read, Spring throws `HttpMessageNotReadableException` and the method never runs, so the existence check inside it never happens either. Today, `PATCH /api/v1/tickets/999` with a broken body answers **400**, but the contract's check order (decision 3) says it must be **404**, because ticket 999 doesn't exist.

**The fix, on Thursday, in the global exception handler.** When the handler catches `HttpMessageNotReadableException`, it doesn't answer 400 straight away. It first reads the id from the request path and checks whether that ticket exists:

- If the id isn't valid or the ticket doesn't exist, it answers **404 `NOT_FOUND`**.
- Only if the ticket exists does it answer **400 `BAD_REQUEST`**.

That puts the existence check first, as the contract requires, without changing how Spring reads bodies. **Not fixed yet**: today's behaviour is the 400.

## Findings

### 7. The repository swap needed no code change, but it broke "sorted by id"

**What happened.** On Wednesday the in-memory `TicketRepository` became an interface extending `JpaRepository<Ticket, Long>`. Decision 5 predicted that `TicketService` would not need to change, and it didn't need to in order to *compile*: every method it calls exists on `JpaRepository` with the same name and signature, and the tests passed.

But the service had been depending on something the old repository never promised. The contract says `GET /tickets` is sorted by id. The in-memory version kept that order only because tickets were appended to a `List` in id order. `findAll()` never said anything about order. `JpaRepository.findAll()` sends a `SELECT` with no `ORDER BY`, and without one, PostgreSQL makes no promise about row order.

**Why it only showed up after a PATCH.** On a freshly seeded table, the rows sit on disk in the order they were inserted, which happens to be id order, so the list looked right. But PostgreSQL never changes a row in place: an `UPDATE` writes a new version of the row, usually at the end of the table, and marks the old one dead. A query with no `ORDER BY` reads the table front to back (a sequential scan) and returns rows in their physical order, so the updated row moved to the end:

```
list ids BEFORE patch: 1 2 3 4 5 6 7 8 9 10 11 12
PATCH 1 -> 200
list ids AFTER patch:  2 3 4 5 6 7 8 9 10 11 12 1
```

**The fix: one line in `TicketService.listTickets()`.**

```java
// before
return ticketRepository.findAll();
// after
return ticketRepository.findAll(Sort.by("id"));
```

**Why the fix belongs in the service, not the repository.** "Sorted by id" is a rule about what the API returns, so it belongs in the layer that knows about the contract. The repository's job is storage, and it should return what it's asked for. Overriding `findAll()` inside the repository to always sort would have left `TicketService` untouched, but it would hide the rule in the storage layer again. That's the same mistake that caused this bug, just with a different store underneath.

**The lesson.** Keeping the method names the same made the swap compile, but the old implementation had a behaviour nobody wrote down (insertion order), and the service relied on it. Order, uniqueness and atomicity are worth asking about whenever storage changes.

**For Thursday's pagination.** When `page`, `size` and `status` go in, the sort has to go into the paged query too, as in `PageRequest.of(page - 1, size, Sort.by("id"))`. Otherwise the same bug comes back one page at a time. Pages cut from an unordered result can skip or repeat tickets between page 1 and page 2, and the order inside a page can change after any update.

### 8. Adding relationships to the entity changed the API, because the controller returned the entity

**What happened.** On Wednesday, `Ticket` gained two relationships: `project` and `assignee`, both `@ManyToOne(fetch = LAZY)`. The controller was still returning the `Ticket` entity, and Jackson writes every getter into the JSON. So the response silently gained two fields that the contract doesn't have:

```
GET /api/v1/tickets/2   (no project, no assignee)
200  {"title":"Order new laptops for the March starters",...,"team":"Operations","assignee":null,"id":2,"project":null}
```

**Tickets that had a project or assignee didn't just gain fields, they failed.** `GET /api/v1/tickets/1` and the whole list answered **500**:

```
HttpMessageNotWritableException: Could not write JSON: Could not initialize proxy [com.opsdesk.backend.user.AppUser#2] - no session
```

With `fetch = LAZY`, the ticket's `assignee` isn't loaded. It's a proxy, a stand-in that runs a query the first time something reads it. Jackson read it while writing the response. But `open-in-view` is `false`, so the database session closed when the service method returned, before Jackson started writing. The proxy had no session to query with, so the request failed.

**The underlying mistake.** The entity is the database's shape, and the DTO is the API's shape. While the controller returned the entity, those were the same thing, so **a database change silently became an API change**. Nobody edited the controller, and the contract broke anyway.

**The fix.** `TicketResponse`, a record with exactly the contract's six fields, built in one place (`TicketResponse.from`), which only reads plain columns and never touches the relationships. Now the entity can gain columns and relationships, and the JSON only changes when someone edits `TicketResponse` on purpose. (Decision 4.)

**Alternatives considered and rejected:**

- **`@JsonIgnore` on `getProject()` and `getAssignee()`.** It works, but it puts JSON rules inside the entity, the class that's supposed to describe the database. It also only protects the fields someone remembered to annotate. The next relationship added to `Ticket` would leak into the API in exactly the same way unless someone remembered again.
- **`open-in-view: true`, or `FetchType.EAGER`.** Either one stops the 500, because the proxy can then load or is never a proxy. But `project` and `assignee` would still be in the response, now as whole nested objects, so the contract is still broken. And every request would run extra queries to load data the API doesn't return: up to two more per ticket on `GET /tickets`.

### 9. What Hibernate actually built: three things to know about the schema

Found on Wednesday by reading the tables with `\d ticket`, `\d project` and `\d app_user` in psql.

**1. Every enum column has a CHECK constraint, and `ddl-auto: update` will never change it.**

Hibernate generated a CHECK constraint for each `@Enumerated(EnumType.STRING)` column, listing the allowed values:

```
"ticket_priority_check" CHECK (priority::text = ANY (ARRAY['LOW', 'MEDIUM', 'HIGH', 'URGENT']))
```

That's good protection, because the database refuses a bad value even if a bug gets one past the Java code. But `ddl-auto: update` only *adds* things. It never changes or drops an existing constraint. So adding a new constant, say `CRITICAL` to `Priority`, compiles fine and the app starts fine. Then the first insert of a `CRITICAL` ticket fails with a constraint violation.

The real fix is a migration tool such as **Flyway**: versioned SQL scripts that change the schema on purpose, including altering this constraint. `ddl-auto: update` is a development convenience. It isn't something to run against a production database.

**Thursday: the first time this limitation actually bit.** The contract sets no maximum title length, but `title` was created as `varchar(255)`. A 300-character title passed validation, then failed in PostgreSQL (`value too long for type character varying(255)`), and the API answered 500. Adding `columnDefinition = "text"` to the entity fixes fresh databases. But `ddl-auto: update` won't change the type of a column that already exists, so the dev database needed a hand-run `ALTER TABLE ticket ALTER COLUMN title TYPE text;`. Anyone else's existing database needs the same command, and nothing will tell them. In a real project, this is exactly a Flyway migration (`V2__ticket_title_text.sql`): written once, committed, and applied automatically to every database, including production.

**A design point for v1.1, not for now.** An unbounded title is questionable API design. Real APIs normally set a maximum length on every free-text field, to bound storage, keep lists readable, and limit abuse. That needs a contract change: a maximum in the Ticket table, a new validation message, and the frontend's form enforcing the same limit. Until then, `text` matches the contract as written.

**2. Constraint names are Hibernate's hashes, and they show up in error messages.**

The foreign keys and unique constraints have generated names such as `fkon3g4edms44nvfti4skwns3vn` and `uk1j9d9a06i600gd43uu3km82jw`. PostgreSQL puts the name in its error messages exactly as it is: a duplicate email fails with "violates unique constraint uk1j9d9a06i600gd43uu3km82jw". They can be given readable names through `@JoinColumn(foreignKey = @ForeignKey(name = ...))` and `@Table(uniqueConstraints = @UniqueConstraint(name = ..., columnNames = ...))`.

**Not fixed now.** Renaming an existing constraint means dropping it and creating it again, which `ddl-auto: update` won't do, and nothing this week depends on matching a constraint name. It becomes relevant in **Week 8**, when a duplicate email on registration has to become the contract's `409 CONFLICT`. Detecting that reliably may mean recognising which constraint failed.

**3. The foreign key columns have no index.**

PostgreSQL creates an index for every primary key and unique constraint, but **not for foreign keys**. So `ticket.project_id` and `ticket.assignee_id` are unindexed. At 12 rows that makes no difference. It matters for two things:
- queries like "all tickets in project X", which have to read the whole `ticket` table
- deleting a project, because PostgreSQL then scans `ticket` to check that no row still points at it

Revisit in **Week 7**, when filtering and pagination are done properly.

### 10. Request fields are `Object`, not enums or `String`

`CreateTicketRequest` and `UpdateTicketRequest` declare every field as `Object`, and two custom constraints (`@TextField` and `@OneOf`) check them. The usual choices each break part of the contract:

- **An enum field** (`Priority priority`): `"priority": "SUPER"` fails inside Jackson while it's still reading the body. The client gets an unspecific `BAD_REQUEST`, instead of the contract's `VALIDATION_ERROR` naming the `priority` field.
- **A `String` field** (`String title`): Jackson quietly turns `"title": 123` into `"123"`. That's a valid 3-character title, so it passes, when the contract says it must fail with `"Title must be text."` An object or array sent as the title makes Jackson fail outright, again with no field-level message.
- **One message per field.** The contract's `fields` object holds exactly one message per field. The standard `@NotBlank` plus `@Size(min = 3)` would give an empty title *two* messages, in no guaranteed order. The custom constraints check in a fixed order (missing, not text, empty, too short) and stop at the first problem.

With `Object` fields, Jackson accepts whatever JSON type arrives, and validation decides what's wrong and says which field.

**The cost.** The request records aren't strongly typed: `title` is an `Object`, not a `String`. So each record carries small conversion methods (`titleText()`, `priorityValue()`, `teamValue()` and so on) that cast and convert after validation has passed. Type safety arrives one step later than usual: at the controller, after `@Valid`, rather than when Jackson reads the body. The allowed values in each `@OneOf` also repeat the enum constants, because an annotation can't ask an enum for its values. If they drift apart, the new value is rejected (it fails safe).

Trimming happens in each record's compact constructor, so validation only ever sees the trimmed value, and the value validated is the value saved. `priority`, `status` and `team` are deliberately not trimmed: the contract says `" Support "` is invalid.

### 11. A `PATCH` with `"title": null` is treated as if `title` were left out

In `UpdateTicketRequest`, a field that wasn't sent arrives as `null`, and `null` means "leave this field alone". A field explicitly sent as `null` also arrives as `null`, so the two can't be told apart. `{"title": null}` changes nothing and returns 200, when arguably it should be rejected as `"Title must be text."`

Telling "sent as null" apart from "not sent" needs a different way of reading the body: reading it as a raw JSON tree, or a wrapper type that records whether a field was present at all.

**Not fixed.** The frontend never sends `null` for a field; it leaves the field out.

### 12. An unsupported method returns 404, not HTTP's 405

A request using a method the endpoint doesn't support, such as `PUT /api/v1/tickets/1`, answers **404 `NOT_FOUND`** ("Nothing exists at PUT /api/v1/tickets/1."). HTTP's usual answer would be **405 Method Not Allowed**.

The contract's error code list says it's complete ("The backend must not invent others without updating this document"), and it has no 405 and no code to go with one. Returning 405 would mean inventing a status and a code the frontend doesn't know about. Treating "no endpoint for this method and path" as "a path that doesn't exist" keeps every response inside the contract.

**The cost.** A developer who calls the wrong verb sees "not found" for a resource that does exist, which can send them looking for the wrong problem. If that becomes a real annoyance, the fix is a contract change adding `METHOD_NOT_ALLOWED` with 405, not a quiet change in the backend.

The same reasoning sends a body that isn't sent as JSON (Spring's 415 Unsupported Media Type) to 400 `BAD_REQUEST`: the body can't be read as a JSON object, which is exactly the contract's definition.

### 13. Pagination on `GET /tickets`: the backend has it, the frontend doesn't use it yet (decided Thursday)

**The backend implements `page`, `size`, `status` and `search`** and returns the contract's envelope:

```json
{ "items": [], "page": 1, "size": 10, "totalItems": 0, "totalPages": 0 }
```

The contract's section on this moved from "Planned for v1.1" into v1, labelled SPECIFIED (the backend must build it, but the frontend doesn't call it yet). Every rule and message stayed exactly as written, including `search`, so the section moved unchanged. The contract change went through its own PR in `opsdesk-frontend` (branch `docs/contract-pagination-v1`).

**No parameters still returns the plain array.** That's the contract's own rule: the envelope is returned only when at least one of `page`, `size`, `status` or `search` is sent. It's why the frontend needs no change. Its `getTickets` query still sends no parameters and still gets the array it expects.

**The frontend keeps filtering, searching and paging in the browser**, as it does now. The Tickets page could move to server-side pages, but the Dashboard and Teams pages need *every* ticket to count correctly (tickets per status, open tickets per team). Moving them to a paged endpoint would mean either fetching every page or adding count endpoints. Neither is worth it this week.

**Deferred on purpose, not forgotten.** Week 7 covers pagination and filtering done properly. At 12 tickets, fetching everything is the right call. At 500 it wouldn't be.

### 14. A page number too large for a `long` is accepted, and the response echoes `Long.MAX_VALUE`

The contract caps `size` at 100 but puts no upper limit on `page`, so any whole number of 1 or more is a valid page. `?page=99999999999999999999` is therefore accepted, and it's past the last page, so it gets a 200 with `"items": []` and the real totals. But the number doesn't fit in a Java `long`, so the backend caps it, and the response echoes `"page": 9223372036854775807` (`Long.MAX_VALUE`) rather than the value that was sent.

No real client can send such a number: JavaScript can't represent an integer above 2⁵³ exactly, so the frontend could never produce one or read one back correctly.

**Noted, not fixed.** Rejecting it would mean adding a maximum to `page`, which is a contract change. A v1.1 would probably cap `page` the way `size` is capped.

### 15. The User shape's `phone`, `address` and `company` have no source in OpsDesk

The contract's User object carries `phone`, `address { street, suite, city, zipcode }` and `company { name }`. They were inherited from JSONPlaceholder, the public API the frontend's Users page read from while there was no backend. OpsDesk has no source for any of them: `AppUser` stores only `name`, `username` and `email`, and the frontend's `employees.ts` has no phone, street or company to seed from.

`GET /users` returns empty strings for all of them. The contract permits that: it's the same rule it uses for a newly registered user ("fields the register form doesn't collect come back as empty strings"). Nothing is invented. If the Users page looks broken with blanks, the likely fix is the frontend not rendering an empty address block, not the backend making data up.

**The real question is whether these fields belong in the contract at all.** That's a v1.1 decision, not this week's: keep them and add real storage and a way to fill them in, or remove them from the User shape.

### 16. Two simultaneous edits to the same ticket can silently overwrite each other

`TicketService.updateTicket` isn't transactional, and `Ticket` has no `@Version` field. An update reads the ticket, changes the fields that were sent, and saves the whole ticket back. If two people edit the same ticket at the same time, both read the same starting version, and **the second write wins: the first person's change is silently lost.** Neither of them gets an error. This is the classic **lost update** problem.

For example, one person moves ticket 3 to `RESOLVED` while another changes its title. Both read the ticket as `OPEN` with the old title, and whichever saves second writes back its own copy, undoing the other's change.

**Noted, not fixed.** The two standard fixes are:
- **Optimistic locking:** a `@Version` field on `Ticket`. Every save checks that the version hasn't changed since the ticket was read, and the second writer gets a conflict instead of overwriting. The contract's `CONFLICT` code (409) would fit, but the contract currently defines it narrowly ("registering an email that's already taken"), so that description would need widening first.
- **A transaction with the right isolation level** around the read and the write, so the database itself refuses or serialises the clash.

**When it matters:** as soon as more than one person uses the system. Today it's one developer and 12 sample tickets.

### 17. The HTTP layer and the repository have no automated tests

The only tests are `contextLoads`, which proves the app starts, and the two `TicketServiceTest` unit tests, which test the service with a mocked repository. There are **no controller tests and no repository tests.**

Everything about the HTTP layer was proven **by hand with curl**: every status code, every error body (`{ error, message, fields }`), the field messages, the 404-before-400 check order (decision 6), and the pagination edge rules. Nothing automated protects any of it. **A change to `GlobalExceptionHandler` could break the contract's error shape, and every test would still pass.** The same goes for the repository: a broken query, or a sort that's been dropped (decision 7), would go unnoticed.

Spring's tools for this are already on the classpath, through the per-feature test starters Initializr added on day one:
- **`@WebMvcTest`** starts only the web layer (controllers, the exception handler, JSON and validation), with the service mocked, and checks real requests and responses through `MockMvc`. In Boot 4 it's `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest`; in Boot 3 it was `org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest`.
- **`@DataJpaTest`** starts only JPA and the repositories, against a real database, to check queries such as `findByStatus` and the paged, sorted search. In Boot 4 it's `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`; in Boot 3 it was `org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest`.

**Noted, not fixed.** The curl checks recorded in each PR are the specification these tests should encode, starting with the error shape and the check order, because those are the easiest to break without noticing.

## Week 7: auth and permissions

Decided before Tuesday of Week 7, when sign-in and roles arrive. These are decisions only: **API_CONTRACT.md is not edited until Thursday**, as the Week 7 guide specifies. Until then, the contract still describes the old design, and the decisions below say where it is going to change.

### 18. Auth moves to `POST /api/v1/auth/register` and `POST /api/v1/auth/login`

**What the contract says now.** Register is `POST /users` and login is `POST /sessions`. Its Paths convention explains why: "No verbs in paths. The HTTP method says what happens", so logging in is "create a session" and registering is "create a user", "not `/login` and `/register`".

**Why it's overridden.** Two reasons:
- The Week 7 guide specifies `/auth/register` and `/auth/login`.
- Register can't require sign-in, because nobody has an account yet. But `GET /users` will require sign-in. With register at `POST /users`, one path would have two different auth rules depending on the method: open for `POST`, closed for `GET`. That's easy to get wrong in the security config, and easy to break later. Under `/auth/**`, the rule is simple: everything under `/auth` is open, and everything else needs a signed-in user.

**What doesn't change.** The "no verbs in paths" convention still holds everywhere else. `/auth/register` and `/auth/login` are the one deliberate exception, and the contract will say so on Thursday, rather than leaving the convention and the paths contradicting each other. `POST /users` and `POST /sessions` go away; `/sessions` also comes out of the Paths list.

### 19. The login response gains a token

**What the contract says now.** `POST /sessions` returns only `{ "user": { ... } }`. It says how the session reaches the browser is "a Week 8 decision" and gives two options: an `httpOnly` cookie, or a token in the response body.

**Decided: a JWT in the response body.** The frontend sends it back on every request in an `Authorization: Bearer <token>` header. The body gains the token alongside the existing `user` object. The exact field names follow the guide and are fixed in the contract on Thursday.

### 20. Login answers 200, not 201

**What the contract says now.** `201 Created`, because "a session was created".

**Why that no longer holds.** A JWT is stateless. The server checks a token by its signature alone, and keeps no record of sessions, so logging in creates nothing on the server. With nothing created, 201 would be wrong, so a successful login is **200 OK**: the request worked, and the body is the result.

Register keeps **201**, because it really does create something: a new row in `app_user`.

### 21. `403 FORBIDDEN` joins the error codes and the status codes

The contract says its error code list "is the complete list. The backend must not invent others without updating this document." The permission table (README, Permissions) needs an answer for **"we know who you are, and the answer is no"**: for example, a signed-in AGENT trying to delete a ticket. 401 doesn't fit, because it means "we don't know who you are". So the contract gains:

- **`FORBIDDEN`, 403**, in the error codes table: the caller is signed in, but their role or their relationship to the ticket doesn't allow this action.
- **403 Forbidden** in the status codes table, mapped to `FORBIDDEN`.

At the same time, `UNAUTHORIZED` loses its "(From Week 8, also: …)" wording, because it now covers both cases: a login with the wrong email or password, and a request that needs a signed-in user but has no valid token.

### 22. The check order gains 401 and 403

**What the contract says now.** It only gives the order 404, then `BAD_REQUEST`, then `VALIDATION_ERROR` (decision 3). It says nothing about 401 or 403.

**Decided.** When a request has more than one problem, the server answers with the first of these that applies:

1. **401 `UNAUTHORIZED`**: no valid token. This is checked before anything else, because the security filter runs before the request reaches any controller. It also applies to paths that don't exist (decision 24).
2. **403 `FORBIDDEN`, for role rules**: the caller's role can never do this, whichever ticket it is. For example, a MANAGER deleting a ticket, or an AGENT listing users. The rule doesn't depend on the ticket, so it's checked before the ticket is looked up. A MANAGER deleting ticket 999 gets 403, not 404. That reveals nothing, because the answer is the same for every id.
3. **404 `NOT_FOUND`**: the ticket doesn't exist, **or** it exists but the caller isn't allowed to see it (decision 23).
4. **403 `FORBIDDEN`, for ticket rules**: the caller can see the ticket but can't make this change. For example, an AGENT editing a ticket that isn't assigned to them, or a USER editing their own ticket that is no longer `OPEN`.
5. **400 `BAD_REQUEST`**: the body can't be read.
6. **400 `VALIDATION_ERROR`**: the body was read, but fields break the rules.

**Why the 404 and ticket-rule 403 come before the body checks.**
- **The 404 stays ahead of the 400s for consistency, not for security.** That order has been in the contract since Week 4 and built since Week 6 (decision 3). It was justified there by a `PATCH` to a missing ticket, which should say "not found" rather than complain about the body. Putting the 400s first wouldn't leak anything either: a broken body would answer 400 for a hidden ticket, a missing one and the caller's own alike, so they couldn't be told apart. But reordering now would rewrite a rule that's already built, documented and tested, for no gain.
- **Permission is about who; the body is about what.** There's no point telling a caller their body is invalid for a change they aren't allowed to make at all.

**On the two open endpoints, `/auth/register` and `/auth/login`,** there's no token to check, so steps 1 to 4 don't apply. Login checks `BAD_REQUEST`, then `VALIDATION_ERROR`, and only then the credentials. A wrong email or password is a **401** that comes last, because the credentials can't be checked until the body has been read.

**What this costs to build.** Spring reads the body *before* the controller method runs, so steps 3 and 4 don't happen in that order on their own. The fix from decision 6 already puts the 404 before `BAD_REQUEST`: when a body can't be read, the exception handler checks first whether the ticket exists. That check now also has to ask whether the caller can see the ticket, and whether they can change it. Assigning has its own endpoint (decision 27), so its role check happens before the body is read, like every other role rule.

### 23. Fetching a ticket you aren't allowed to see answers 404, not 403, on purpose

A USER can only see their own tickets. When a USER asks for someone else's ticket by id (`GET /tickets/7`), the answer is **404 `NOT_FOUND`**, exactly the same as for an id that doesn't exist. **403 would be the wrong answer**, because it would tell the caller that ticket 7 exists and belongs to someone else, which lets anyone count and probe other people's tickets.

The same rule applies to `PATCH` on a ticket the caller can't see, and `GET /tickets` simply leaves those tickets out of the list.

**This looks like a bug to anyone who doesn't know it's deliberate.** A tester who sees a ticket in the database and gets 404 for it will reasonably report it. So it is written down in two places:
- **in the contract on Thursday**, as part of the 404 rule
- **in a code comment, at the visibility check itself**, added with that check on Tuesday, so nobody "fixes" it to 403

### 24. A request with no valid token to a path that doesn't exist answers 401, not 404

**What happens today.** `GET /api/v1/nothing-here` answers **404 `NOT_FOUND`** with "Nothing exists at /api/v1/nothing-here." That comes from the `NoResourceFoundException` handler in `GlobalExceptionHandler`, built in Week 6. The contract describes it: 404 covers "a path that doesn't exist".

**What changes.** The security filter checks the token before Spring works out which controller a path belongs to (decision 22, step 1). So a caller with no valid token who asks for a path that doesn't exist gets **401 `UNAUTHORIZED`**, not the Week 6 404 body. A signed-in caller still gets the 404.

**Deliberate, and a security improvement.** A caller who isn't signed in gets the same 401 for every path, real or not, so they can't map out which endpoints exist. The same applies to decision 12: an unsupported method such as `PUT /tickets/1` answers 401 instead of 404 when there's no token.

**It changes documented and tested behaviour, so both are updated on Thursday.**
- **The contract:** the 404 rule says "a path that doesn't exist" with no condition. It gains "for a signed-in caller", and the check order says 401 comes first.
- **The tests:** no automated test covers the unknown-path 404 today. The only tests are `contextLoads` and `TicketServiceTest` (decision 17). It was checked by hand with curl, and that curl check is what changes: it now needs a token to get the 404, and without one it should expect 401. Any controller test written before Thursday that requests an unknown path has to send a token or expect 401.

### 25. The development database is recreated, not migrated

**The problem.** Tuesday adds `passwordHash` and `role` to `AppUser`, both non-null, and `createdBy` to `Ticket` (decision 27). `ddl-auto: update` turns that into `alter table app_user add column … not null`, and PostgreSQL refuses it on a table that already has rows, because the six seeded users would have no value for the new column. Hibernate logs the failure as a warning and starts anyway. The columns are never created, but the app looks healthy: the ticket endpoints still work, and `contextLoads` still passes. Meanwhile every endpoint that loads a user (`GET /users`, register, login) answers 500. The seeder can't repair this either, because it skips any table that isn't empty.

**The decision.** Recreate the database: `docker rm -f opsdesk-db`, then the README's `docker run` command. Nothing is lost, because everything in the database is seed data. A fresh database gets both columns as `NOT NULL` from the start, and the seeder creates the six users with a password hash and a role. The README tells anyone with an older database to do the same, next to the existing `ALTER TABLE` note.

**Rejected:**
- **Hand-run SQL** (add the columns as nullable, fill them in, then `SET NOT NULL`): it needs real bcrypt hashes from the app's `PasswordEncoder`, and it's one more manual step that every database needs and nothing announces.
- **Adding the columns as nullable, then switching to `nullable = false`**: `ddl-auto: update` never tightens an existing column, so the database would never get `NOT NULL`, and the entity and the table would silently disagree (decision 9).

**Flyway is still the real answer**, as decision 9 says: a versioned migration would make this change once and apply it to every database automatically. It's still out of scope this week.

### 26. Email is stored and compared in lowercase

**The problem.** The contract says email is "compared without regard to case", but the unique constraint on `app_user.email` is case-sensitive. `Ama@example.com` and `ama@example.com` could both register, so two accounts could exist for one address. That breaks the contract's 409 rule (an email that's already taken must be refused), and it makes login ambiguous: two accounts could match one email.

**The decision.** The backend lowercases the email at every boundary:
- **Register** lowercases the email *before* checking for a duplicate and before saving it. The stored value is therefore always lowercase, and the existing unique constraint enforces case-insensitive uniqueness by itself.
- **Login** lowercases the email before looking it up.

**Rejected: a functional unique index on `lower(email)`.** It would enforce the rule in the database itself, even against a bug in the code. But the stored value would still be whatever case the user typed, so every lookup would have to remember to compare with `lower()` on both sides, and the value a response returns would be unpredictable. Lowercasing on the way in gives one stored form that every query can match exactly.

### 27. The seed data is designed to test every cell of the permission table

**`createdBy` arrives on Tuesday, not Wednesday.** `Ticket` gains a `createdBy` column (a many-to-one link to `app_user`, like `assignee`) on Tuesday. The ownership rules that use it ("own only", "own, if OPEN") are still enforced on **Wednesday**, as the guide specifies. Adding the column a day early has two benefits:
- **One database recreate, not two.** Adding a required column to a populated table fails in the same way decision 25 describes. Adding `createdBy` on Wednesday would mean recreating the database a second time.
- **The seeder lays out ownership once.** It sets roles, assignments and creators together, from the tables below.

**The seeder implements these tables. It doesn't invent data.** If the seeder and this decision disagree, the seeder is wrong.

#### Users

| User | Role | Why this role |
| --- | --- | --- |
| Priya Sharma | ADMIN | She has no assigned tickets, so she can be the admin without anyone's work moving. |
| Kwame Asante | MANAGER | He creates the unassigned ticket the manager tests use (#2), and keeps his own assignment (#7). |
| Daniel Mensah | AGENT | He has two assigned tickets (#1, #10). He is the main agent for the "edit assigned" tests. |
| Liam Carter | AGENT | One assigned ticket (#3). It belongs to a *different* agent from Daniel's. |
| Marco Silva | AGENT | One assigned ticket (#5). |
| Fatima Bello | USER | The only USER. She creates #4 and #9, and is assigned nothing. |

That's 1 ADMIN, 1 MANAGER, 3 AGENT and 1 USER. **Only one assignment changes:** #9 used to be assigned to Fatima, and is now unassigned. A USER doesn't work tickets, and the ticket is already `RESOLVED`. Every other assignment stays as it was in Week 6.

#### Tickets

| # | Title | Status | Created by | Assigned to | Here to prove |
| --- | --- | --- | --- | --- | --- |
| 1 | Login page times out on slow networks | OPEN | Priya | Daniel | AGENT edits a ticket assigned to them: allowed |
| 2 | Order new laptops for the March starters | OPEN | Kwame | none | MANAGER edits but can't delete; USER can't see someone else's ticket (404, decision 23); MANAGER assigns |
| 3 | Customer cannot reset their password | OPEN | Liam | Liam | AGENT edits a ticket assigned to *another* agent: refused |
| 4 | Update the holiday policy document | OPEN | **Fatima** | none | USER edits her own `OPEN` ticket: allowed |
| 5 | Fix rounding error in monthly invoices | IN_PROGRESS | Kwame | Marco | (background) |
| 6 | Add dark mode to the admin panel | IN_PROGRESS | Priya | none | AGENT edits a ticket assigned to nobody: refused |
| 7 | Office air conditioning is broken | IN_PROGRESS | Liam | Kwame | (background) |
| 8 | Reply to the backlog of refund emails | RESOLVED | Liam | none | (background) |
| 9 | Schedule onboarding for new hires | RESOLVED | **Fatima** | none | USER edits her own ticket that isn't `OPEN`: refused |
| 10 | Rotate the expired API keys | RESOLVED | Daniel | Daniel | (background) |
| 11 | Close the Q3 expense reports | CLOSED | Marco | none | (background) |
| 12 | Replace the broken meeting room screen | CLOSED | Kwame | none | (background) |

Titles, descriptions, priorities, teams, projects and statuses are unchanged from Week 6. Only `createdBy` is new, and #9 loses its assignee.

#### The checks: one or more per permission-table cell

| Cell | Caller | Request | Expected |
| --- | --- | --- | --- |
| View tickets: ADMIN, MANAGER, AGENT see all | Priya, Kwame, Daniel | `GET /tickets` | 200, all 12 |
| View tickets: USER sees own only | Fatima | `GET /tickets` | 200, only #4 and #9 |
| View tickets: USER, someone else's ticket | Fatima | `GET /tickets/2` | **404**, the same body as `GET /tickets/999` (decision 23) |
| Create ticket: everyone | each of the four roles | `POST /tickets` | 201, with `createdBy` set to the caller |
| Edit ticket: ADMIN any | Priya | `PATCH /tickets/3` | 200 |
| Edit ticket: MANAGER any | Kwame | `PATCH /tickets/2` | 200 |
| Edit ticket: AGENT, assigned | Daniel | `PATCH /tickets/1` | 200 |
| Edit ticket: AGENT, not assigned to anyone | Daniel | `PATCH /tickets/6` | **403** |
| Edit ticket: AGENT, assigned to someone else | Daniel | `PATCH /tickets/3` | **403** |
| Edit ticket: USER, own and `OPEN` | Fatima | `PATCH /tickets/4` with `title` or `description` | 200 |
| Edit ticket: USER, own and `OPEN`, a field she may not change | Fatima | `PATCH /tickets/4` with `status`, `priority` or `team` | **403** |
| Edit ticket: USER, own but not `OPEN` | Fatima | `PATCH /tickets/9` | **403** |
| Edit ticket: USER, not own | Fatima | `PATCH /tickets/2` | **404** (she can't see it) |
| Assign ticket: ADMIN, MANAGER | Priya, Kwame | `PATCH /tickets/2/assignee`, to Liam | 200 |
| Assign ticket: AGENT | Daniel | `PATCH /tickets/1/assignee`, to Marco | **403** |
| Assign ticket: USER | Fatima | `PATCH /tickets/4/assignee`, to anyone | **403** |
| Delete ticket: ADMIN | Priya | `DELETE` a ticket created during the test | 204 |
| Delete ticket: MANAGER | Kwame | `DELETE /tickets/2` | **403** (role rule, decision 22) |
| Delete ticket: AGENT, USER | Daniel, Fatima | `DELETE /tickets/1`, `DELETE /tickets/4` | **403** |
| List users: ADMIN, MANAGER | Priya, Kwame | `GET /users` | 200 |
| List users: AGENT, USER | Daniel, Fatima | `GET /users` | **403** |

**Rules for running these checks, so the seed data stays usable:**
- **Never delete a seeded ticket to prove that ADMIN can delete.** The seeder only fills empty tables, so a deleted seed ticket never comes back without recreating the database. Create a ticket, then delete that one.
- **Change `title`, not `status`, in the edit checks.** Moving #4 off `OPEN` would destroy the only "own and `OPEN`" case.
- **Assigning #2 changes who it's assigned to.** Run the agent checks before it, or set it back afterwards.

**Two questions this table left open, now decided:**

**Assigning is its own endpoint: `PATCH /api/v1/tickets/{id}/assignee`.** It is not a field in the ticket `PATCH` body. The reasons:
- **Assigning and editing are separate rows of the permission table, with different permissions.** An AGENT may edit a ticket assigned to them but may not assign it. A combined request could mix a change the caller is allowed to make with one they aren't, and the server would have to refuse the whole request or apply half of it.
- **The role check can run before the body is read.** That's where the check order puts it (decision 22, step 2). AGENTs and USERs are refused by method and path alone, without the server reading or validating anything.
- **It keeps the "no verbs in paths" convention.** `assignee` is a noun: the request changes one field of the ticket, just as `PATCH /tickets/{id}` changes others.

**A USER editing her own `OPEN` ticket may change `title` and `description` only.** She can't change `status`, `priority` or `team`. **The reason is a trap in the wider reading.** If a USER could change the status, she could move her own ticket to `IN_PROGRESS`. At that moment it is no longer `OPEN`, so she loses the right to edit it. She also can't move it back, because reopening it would itself be an edit. One request would lock her out of her own ticket, with no way back. Limiting her to title and description means nothing she is allowed to do can take that right away.

**`createdBy` is required, not nullable.** Every ticket has a creator by definition. The seeder supplies one for each seeded ticket, and the logged-in user supplies one for each new ticket, so there is no legitimate null. Making the column `NOT NULL` has two benefits:
- **The ownership rules carry no null checks.** "Own only" and "own, if OPEN" compare `createdBy` with the caller, and that comparison can never meet a ticket with no owner.
- **An ownerless ticket can't be created by accident.** A code path that forgets to set the creator fails on insert instead of quietly saving a ticket nobody owns.

### 28. The filter uses the role from the database, not the role in the token

**The decision.** `JwtAuthFilter` takes only the email from the token. On every request it loads that user from the database and uses the role stored there. The token's `role` claim is never used for a permission decision. It exists only as a hint for the frontend, so it can show or hide actions without another request.

**The reason.** A role changed in the database takes effect on the next request. If the filter trusted the claim instead, a demoted user would keep their old role until the token expired, up to 60 minutes (`app.jwt.expiry-minutes`). A token can't be withdrawn once it's issued, so the claim would outrank the database for that whole time.

**The cost** is one database query per authenticated request. That is the deliberate trade: one indexed lookup by email (the unique constraint is an index) in exchange for permissions that are never out of date. Loading the user also catches a deleted account: a valid token for a user who no longer exists is treated as no valid token, and the request gets a 401.

### 29. Passwords are limited to 72 bytes of UTF-8

**The problem.** BCrypt only uses the first 72 bytes of a password. Spring Security 7 refuses longer input with `IllegalArgumentException: password cannot be more than 72 bytes` rather than silently truncating it. Refusing is the better behaviour, because with silent truncation two different long passwords that share their first 72 bytes would hash identically. But the exception reached the catch-all handler, so registering with an 80-character password answered **500**.

**The decision.** Register validates the length in the contract's error shape: a password over 72 bytes is a `400 VALIDATION_ERROR` with the message `Password must be at most 72 bytes.`
- **The limit is counted in bytes, not characters.** In UTF-8 one character takes one to four bytes, so 72 bytes can be anywhere from 18 to 72 characters.
- **The message says bytes for the same reason.** "At most 72 characters" would be wrong for non-ASCII input: a 30-character password could be refused while the message said it was allowed.
- Login doesn't validate the length (it only checks that both fields are present). An over-long password there simply doesn't match, and gets the ordinary 401.

**Rejected: pre-hashing** (SHA-256 the password, then BCrypt the result). It removes the limit, but adds a step to every hash and every check, for an edge case nobody hits.

**The contract change goes in on Thursday.**

### 30. Usernames are stored and compared in lowercase, like emails

The contract's username rule allows letters in either case, and the unique constraint on `app_user.username` is case-sensitive, so `Ama.Owusu` and `ama.owusu` could both register. That has now been changed: the username is lowercased before the duplicate check and before saving, exactly as decision 26 does for email.

**The reason.** The display name lives in the separate `name` field, so the username is purely an identifier. Identifiers that differ only by case invite impersonation: `@Ama.Owusu` would look like `@ama.owusu` to anyone reading it.

### 31. A taken username answers 409 with its own message

**The problem.** The contract's 409 only covers a taken email. A taken username would reach the database's unique constraint and answer 500.

**The decision.** A taken username answers **`409 CONFLICT`** with `An account with this username already exists.` When both the email and the username are taken, the email message wins: the caller most likely already has an account, and that's the more useful thing to tell them.

**The contract change goes in on Thursday.**

### 32. Register reveals which emails have accounts. This is a known trade-off, not a bug

Login is deliberately silent about which emails exist. A wrong password and an unregistered email get one answer, and login runs a throwaway BCrypt check for an unknown email so that the response time doesn't give it away either. **Register's 409 gives the same information away directly**: try to register an address, and a 409 means it has an account.

**This is inherent to having a 409 at all.** The alternatives are:
- **Answering 201 regardless, and sending an email** ("you already have an account"). That needs email sending, which OpsDesk doesn't have.
- **A generic error**, which leaves a real user unable to tell why they can't register.

**Recorded, not fixed,** because the contract specifies the 409.

### 33. Authorization checks run in a `HandlerInterceptor`, before the body is read

**The problem.** Decision 22's check order puts three checks ahead of both 400s: step 2 (the role 403), step 3 (404) and step 4 (the ticket-rule 403). None of them needs the body. The id is in the URL, the caller is in the token, and the creator, assignee and status are on the ticket's row. They do need Spring to have chosen the handler, because that's when the path variables exist. So they have to run in the window **after the handler is chosen and before the body is bound**.

**The decision.** `TicketEditAccessInterceptor.preHandle` runs those checks for `PATCH /tickets/{id}` and for both methods of `/tickets/{id}/assignee`. It decides nothing itself: it calls the same `TicketService` rules the service runs again after binding, so the rules live in one place. A malformed body gets the same 404 or 403 as a well-formed one, with no special case.

**Rejected:**
- **`@PreAuthorize` alone.** It wraps the method call, and Spring reads and `@Valid`s the `@RequestBody` to build the method's arguments before it can call the method. So it runs *after* the body. This was measured, not assumed: with only `@PreAuthorize` on the assign endpoint, a USER sending a broken body got **400**, not 403. `@PreAuthorize` stays on the gated endpoints as the declaration at the door, and as a backstop if the interceptor's path patterns ever stop matching.
- **The security filter chain.** It runs before Spring has chosen the handler, so it has no path variables. Doing the 404 lookup there would mean repeating Spring's routing.
- **The exception handler,** which was Tuesday's way to put the 404 before the 400 (decision 6). It worked only by looking the ticket up again in the error path, and putting authorization there would have meant a second copy of every rule. All of it now lives in the interceptor, and GlobalExceptionHandler only translates exceptions.

**The cost** is that a PATCH loads the ticket twice, once in the interceptor and once in the service. The interceptor's path patterns also have to cover every path that reaches those handlers. `/api/v1/tickets/*` doesn't match `/tickets/{id}/assignee`, because `*` is one path segment, which is why the second pattern exists.

### 34. A USER's field restriction means "present and different", not "present at all"

**The decision.** On her own OPEN ticket, a USER may change only `title` and `description` (decision 27). A request is refused only if `status`, `priority` or `team` would actually **change**: present in the body **and different** from the current value. Sending a field with the value it already has is not an attempt to change it.

**Rejected: "present at all",** refusing any request that contains those fields. **The real frontend would break.** Its Edit page sends the whole form on every save, including priority, team and status (`EditTicketPage.tsx`, `{ id, ...values }`). Under "present at all", every edit Fatima made through the real UI would get a 403, even one that changed only the title. The rule exists to stop her *moving* her ticket out of OPEN, and a no-op can't move it.

**What it costs.** Whether a request is accepted depends on the ticket's current state, not only on the request. That can't be used to escape the rule: once the ticket isn't OPEN, the ticket-level rule refuses her edits anyway.

### 35. The 404 quotes the id exactly as it appeared in the URL, so a hidden ticket and a missing one are byte-identical

**The decision.** Both refusals are built in one place, `TicketService.getVisibleTicket`, from the raw text of the id in the URL: "no ticket has this id" and "a ticket has this id, but you may not see it" (decision 23). `/tickets/02` answers `No ticket with id "02".` whether ticket 2 is missing or merely hidden. Fetching Kwame's ticket as Fatima and fetching ticket 999 give responses that differ only in the id each echoes, headers included.

**Rejected: building the hidden one's message from the loaded ticket's id.** For `/tickets/02` that would say `"2"`, while a genuinely missing `/tickets/0999` says `"0999"`. The difference would show the id resolved to a real ticket, which is the leak decision 23 exists to prevent.

**One behaviour changed.** Before this, a missing ticket whose id had leading zeros was reported with the parsed number (`/tickets/0999` said `"999"`). It now quotes the URL's text, which is what the contract says to do. Text that isn't an id at all (`/tickets/abc`) already quoted the URL, and still does.

### 36. Unassigning is `DELETE /tickets/{id}/assignee`, not `{"assigneeId": null}`

**The decision.** `PATCH /tickets/{id}/assignee` with `{"assigneeId": n}` assigns, and `assigneeId` is required. `DELETE /tickets/{id}/assignee` unassigns. Both use the same role gate, and both are no-ops when the end state already holds.

**Rejected: `null` meaning "unassign".** Decision 11 records that in this API's PATCH bodies, `null` and "not sent" can't be told apart. If `null` unassigned, then `{}` would either unassign too (a forgotten field silently taking a ticket away from whoever was working on it), or this endpoint would have to read `null` differently from every other PATCH. A DELETE of the assignee says "remove the assignee", with nothing to misread.

### 37. The assign validation messages reveal which user ids exist and each one's role. Safe only while assigning is ADMIN and MANAGER

**The decision.** An assignee id that matches no user answers `No user with id 99.`, and a USER as assignee answers `A USER can't be assigned tickets.` Both are 400 `VALIDATION_ERROR` on `assigneeId`, not 404s: the URL's resource, the ticket, exists, and what's wrong is a value in the body.

**What they reveal.** Together, the two messages let a caller find out which user ids exist and which of them are USERs. **That's safe only because assigning is limited to ADMIN and MANAGER,** who can already list every user with `GET /users`.

**If assigning is ever opened to AGENT,** these messages become a user-enumeration oracle for a role that can't list users. They would then have to become one message that says neither. A comment at the messages says so.

**Rejected: one generic message now.** It would protect nobody today, because the only callers can see the whole user list, and it would make a real mistake harder for an ADMIN or MANAGER to diagnose.

### 38. "A USER may not change status" is a gate on the request, not a guarantee about the stored row

**The problem.** The permission table's USER field rule is enforced on every request: no request from a USER that changes status, priority or team is accepted. But `updateTicket` isn't transactional. The ticket it loads is detached by the time `save()` runs, and `save()` on a detached entity is a JPA `merge`, which copies **every field of the copy read at the start** onto the row. Measured: a title-only edit produced `update ticket set assignee_id=?, created_by_id=?, description=?, priority=?, project_id=?, status=?, team=?, title=? where id=?`.

So if Kwame moves #4 to IN_PROGRESS between Fatima's read and her save, her title edit writes OPEN back, and neither of them gets an error. This is decision 16's lost update, reaching a permission rule.

**The decision.** Record the cell honestly: the rule is a **gate on the request, not a guarantee about the stored row**. The fix is Thursday's, in two parts:
- **`@Version`** on `Ticket`, so a write based on a stale read gets a conflict instead of overwriting.
- **`@DynamicUpdate` plus a transactional load-and-modify,** so a title-only edit writes only `title`.

**Rejected half-fixes:**
- **Narrowing the setters** so a USER's request only ever applies title and description. That doesn't close it: the merge writes every column regardless of which setters ran.
- **`@DynamicUpdate` alone.** The detached merge copies the stale status onto the fresh row, so status still counts as changed and is still written. It only works together with the transactional load-and-modify, where "changed" means "changed by this request".
- **Either of those without `@Version`.** They stop an unchanged column being overwritten, but not two people changing the *same* column. `@Version` catches that.

**Accepted tradeoff: `@Version` conflicts on the row, not the column.** There is one version number for the whole ticket, and every UPDATE checks it, whichever columns it writes. So two requests that touch *different* columns of the same ticket still conflict. If Daniel moves #1, which is assigned to him, to IN_PROGRESS at the same moment as Kwame reassigns it, whichever commits second gets a **409**, even though `@DynamicUpdate` means the two UPDATEs would never have overwritten each other. `TicketLostUpdateTest` shows exactly this: a title-only write is refused because someone else changed the status.

This is deliberate:
- **The cost** is a false conflict on a rare race between two people working the same ticket. The loser reloads and tries again.
- **The alternative is worse.** A per-column check (Hibernate's `OptimisticLockType.DIRTY`) would let those two through, but it would also let through changes that are only safe *together*. For example, an assign decided on the basis of a status that has since moved on. It also only works with `@DynamicUpdate` on every write, and it's much harder to reason about than one number per row.

If false 409s ever show up in the WARN log often enough to matter, revisit it then, with the log as evidence.

**Implemented on Thursday.** `Ticket` has `@Version` and `@DynamicUpdate`. `updateTicket` and `assignTicket` are `@Transactional`, so the ticket stays managed from load to commit. A stale write answers **409 `CONFLICT`** with a fixed message, and the detail is logged at WARN, like the 403.

Measured with SQL logging. Fatima's title-only PATCH on #4:
- Before: `update ticket set assignee_id=?, created_by_id=?, description=?, priority=?, project_id=?, status=?, team=?, title=? where id=?`
- After: `update ticket set title=?,version=? where id=? and version=?`

SELECTs per request:

| Request | Before | After | What went |
| --- | --- | --- | --- |
| `PATCH /tickets/{id}` | 4 | 3 | the merge's re-read of the ticket |
| `PATCH /tickets/{id}/assignee` | 5 | 4 | the merge's re-read |
| `DELETE /tickets/{id}/assignee` | 4 | 3 | the merge's re-read |

What's left for PATCH: the caller (by email), the ticket in the interceptor, and the ticket in the service. Assign adds the assignee by id. The interceptor's read is outside the service's transaction, so the ticket is read twice. That's harmless: the service re-checks `canEdit` and `canAssign` on its own copy, so a change between the two reads is judged against what the service is about to write. The permission walk passed 55 of 55 and 20 of 20 on a fresh database after the change.

The assign path was already safe once `@Version` existed: merge compares the detached copy's version with the row's and throws. `@Transactional` there was for consistency and the query count.

**The race test needs the dev PostgreSQL.** `TicketLostUpdateTest` runs against the dev PostgreSQL on port 5433, in a throwaway `lost_update_test` schema that it creates and drops. `./mvnw test` already needed that database for `contextLoads`, so the dependency isn't new, but it's now a real test rather than a smoke test. It is the test **most likely to resist Friday's H2 profile**, because what it proves is about real transactions:
- two connections at once (`REQUIRES_NEW`)
- one transaction's commit being visible to another that's already open
- the schema properties it sets

An in-memory H2 may lock, isolate or create schemas differently. This hasn't been tried. If it won't run on H2, keep it on PostgreSQL (or Testcontainers) rather than weaken it until it passes.

### 39. Decided now for Thursday: `assigneeId` on the ticket shape is readable on output and refused on input

**The problem.** Thursday adds `assigneeId` to the ticket shape, because today no response can show who a ticket is assigned to (decision 27's assign cells could only be proved from the row). The obvious way to add a field is to read and write it everywhere. **If `assigneeId` binds on `PATCH /tickets/{id}`, it bypasses the assign endpoint and its ADMIN-or-MANAGER gate.** An AGENT may edit a ticket assigned to them, so they could send `{"assigneeId": <own id>}` on any ticket they're allowed to edit, or move one of theirs to someone else. Nothing would refuse it, because the edit rules don't know the field means assigning.

**The decision.** `assigneeId` appears in every ticket **response**. In a request to `PATCH /tickets/{id}` or `POST /tickets`, it is **refused** with 400 `VALIDATION_ERROR` on `assigneeId`, pointing to the assign endpoint. Assigning stays exclusively on `/tickets/{id}/assignee`, where the gate is.

**Rejected:**
- **Binding it on the ticket PATCH**, for the reason above.
- **Silently ignoring it, as `createdBy` is.** A client has no legitimate way to set `createdBy`, so ignoring it hides nothing. A client sending `assigneeId` on the ticket PATCH is trying to assign, and there *is* a right way to do that. Ignoring it would answer 200 while not doing what was asked, and the client would find out later, if at all.

  *Superseded by decision 40:* `createdBy` is now refused too, so all three output-only fields follow one rule.

**Thursday's tests for it:**
- An AGENT sends `PATCH /tickets/{id}` with `{"assigneeId": <someone else's id>}` on a ticket assigned to them. They get **400**, and the row's `assignee_id` is unchanged.
- The same AGENT sends `{"assigneeId": <their own id>}` on a ticket assigned to nobody. They get **403**: they can't edit that ticket at all, and the interceptor refuses before the body is read (decision 33). The row's `assignee_id` is still NULL.

Together these cover both ways the field could be abused: handing off a ticket they hold, and claiming one they were never given.

**`version` joins it** on Thursday's contract change: on every ticket response, refused on input, for the reasons in decision 40.

### 40. `@Version` only catches simultaneous writes. A stale full-form save can still silently revert other people's changes

**What the guard compares.** `@Version` compares the version the **server** loaded with the row's version at the moment of writing. The server loads the ticket fresh at the start of each request, microseconds before it writes. So the guard fires only on **true simultaneity**: two requests whose load-to-commit windows overlap. That's what decision 38 closed, and what `TicketLostUpdateTest` proves.

**The case it doesn't catch: a stale form.** Someone opens a ticket's edit form, someone else changes the ticket and saves, and minutes later the first person saves. The second request loads the ticket *after* the other commit, so the server sees no conflict. What happens next depends on the role, because the frontend's Edit page sends the **whole form** on every save. Every field the person didn't touch carries the value they *saw* when the page loaded, which may now be old.

- **A USER is saved by the field restriction.** Fatima opens #4. Kwame changes its priority (or moves it on from OPEN). Fatima fixes the title and saves. Her form sends the old priority, which is now "present and different" (decision 34), so she gets a **403** and nothing is written. If Kwame moved the ticket out of OPEN, `canEdit` refuses her anyway. Either way, nothing is lost.

  The answer misleads her, though: she's told she lacks permission when she only touched the title. Reloading shows why. Only a field she *may* change (title, description) can overwrite someone else's change silently, and only if they changed the same one.
- **ADMIN, MANAGER and AGENT have no field restriction, and that is where the exposure is widest.** Daniel opens #1, which is assigned to him. Kwame moves it to IN_PROGRESS. Daniel fixes a typo in the title and saves. His form sends the status he saw, `OPEN`. It's different from the row's, so `@DynamicUpdate` counts it as a change, and **Kwame's status is silently reverted**, with no 409 and nothing in the log. That holds for every field, not just the one being edited. The exposure is widest for exactly the roles whose changes matter most, and what gets reverted is status, not just title.
- **The same field, changed on purpose by both, is a genuine conflict for every role.** The later save wins silently.

**There is no server-only fix.** A full-form PATCH is **ambiguous by construction**. When the body says `"status": "OPEN"`, the server can't tell "I want the status to be OPEN" from "the status was OPEN when my page loaded". Both are the same bytes. `@Version` and `@DynamicUpdate` are everything the backend can do alone. They stop two requests in flight from losing each other's writes (decision 38), but they can't recover an intent the request never carried.

**What we can claim.** Concurrent requests can't lose each other's writes. A client can still save on top of a state it never saw, and from the full-form page, that can revert fields the person never meant to touch.

**The preferred fix: the frontend sends only the fields that were changed.** The Edit page compares the form with the ticket as loaded, and PATCHes only the differences. Then:
- **The stale-form revert disappears.** Daniel's typo fix sends only `title`, so Kwame's status is never in the request.
- **Decision 34 can become the simpler "present at all".** "Present and different" existed only to keep the full-form page working for a USER. Once the page sends only changes, any `status`, `priority` or `team` in a USER's request is an attempt to change it, and can be refused as such.

One change on the other side of the wire resolves both compromises. It needs no contract change to the request shape (a PATCH with fewer fields is already valid), only a frontend change, so it is raised with the frontend rather than made here.

What it leaves is the genuine same-field conflict. That's the remaining case, and it's tolerable: both people meant to change that field, and the later one did.

**The rejected alternative: `If-Match` (or a `version` field in the PATCH body).** The client sends back the version it read. The server compares it with the row instead of its own fresh load, and answers 409 when they differ. That closes even the same-field case. It loses on cost:
- a contract change: an ETag or `version` on every response, and a rule for requests that omit it
- the frontend storing the version and sending it back on every edit and assign
- a "someone changed this, reload" screen

That's a round of contract and frontend work, for a case that matters most on title and description, where a silent overwrite is annoying (someone retypes a sentence) rather than dangerous. Revisit it if a field arrives whose silent overwrite would be dangerous.

**`version` goes on the ticket shape now, output-only, next to `assigneeId`** (decision 39). Without it in responses, nothing can ever send it back, so `If-Match` could never be added without another contract change. Friday's tests also can't read it, and they need to: versions only go up, and a restore is a write too, so the walk leaves them well above 0.

On input, it's refused with a 400, not ignored. Two reasons:
- **It doesn't pretend.** A client sending `version` on a PATCH believes it's asking for a conflict check. Ignoring it would answer 200 without doing what was asked: decision 39's own argument.
- **It's forward compatible.** If `If-Match`-style checks ever arrive through a `version` field, relaxing a 400 into an accepted field breaks nobody, because no working client sends it. Ignoring it now would let clients get into the habit of sending it, harmlessly. On the day it started meaning something, they would suddenly get conflict checks, and 409s, they never asked for. A refusal keeps the field unused until it means something.

**One rule for all three output-only fields.** `createdBy`, `assigneeId` and `version` appear in responses, and **every one of them is refused with a 400 on input**, on `POST /tickets` and `PATCH /tickets/{id}`. That's one rule in the contract instead of "refuse two, ignore one".

This replaces decision 39's "ignore `createdBy`". The frontend was checked first (`opsdesk-frontend` main, `70dc092`), and nothing sends it:
- **Create** sends the form's `TicketFormValues`.
- **Edit** builds its form from exactly five named fields (title, description, priority, team, status), not by spreading a `Ticket`.
- **The board's move** sends only `status`.

So refusing it breaks no existing client. If the Edit page were ever changed to `{...ticket}`, it would now fail loudly with a 400, rather than quietly echoing server-owned fields back.

**Found while checking: unknown fields are accepted silently today.** Measured against the running app:
- `PATCH /tickets/12` with `{"titel": "..."}` (a typo in the key) answered **200** and changed nothing.
- `{"version": 0}` and `{"createdBy": 5}` on a PATCH answered 200, ignored.
- A `POST` carrying `createdBy`, `assigneeId` and `version` answered 201 with all three dropped.

Nothing in this repo configures that. It's Spring Boot's auto-configured mapper, which doesn't fail on unknown properties, and nothing here overrides it. So `createdBy` was never "declared to be ignored": it's ignored because every unknown field is, typos included.

**And the contract promises it.** Its Conventions section says: "Fields the server doesn't recognise in a request body are **ignored**, not rejected". `POST /tickets` says the same of `id` and `status`. So the silence is today's contract, not an accident of configuration. It's also decision 39's "answer 200 while not doing what was asked", for any misspelt key.

**Two consequences for Thursday's contract rewrite:**
- **Refusing the three output-only fields is an exception to that rule.** The rewrite must either name them as exceptions, or reverse the rule. It can't do neither.
- **Reversing the rule is a change of status code** (200 → 400) for bodies that are accepted today. The contract's own versioning paragraph lists that as breaking. The frontend sends no unrecognised fields (checked above), so in practice it breaks nobody. It still has to be a deliberate decision in the rewrite, not something that slips in.

If the rule is reversed, the likely way to do it:
- Make the mapper fail on unknown properties.
- Map that failure to `VALIDATION_ERROR` on the offending field's name, rather than letting it fall into the generic `BAD_REQUEST` ("The request body must be a JSON object."), which would be the wrong message.
- `createdBy`, `assigneeId` and `version` then need no code of their own: they're unknown to the request records. Each gets a message that names the right way to do the thing, such as the assign endpoint for `assigneeId`.

Not done yet: it's part of the contract change.

### 41. Ticket list queries can't be run without the caller's visibility

**The danger.** Moving filters to composed Specifications is the most dangerous change of the week, because it fails silently. Suppose a Specification filters by status but forgets `created_by_id`. Then a USER's `?status=OPEN` returns everyone's open tickets, and the response looks perfectly normal.

**The decision: make the unsafe version impossible to write, not merely avoided.**
- **Nothing outside one class can run a ticket Specification.** `TicketRepository` extends the bare `Repository`, not `JpaRepository` or `JpaSpecificationExecutor`. It declares only single-row reads and writes (`findById`, `save`, `saveAll`, `delete`, `deleteById`, `count`), so `findAll()` and `findAll(spec)` don't exist. The unused `findByStatus` is gone too: it listed tickets with no caller.
- **The only multi-ticket queries take the caller.** These are `findVisible(actor, filters, order[, pageable])` and `countVisible(actor, filters)`, on a Spring Data fragment. Its implementation, `TicketQueriesImpl`, holds the only Specification executor for tickets, in a private field.
- **Visibility always comes first.** The one private method that builds the WHERE clause starts from `visibleTo(actor)` and ANDs the filters onto it. A new filter can only narrow what the caller sees.
- **Nothing outside that class can even build a ticket Specification.** The factories are private there. Filters arrive as plain data (`TicketFilters`), and the order as a closed enum (`TicketOrder`).
- **A test keeps it that way.** `TicketRepositoryShapeTest` fails if `TicketRepository` gains a method that returns many tickets without taking the caller, or that takes a `Specification` or a `Sort`. I proved it can fail: putting `JpaRepository` and `JpaSpecificationExecutor` back produced 17 named violations, including `findOne(Specification)` and the query-by-`Example` methods.

**What it can't stop:** hand-written JPQL against the `EntityManager`. That's a deliberate act that shows in review, not a forgotten `.and()`.

**Measured.** As Fatima, `?status=OPEN&size=1` gives `totalItems: 1`, and both queries carry her visibility condition first:
- `select … from ticket t1_0 where t1_0.created_by_id=? and t1_0.status=? order by …`
- `select count(t1_0.id) from ticket t1_0 where t1_0.created_by_id=? and t1_0.status=?`

Her `?team=Operations` returns 0, though three Operations tickets exist. The permission walk passed 55 of 55 and 20 of 20 on a fresh database after the change.

**What the walk doesn't cover.** It calls only plain `GET /tickets`, so it proves the visibility path for every role but not the filters or the sort. Those were proved by hand. Friday's tests should cover them, as a USER especially.

(Spring Data skips the COUNT when a first page comes back less than full, because it already knows the total. A test that wants to see the count query has to fill the first page, for example with `size=1`.)

### 42. Filters: status, priority and team, and the four edge cases

`GET /tickets` takes `status`, `priority` and `team`, alongside `page`, `size`, `search` and `sort`. Any one of them switches the response to the envelope, as the four original parameters do.

| Case | Answer | Why |
| --- | --- | --- |
| Unknown value: `status=BANANA`, `team=Marketing`, `status=open` | 400 `VALIDATION_ERROR` on the parameter, with the same message and exact spelling as the field in a body. `team` takes its label (`Operations`). | A dropped bad value would quietly become "no filter" and return more than asked. One vocabulary for bodies and filters means clients never translate. |
| Sent twice: `status=OPEN&status=CLOSED` | 400: "Status can only be sent once." | Spring joins repeated values with commas. `search=printer&search=laptop` silently searched for `printer,laptop`: a 200 with the wrong answer. First or last would be arbitrary, and OR would be a feature the contract doesn't have. Refusing keeps OR addable. |
| Empty: `status=`, `priority=`, `team=`, `page=`, `size=`, `sort=` | 400 | `""` isn't an allowed value. Treating it as "not sent" would give everything to a client that blanked it by mistake. |
| Empty `search=` | Every visible ticket, as the envelope | Every text contains `""`. This is the contract's own definition, not a special case. |
| Unknown parameter | 400, see decision 44 | |

A repeated parameter's message wins over its other messages: the check is put first among the binder's validators. Every failing parameter is listed, in field order.

### 43. Search covers title and description: a closed list, and wildcards are literal

**The closed list.** `search` matches `title` or `description`, ignoring case, and nothing else. Both appear in every response the caller gets for those tickets, and search only runs inside her visible rows.

The reason it's closed is the same as the sort whitelist. A search over a field the caller can't read is an **oracle**: the row count reveals whether a value exists without the value ever appearing in a response. Any field added later must already be in the response for the same tickets.

**Wildcards are literal.** `%`, `_` and the escape character `\` are escaped, and the query uses `like ? escape '\'`. Measured as Priya:
- `?search=%` returned 0 rows before any ticket contained a `%`.
- With a ticket titled `Apply the 15% discount to plan_b (C:\billing)`, `%`, `15%`, `n_b`, `\` and `C:\b` each find only that ticket.
- `nxb` and `C:b` find nothing, so `_` is not "any one character" and `\` is not skipped.

### 44. Unknown query parameters are refused where an endpoint takes parameters. Unknown body fields stay ignored

**The general rule.** An endpoint that accepts query parameters rejects unknown ones with a 400 `VALIDATION_ERROR`, keyed by the unknown name: `"There is no query parameter called \"stauts\"."`. An endpoint that accepts none ignores everything.

Today only `GET /tickets` takes parameters, so the behaviour is the same as "only `GET /tickets`". The general form means no amendment when `GET /users` gains a filter.

**Why split it from the body rule.** The two cases differ in severity:
- **An ignored body field is a write that silently didn't happen.** The client can detect that by reading the resource back.
- **An ignored query parameter is a read that silently returned a superset.** `?stauts=OPEN` returned every visible ticket, which looks exactly like a correct answer. No client can detect it.
- **On an endpoint with no parameters,** an unknown one can't change the answer, so there's nothing to misread. Rejecting it there would cost compatibility for nothing.

**What it breaks.** Anything that appends a cache-busting or tracking parameter (`?_=1712345`, `utm_*`) to `GET /tickets` now gets a 400. Nothing does today: neither the frontend, which calls `/tickets` bare, nor the walk script. A client that needs cache-busting should use `Cache-Control`. A reserved prefix can be let through later; relaxing a 400 breaks nobody.

**Rejected:**
- **Keep ignoring everywhere** (the contract until now). The superset is undetectable.
- **Reject unknown fields everywhere, bodies included.** That turns a detectable, low-severity case into a contract break for writes, in the same change. It remains open for the contract rewrite (decision 40).

**Goes in the contract.** It replaces "Any other query parameter is ignored" in `GET /tickets`.

### 45. Sorting: a closed list of five fields, never a property path

**The parameter.** `sort=<field>` or `sort=<field>,asc|desc`, where the field is one of `id`, `title`, `status`, `priority` or `team`. Anything else is a 400 `VALIDATION_ERROR`: "Sort must be id, title, status, priority or team, optionally followed by ,asc or ,desc."

**Why a whitelist, and why it's structural.** Spring Data's `Sort` takes a property path and follows associations, so `sort=createdBy.passwordHash` would either error or order rows by a value the caller can't read. That second case is an oracle: the order of the rows leaks the hidden value one comparison at a time.
- **The client's text never reaches the query.** It's matched against fifteen exact spellings and becomes a `TicketOrder.Field` enum, which `TicketQueriesImpl` maps to a fixed expression.
- **No ticket query takes a Spring `Sort`.** `TicketQueries` has no such parameter, any `Sort` inside a page request is dropped, and the shape test forbids one.

**Why reject rather than ignore.** An ignored sort answers in an order the client didn't ask for. Any order looks plausible, so a client paging "by priority" would never notice it isn't.

**The decisions:**
- **Default:** `id` ascending, the contract's existing order, so existing clients see no change.
- **One field only.** `id` ascending is always appended as the tiebreaker, so equal keys come back in the same order and page boundaries are stable (decision 7). A second `sort` is a 400 under "sent once". Multi-field sorting is a feature nobody needs yet, and refusing keeps it addable.
- **Direction:** exactly `asc` or `desc`. `DESC`, `up`, a trailing comma and a third part are all 400s.
- **Meaningful orders, not stored text:**
  - `priority` sorts by rank (LOW < MEDIUM < HIGH < URGENT).
  - `status` sorts by workflow order (OPEN < IN_PROGRESS < RESOLVED < CLOSED). Both are CASE expressions. Alphabetical would give HIGH, LOW, MEDIUM, URGENT and put CLOSED first: wrong, while looking plausible.
  - `title` sorts by `lower(title)`.
  - `team` sorts by its stored name, which is in the same order as its labels.

Measured against the seed data: every order matches, and `status=OPEN&sort=priority,desc&size=2&page=2` returns `[2, 4]` of 4. The COUNT behind a page carries no ORDER BY.

### 46. Sorting by expressions uses no index. Accepted at this data size

**The problem.** Three of the five sortable fields sort by expressions: `lower(title)`, and the two CASEs for `status` and `priority`. A plain btree index on the column can't serve an ORDER BY on an expression, so PostgreSQL sorts those rows itself.

**What gets sorted is every matching row, not the page.** To return page 3 by priority, the database ranks all the rows that pass the WHERE clause, then skips and takes. With a LIMIT it uses a top-N heapsort, so the cost grows with the size of the filtered set, not with the page size. At this size (12 rows, and for an internal desk plausibly thousands), that's an in-memory sort of well under a millisecond.

**The decision: accept the in-memory sort, and add no indexes for it.**

**Rejected:**
- **Expression indexes,** for example `create index on ticket ((lower(title)))` and the same two CASEs. They only work if the index expression matches the query's *exactly*.
  - For the CASEs that can't happen as the code stands. Hibernate sends them with bound parameters (`case when t1_0.priority=? then ? …`, as logged), and an index expression is written with literals. The query would have to be changed to inline them first.
  - Even then, the CASE in `TicketQueriesImpl` and the one in a migration would have to stay in step by hand. A new priority added to one but not the other silently stops the index being used.
  - They also cost a write on every insert and update, for a sort nobody has measured as slow. `lower(title)` alone would work, but on its own it isn't worth an index either.
- **Persisted rank columns** (`priority_rank`, `status_rank`, kept in sync with the enum). That means a schema change, denormalised data that must be updated on every write, and a migration for every existing row. It's the right shape for a big table, and the wrong one for twelve rows.

**Revisit when** `EXPLAIN ANALYZE` on a real list query shows a sort over many thousands of rows on a hot path, or when the index work starts. Then pick between the two above with numbers. `id` and `team` already sort by plain columns, so they can use ordinary btree indexes whenever those exist.

### 47. Offset pagination is a snapshot per request, not a cursor

**The behaviour.** Each page is its own query: page 2 is "skip `size` rows, take `size`" *at the moment page 2 is asked for*. If the set or the order changes between a client's page 1 and page 2 requests, rows shift across the boundary. For example, a ticket is created, deleted, or moves to another status. Then a row can appear on both pages, or on neither.

**Making `status` sortable makes this likelier.** Under `sort=status`, a status change doesn't just add or remove a row, it *moves* it within the order. A ticket moving from OPEN to IN_PROGRESS while someone pages through `?sort=status` can jump from page 2 to page 1 between their two requests, and they never see it. The same happens with `priority`, and with filters on either.

**The decision.** Accept it, and say it in the contract. It's how offset pagination works everywhere, the frontend still pages a dozen tickets in the browser rather than through the API, and the `id` tiebreaker already rules out the *other* failure: rows trading places between two identical requests. The fix, if it's ever needed, is keyset pagination: "after this `(sort key, id)`" instead of "skip N". That's a contract change of its own, with a cursor in the envelope.

**Goes in the contract.** The pagination section says pages are computed per request, and that changes between requests can make a ticket appear twice or not at all.

### 48. No single casing rule for fixed values. The contract shows an example of each

**The situation.**
- Enumerated values on the wire are `OPEN`, `IN_PROGRESS`, `LOW`, `URGENT`: uppercase.
- Team labels are `Engineering`, `HR`: as written.
- The sort field and direction are `priority,desc`: lowercase.

All are matched exactly, so `status=open` and `sort=priority,DESC` are both 400s. There is no one rule like "values are uppercase" or "matching ignores case" that describes all three. Stating one would be wrong for at least one of them.

**The decision.** The contract states no casing rule. It shows a correct example of each kind next to its parameter, for example `?status=IN_PROGRESS`, `?team=Operations` and `?sort=priority,desc`, and says the values are matched exactly.

**`DESC` will be the first thing someone tries.** Spring Data's own `Sort` parameter accepts `DESC` and `desc` alike, and so do most APIs built on it. Someone used to that will send `sort=priority,DESC` and get a 400. The example has to show the lowercase form, and the 400's message already does. Accepting both was considered and left out for consistency with every other fixed value here, which is exact. Relaxing it later would break nobody.

### Comments in `AppUser` that this week makes wrong

Two comments in `AppUser.java` say things arrive in **Week 8**: the password hash, and the `409 CONFLICT` for registering a taken email. Both arrive tomorrow, in Week 7, so both comments become wrong. They're fixed as part of Tuesday's work, not separately.

The contract has the same Week 8 wording in five places: the `UNAUTHORIZED` row, the `GET /users` failure note, both paragraphs of the Auth intro, and the `POST /sessions` 201 note. All of it is reworded together on Thursday (decisions 18 to 24).
