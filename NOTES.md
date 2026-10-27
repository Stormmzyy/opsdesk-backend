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

**2. Constraint names are Hibernate's hashes, and they show up in error messages.**

The foreign keys and unique constraints have generated names such as `fkon3g4edms44nvfti4skwns3vn` and `uk1j9d9a06i600gd43uu3km82jw`. PostgreSQL puts the name in its error messages exactly as it is: a duplicate email fails with "violates unique constraint uk1j9d9a06i600gd43uu3km82jw". They can be given readable names through `@JoinColumn(foreignKey = @ForeignKey(name = ...))` and `@Table(uniqueConstraints = @UniqueConstraint(name = ..., columnNames = ...))`.

**Not fixed now.** Renaming an existing constraint means dropping it and creating it again, which `ddl-auto: update` won't do, and nothing this week depends on matching a constraint name. It becomes relevant in **Week 8**, when a duplicate email on registration has to become the contract's `409 CONFLICT`. Detecting that reliably may mean recognising which constraint failed.

**3. The foreign key columns have no index.**

PostgreSQL creates an index for every primary key and unique constraint, but **not for foreign keys**. So `ticket.project_id` and `ticket.assignee_id` are unindexed. At 12 rows that makes no difference. It matters for two things:
- queries like "all tickets in project X", which have to read the whole `ticket` table
- deleting a project, because PostgreSQL then scans `ticket` to check that no row still points at it

Revisit in **Week 7**, when filtering and pagination are done properly.

## Open decisions

### Pagination on `GET /tickets` (planned for Thursday, not built yet)

The contract says `GET /tickets` returns a plain array, and describes pagination (`page`, `size`, `status`, `search`) as planned for v1.1 and not implemented. The Week 6 deliverable needs `page`, `size` and a `status` filter that return the contract's paginated envelope:

```json
{ "items": [], "page": 1, "size": 10, "totalItems": 0, "totalPages": 0 }
```

The plan is to build the envelope on Thursday and, in the same piece of work, update `API_CONTRACT.md` and the frontend's `getTickets` query to match.

Worth deciding first: the contract's v1.1 design says the plain array stays the answer when **none** of the query parameters is sent, and the envelope is returned only when at least one is. Keeping that rule means the frontend doesn't have to change at the same moment the backend does.
