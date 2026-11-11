#!/usr/bin/env bash
# DEVELOPMENT ONLY. Walks every row of NOTES.md decision 27's checks table
# against a running local backend, logging in as the seeded users with the
# seeded development password (devpassword123; README.md, "Development
# password"). Never point it at anything but a seeded development database.
#
# Usage, from the project root, with the app running on localhost:8080 under
# the dev profile (SPRING_PROFILES_ACTIVE=dev), which is the only profile that
# seeds the users this script logs in as (decision 69):
#     bash scripts/week7-permission-walk.sh
# Needs bash, curl, and docker (it reads rows with psql inside the database
# container; see "Where the database is" below).
#
# Where the database is. Some checks read rows with psql run inside the
# database container. That never touches a host port, which matters because
# the compose database has no host port mapping at all. Two ways to reach it:
#   - Default: the container started by the README's docker run command.
#         bash scripts/week7-permission-walk.sh
#     WALK_DB_CONTAINER=other-name overrides the name (default opsdesk-db).
#   - Under docker compose: name the service instead. Compose looks up the
#     container itself, because its name (e.g. opsdesk-system-db-1) depends on
#     the project, which is exactly what made a hardcoded name break.
#         WALK_DB_SERVICE=db COMPOSE_FILE=../opsdesk-system/compose.yaml \
#             bash scripts/week7-permission-walk.sh
#     COMPOSE_FILE is read by docker compose itself, not by this script; leave
#     it out when running from the directory that holds the compose file.
#
# It assumes a schema freshly built from the entities. ddl-auto: update adds
# columns but never drops or loosens one, so a column left over from older
# code can make the walk fail for a reason unrelated to the change under
# test: with @Version removed, the walk went red only because the stale
# "version" column was still NOT NULL, while the real loss (lost updates)
# went unseen (NOTES.md, decision 65). After changing an entity, rebuild the
# database (README.md: docker rm -f opsdesk-db, then the docker run command)
# before trusting a red here.
#
# Each request states the status code decision 27 expects and prints PASS or
# FAIL; the script exits non-zero if anything failed. Friday's tests are meant
# to turn these same requests and expectations into automated tests.
#
# It restores everything it changes, following decision 27's three rules:
#   - the ADMIN delete is proved on a ticket the walk creates, never a seeded one
#     (and the search and sort probe ticket is deleted as soon as it's used)
#   - edits change the title, never #4's status, so #4 stays OPEN
#   - assigning #2 runs after the AGENT edit checks, and #2 is put back to
#     unassigned afterwards
# Every changed title is put back, and every ticket it creates is deleted.
#
# One thing it can't put back: the ticket id sequence. Ids are never reused
# (decision 1), so each run consumes five ids, and the tickets it creates are
# #13 to #17 only on a freshly created database. The script reads every
# created id from the response instead of assuming it, and Friday's tests
# must do the same.
#
# Some cells can only be proved from the database row, because the API's
# ticket has no assigneeId or createdBy field; those print the row as well.

A=http://localhost:8080/api/v1
J='Content-Type: application/json'
tok() { curl -s -H "$J" -X POST $A/auth/login -d "{\"email\":\"$1\",\"password\":\"devpassword123\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'; }
PRIYA=$(tok priya.sharma@opsdesk.com); KWAME=$(tok kwame.asante@opsdesk.com)
DANIEL=$(tok daniel.mensah@opsdesk.com); FATIMA=$(tok fatima.bello@opsdesk.com)
if [ -z "$PRIYA" ] || [ -z "$KWAME" ] || [ -z "$DANIEL" ] || [ -z "$FATIMA" ]; then
  echo "Could not log in as the seeded users. Is the app running against a seeded database?" >&2
  exit 2
fi
WALK_DB_CONTAINER=${WALK_DB_CONTAINER:-opsdesk-db}
if [ -n "$WALK_DB_SERVICE" ]; then
  # -T: compose exec allocates a terminal by default, and inside $(...) there
  # is none, so without it every read fails with "the input device is not a TTY".
  sql() { docker compose exec -T "$WALK_DB_SERVICE" psql -U opsdesk -tAc "$1"; }
  db_where="compose service '$WALK_DB_SERVICE'"
else
  sql() { docker exec "$WALK_DB_CONTAINER" psql -U opsdesk -tAc "$1"; }
  db_where="container '$WALK_DB_CONTAINER'"
fi
# Prove the database is reachable before the first request. Otherwise a wrong
# name surfaces 30 requests later as a row check that "failed", which points
# at the permission rules rather than at the setup.
if [ "$(sql 'select 1' 2>/dev/null)" != "1" ]; then
  echo "Could not run psql in the $db_where. Is it running? (See \"Where the database is\" at the top of this script.)" >&2
  exit 2
fi
n=0; failures=0
# r "label" EXPECTED_STATUS TOKEN|- METHOD PATH [BODY]
r() {
  n=$((n+1)); local label=$1 want=$2 t=$3 m=$4 p=$5 b=$6 auth=()
  [ "$t" != "-" ] && auth=(-H "Authorization: Bearer $t")
  if [ -n "$b" ]; then
    code=$(curl -s -o /tmp/walk_body -w '%{http_code}' "${auth[@]}" -H "$J" -X "$m" "$A$p" -d "$b")
  else
    code=$(curl -s -o /tmp/walk_body -w '%{http_code}' "${auth[@]}" -X "$m" "$A$p")
  fi
  BODY=$(cat /tmp/walk_body)
  local verdict=PASS; [ "$code" != "$want" ] && { verdict=FAIL; failures=$((failures+1)); }
  printf '\n[%02d] %s  %s (expected %s)\n     > %s /api/v1%s %s\n     < %s %s\n' \
    "$n" "$verdict" "$label" "$want" "$m" "$p" "$b" "$code" "$(echo "$BODY" | cut -c1-170)"
}
check() { # check "label" "actual" "expected": for facts read from the database
  local verdict=PASS; [ "$2" != "$3" ] && { verdict=FAIL; failures=$((failures+1)); }
  printf '     %s  %s: %s (expected %s)\n' "$verdict" "$1" "$2" "$3"
}
ids() { echo "$BODY" | grep -oE '"id":[0-9]+,"title"' | grep -oE '[0-9]+' | tr '\n' ' ' | sed 's/ $//'; }
assignee_of() { sql "select coalesce(assignee_id::text,'NULL') from ticket where id=$1"; }
created_id() { echo "$BODY" | sed -n 's/^{"id":\([0-9]*\).*/\1/p'; }
creator_of() { sql "select created_by_id from ticket where id=$1"; }

echo "################ JSON type strictness: every body field is type-checked, nothing is coerced"
r "title sent as a number"    400 "$PRIYA" POST /tickets '{"title":5,"description":"x","priority":"LOW","team":"HR"}'
r "priority sent as a number" 400 "$PRIYA" POST /tickets '{"title":"Strict","description":"x","priority":3,"team":"HR"}'

echo; echo "################ VIEW"
r "View all: ADMIN"     200 "$PRIYA"  GET /tickets; check "ids" "$(ids)" "1 2 3 4 5 6 7 8 9 10 11 12"
r "View all: MANAGER"   200 "$KWAME"  GET /tickets; check "ids" "$(ids)" "1 2 3 4 5 6 7 8 9 10 11 12"
r "View all: AGENT"     200 "$DANIEL" GET /tickets; check "ids" "$(ids)" "1 2 3 4 5 6 7 8 9 10 11 12"
r "View own only: USER" 200 "$FATIMA" GET /tickets; check "ids" "$(ids)" "4 9"
r "USER, someone else's ticket (decision 23: 404, not 403)" 404 "$FATIMA" GET /tickets/2; HIDDEN=$BODY
r "USER, a ticket that doesn't exist"                       404 "$FATIMA" GET /tickets/999; MISSING=$BODY
check "the two 404 bodies, ids masked" "$(echo "$HIDDEN" | sed 's/[0-9]\+/N/g')" "$(echo "$MISSING" | sed 's/[0-9]\+/N/g')"

echo; echo "################ FILTER, SORT, SEARCH AND PAGING ON REAL POSTGRESQL (NOTES.md, decision 62)"
# The tests run on H2, which can't vouch for the SQL PostgreSQL gets: lower()
# and its collation, LIKE escaping, the CASE expressions behind the sorts, and
# the limit, offset and COUNT queries. These requests run that SQL here.
# Every expected order is written out from decision 27's rows, not computed
# by a query, so a wrong ORDER BY can't be copied into the expectation.
# Run before the edits, while every title is the seeded one.
totals() { echo "$BODY" | sed -n 's/.*"totalItems":\([0-9]*\),"totalPages":\([0-9]*\).*/\1 \2/p'; }
field() { echo "$BODY" | sed -n 's/.*"fields":{"\([^"]*\)".*/\1/p'; }

# Search: lower() on both sides, inside the visibility condition. "holiday"
# is in #4's title and #8's description. Fatima sees only #4 and #9, so she
# gets #4: #8 is cut by visibility, #9 by the search.
r "Search, USER: visibility and lower() together" 200 "$FATIMA" GET "/tickets?search=HOLIDAY"; check "ids" "$(ids)" "4"
r "  the same search, ADMIN"                       200 "$PRIYA"  GET "/tickets?search=HOLIDAY"; check "ids" "$(ids)" "4 8"

# The sorts that are CASE expressions. size=20 so all twelve come back.
# Ascending status would be useless here: the seed is already in workflow
# order, so it equals the id order, and a sort that was ignored would pass.
r "Sort by priority (rank, not alphabetical)" 200 "$KWAME" GET "/tickets?sort=priority&size=20"
check "ids" "$(ids)" "4 6 12 2 8 9 11 3 5 10 1 7"
r "Sort by priority, descending (ties stay by id ascending)" 200 "$KWAME" GET "/tickets?sort=priority,desc&size=20"
check "ids" "$(ids)" "1 7 3 5 10 2 8 9 11 4 6 12"
r "Sort by status, descending (workflow order, reversed)" 200 "$KWAME" GET "/tickets?sort=status,desc&size=20"
check "ids" "$(ids)" "11 12 8 9 10 5 6 7 1 2 3 4"

# A probe ticket, deleted at the end of this section. Its title has a
# literal % (no seeded title or description has % or _), and starts with a
# lower-case letter.
r "Create the probe ticket" 201 "$PRIYA" POST /tickets '{"title":"a 100% walk probe","description":"x","priority":"LOW","team":"HR"}'; T_PROBE=$(created_id)

# LIKE escaping: search for "%" alone. Escaped, it matches only a literal
# percent sign: the probe. Unescaped, the pattern would be %%% and match all.
r "Search for a literal %" 200 "$PRIYA" GET "/tickets?search=%25"
check "ids" "$(ids)" "$T_PROBE"; check "totalItems totalPages" "$(totals)" "1 1"

# The lower() sort. Seeded titles all start with a capital, so the probe is
# what makes case matter: a case-sensitive byte order would put it last.
# This database's collation (en_US.utf8) also ignores case at first, so this
# proves the order ignores case on PostgreSQL, not which of the two did it.
r "Sort by title (case-insensitive)" 200 "$PRIYA" GET "/tickets?sort=title&size=20"
check "ids" "$(ids)" "$T_PROBE 6 11 3 5 1 7 2 12 8 10 9 4"

r "Delete the probe ticket" 204 "$PRIYA" DELETE "/tickets/$T_PROBE"

# Paging a filtered list. MEDIUM matches 4 tickets (#2 #8 #9 #11). With
# size=3 the first page is full, so Spring Data must run the COUNT query to
# know the total (with fewer rows than size it can skip it): totalItems 4
# can't be read off three items.
r "Filtered page 1, size smaller than the match" 200 "$KWAME" GET "/tickets?priority=MEDIUM&size=3"
check "ids" "$(ids)" "2 8 9"; check "totalItems totalPages" "$(totals)" "4 2"
r "Filtered page 2, the offset"                  200 "$KWAME" GET "/tickets?priority=MEDIUM&size=3&page=2"
check "ids" "$(ids)" "11"; check "totalItems totalPages" "$(totals)" "4 2"
r "Page past the last: empty, totals still right" 200 "$KWAME" GET "/tickets?priority=MEDIUM&size=3&page=3"
check "ids" "$(ids)" ""; check "totalItems totalPages" "$(totals)" "4 2"

# Three 400s, each checked for the field it names, so a 400 for some other
# reason doesn't pass.
r "Sort by a field off the list (a property path)" 400 "$KWAME" GET "/tickets?sort=createdBy.passwordHash"; check "field" "$(field)" "sort"
r "An unknown query parameter"                     400 "$KWAME" GET "/tickets?colour=red";                  check "field" "$(field)" "colour"
r "A status that doesn't exist"                    400 "$KWAME" GET "/tickets?status=BANANA";               check "field" "$(field)" "status"

echo; echo "################ EDIT (titles changed, then restored)"
r "ADMIN edits any (#3)"                  200 "$PRIYA"  PATCH /tickets/3 '{"title":"Walk: Priya edited #3"}'
r "  restore #3"                          200 "$PRIYA"  PATCH /tickets/3 '{"title":"Customer cannot reset their password"}'
r "MANAGER edits any (#2)"                200 "$KWAME"  PATCH /tickets/2 '{"title":"Walk: Kwame edited #2"}'
r "  restore #2"                          200 "$KWAME"  PATCH /tickets/2 '{"title":"Order new laptops for the March starters"}'
r "AGENT edits assigned (#1), title"      200 "$DANIEL" PATCH /tickets/1 '{"title":"Walk: Daniel edited #1"}'
r "  restore #1"                          200 "$DANIEL" PATCH /tickets/1 '{"title":"Login page times out on slow networks"}'
r "AGENT edits assigned (#1), status"     200 "$DANIEL" PATCH /tickets/1 '{"status":"IN_PROGRESS"}'
r "  restore #1 status"                   200 "$DANIEL" PATCH /tickets/1 '{"status":"OPEN"}'
r "AGENT, assigned to nobody (#6)"        403 "$DANIEL" PATCH /tickets/6 '{"title":"Walk"}'
r "AGENT, assigned to someone else (#3)"  403 "$DANIEL" PATCH /tickets/3 '{"title":"Walk"}'
r "USER, own and OPEN (#4), title"        200 "$FATIMA" PATCH /tickets/4 '{"title":"Walk: Fatima edited #4"}'
r "  restore #4"                          200 "$FATIMA" PATCH /tickets/4 '{"title":"Update the holiday policy document"}'
r "USER, own OPEN (#4), status"           403 "$FATIMA" PATCH /tickets/4 '{"status":"IN_PROGRESS"}'
r "USER, own OPEN (#4), priority"         403 "$FATIMA" PATCH /tickets/4 '{"priority":"HIGH"}'
r "USER, own OPEN (#4), team"             403 "$FATIMA" PATCH /tickets/4 '{"team":"Support"}'
r "USER, own but not OPEN (#9)"           403 "$FATIMA" PATCH /tickets/9 '{"title":"Walk"}'
r "USER, not own (#2): can't see it"      404 "$FATIMA" PATCH /tickets/2 '{"title":"Walk"}'
check "#4 after the edits" "$(sql "select status||' / '||priority||' / '||team||' / '||title from ticket where id=4")" "OPEN / LOW / HR / Update the holiday policy document"

echo; echo "################ ASSIGN AND UNASSIGN (after the AGENT edit checks; #2 put back afterwards)"
r "AGENT assigns (#1 to Marco)"   403 "$DANIEL" PATCH /tickets/1/assignee '{"assigneeId":6}'
check "#1 assignee unchanged" "$(assignee_of 1)" "2"
r "USER assigns (#4 to Daniel)"   403 "$FATIMA" PATCH /tickets/4/assignee '{"assigneeId":2}'
r "AGENT unassigns (#1)"          403 "$DANIEL" DELETE /tickets/1/assignee
check "#1 assignee unchanged" "$(assignee_of 1)" "2"
r "USER unassigns (#4)"           403 "$FATIMA" DELETE /tickets/4/assignee
r "ADMIN assigns #2 to Liam"      200 "$PRIYA"  PATCH /tickets/2/assignee '{"assigneeId":4}'
check "#2 assignee (row)" "$(assignee_of 2)" "4"
r "  put #2 back"                 200 "$KWAME"  DELETE /tickets/2/assignee
check "#2 assignee (row)" "$(assignee_of 2)" "NULL"
r "MANAGER assigns #2 to Liam"    200 "$KWAME"  PATCH /tickets/2/assignee '{"assigneeId":4}'
check "#2 assignee (row)" "$(assignee_of 2)" "4"
r "  put #2 back"                 200 "$KWAME"  DELETE /tickets/2/assignee
check "#2 assignee (row)" "$(assignee_of 2)" "NULL"

echo; echo "################ CREATE (createdBy is checked in the database)"
r "Create: ADMIN"   201 "$PRIYA"  POST /tickets '{"title":"Walk ticket by Priya","description":"x","priority":"LOW","team":"HR"}';  T_PRIYA=$(created_id)
check "#$T_PRIYA created_by_id (row)" "$(creator_of "$T_PRIYA")" "1"
r "Create: MANAGER" 201 "$KWAME"  POST /tickets '{"title":"Walk ticket by Kwame","description":"x","priority":"LOW","team":"HR"}';  T_KWAME=$(created_id)
check "#$T_KWAME created_by_id (row)" "$(creator_of "$T_KWAME")" "3"
r "Create: AGENT"   201 "$DANIEL" POST /tickets '{"title":"Walk ticket by Daniel","description":"x","priority":"LOW","team":"HR"}'; T_DANIEL=$(created_id)
check "#$T_DANIEL created_by_id (row)" "$(creator_of "$T_DANIEL")" "2"
r "Create: USER, sending createdBy 1 (Priya), which must be ignored" 201 "$FATIMA" POST /tickets '{"title":"Walk ticket by Fatima","description":"x","priority":"LOW","team":"HR","createdBy":1}'; T_FATIMA=$(created_id)
check "#$T_FATIMA created_by_id (row)" "$(creator_of "$T_FATIMA")" "5"
r "  USER's list now includes her new ticket" 200 "$FATIMA" GET /tickets; check "ids" "$(ids)" "4 9 $T_FATIMA"

echo; echo "################ DELETE"
r "MANAGER deletes (#2)" 403 "$KWAME"  DELETE /tickets/2
r "AGENT deletes (#1)"   403 "$DANIEL" DELETE /tickets/1
r "USER deletes (#4)"    403 "$FATIMA" DELETE /tickets/4
r "ADMIN deletes a ticket created during the test (#$T_PRIYA)" 204 "$PRIYA" DELETE /tickets/$T_PRIYA
r "  and it's gone"      404 "$PRIYA"  GET /tickets/$T_PRIYA

echo; echo "################ LIST USERS"
r "List users: ADMIN"   200 "$PRIYA"  GET /users; check "users returned" "$(echo "$BODY" | grep -o '"username"' | wc -l | tr -d ' ')" "6"
r "List users: MANAGER" 200 "$KWAME"  GET /users; check "users returned" "$(echo "$BODY" | grep -o '"username"' | wc -l | tr -d ' ')" "6"
r "List users: AGENT"   403 "$DANIEL" GET /users
r "List users: USER"    403 "$FATIMA" GET /users

echo; echo "################ THE EASY-TO-MISS ONES"
r "No token, where a valid MANAGER token gives 403"      401 -        DELETE /tickets/2
r "/tickets/999, gate refuses before the lookup: MANAGER" 403 "$KWAME"  DELETE /tickets/999
r "/tickets/999, gate passes: ADMIN"                     404 "$PRIYA"  DELETE /tickets/999
r "/tickets/999/assignee, gate refuses: USER"            403 "$FATIMA" PATCH /tickets/999/assignee '{"assigneeId":2}'
r "/tickets/999/assignee, gate passes: MANAGER"          404 "$KWAME"  PATCH /tickets/999/assignee '{"assigneeId":2}'

echo; echo "################ CLEAN-UP: the ADMIN deletes the other tickets the walk created"
for t in $T_KWAME $T_DANIEL $T_FATIMA; do r "  delete #$t" 204 "$PRIYA" DELETE /tickets/$t; done
check "tickets left" "$(sql "select count(*) from ticket")" "12"

echo
if [ "$failures" -eq 0 ]; then
  echo "All checks passed ($n requests)."
else
  echo "$failures check(s) FAILED ($n requests)." >&2
  exit 1
fi
