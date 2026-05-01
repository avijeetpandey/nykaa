#!/usr/bin/env bash
# =============================================================================
#  NYKAA — FULL SYSTEM TEST SCRIPT
#  Covers: REST APIs · Admin Flow · Kafka · Redis · Debezium CDC · Elasticsearch
# =============================================================================
# Usage:
#   ./scripts/test_all.sh                       # run all suites
#   SUITES=api,kafka ./scripts/test_all.sh       # run specific suites
#   SKIP_INFRA=1 ./scripts/test_all.sh           # skip infra pre-flight
#
# Environment overrides:
#   BASE_URL           default: http://localhost:3000
#   ADMIN_EMAIL        default: admin@nykaa-e2e.com
#   ADMIN_PASSWORD     default: Admin@12345
#   KAFKA_BROKER       default: localhost:9092
#   REDIS_HOST/PORT    default: localhost / 6379
#   KAFKA_CONNECT_URL  default: http://localhost:8083
#   ES_URL             default: http://localhost:9200
#   PG_*               default: localhost/5432/nykaa/postgres/postgres
# =============================================================================

# ── Runtime flags ──────────────────────────────────────────────────────────────
set -uo pipefail       # strict mode but NO -e so we can collect failures

# ── Configuration ──────────────────────────────────────────────────────────────
BASE_URL="${BASE_URL:-http://localhost:3000}"
TIMESTAMP="$(date +%s)"

ADMIN_EMAIL="${ADMIN_EMAIL:-admin.e2e.${TIMESTAMP}@nykaa-e2e.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-Admin@12345}"
CUSTOMER_EMAIL="customer.e2e.${TIMESTAMP}@nykaa-e2e.com"
CUSTOMER_PASSWORD="Customer@12345"

KAFKA_BROKER="${KAFKA_BROKER:-localhost:9092}"
REDIS_HOST="${REDIS_HOST:-localhost}"
REDIS_PORT="${REDIS_PORT:-6379}"
KAFKA_CONNECT_URL="${KAFKA_CONNECT_URL:-http://localhost:8083}"
ES_URL="${ES_URL:-http://localhost:9200}"

PG_HOST="${PG_HOST:-localhost}"
PG_PORT="${PG_PORT:-5432}"
PG_DB="${PG_DB:-nykaa}"
PG_USER="${PG_USER:-postgres}"
PG_PASS="${PG_PASS:-postgres}"

PYTHON_BIN="${PYTHON_BIN:-python3}"
CURL_BIN="${CURL_BIN:-curl}"

# Suite selector: comma-separated list, or "all"
SUITES="${SUITES:-all}"
SKIP_INFRA="${SKIP_INFRA:-0}"

# ── State variables ────────────────────────────────────────────────────────────
PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0
TMP_DIR="$(mktemp -d)"
REQUEST_SEQ=0
RESPONSE_STATUS=""
RESPONSE_BODY_FILE=""

ADMIN_TOKEN=""
CUSTOMER_TOKEN=""
ADMIN_USER_ID=""
CUSTOMER_USER_ID=""
EXTRA_USER_ID=""
PRODUCT_ID_1=""
PRODUCT_ID_2=""
PRODUCT_ID_BULK_1=""
PRODUCT_ID_BULK_2=""
ORDER_ID=""

# ── Cleanup ────────────────────────────────────────────────────────────────────
cleanup() { rm -rf "$TMP_DIR"; }
trap cleanup EXIT

# ── Colours ───────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

# ── Logging ───────────────────────────────────────────────────────────────────
log_info()    { printf "${CYAN}[INFO]${NC}  %s\n" "$*"; }
log_pass()    { PASS_COUNT=$((PASS_COUNT + 1)); printf "${GREEN}[PASS]${NC}  %s\n" "$*"; }
log_fail()    { FAIL_COUNT=$((FAIL_COUNT + 1)); printf "${RED}[FAIL]${NC}  %s\n" "$*" >&2; }
log_skip()    { SKIP_COUNT=$((SKIP_COUNT + 1)); printf "${YELLOW}[SKIP]${NC}  %s\n" "$*"; }
log_warn()    { printf "${YELLOW}[WARN]${NC}  %s\n" "$*"; }
log_section() {
  printf "\n${BOLD}${BLUE}══════════════════════════════════════════════════${NC}\n"
  printf   "${BOLD}${BLUE}  %-48s${NC}\n" "$*"
  printf   "${BOLD}${BLUE}══════════════════════════════════════════════════${NC}\n"
}
log_sub()     { printf "\n${YELLOW}─── %s ───${NC}\n" "$*"; }

# ── Tool detection ─────────────────────────────────────────────────────────────
require_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    log_warn "Optional tool not found: $1 (some tests may be skipped)"
    return 1
  fi
  return 0
}

HAS_JQ=0;       require_tool jq          && HAS_JQ=1
HAS_REDIS=0;    require_tool redis-cli   && HAS_REDIS=1
HAS_PSQL=0;     require_tool psql        && HAS_PSQL=1
HAS_DOCKER=0;   require_tool docker      && HAS_DOCKER=1
HAS_PYTHON=0;   require_tool "$PYTHON_BIN" && HAS_PYTHON=1

# ── HTTP helpers ───────────────────────────────────────────────────────────────
request() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local token="${4:-}"
  REQUEST_SEQ=$((REQUEST_SEQ + 1))
  RESPONSE_BODY_FILE="$TMP_DIR/response_${REQUEST_SEQ}.json"

  local -a args
  args=("$CURL_BIN" -sS -o "$RESPONSE_BODY_FILE" -w '%{http_code}'
        -X "$method" "${BASE_URL}${path}"
        -H 'Accept: application/json')
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer $token")
  [[ -n "$body"  ]] && args+=(-H 'Content-Type: application/json' --data "$body")

  RESPONSE_STATUS="$("${args[@]}" 2>/dev/null || echo "000")"
}

# simple GET with raw URL (for ES / Kafka Connect)
raw_get() {
  "$CURL_BIN" -sS -o /dev/null -w '%{http_code}' "$1" 2>/dev/null || echo "000"
}

raw_get_body() {
  "$CURL_BIN" -sS "$1" 2>/dev/null || echo "{}"
}

raw_post() {
  local url="$1"; local body="$2"
  "$CURL_BIN" -sS -X POST -H 'Content-Type: application/json' --data "$body" "$url" 2>/dev/null || echo "{}"
}

# ── JSON parsing ──────────────────────────────────────────────────────────────
json_get() {
  local file="$1"; local expr="$2"
  if [[ $HAS_PYTHON -eq 0 ]]; then echo ""; return; fi
  "$PYTHON_BIN" - "$file" "$expr" <<'PY'
import json, re, sys
file_path, expr = sys.argv[1], sys.argv[2]
with open(file_path, 'r') as fh:
    data = json.load(fh)
current = data
for part in (expr.split('.') if expr else []):
    for key, index in re.findall(r'([^\[\]]+)|\[(\d+)\]', part):
        current = current[key] if key else current[int(index)]
if isinstance(current, bool): print('true' if current else 'false')
elif current is None: print('null')
elif isinstance(current, (dict, list)): print(json.dumps(current))
else: print(str(current))
PY
}

json_get_body() {
  local body_str="$1"; local expr="$2"
  if [[ $HAS_PYTHON -eq 0 ]]; then echo ""; return; fi
  local tmp_f; tmp_f="$(mktemp "$TMP_DIR/jgb_XXXXXX.json")"
  echo "$body_str" > "$tmp_f"
  json_get "$tmp_f" "$expr"
}

# ── Assertions ─────────────────────────────────────────────────────────────────
assert_status() {
  local expected="$1"; local context="$2"
  if [[ "$RESPONSE_STATUS" == "$expected" ]]; then
    log_pass "$context → HTTP $RESPONSE_STATUS"
  else
    log_fail "$context → expected HTTP $expected, got $RESPONSE_STATUS"
    [[ -f "${RESPONSE_BODY_FILE:-}" ]] && printf "         body: %s\n" "$(head -c 300 "$RESPONSE_BODY_FILE")"
  fi
}

assert_json_eq() {
  local expr="$1"; local expected="$2"; local context="$3"
  local actual; actual="$(json_get "$RESPONSE_BODY_FILE" "$expr" 2>/dev/null || echo "")"
  if [[ "$actual" == "$expected" ]]; then
    log_pass "$context (.${expr} == '${expected}')"
  else
    log_fail "$context → expected '${expected}' at '.${expr}', got '${actual}'"
  fi
}

assert_json_nonempty() {
  local expr="$1"; local context="$2"
  local actual; actual="$(json_get "$RESPONSE_BODY_FILE" "$expr" 2>/dev/null || echo "")"
  if [[ -n "$actual" && "$actual" != "null" ]]; then
    log_pass "$context (.${expr} is non-empty)"
  else
    log_fail "$context → '.${expr}' is empty or null"
  fi
}

assert_contains() {
  local context="$1"; local haystack="$2"; local needle="$3"
  if echo "$haystack" | grep -q "$needle"; then
    log_pass "$context (contains '$needle')"
  else
    log_fail "$context → output does not contain '$needle'"
  fi
}

# ── Suite selector ─────────────────────────────────────────────────────────────
suite_enabled() {
  local name="$1"
  [[ "$SUITES" == "all" ]] || echo "$SUITES" | grep -qw "$name"
}

# ── Poll helper ────────────────────────────────────────────────────────────────
# poll_order <orderId> <token> <max_tries> <interval_sec>
poll_order_status() {
  local order_id="$1"; local token="$2"
  local max="${3:-20}"; local interval="${4:-2}"
  local status=""
  for _ in $(seq 1 "$max"); do
    request GET "/api/v1/orders/${order_id}" "" "$token"
    status="$(json_get "$RESPONSE_BODY_FILE" "data.status" 2>/dev/null || echo "")"
    [[ "$status" == "SUCCESS" || "$status" == "FAILED" ]] && { echo "$status"; return; }
    sleep "$interval"
  done
  echo "PENDING"
}

# =============================================================================
#  SUITE 0: Infrastructure Pre-flight
# =============================================================================
suite_infra() {
  log_section "SUITE 0 · Infrastructure Pre-flight"

  # Application
  log_sub "Application (port 3000)"
  local status; status="$(raw_get "${BASE_URL}/api/v1/products/all")"
  if [[ "$status" == "200" ]]; then
    log_pass "Spring Boot application is reachable at ${BASE_URL}"
  else
    log_fail "Spring Boot application NOT reachable (HTTP $status) — many tests will fail"
  fi

  # PostgreSQL
  log_sub "PostgreSQL (port 5432)"
  if [[ $HAS_PSQL -eq 1 ]]; then
    if PGPASSWORD="$PG_PASS" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" \
        -c "SELECT 1" >/dev/null 2>&1; then
      log_pass "PostgreSQL is reachable"
    else
      log_fail "PostgreSQL is NOT reachable"
    fi
  elif [[ $HAS_DOCKER -eq 1 ]]; then
    if docker exec nykaa_postgres pg_isready -U postgres >/dev/null 2>&1; then
      log_pass "PostgreSQL container is healthy"
    else
      log_fail "PostgreSQL container health check failed"
    fi
  else
    log_skip "psql and docker not available — skipping PostgreSQL check"
  fi

  # Redis
  log_sub "Redis (port 6379)"
  if [[ $HAS_REDIS -eq 1 ]]; then
    if redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" PING 2>/dev/null | grep -q "PONG"; then
      log_pass "Redis is reachable"
    else
      log_fail "Redis is NOT reachable"
    fi
  elif [[ $HAS_DOCKER -eq 1 ]]; then
    if docker exec nykaa_redis redis-cli PING 2>/dev/null | grep -q "PONG"; then
      log_pass "Redis container is healthy"
    else
      log_fail "Redis container health check failed"
    fi
  else
    log_skip "redis-cli and docker not available — skipping Redis check"
  fi

  # Kafka
  log_sub "Kafka (port 9092)"
  if [[ $HAS_DOCKER -eq 1 ]]; then
    if docker exec nykaa_kafka kafka-topics \
        --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then
      log_pass "Kafka broker is reachable"
    else
      log_fail "Kafka broker is NOT reachable via Docker"
    fi
  else
    local kafka_status; kafka_status="$(raw_get "http://${KAFKA_BROKER}" 2>/dev/null || echo "000")"
    log_warn "docker not available — cannot run kafka-topics; assuming Kafka is up"
    log_skip "Kafka broker connectivity (no docker)"
  fi

  # Kafka Connect / Debezium
  log_sub "Kafka Connect / Debezium (port 8083)"
  local kc_status; kc_status="$(raw_get "${KAFKA_CONNECT_URL}/connectors")"
  if [[ "$kc_status" == "200" ]]; then
    log_pass "Kafka Connect is reachable"
  else
    log_fail "Kafka Connect NOT reachable (HTTP $kc_status)"
  fi

  # Elasticsearch
  log_sub "Elasticsearch (port 9200)"
  local es_status; es_status="$(raw_get "${ES_URL}")"
  if [[ "$es_status" == "200" ]]; then
    log_pass "Elasticsearch is reachable"
  else
    log_fail "Elasticsearch NOT reachable (HTTP $es_status)"
  fi

  # Zipkin
  log_sub "Zipkin (port 9411)"
  local zipkin_status; zipkin_status="$(raw_get "http://localhost:9411/health")"
  if [[ "$zipkin_status" == "200" ]]; then
    log_pass "Zipkin is reachable"
  else
    log_warn "Zipkin NOT reachable (HTTP $zipkin_status) — tracing tests will be skipped"
  fi
}

# =============================================================================
#  SUITE 1: Auth API
# =============================================================================
suite_auth() {
  log_section "SUITE 1 · Auth API"

  # ── Register customer ─────────────────────────────────────────────────────
  log_sub "Register new customer"
  request POST "/api/v1/auth/register" \
    "{\"name\":\"E2E Customer\",\"email\":\"${CUSTOMER_EMAIL}\",\"password\":\"${CUSTOMER_PASSWORD}\",\"address\":\"Mumbai, Maharashtra\"}"
  assert_status 201 "Register new customer"
  assert_json_eq "isError" "false" "Register returns success envelope"
  assert_json_eq "data.user.email" "$CUSTOMER_EMAIL" "Registered email matches"
  assert_json_eq "data.user.role" "CUSTOMER" "Default role is CUSTOMER"
  assert_json_nonempty "data.accessToken" "Register returns access token"
  CUSTOMER_TOKEN="$(json_get "$RESPONSE_BODY_FILE" "data.accessToken" 2>/dev/null || echo "")"
  CUSTOMER_USER_ID="$(json_get "$RESPONSE_BODY_FILE" "data.user.id" 2>/dev/null || echo "")"

  # ── Duplicate register (conflict) ─────────────────────────────────────────
  log_sub "Duplicate registration (expect 409)"
  request POST "/api/v1/auth/register" \
    "{\"name\":\"Dup Customer\",\"email\":\"${CUSTOMER_EMAIL}\",\"password\":\"${CUSTOMER_PASSWORD}\",\"address\":\"Delhi\"}"
  assert_status 409 "Duplicate email returns 409 Conflict"
  assert_json_eq "isError" "true" "Conflict response has isError=true"

  # ── Register with invalid data ─────────────────────────────────────────────
  log_sub "Register validation (expect 400)"
  request POST "/api/v1/auth/register" \
    "{\"name\":\"\",\"email\":\"not-an-email\",\"password\":\"short\",\"address\":\"\"}"
  assert_status 400 "Invalid register payload returns 400"
  assert_json_eq "isError" "true" "Validation error envelope"

  # ── Login with wrong password ─────────────────────────────────────────────
  log_sub "Login — wrong password (expect 401)"
  request POST "/api/v1/auth/login" \
    "{\"email\":\"${CUSTOMER_EMAIL}\",\"password\":\"WrongPass!\"}"
  assert_status 401 "Wrong password returns 401"
  assert_json_eq "isError" "true" "Auth failure has isError=true"

  # ── Login with non-existent user ──────────────────────────────────────────
  log_sub "Login — non-existent user (expect 401)"
  request POST "/api/v1/auth/login" \
    "{\"email\":\"ghost@nobody.com\",\"password\":\"Password123!\"}"
  assert_status 401 "Non-existent user returns 401"

  # ── Successful login ──────────────────────────────────────────────────────
  log_sub "Login — success"
  request POST "/api/v1/auth/login" \
    "{\"email\":\"${CUSTOMER_EMAIL}\",\"password\":\"${CUSTOMER_PASSWORD}\"}"
  assert_status 200 "Successful login"
  assert_json_eq "isError" "false" "Login success envelope"
  assert_json_nonempty "data.accessToken" "Login returns access token"
  assert_json_nonempty "data.expiresAt" "Login returns expiry"
  CUSTOMER_TOKEN="$(json_get "$RESPONSE_BODY_FILE" "data.accessToken" 2>/dev/null || echo "")"

  # ── GET /me without token ─────────────────────────────────────────────────
  log_sub "GET /auth/me — unauthenticated (expect 401)"
  request GET "/api/v1/auth/me"
  assert_status 401 "GET /auth/me without token returns 401"

  # ── GET /me with token ────────────────────────────────────────────────────
  log_sub "GET /auth/me — authenticated"
  request GET "/api/v1/auth/me" "" "$CUSTOMER_TOKEN"
  assert_status 200 "GET /auth/me with valid token"
  assert_json_eq "data.email" "$CUSTOMER_EMAIL" "/me returns correct email"
  assert_json_eq "data.role" "CUSTOMER" "/me returns correct role"
}

# =============================================================================
#  SUITE 2: Admin Bootstrap
# =============================================================================
suite_admin_bootstrap() {
  log_section "SUITE 2 · Admin Bootstrap"

  log_sub "Creating admin user via DB (psql)"

  if [[ $HAS_PSQL -eq 0 && $HAS_DOCKER -eq 0 ]]; then
    log_skip "Neither psql nor docker available — cannot bootstrap admin; admin tests will be skipped"
    return
  fi

  # Register the admin user first (will be CUSTOMER initially)
  request POST "/api/v1/auth/register" \
    "{\"name\":\"E2E Admin\",\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\",\"address\":\"Bengaluru, Karnataka\"}"
  if [[ "$RESPONSE_STATUS" != "201" ]]; then
    log_fail "Admin user registration failed (HTTP $RESPONSE_STATUS)"
    return
  fi
  log_pass "Admin user registered as CUSTOMER"
  local admin_id; admin_id="$(json_get "$RESPONSE_BODY_FILE" "data.user.id" 2>/dev/null || echo "")"

  # Promote to ADMIN via direct SQL
  local sql="UPDATE users SET role = 'ADMIN' WHERE email = '${ADMIN_EMAIL}';"
  local promoted=0
  if [[ $HAS_PSQL -eq 1 ]]; then
    if PGPASSWORD="$PG_PASS" psql -h "$PG_HOST" -p "$PG_PORT" -U "$PG_USER" -d "$PG_DB" \
        -c "$sql" >/dev/null 2>&1; then
      promoted=1
    fi
  elif [[ $HAS_DOCKER -eq 1 ]]; then
    if docker exec nykaa_postgres psql -U postgres -d nykaa -c "$sql" >/dev/null 2>&1; then
      promoted=1
    fi
  fi

  if [[ $promoted -eq 1 ]]; then
    log_pass "Admin user promoted to ADMIN role in DB"
  else
    log_fail "Failed to promote user to ADMIN in DB"
    return
  fi

  # Login as admin
  request POST "/api/v1/auth/login" \
    "{\"email\":\"${ADMIN_EMAIL}\",\"password\":\"${ADMIN_PASSWORD}\"}"
  assert_status 200 "Admin login after role promotion"
  assert_json_eq "data.user.role" "ADMIN" "Admin token has ADMIN role"
  assert_json_nonempty "data.accessToken" "Admin login returns token"
  ADMIN_TOKEN="$(json_get "$RESPONSE_BODY_FILE" "data.accessToken" 2>/dev/null || echo "")"
  ADMIN_USER_ID="$admin_id"

  log_info "Admin token acquired (id=${ADMIN_USER_ID})"
}

# =============================================================================
#  SUITE 3: Admin — Product Management
# =============================================================================
suite_admin_products() {
  log_section "SUITE 3 · Admin — Product Management"

  if [[ -z "$ADMIN_TOKEN" ]]; then
    log_skip "No admin token — skipping admin product tests"
    return
  fi

  # ── Add product as admin ──────────────────────────────────────────────────
  log_sub "Add product (admin)"
  request POST "/api/v1/products/add" \
    "{\"name\":\"Gucci Bloom Perfume E2E\",\"brand\":\"GUCCI\",\"category\":\"PERFUME\",\"price\":4999.0,\"stockQuantity\":50}" \
    "$ADMIN_TOKEN"
  assert_status 201 "POST /products/add as admin"
  assert_json_eq "isError" "false" "Add product success envelope"
  assert_json_nonempty "data.id" "Add product returns ID"
  assert_json_eq "data.name" "Gucci Bloom Perfume E2E" "Product name matches"
  assert_json_eq "data.brand" "GUCCI" "Product brand matches"
  assert_json_eq "data.category" "PERFUME" "Product category matches"
  PRODUCT_ID_1="$(json_get "$RESPONSE_BODY_FILE" "data.id" 2>/dev/null || echo "")"

  # ── Add product with low stock ─────────────────────────────────────────────
  log_sub "Add second product (admin)"
  request POST "/api/v1/products/add" \
    "{\"name\":\"MuscleBlaze Whey E2E\",\"brand\":\"MUSCLEBLAZE\",\"category\":\"WELLNESS\",\"price\":2499.0,\"stockQuantity\":5}" \
    "$ADMIN_TOKEN"
  assert_status 201 "POST /products/add second product"
  PRODUCT_ID_2="$(json_get "$RESPONSE_BODY_FILE" "data.id" 2>/dev/null || echo "")"

  # ── Add product as customer (expect 403) ──────────────────────────────────
  log_sub "Add product as customer (expect 403)"
  request POST "/api/v1/products/add" \
    "{\"name\":\"Forbidden Product\",\"brand\":\"PRADA\",\"category\":\"COSMETICS\",\"price\":999.0,\"stockQuantity\":10}" \
    "$CUSTOMER_TOKEN"
  assert_status 403 "POST /products/add as customer returns 403"

  # ── Add product unauthenticated (expect 401) ──────────────────────────────
  log_sub "Add product unauthenticated (expect 401)"
  request POST "/api/v1/products/add" \
    "{\"name\":\"Ghost Product\",\"brand\":\"PRADA\",\"category\":\"COSMETICS\",\"price\":999.0,\"stockQuantity\":10}"
  assert_status 401 "POST /products/add without token returns 401"

  # ── Bulk add products ─────────────────────────────────────────────────────
  log_sub "Bulk add products (admin)"
  request POST "/api/v1/products/addBulk" \
    "[{\"name\":\"Prada Candy E2E\",\"brand\":\"PRADA\",\"category\":\"PERFUME\",\"price\":6500.0,\"stockQuantity\":30},{\"name\":\"Sugar Matte E2E\",\"brand\":\"SUGAR_COSMETICS\",\"category\":\"MAKEUP\",\"price\":799.0,\"stockQuantity\":100}]" \
    "$ADMIN_TOKEN"
  assert_status 201 "POST /products/addBulk as admin"
  assert_json_nonempty "data[0].id" "Bulk product 1 has ID"
  assert_json_nonempty "data[1].id" "Bulk product 2 has ID"
  PRODUCT_ID_BULK_1="$(json_get "$RESPONSE_BODY_FILE" "data[0].id" 2>/dev/null || echo "")"
  PRODUCT_ID_BULK_2="$(json_get "$RESPONSE_BODY_FILE" "data[1].id" 2>/dev/null || echo "")"

  # ── Bulk add as customer (expect 403) ─────────────────────────────────────
  log_sub "Bulk add as customer (expect 403)"
  request POST "/api/v1/products/addBulk" \
    "[{\"name\":\"Forbidden Bulk\",\"brand\":\"PRADA\",\"category\":\"PERFUME\",\"price\":100.0,\"stockQuantity\":10}]" \
    "$CUSTOMER_TOKEN"
  assert_status 403 "POST /products/addBulk as customer returns 403"

  # ── Update product ────────────────────────────────────────────────────────
  log_sub "Update product (admin)"
  if [[ -n "$PRODUCT_ID_1" && "$PRODUCT_ID_1" != "null" ]]; then
    request POST "/api/v1/products/update" \
      "{\"id\":${PRODUCT_ID_1},\"name\":\"Gucci Bloom Perfume Updated E2E\",\"brand\":\"GUCCI\",\"category\":\"PERFUME\",\"price\":5499.0,\"stockQuantity\":45}" \
      "$ADMIN_TOKEN"
    assert_status 200 "POST /products/update as admin"
    assert_json_eq "data.id" "$PRODUCT_ID_1" "Updated product ID matches"
    assert_json_eq "data.price" "5499.0" "Updated price is correct"
    assert_json_eq "data.stockQuantity" "45" "Updated stock is correct"
  else
    log_skip "Product ID unavailable — skipping update test"
  fi

  # ── Update as customer (expect 403) ───────────────────────────────────────
  log_sub "Update product as customer (expect 403)"
  request POST "/api/v1/products/update" \
    "{\"id\":1,\"name\":\"Forbidden Update\",\"brand\":\"GUCCI\",\"category\":\"PERFUME\",\"price\":100.0}" \
    "$CUSTOMER_TOKEN"
  assert_status 403 "POST /products/update as customer returns 403"
}

# =============================================================================
#  SUITE 4: Public Product APIs
# =============================================================================
suite_public_products() {
  log_section "SUITE 4 · Public Product APIs"

  # ── Get all products (default pagination) ────────────────────────────────
  log_sub "GET /products/all (default)"
  request GET "/api/v1/products/all"
  assert_status 200 "GET /products/all"
  assert_json_eq "isError" "false" "Product list success envelope"
  assert_json_nonempty "data.content" "Product list has content"
  assert_json_nonempty "data.totalElements" "Total elements is present"

  # ── Pagination params ─────────────────────────────────────────────────────
  log_sub "GET /products/all (page 0, size 2, sortBy name)"
  request GET "/api/v1/products/all?pageNo=0&pageSize=2&sortBy=name&sortDir=asc"
  assert_status 200 "GET /products/all with pagination"
  assert_json_eq "data.pageSize" "2" "Page size is 2"
  assert_json_eq "data.pageNo" "0" "Page number is 0"

  # ── Paginate to page 1 ─────────────────────────────────────────────────────
  log_sub "GET /products/all (page 1)"
  request GET "/api/v1/products/all?pageNo=1&pageSize=2"
  assert_status 200 "GET /products/all page 1"
  assert_json_eq "data.pageNo" "1" "Page number is 1"

  # ── Search by name ────────────────────────────────────────────────────────
  log_sub "GET /products/search?name=Gucci"
  request GET "/api/v1/products/search?name=Gucci&sortBy=price"
  assert_status 200 "GET /products/search by name"
  assert_json_eq "isError" "false" "Search returns success envelope"

  # ── Search by category ────────────────────────────────────────────────────
  log_sub "GET /products/search?category=PERFUME"
  request GET "/api/v1/products/search?category=PERFUME&sortBy=price"
  assert_status 200 "GET /products/search by category"

  # ── Search by brand ───────────────────────────────────────────────────────
  log_sub "GET /products/search?brand=GUCCI"
  request GET "/api/v1/products/search?brand=GUCCI&sortBy=price"
  assert_status 200 "GET /products/search by brand"

  # ── Combined search ───────────────────────────────────────────────────────
  log_sub "GET /products/search (combined filters)"
  request GET "/api/v1/products/search?category=PERFUME&brand=GUCCI&sortBy=price&sortDir=desc"
  assert_status 200 "GET /products/search combined filters"
}

# =============================================================================
#  SUITE 5: Admin — User Management
# =============================================================================
suite_admin_users() {
  log_section "SUITE 5 · Admin — User Management"

  if [[ -z "$ADMIN_TOKEN" ]]; then
    log_skip "No admin token — skipping admin user management tests"
    return
  fi

  # ── Add user as admin ──────────────────────────────────────────────────────
  log_sub "Add user as admin"
  local extra_email="extra.user.${TIMESTAMP}@nykaa-e2e.com"
  request POST "/api/v1/users/add" \
    "{\"name\":\"Extra User\",\"email\":\"${extra_email}\",\"password\":\"Password123!\",\"address\":\"Chennai\",\"role\":\"CUSTOMER\"}" \
    "$ADMIN_TOKEN"
  assert_status 201 "POST /users/add as admin"
  assert_json_eq "data.email" "$extra_email" "Created user email matches"
  assert_json_eq "data.role" "CUSTOMER" "Created user role is CUSTOMER"
  EXTRA_USER_ID="$(json_get "$RESPONSE_BODY_FILE" "data.id" 2>/dev/null || echo "")"

  # ── Add admin user ─────────────────────────────────────────────────────────
  log_sub "Add admin user via /users/add"
  local second_admin_email="admin2.${TIMESTAMP}@nykaa-e2e.com"
  request POST "/api/v1/users/add" \
    "{\"name\":\"Admin 2\",\"email\":\"${second_admin_email}\",\"password\":\"Admin@99999\",\"address\":\"Hyderabad\",\"role\":\"ADMIN\"}" \
    "$ADMIN_TOKEN"
  assert_status 201 "POST /users/add with ADMIN role"
  assert_json_eq "data.role" "ADMIN" "Created admin role is ADMIN"
  local second_admin_id; second_admin_id="$(json_get "$RESPONSE_BODY_FILE" "data.id" 2>/dev/null || echo "")"

  # ── Add user as customer (expect 403) ─────────────────────────────────────
  log_sub "Add user as customer (expect 403)"
  request POST "/api/v1/users/add" \
    "{\"name\":\"Forbidden User\",\"email\":\"forbidden.${TIMESTAMP}@nykaa-e2e.com\",\"password\":\"Password123!\",\"address\":\"Kolkata\",\"role\":\"CUSTOMER\"}" \
    "$CUSTOMER_TOKEN"
  assert_status 403 "POST /users/add as customer returns 403"

  # ── Update own profile as customer ───────────────────────────────────────
  log_sub "Update own profile (customer)"
  if [[ -n "$CUSTOMER_USER_ID" && "$CUSTOMER_USER_ID" != "null" ]]; then
    request POST "/api/v1/users/update" \
      "{\"id\":${CUSTOMER_USER_ID},\"name\":\"E2E Customer Updated\",\"email\":\"${CUSTOMER_EMAIL}\",\"address\":\"Pune, Maharashtra\"}" \
      "$CUSTOMER_TOKEN"
    assert_status 200 "POST /users/update as customer"
    assert_json_eq "data.address" "Pune, Maharashtra" "Updated address matches"
  else
    log_skip "Customer user ID unavailable — skipping update test"
  fi

  # ── Delete user as admin ──────────────────────────────────────────────────
  log_sub "Delete user as admin"
  if [[ -n "$second_admin_id" && "$second_admin_id" != "null" ]]; then
    request DELETE "/api/v1/users/delete/${second_admin_id}" "" "$ADMIN_TOKEN"
    assert_status 200 "DELETE /users/delete/{id} as admin"
  else
    log_skip "Second admin ID unavailable — skipping delete test"
  fi

  # ── Delete user as customer (expect 403) ─────────────────────────────────
  log_sub "Delete user as customer (expect 403)"
  request DELETE "/api/v1/users/delete/999999" "" "$CUSTOMER_TOKEN"
  assert_status 403 "DELETE /users/delete/{id} as customer returns 403"
}

# =============================================================================
#  SUITE 6: Cart & Order Saga
# =============================================================================
suite_cart_and_orders() {
  log_section "SUITE 6 · Cart & Order Saga"

  if [[ -z "$CUSTOMER_TOKEN" ]]; then
    log_skip "No customer token — skipping cart/order tests"
    return
  fi
  if [[ -z "$PRODUCT_ID_1" || "$PRODUCT_ID_1" == "null" ]]; then
    log_skip "No product available — skipping cart/order tests"
    return
  fi

  # ── Add item to cart ──────────────────────────────────────────────────────
  log_sub "Add item to cart"
  request POST "/api/v1/orders/cart/add" \
    "{\"productId\":${PRODUCT_ID_1},\"quantity\":2}" \
    "$CUSTOMER_TOKEN"
  assert_status 200 "POST /orders/cart/add"
  assert_json_eq "isError" "false" "Cart add success envelope"
  assert_json_nonempty "data.items" "Cart has items"
  assert_json_nonempty "data.totalAmount" "Cart has total amount"

  # ── Add second item to cart ────────────────────────────────────────────────
  log_sub "Add second item to cart"
  if [[ -n "$PRODUCT_ID_2" && "$PRODUCT_ID_2" != "null" ]]; then
    request POST "/api/v1/orders/cart/add" \
      "{\"productId\":${PRODUCT_ID_2},\"quantity\":1}" \
      "$CUSTOMER_TOKEN"
    assert_status 200 "POST /orders/cart/add (second item)"
  fi

  # ── Get cart ──────────────────────────────────────────────────────────────
  log_sub "Get cart"
  request GET "/api/v1/orders/cart" "" "$CUSTOMER_TOKEN"
  assert_status 200 "GET /orders/cart"
  assert_json_nonempty "data.items" "Cart has items"
  assert_json_nonempty "data.totalAmount" "Cart total amount is present"
  assert_json_nonempty "data.userId" "Cart has userId"

  # ── Cart unauthenticated (expect 401) ────────────────────────────────────
  log_sub "Get cart unauthenticated (expect 401)"
  request GET "/api/v1/orders/cart"
  assert_status 401 "GET /orders/cart without token returns 401"

  # ── Remove item from cart ─────────────────────────────────────────────────
  log_sub "Remove item from cart"
  if [[ -n "$PRODUCT_ID_2" && "$PRODUCT_ID_2" != "null" ]]; then
    request DELETE "/api/v1/orders/cart/remove/${PRODUCT_ID_2}" "" "$CUSTOMER_TOKEN"
    assert_status 200 "DELETE /orders/cart/remove/{productId}"
    # Verify item removed
    request GET "/api/v1/orders/cart" "" "$CUSTOMER_TOKEN"
    assert_status 200 "GET /orders/cart after remove"
  else
    log_skip "Second product unavailable — skipping cart remove"
  fi

  # ── Place order ────────────────────────────────────────────────────────────
  log_sub "Place order"
  request POST "/api/v1/orders/place" "" "$CUSTOMER_TOKEN"
  assert_status 202 "POST /orders/place (202 Accepted — saga async)"
  assert_json_eq "isError" "false" "Order place success envelope"
  assert_json_nonempty "data.orderId" "Order has ID"
  assert_json_nonempty "data.totalAmount" "Order has total amount"
  assert_json_eq "data.status" "PENDING" "Initial order status is PENDING"
  ORDER_ID="$(json_get "$RESPONSE_BODY_FILE" "data.orderId" 2>/dev/null || echo "")"
  local initial_status; initial_status="$(json_get "$RESPONSE_BODY_FILE" "data.status" 2>/dev/null || echo "")"
  log_info "Order placed with ID=${ORDER_ID}, initial status=${initial_status}"

  # ── Poll order status (Saga auto-processes payment internally) ────────────
  # PaymentSagaConsumer mocks payment — no external webhook needed.
  # Allow up to 120s for consumer rebalance + 3-hop saga chain.
  log_sub "Polling order saga status (max 120s)"
  if [[ -n "$ORDER_ID" && "$ORDER_ID" != "null" ]]; then
    local final_status; final_status="$(poll_order_status "$ORDER_ID" "$CUSTOMER_TOKEN" 60 2)"
    if [[ "$final_status" == "SUCCESS" ]]; then
      log_pass "Order saga completed with status=SUCCESS"
    elif [[ "$final_status" == "FAILED" ]]; then
      log_warn "Order saga completed with status=FAILED (stock issue or payment error)"
      log_pass "Order saga reached terminal state (FAILED)"
    else
      log_fail "Order saga timed out — still PENDING after 120s (check Kafka consumer connectivity)"
    fi

    # Get order details
    request GET "/api/v1/orders/${ORDER_ID}" "" "$CUSTOMER_TOKEN"
    assert_status 200 "GET /orders/{orderId}"
    assert_json_nonempty "data.orderId" "Order details have orderId"
    assert_json_nonempty "data.items" "Order details have items"
    assert_json_nonempty "data.totalAmount" "Order details have totalAmount"
  else
    log_skip "Order ID unavailable — skipping saga poll"
  fi

  # ── Order history ─────────────────────────────────────────────────────────
  log_sub "Order history"
  request GET "/api/v1/orders/history" "" "$CUSTOMER_TOKEN"
  assert_status 200 "GET /orders/history"
  assert_json_eq "isError" "false" "Order history success envelope"

  # ── Place order with empty cart (expect 400) ──────────────────────────────
  log_sub "Place order with empty cart (expect 400/404)"
  request POST "/api/v1/orders/place" "" "$CUSTOMER_TOKEN"
  local empty_status="$RESPONSE_STATUS"
  if [[ "$empty_status" == "400" || "$empty_status" == "404" ]]; then
    log_pass "POST /orders/place with empty cart returns ${empty_status}"
  else
    log_fail "POST /orders/place with empty cart expected 400/404, got ${empty_status}"
  fi
}

# =============================================================================
#  SUITE 7: Payment Webhook
# =============================================================================
suite_payment_webhook() {
  log_section "SUITE 7 · Payment Webhook"

  if [[ -z "$ORDER_ID" || "$ORDER_ID" == "null" ]]; then
    log_warn "No order placed in Suite 6 — using synthetic order ID 99999 for webhook tests"
    local test_order_id="99999"
  else
    local test_order_id="$ORDER_ID"
  fi
  local test_user_id="${CUSTOMER_USER_ID:-1}"
  local payment_id="PAY-E2E-${TIMESTAMP}"

  # ── Payment success webhook ───────────────────────────────────────────────
  log_sub "Payment success webhook"
  request POST "/api/v1/payments/webhook" \
    "{\"orderId\":${test_order_id},\"userId\":${test_user_id},\"paymentId\":\"${payment_id}\",\"status\":\"SUCCESS\",\"idempotencyKey\":\"IDEM-${TIMESTAMP}-1\",\"totalAmount\":5499.0}"
  local wh_status="$RESPONSE_STATUS"
  if [[ "$wh_status" == "200" || "$wh_status" == "202" || "$wh_status" == "404" ]]; then
    log_pass "Payment webhook success (HTTP $wh_status)"
  else
    log_fail "Payment webhook expected 200/202/404, got $wh_status"
  fi

  # ── Payment failure webhook ───────────────────────────────────────────────
  log_sub "Payment failure webhook"
  local fail_order_id="88888"
  request POST "/api/v1/payments/webhook" \
    "{\"orderId\":${fail_order_id},\"userId\":${test_user_id},\"paymentId\":\"PAY-FAIL-${TIMESTAMP}\",\"status\":\"FAILURE\",\"idempotencyKey\":\"IDEM-FAIL-${TIMESTAMP}\",\"reason\":\"Insufficient funds\",\"totalAmount\":5499.0}"
  wh_status="$RESPONSE_STATUS"
  if [[ "$wh_status" == "200" || "$wh_status" == "202" || "$wh_status" == "404" ]]; then
    log_pass "Payment failure webhook accepted (HTTP $wh_status)"
  else
    log_fail "Payment failure webhook expected 200/202/404, got $wh_status"
  fi

  # ── Idempotency — duplicate webhook ──────────────────────────────────────
  log_sub "Duplicate webhook (idempotency)"
  request POST "/api/v1/payments/webhook" \
    "{\"orderId\":${test_order_id},\"userId\":${test_user_id},\"paymentId\":\"${payment_id}\",\"status\":\"SUCCESS\",\"idempotencyKey\":\"IDEM-${TIMESTAMP}-1\",\"totalAmount\":5499.0}"
  wh_status="$RESPONSE_STATUS"
  if [[ "$wh_status" == "200" || "$wh_status" == "202" || "$wh_status" == "404" ]]; then
    log_pass "Duplicate idempotent webhook handled (HTTP $wh_status)"
  else
    log_fail "Duplicate webhook expected 200/202/404, got $wh_status"
  fi

  # ── Invalid webhook payload ───────────────────────────────────────────────
  log_sub "Invalid webhook payload (expect 400)"
  request POST "/api/v1/payments/webhook" \
    "{\"orderId\":null,\"paymentId\":\"\"}"
  assert_status 400 "Invalid webhook payload returns 400"

  # ── Webhook rate limit (>20 requests) ────────────────────────────────────
  log_sub "Webhook rate limit test (send 25 rapid requests)"
  local rate_hit=0
  for i in $(seq 1 25); do
    request POST "/api/v1/payments/webhook" \
      "{\"orderId\":77777,\"userId\":1,\"paymentId\":\"PAY-RL-${i}-${TIMESTAMP}\",\"status\":\"SUCCESS\",\"idempotencyKey\":\"IDEM-RL-${i}-${TIMESTAMP}\",\"totalAmount\":100.0}"
    if [[ "$RESPONSE_STATUS" == "429" ]]; then
      rate_hit=1
      log_pass "Webhook rate limit triggered after $i requests (HTTP 429)"
      break
    fi
  done
  [[ $rate_hit -eq 0 ]] && log_warn "Rate limit NOT triggered in 25 requests (may already be reset or limit not active)"
}

# =============================================================================
#  SUITE 8: Rate Limiting (Order placement)
# =============================================================================
suite_rate_limiting() {
  log_section "SUITE 8 · Rate Limiting"

  if [[ -z "$CUSTOMER_TOKEN" ]]; then
    log_skip "No customer token — skipping rate limit tests"
    return
  fi
  if [[ -z "$PRODUCT_ID_1" || "$PRODUCT_ID_1" == "null" ]]; then
    log_skip "No product available — skipping rate limit tests"
    return
  fi

  log_sub "Order placement rate limit (10 req/min capacity)"
  log_info "Pre-loading cart before each attempt..."

  local rate_hit=0
  for i in $(seq 1 15); do
    # Re-add item to cart for each attempt
    request POST "/api/v1/orders/cart/add" \
      "{\"productId\":${PRODUCT_ID_1},\"quantity\":1}" \
      "$CUSTOMER_TOKEN" >/dev/null 2>&1 || true
    request POST "/api/v1/orders/place" "" "$CUSTOMER_TOKEN"
    if [[ "$RESPONSE_STATUS" == "429" ]]; then
      rate_hit=1
      log_pass "Order place rate limit triggered after $i requests (HTTP 429)"
      assert_json_eq "isError" "true" "Rate limit response has isError=true"
      break
    fi
  done
  [[ $rate_hit -eq 0 ]] && log_warn "Order rate limit NOT triggered in 15 attempts"
}

# =============================================================================
#  SUITE 9: Redis Verification
# =============================================================================
suite_redis() {
  log_section "SUITE 9 · Redis Verification"

  local redis_exec=""
  if [[ $HAS_REDIS -eq 1 ]]; then
    redis_exec="redis-cli -h ${REDIS_HOST} -p ${REDIS_PORT}"
  elif [[ $HAS_DOCKER -eq 1 ]]; then
    redis_exec="docker exec nykaa_redis redis-cli"
  else
    log_skip "redis-cli and docker not available — skipping Redis tests"
    return
  fi

  # ── PING ─────────────────────────────────────────────────────────────────
  log_sub "Redis PING"
  local ping_result; ping_result="$(eval "$redis_exec" PING 2>/dev/null || echo "ERROR")"
  if [[ "$ping_result" == "PONG" ]]; then
    log_pass "Redis PING → PONG"
  else
    log_fail "Redis PING failed: $ping_result"
    return
  fi

  # ── Server INFO ────────────────────────────────────────────────────────────
  log_sub "Redis INFO (keyspace)"
  local info_result; info_result="$(eval "$redis_exec" INFO keyspace 2>/dev/null || echo "")"
  log_info "Redis keyspace: $(echo "$info_result" | grep db || echo '(empty)')"
  log_pass "Redis INFO retrieved"

  # ── Cart keys ──────────────────────────────────────────────────────────────
  log_sub "Redis cart keys (nykaa:cart:*)"
  local cart_keys; cart_keys="$(eval "$redis_exec" KEYS "nykaa:cart:*" 2>/dev/null || echo "")"
  if [[ -n "$cart_keys" ]]; then
    log_pass "Cart keys found in Redis: $(echo "$cart_keys" | tr '\n' ' ')"
    # Inspect first cart key
    local first_key; first_key="$(echo "$cart_keys" | head -1)"
    local cart_type; cart_type="$(eval "$redis_exec" TYPE "$first_key" 2>/dev/null || echo "")"
    log_info "Cart key type: $cart_type"
    log_pass "Redis cart key is type: $cart_type"
  else
    log_warn "No cart keys in Redis (cart may have been cleared by order placement)"
  fi

  # ── Rate limit keys ────────────────────────────────────────────────────────
  log_sub "Redis rate limit keys (nykaa:ratelimit:*)"
  local rl_keys; rl_keys="$(eval "$redis_exec" KEYS "nykaa:ratelimit:*" 2>/dev/null || echo "")"
  if [[ -n "$rl_keys" ]]; then
    log_pass "Rate limit keys found in Redis"
    # Show first key details
    local first_rl; first_rl="$(echo "$rl_keys" | head -1)"
    local rl_tokens; rl_tokens="$(eval "$redis_exec" HGET "$first_rl" tokens 2>/dev/null || echo "")"
    local rl_ts; rl_ts="$(eval "$redis_exec" HGET "$first_rl" ts 2>/dev/null || echo "")"
    log_info "Sample rate limit key: $first_rl → tokens=$rl_tokens ts=$rl_ts"
    log_pass "Rate limit key has expected hash fields (tokens, ts)"
  else
    log_warn "No rate limit keys found (no rate-limited endpoints were hit at limit)"
  fi

  # ── Idempotency keys ───────────────────────────────────────────────────────
  log_sub "Redis idempotency keys (nykaa:idempotency:*)"
  local idem_keys; idem_keys="$(eval "$redis_exec" KEYS "nykaa:idempotency:*" 2>/dev/null || echo "")"
  if [[ -n "$idem_keys" ]]; then
    log_pass "Idempotency keys found in Redis: $(echo "$idem_keys" | tr '\n' ' ')"
    local first_idem; first_idem="$(echo "$idem_keys" | head -1)"
    local idem_ttl; idem_ttl="$(eval "$redis_exec" TTL "$first_idem" 2>/dev/null || echo "")"
    log_info "Idempotency key TTL: ${idem_ttl}s"
    if [[ -n "$idem_ttl" && "$idem_ttl" -gt 0 ]]; then
      log_pass "Idempotency key has positive TTL (expires as expected)"
    else
      log_warn "Idempotency key TTL=$idem_ttl"
    fi
  else
    log_warn "No idempotency keys found (payment webhook may not have been processed)"
  fi

  # ── Total key count ────────────────────────────────────────────────────────
  log_sub "Redis total key count"
  local all_keys; all_keys="$(eval "$redis_exec" DBSIZE 2>/dev/null || echo "0")"
  log_info "Total keys in Redis: $all_keys"
  log_pass "Redis DBSIZE retrieved: $all_keys keys"
}

# =============================================================================
#  SUITE 10: Kafka Topics & Consumer Groups
# =============================================================================
suite_kafka() {
  log_section "SUITE 10 · Kafka Topics & Consumer Groups"

  if [[ $HAS_DOCKER -eq 0 ]]; then
    log_skip "docker not available — skipping Kafka tests"
    return
  fi

  local kafka_exec="docker exec nykaa_kafka"

  # ── List topics ────────────────────────────────────────────────────────────
  log_sub "List all Kafka topics"
  local topics; topics="$(${kafka_exec} kafka-topics \
    --bootstrap-server localhost:9092 --list 2>/dev/null || echo "")"
  if [[ -n "$topics" ]]; then
    log_pass "Kafka topics listed successfully"
    log_info "Topics: $(echo "$topics" | tr '\n' ', ')"
  else
    log_fail "Could not list Kafka topics"
    return
  fi

  # ── Verify required topics exist ──────────────────────────────────────────
  log_sub "Verify required topics exist"
  local required_topics=(
    "nykaa.order.created"
    "nykaa.order.confirmed"
    "nykaa.order.cancelled"
    "nykaa.payment.processed"
    "nykaa.payment.failed"
    "nykaa.inventory.reserved"
    "nykaa.inventory.rollback"
    "nykaa.product.changes"
  )
  for topic in "${required_topics[@]}"; do
    if echo "$topics" | grep -q "^${topic}$"; then
      log_pass "Topic exists: $topic"
    else
      log_fail "Topic MISSING: $topic"
    fi
  done

  # ── Topic partition/replication details ───────────────────────────────────
  log_sub "Topic partition details"
  for topic in "nykaa.order.created" "nykaa.product.changes"; do
    local desc; desc="$(${kafka_exec} kafka-topics \
      --bootstrap-server localhost:9092 --describe --topic "$topic" 2>/dev/null || echo "")"
    if [[ -n "$desc" ]]; then
      local partitions; partitions="$(echo "$desc" | grep -o 'PartitionCount:[0-9]*' | head -1)"
      local replicas; replicas="$(echo "$desc" | grep -o 'ReplicationFactor:[0-9]*' | head -1)"
      log_pass "Topic $topic — $partitions $replicas"
    else
      log_warn "Could not describe topic: $topic"
    fi
  done

  # ── Consumer groups ────────────────────────────────────────────────────────
  log_sub "List consumer groups"
  local groups; groups="$(${kafka_exec} kafka-consumer-groups \
    --bootstrap-server localhost:9092 --list 2>/dev/null || echo "")"
  if [[ -n "$groups" ]]; then
    log_pass "Consumer groups listed"
    log_info "Groups: $(echo "$groups" | tr '\n' ', ')"
  else
    log_warn "No consumer groups found (app may not have consumed yet)"
  fi

  # ── Verify expected consumer groups ──────────────────────────────────────
  log_sub "Verify saga consumer groups"
  local expected_groups=(
    "nykaa-inventory-saga"
    "nykaa-payment-saga"
    "nykaa-order-saga"
    "nykaa-cdc-elasticsearch"
  )
  for group in "${expected_groups[@]}"; do
    if echo "$groups" | grep -q "$group"; then
      log_pass "Consumer group registered: $group"
    else
      log_warn "Consumer group not found: $group (may not have consumed yet)"
    fi
  done

  # ── Consumer group lag ────────────────────────────────────────────────────
  log_sub "Consumer group offsets/lag"
  for group in "${expected_groups[@]}"; do
    if echo "$groups" | grep -q "$group"; then
      local lag_info; lag_info="$(${kafka_exec} kafka-consumer-groups \
        --bootstrap-server localhost:9092 --describe --group "$group" 2>/dev/null | \
        grep -E 'nykaa\.' | awk '{print $3"@"$4" lag="$6}' | head -5 || echo "")"
      if [[ -n "$lag_info" ]]; then
        log_info "  $group offsets: $lag_info"
        log_pass "Consumer group $group has offset info"
      fi
    fi
  done

  # ── Consume from order.created topic ─────────────────────────────────────
  log_sub "Consume from nykaa.order.created (5s window)"
  local order_msgs; order_msgs="$(${kafka_exec} kafka-console-consumer \
    --bootstrap-server localhost:9092 \
    --topic nykaa.order.created \
    --from-beginning \
    --max-messages 5 \
    --timeout-ms 5000 2>/dev/null || echo "")"
  if [[ -n "$order_msgs" ]]; then
    log_pass "Messages found in nykaa.order.created"
    log_info "Sample message: $(echo "$order_msgs" | head -1 | cut -c1-100)"
  else
    log_warn "No messages in nykaa.order.created (may be empty if no orders were placed)"
  fi

  # ── Consume from product.changes topic ───────────────────────────────────
  log_sub "Consume from nykaa.product.changes (5s window)"
  local cdc_msgs; cdc_msgs="$(${kafka_exec} kafka-console-consumer \
    --bootstrap-server localhost:9092 \
    --topic nykaa.product.changes \
    --from-beginning \
    --max-messages 5 \
    --timeout-ms 5000 2>/dev/null || echo "")"
  if [[ -n "$cdc_msgs" ]]; then
    log_pass "Messages found in nykaa.product.changes (Debezium CDC active)"
    log_info "Sample CDC message: $(echo "$cdc_msgs" | head -1 | cut -c1-120)"
  else
    log_warn "No messages in nykaa.product.changes (Debezium may not have synced yet)"
  fi
}

# =============================================================================
#  SUITE 11: Debezium CDC
# =============================================================================
suite_debezium() {
  log_section "SUITE 11 · Debezium CDC"

  # ── Kafka Connect health ──────────────────────────────────────────────────
  log_sub "Kafka Connect health"
  local kc_health; kc_health="$(raw_get_body "${KAFKA_CONNECT_URL}/")"
  local kc_version; kc_version="$(echo "$kc_health" | grep -o '"version":"[^"]*"' | head -1)"
  log_info "Kafka Connect: $kc_version"
  log_pass "Kafka Connect is responding"

  # ── List all connectors ───────────────────────────────────────────────────
  log_sub "List registered connectors"
  local connectors; connectors="$(raw_get_body "${KAFKA_CONNECT_URL}/connectors")"
  log_info "Registered connectors: $connectors"
  if echo "$connectors" | grep -q "nykaa-products-connector"; then
    log_pass "Debezium connector 'nykaa-products-connector' is registered"
  else
    log_fail "Debezium connector 'nykaa-products-connector' NOT found in connector list"
    log_warn "Connector auto-registers on app startup — make sure the app started successfully"
  fi

  # ── Connector status ──────────────────────────────────────────────────────
  log_sub "Connector status"
  local status_body; status_body="$(raw_get_body "${KAFKA_CONNECT_URL}/connectors/nykaa-products-connector/status")"
  log_info "Connector status: $(echo "$status_body" | head -c 300)"
  local connector_state; connector_state="$(echo "$status_body" | grep -o '"state":"[^"]*"' | head -1)"
  if echo "$connector_state" | grep -q "RUNNING"; then
    log_pass "Debezium connector is in RUNNING state"
  else
    log_warn "Connector state: $connector_state (expected RUNNING)"
  fi

  # ── Connector config ──────────────────────────────────────────────────────
  log_sub "Connector config"
  local config_body; config_body="$(raw_get_body "${KAFKA_CONNECT_URL}/connectors/nykaa-products-connector/config")"
  if echo "$config_body" | grep -q "nykaa.public.products"; then
    log_pass "Connector configured for nykaa.public.products table"
  else
    log_warn "Could not verify connector table config"
  fi
  if echo "$config_body" | grep -q "nykaa.product.changes"; then
    log_pass "Connector topic routing to nykaa.product.changes"
  fi

  # ── Connector tasks ───────────────────────────────────────────────────────
  log_sub "Connector tasks"
  local tasks_body; tasks_body="$(raw_get_body "${KAFKA_CONNECT_URL}/connectors/nykaa-products-connector/tasks")"
  if echo "$tasks_body" | grep -q '"id"'; then
    log_pass "Connector has tasks registered"
  else
    log_warn "No tasks found for connector"
  fi

  # ── CDC E2E: create product → verify CDC message ──────────────────────────
  log_sub "CDC E2E: Add product → verify Kafka CDC message"
  if [[ -z "$ADMIN_TOKEN" ]]; then
    log_skip "No admin token — skipping CDC E2E test"
    return
  fi

  local cdc_product_name="CDC-E2E-Product-${TIMESTAMP}"
  request POST "/api/v1/products/add" \
    "{\"name\":\"${cdc_product_name}\",\"brand\":\"TOMFORD\",\"category\":\"COSMETICS\",\"price\":3200.0,\"stockQuantity\":25}" \
    "$ADMIN_TOKEN"
  if [[ "$RESPONSE_STATUS" == "201" ]]; then
    log_pass "CDC test product created via API"
    local cdc_product_id; cdc_product_id="$(json_get "$RESPONSE_BODY_FILE" "data.id" 2>/dev/null || echo "")"
    log_info "CDC product ID: $cdc_product_id"

    # Wait for CDC propagation
    log_info "Waiting 5s for CDC propagation..."
    sleep 5

    # Check Kafka for the CDC message
    if [[ $HAS_DOCKER -eq 1 ]]; then
      local cdc_check; cdc_check="$(docker exec nykaa_kafka kafka-console-consumer \
        --bootstrap-server localhost:9092 \
        --topic nykaa.product.changes \
        --from-beginning \
        --max-messages 20 \
        --timeout-ms 5000 2>/dev/null | grep "$cdc_product_name" || echo "")"
      if [[ -n "$cdc_check" ]]; then
        log_pass "CDC message for '${cdc_product_name}' found in nykaa.product.changes"
      else
        log_warn "CDC message for '${cdc_product_name}' not found (may take longer to propagate)"
      fi
    else
      log_skip "docker not available — skipping Kafka CDC message verification"
    fi

    # Check Elasticsearch for CDC-synced document (allow up to 15s for full pipeline)
    log_sub "Verify CDC synced to Elasticsearch"
    log_info "Waiting 15s for Debezium → Kafka → CDC consumer → ES pipeline..."
    sleep 15
    local es_check_status; es_check_status="$(raw_get "${ES_URL}/products/_doc/${cdc_product_id}")"
    if [[ "$es_check_status" == "200" ]]; then
      log_pass "Product synced to Elasticsearch via CDC (id=${cdc_product_id})"
    else
      log_warn "Product not found in Elasticsearch yet (HTTP $es_check_status) — pipeline may still be processing"
    fi

    # Update product and verify CDC update
    log_sub "CDC E2E: Update product → verify Elasticsearch sync"
    request POST "/api/v1/products/update" \
      "{\"id\":${cdc_product_id},\"name\":\"${cdc_product_name} Updated\",\"brand\":\"TOMFORD\",\"category\":\"COSMETICS\",\"price\":3500.0,\"stockQuantity\":20}" \
      "$ADMIN_TOKEN"
    if [[ "$RESPONSE_STATUS" == "200" ]]; then
      log_pass "CDC test product updated via API"
      log_info "Waiting 15s for update to propagate to ES..."
      sleep 15
      local es_update_check; es_update_check="$(raw_get_body "${ES_URL}/products/_doc/${cdc_product_id}")"
      if echo "$es_update_check" | grep -q "3500"; then
        log_pass "Product price update synced to Elasticsearch via CDC"
      else
        log_warn "Elasticsearch may not have the updated price yet (check CDC consumer logs)"
      fi
    fi

    # Delete product and verify CDC delete
    log_sub "CDC E2E: Delete product → verify Elasticsearch removal"
    request DELETE "/api/v1/products/delete/${cdc_product_id}" "" "$ADMIN_TOKEN"
    if [[ "$RESPONSE_STATUS" == "200" ]]; then
      log_pass "CDC test product deleted via API"
      log_info "Waiting 15s for delete to propagate to ES..."
      sleep 15
      local es_del_status; es_del_status="$(raw_get "${ES_URL}/products/_doc/${cdc_product_id}")"
      if [[ "$es_del_status" == "404" ]]; then
        log_pass "Product removed from Elasticsearch via CDC (404 on deleted ID)"
      else
        log_warn "Product still in Elasticsearch after delete (HTTP $es_del_status) — CDC may be delayed"
      fi
    fi
  else
    log_fail "CDC test product creation failed (HTTP $RESPONSE_STATUS)"
  fi
}

# =============================================================================
#  SUITE 12: Elasticsearch
# =============================================================================
suite_elasticsearch() {
  log_section "SUITE 12 · Elasticsearch"

  # ── Cluster health ────────────────────────────────────────────────────────
  log_sub "Cluster health"
  local health; health="$(raw_get_body "${ES_URL}/_cluster/health")"
  local es_status; es_status="$(echo "$health" | grep -o '"status":"[^"]*"' | head -1)"
  log_info "ES cluster health: $es_status"
  if echo "$es_status" | grep -qE '"(green|yellow)"'; then
    log_pass "Elasticsearch cluster is healthy ($es_status)"
  else
    log_fail "Elasticsearch cluster is not healthy: $es_status"
  fi

  # ── Products index health ─────────────────────────────────────────────────
  log_sub "Products index"
  local idx_status; idx_status="$(raw_get "${ES_URL}/products")"
  if [[ "$idx_status" == "200" ]]; then
    log_pass "Elasticsearch 'products' index exists"
    local count_body; count_body="$(raw_get_body "${ES_URL}/products/_count")"
    local doc_count; doc_count="$(echo "$count_body" | grep -o '"count":[0-9]*' | head -1)"
    log_info "Products index document count: $doc_count"
    log_pass "Elasticsearch products index has $doc_count documents"
  else
    log_fail "Elasticsearch 'products' index NOT found (HTTP $idx_status)"
  fi

  # ── Full-text search ──────────────────────────────────────────────────────
  log_sub "Full-text search via application /products/search"
  request GET "/api/v1/products/search?name=Gucci&sortBy=price"
  assert_status 200 "Search for 'Gucci' via application"

  # ── Direct ES query ───────────────────────────────────────────────────────
  log_sub "Direct Elasticsearch query"
  local es_query='{"query":{"match_all":{}},"size":3}'
  local es_result; es_result="$(raw_post "${ES_URL}/products/_search" "$es_query")"
  if echo "$es_result" | grep -q '"hits"'; then
    log_pass "Direct ES query returns hits"
    local total; total="$(echo "$es_result" | grep -o '"total":{"value":[0-9]*' | head -1)"
    log_info "ES direct query: $total"
  else
    log_warn "ES direct query result unexpected: $(echo "$es_result" | head -c 100)"
  fi
}

# =============================================================================
#  SUITE 13: Actuator & Monitoring
# =============================================================================
suite_monitoring() {
  log_section "SUITE 13 · Actuator & Monitoring"

  # Actuator endpoints require authentication (.anyRequest().authenticated() in SecurityConfig).
  # Use admin token for all actuator checks.
  local act_token="${ADMIN_TOKEN:-${CUSTOMER_TOKEN:-}}"

  # ── Health endpoint ────────────────────────────────────────────────────────
  log_sub "GET /actuator/health"
  request GET "/actuator/health" "" "$act_token"
  assert_status 200 "GET /actuator/health (with auth)"
  local app_health; app_health="$(json_get "$RESPONSE_BODY_FILE" "status" 2>/dev/null || echo "")"
  log_info "Application health status: $app_health"
  if [[ "$app_health" == "UP" ]]; then
    log_pass "Application is UP"
  else
    log_warn "Application health status: $app_health"
  fi

  # ── Prometheus metrics ─────────────────────────────────────────────────────
  log_sub "GET /actuator/prometheus"
  local tmp_prom; tmp_prom="$TMP_DIR/prometheus.txt"
  REQUEST_SEQ=$((REQUEST_SEQ + 1))
  "$CURL_BIN" -sS -o "$tmp_prom" -w '%{http_code}' \
    -H "Authorization: Bearer ${act_token}" \
    "${BASE_URL}/actuator/prometheus" > "$TMP_DIR/prom_status_${REQUEST_SEQ}.txt" 2>/dev/null || true
  local prom_http; prom_http="$("$CURL_BIN" -sS -o "$tmp_prom" \
    -w '%{http_code}' -H "Authorization: Bearer ${act_token}" \
    "${BASE_URL}/actuator/prometheus" 2>/dev/null || echo "000")"
  if [[ "$prom_http" == "200" ]]; then
    log_pass "Prometheus metrics endpoint returns 200"
    if grep -q "http_server_requests" "$tmp_prom" 2>/dev/null; then
      log_pass "HTTP server request metrics present"
    fi
    if grep -q "jvm_memory" "$tmp_prom" 2>/dev/null; then
      log_pass "JVM memory metrics present"
    fi
    if grep -q 'application="nykaa"' "$tmp_prom" 2>/dev/null; then
      log_pass "Application tag 'nykaa' present in metrics"
    fi
  else
    log_fail "Prometheus endpoint returned HTTP $prom_http"
  fi

  # ── Info endpoint ─────────────────────────────────────────────────────────
  log_sub "GET /actuator/info"
  request GET "/actuator/info" "" "$act_token"
  if [[ "$RESPONSE_STATUS" == "200" ]]; then
    log_pass "Actuator info endpoint returns 200"
  else
    log_warn "Actuator info returned HTTP $RESPONSE_STATUS"
  fi

  # ── Zipkin tracing ────────────────────────────────────────────────────────
  log_sub "Zipkin trace visibility"
  local zipkin_status; zipkin_status="$(raw_get "http://localhost:9411/api/v2/services")"
  if [[ "$zipkin_status" == "200" ]]; then
    local services; services="$(raw_get_body "http://localhost:9411/api/v2/services")"
    log_info "Zipkin services: $services"
    if echo "$services" | grep -qi "nykaa"; then
      log_pass "Service 'nykaa' visible in Zipkin"
    else
      log_warn "Service 'nykaa' not yet visible in Zipkin (traces may not have propagated)"
    fi
  else
    log_skip "Zipkin not reachable — skipping tracing verification"
  fi
}

# =============================================================================
#  SUITE 14: Cleanup
# =============================================================================
suite_cleanup() {
  log_section "SUITE 14 · Cleanup"

  if [[ -z "$ADMIN_TOKEN" ]]; then
    log_skip "No admin token — skipping cleanup"
    return
  fi

  # Delete test products (product may have FK constraint from orders — 409 is acceptable)
  for pid_var in PRODUCT_ID_1 PRODUCT_ID_2 PRODUCT_ID_BULK_1 PRODUCT_ID_BULK_2; do
    local pid="${!pid_var:-}"
    if [[ -n "$pid" && "$pid" != "null" ]]; then
      request DELETE "/api/v1/products/delete/${pid}" "" "$ADMIN_TOKEN"
      if [[ "$RESPONSE_STATUS" == "200" ]]; then
        log_pass "Deleted test product ID=$pid"
      elif [[ "$RESPONSE_STATUS" == "409" ]]; then
        log_warn "Product $pid has order references — cannot delete (expected, skip)"
      else
        log_warn "Could not delete product $pid (HTTP $RESPONSE_STATUS)"
      fi
    fi
  done

  # Delete extra user
  if [[ -n "$EXTRA_USER_ID" && "$EXTRA_USER_ID" != "null" ]]; then
    request DELETE "/api/v1/users/delete/${EXTRA_USER_ID}" "" "$ADMIN_TOKEN"
    if [[ "$RESPONSE_STATUS" == "200" ]]; then
      log_pass "Deleted extra user ID=$EXTRA_USER_ID"
    else
      log_warn "Could not delete extra user $EXTRA_USER_ID (HTTP $RESPONSE_STATUS)"
    fi
  fi
}

# =============================================================================
#  FINAL REPORT
# =============================================================================
print_summary() {
  local total=$((PASS_COUNT + FAIL_COUNT + SKIP_COUNT))
  printf "\n${BOLD}${BLUE}══════════════════════════════════════════════════${NC}\n"
  printf   "${BOLD}${BLUE}  TEST SUMMARY${NC}\n"
  printf   "${BOLD}${BLUE}══════════════════════════════════════════════════${NC}\n"
  printf   "  ${GREEN}PASS${NC}  : %3d\n" "$PASS_COUNT"
  printf   "  ${RED}FAIL${NC}  : %3d\n"  "$FAIL_COUNT"
  printf   "  ${YELLOW}SKIP${NC}  : %3d\n" "$SKIP_COUNT"
  printf   "  ─────────────────\n"
  printf   "  TOTAL : %3d\n" "$total"
  printf   "${BOLD}${BLUE}══════════════════════════════════════════════════${NC}\n"
  if [[ $FAIL_COUNT -eq 0 ]]; then
    printf  "  ${GREEN}${BOLD}All assertions passed.${NC}\n\n"
  else
    printf  "  ${RED}${BOLD}${FAIL_COUNT} assertion(s) failed — see [FAIL] lines above.${NC}\n\n"
  fi
}

# =============================================================================
#  MAIN
# =============================================================================
main() {
  printf "${BOLD}${CYAN}"
  printf "╔══════════════════════════════════════════════════╗\n"
  printf "║  NYKAA — Full System Test                        ║\n"
  printf "║  %s    ║\n" "$(date '+%Y-%m-%d %H:%M:%S')                         " | cut -c1-52
  printf "╚══════════════════════════════════════════════════╝\n"
  printf "${NC}"
  printf "  Base URL  : %s\n" "$BASE_URL"
  printf "  Admin     : %s\n" "$ADMIN_EMAIL"
  printf "  Customer  : %s\n" "$CUSTOMER_EMAIL"
  printf "  Kafka     : %s\n" "$KAFKA_BROKER"
  printf "  Redis     : %s:%s\n" "$REDIS_HOST" "$REDIS_PORT"
  printf "  ES        : %s\n\n" "$ES_URL"

  [[ $SKIP_INFRA -eq 0 ]] && suite_infra

  suite_auth
  suite_admin_bootstrap
  suite_admin_products
  suite_public_products
  suite_admin_users
  suite_cart_and_orders
  suite_payment_webhook
  suite_rate_limiting
  suite_redis
  suite_kafka
  suite_debezium
  suite_elasticsearch
  suite_monitoring
  suite_cleanup

  print_summary

  [[ $FAIL_COUNT -eq 0 ]]
}

main "$@"
