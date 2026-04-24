#!/usr/bin/env bash
# smoke-test.sh [BASE_URL] [ADMIN_TOKEN]
set -euo pipefail
BASE="${1:-http://localhost:8080}"
TOK="${2:-change-me-admin}"
ID="smoke-$(date +%s)"
TYPE="ConcernSmokeProbe"
ITOK="ingest-$(openssl rand -hex 4 2>/dev/null || echo dead)"

green(){ printf '\033[32m✓  %s\033[0m\n' "$*"; }
red(){   printf '\033[31m✗  %s\033[0m\n' "$*"; exit 1; }
info(){  printf '\033[36m→  %s\033[0m\n' "$*"; }
ADMIN=(-H "Authorization: Bearer $TOK")
JSON=(-H "Content-Type: application/json")

info "[1/6] health"
curl -fsS "$BASE/health" > /dev/null || red "health endpoint not reachable"
green "runtime alive"

info "[2/6] list probes"
curl -fsS "${ADMIN[@]}" "$BASE/api/probes" > /dev/null || red "/api/probes failed"
green "probes list OK"

info "[3/6] create probe $ID"
curl -fsS "${ADMIN[@]}" "${JSON[@]}" -X POST "$BASE/api/probes" --data-binary @- <<EOF > /tmp/cr.json
{"id":"$ID","name":"$TYPE","probeType":"$TYPE",
 "broker":{"url":"tcp://activemq:61616","username":"system","password":"manager","topic":"DROOLS-InstanceOne"},
 "ingest":{"enabled":true,"path":"$TYPE","authToken":"$ITOK","payloadMode":"passthrough"},
 "eventTemplate":{"name":"SmokeEvent","cepType":"DROOLS","dataField":"\${payload.value}"},
 "buffer":{"enabled":true},"autoStart":true}
EOF
green "probe created"

info "[4/6] push 3 events"
for i in 1 2 3; do
  curl -fsS -H "Authorization: Bearer $ITOK" "${JSON[@]}" \
    -X POST "$BASE/ingest/$TYPE" --data "{\"value\":$i.0}" > /dev/null \
    || red "ingest failed on event $i"
done
green "3 events pushed"

sleep 1

info "[5/6] check counters"
STATUS=$(curl -fsS "${ADMIN[@]}" "$BASE/api/probes/$ID")
SENT=$(echo "$STATUS" | grep -o '"sentCount":[0-9]*' | cut -d: -f2 || echo 0)
BUF=$(echo  "$STATUS" | grep -o '"bufferedCount":[0-9]*' | cut -d: -f2 || echo 0)
TOTAL=$(( ${SENT:-0} + ${BUF:-0} ))
[ "$TOTAL" -ge 3 ] || red "expected sent+buffered>=3, got $TOTAL (sent=$SENT buf=$BUF)"
green "counters OK (sent=$SENT buffered=$BUF)"

info "[6/6] cleanup"
curl -fsS "${ADMIN[@]}" -X DELETE "$BASE/api/probes/$ID" > /dev/null
green "probe deleted"

printf '\n\033[32m══ SMOKE TEST PASSED ══\033[0m\n\n'
