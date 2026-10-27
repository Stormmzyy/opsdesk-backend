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
