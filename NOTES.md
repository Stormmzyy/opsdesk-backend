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

### 4. The controller returns the `Ticket` model today; on Thursday it returns a response DTO

For now, `TicketController` returns `Ticket` objects directly, and Jackson turns them into JSON. On Thursday that becomes a response DTO, because the deliverable says no entity is ever returned from a controller. Once `Ticket` is a JPA entity, returning it would tie the JSON shape to the database table, and could leak fields the API shouldn't show.

Until then, **nothing outside the controller may depend on the JSON shape of `Ticket`**. The service and repository work with `Ticket` as a Java object only, so swapping the response type is a change to the controller alone.

### 5. The in-memory repository is replaced by a `JpaRepository` tomorrow, and `TicketService` should not change

`TicketRepository` is a class today: a `List`, an id counter, and `synchronized` methods. Tomorrow all of that is replaced by an interface extending `JpaRepository<Ticket, Long>`, and PostgreSQL takes over storage, id generation and concurrency.

The in-memory class deliberately uses the same method names as `JpaRepository` (`findAll`, `findById`, `save`, `existsById`, `deleteById`, `count`), so `TicketService` can stay exactly as it is. **If the switch forces any change in `TicketService`, the layering was wrong.** That gets reported and discussed, not quietly patched.

### 6. A `PATCH` to a missing ticket with an unreadable body must answer 404 (fixed on Thursday)

**The problem.** Spring reads and converts the `@RequestBody` *before* the controller method runs. If the JSON can't be read, Spring throws `HttpMessageNotReadableException` and the method never runs, so the existence check inside it never happens either. Today, `PATCH /api/v1/tickets/999` with a broken body answers **400**, but the contract's check order (decision 3) says it must be **404**, because ticket 999 doesn't exist.

**The fix, on Thursday, in the global exception handler.** When the handler catches `HttpMessageNotReadableException`, it doesn't answer 400 straight away. It first reads the id from the request path and checks whether that ticket exists:

- If the id isn't valid or the ticket doesn't exist, it answers **404 `NOT_FOUND`**.
- Only if the ticket exists does it answer **400 `BAD_REQUEST`**.

That puts the existence check first, as the contract requires, without changing how Spring reads bodies. **Not fixed yet**: today's behaviour is the 400.

## Open decisions

### Pagination on `GET /tickets` (planned for Thursday, not built yet)

The contract says `GET /tickets` returns a plain array, and describes pagination (`page`, `size`, `status`, `search`) as planned for v1.1 and not implemented. The Week 6 deliverable needs `page`, `size` and a `status` filter that return the contract's paginated envelope:

```json
{ "items": [], "page": 1, "size": 10, "totalItems": 0, "totalPages": 0 }
```

The plan is to build the envelope on Thursday and, in the same piece of work, update `API_CONTRACT.md` and the frontend's `getTickets` query to match.

Worth deciding first: the contract's v1.1 design says the plain array stays the answer when **none** of the query parameters is sent, and the envelope is returned only when at least one is. Keeping that rule means the frontend doesn't have to change at the same moment the backend does.
