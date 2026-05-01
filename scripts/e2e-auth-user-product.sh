#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:3000}"
CURL_BIN="${CURL_BIN:-curl}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@example.com}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-admin}"
CUSTOMER_PASSWORD="${CUSTOMER_PASSWORD:-Password123!}"
TIMESTAMP="$(date +%s)"
CUSTOMER_EMAIL="${CUSTOMER_EMAIL:-e2e.customer.${TIMESTAMP}@example.com}"
CUSTOMER_UPDATED_EMAIL="${CUSTOMER_UPDATED_EMAIL:-e2e.customer.updated.${TIMESTAMP}@example.com}"

PASS_COUNT=0
SKIP_COUNT=0
TMP_DIR="$(mktemp -d)"
REQUEST_SEQ=0
RESPONSE_STATUS=""
RESPONSE_BODY_FILE=""
CUSTOMER_TOKEN=""
CUSTOMER_ID=""
ADMIN_TOKEN=""
ADMIN_USER_ID=""
ADMIN_PRODUCT_ID=""

cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

require_tool() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "[ERROR] Required tool not found: $1" >&2
    exit 1
  fi
}

log_step() {
  printf '\n==> %s\n' "$1"
}

pass() {
  PASS_COUNT=$((PASS_COUNT + 1))
  printf '[PASS] %s\n' "$1"
}

skip() {
  SKIP_COUNT=$((SKIP_COUNT + 1))
  printf '[SKIP] %s\n' "$1"
}

fail() {
  printf '[FAIL] %s\n' "$1" >&2
  if [[ -n "${RESPONSE_BODY_FILE:-}" && -f "${RESPONSE_BODY_FILE:-}" ]]; then
    echo '--- response body ---' >&2
    cat "$RESPONSE_BODY_FILE" >&2
    echo >&2
    echo '---------------------' >&2
  fi
  exit 1
}

request() {
  local method="$1"
  local path="$2"
  local body="${3:-}"
  local token="${4:-}"
  REQUEST_SEQ=$((REQUEST_SEQ + 1))
  RESPONSE_BODY_FILE="$TMP_DIR/response_${REQUEST_SEQ}.json"

  local -a args
  args=("$CURL_BIN" -sS -o "$RESPONSE_BODY_FILE" -w '%{http_code}' -X "$method" "$BASE_URL$path" -H 'Accept: application/json')

  if [[ -n "$token" ]]; then
    args+=(-H "Authorization: Bearer $token")
  fi

  if [[ -n "$body" ]]; then
    args+=(-H 'Content-Type: application/json' --data "$body")
  fi

  RESPONSE_STATUS="$("${args[@]}")"
}

json_get() {
  local file="$1"
  local expr="$2"

  "$PYTHON_BIN" - "$file" "$expr" <<'PY'
import json
import re
import sys

file_path = sys.argv[1]
expr = sys.argv[2]
with open(file_path, 'r', encoding='utf-8') as fh:
    data = json.load(fh)

current = data
for part in expr.split('.') if expr else []:
    matches = re.findall(r'([^\[\]]+)|\[(\d+)\]', part)
    for key, index in matches:
        if key:
            current = current[key]
        else:
            current = current[int(index)]

if isinstance(current, bool):
    print('true' if current else 'false')
elif current is None:
    print('null')
elif isinstance(current, (dict, list)):
    print(json.dumps(current))
else:
    print(str(current))
PY
}

assert_status() {
  local expected="$1"
  local context="$2"
  if [[ "$RESPONSE_STATUS" != "$expected" ]]; then
    fail "$context expected HTTP $expected but got $RESPONSE_STATUS"
  fi
  pass "$context returned HTTP $expected"
}

assert_json_equals() {
  local expr="$1"
  local expected="$2"
  local context="$3"
  local actual
  actual="$(json_get "$RESPONSE_BODY_FILE" "$expr")"
  if [[ "$actual" != "$expected" ]]; then
    fail "$context expected '$expected' at '$expr' but got '$actual'"
  fi
  pass "$context"
}

assert_json_not_empty() {
  local expr="$1"
  local context="$2"
  local actual
  actual="$(json_get "$RESPONSE_BODY_FILE" "$expr")"
  if [[ -z "$actual" || "$actual" == "null" ]]; then
    fail "$context expected non-empty value at '$expr'"
  fi
  pass "$context"
}

assert_server_reachable() {
  log_step "Checking server availability at $BASE_URL"
  request GET "/api/v1/products/all"
  if [[ "$RESPONSE_STATUS" != "200" ]]; then
    fail "Server is not reachable or /api/v1/products/all is failing"
  fi
  pass "Server is reachable"
}

run_public_flow() {
  log_step "Testing public endpoints"

  request GET "/api/v1/products/all"
  assert_status 200 "GET /api/v1/products/all"
  assert_json_equals "isError" "false" "Products list should return success envelope"

  request GET "/api/v1/auth/me"
  assert_status 401 "GET /api/v1/auth/me without token"
  assert_json_equals "isError" "true" "Unauthorized /auth/me should be an error"

  local register_body
  register_body=$(cat <<JSON
{
  "name": "E2E Customer",
  "email": "$CUSTOMER_EMAIL",
  "password": "$CUSTOMER_PASSWORD",
  "address": "Mumbai"
}
JSON
)

  request POST "/api/v1/auth/register" "$register_body"
  assert_status 201 "POST /api/v1/auth/register"
  assert_json_equals "isError" "false" "Register should return success envelope"
  assert_json_equals "data.user.email" "$CUSTOMER_EMAIL" "Registered email should match"
  assert_json_equals "data.user.role" "CUSTOMER" "Registered role should default to CUSTOMER"
  assert_json_not_empty "data.accessToken" "Register should return an access token"
  CUSTOMER_TOKEN="$(json_get "$RESPONSE_BODY_FILE" "data.accessToken")"
  CUSTOMER_ID="$(json_get "$RESPONSE_BODY_FILE" "data.user.id")"

  local login_body
  login_body=$(cat <<JSON
{
  "email": "$CUSTOMER_EMAIL",
  "password": "$CUSTOMER_PASSWORD"
}
JSON
)

  request POST "/api/v1/auth/login" "$login_body"
  assert_status 200 "POST /api/v1/auth/login"
  assert_json_equals "isError" "false" "Login should return success envelope"
  assert_json_not_empty "data.accessToken" "Login should return an access token"
  CUSTOMER_TOKEN="$(json_get "$RESPONSE_BODY_FILE" "data.accessToken")"

  request GET "/api/v1/auth/me" "" "$CUSTOMER_TOKEN"
  assert_status 200 "GET /api/v1/auth/me with customer token"
  assert_json_equals "data.email" "$CUSTOMER_EMAIL" "Authenticated customer email should match"

  local update_body
  update_body=$(cat <<JSON
{
  "id": $CUSTOMER_ID,
  "name": "E2E Customer Updated",
  "email": "$CUSTOMER_UPDATED_EMAIL",
  "address": "Pune"
}
JSON
)

  request POST "/api/v1/users/update" "$update_body" "$CUSTOMER_TOKEN"
  assert_status 200 "POST /api/v1/users/update"
  assert_json_equals "data.email" "$CUSTOMER_UPDATED_EMAIL" "Updated customer email should match"

  local relogin_body
  relogin_body=$(cat <<JSON
{
  "email": "$CUSTOMER_UPDATED_EMAIL",
  "password": "$CUSTOMER_PASSWORD"
}
JSON
)

  request POST "/api/v1/auth/login" "$relogin_body"
  assert_status 200 "POST /api/v1/auth/login after email update"
  CUSTOMER_TOKEN="$(json_get "$RESPONSE_BODY_FILE" "data.accessToken")"

  request GET "/api/v1/auth/me" "" "$CUSTOMER_TOKEN"
  assert_status 200 "GET /api/v1/auth/me after email update"
  assert_json_equals "data.email" "$CUSTOMER_UPDATED_EMAIL" "Updated token should resolve updated email"
}

run_customer_forbidden_flow() {
  log_step "Testing customer access restrictions"

  local add_user_body
  add_user_body=$(cat <<JSON
{
  "name": "Forbidden User",
  "email": "forbidden.user.${TIMESTAMP}@example.com",
  "password": "Password123!",
  "address": "Delhi",
  "role": "CUSTOMER"
}
JSON
)
  request POST "/api/v1/users/add" "$add_user_body" "$CUSTOMER_TOKEN"
  assert_status 403 "POST /api/v1/users/add as customer"

  request DELETE "/api/v1/users/delete/999999" "" "$CUSTOMER_TOKEN"
  assert_status 403 "DELETE /api/v1/users/delete/{id} as customer"

  local add_product_body
  add_product_body=$(cat <<JSON
{
  "name": "Forbidden Product",
  "brand": "GUCCI",
  "category": "MAKEUP",
  "price": 999.0
}
JSON
)
  request POST "/api/v1/products/add" "$add_product_body" "$CUSTOMER_TOKEN"
  assert_status 403 "POST /api/v1/products/add as customer"

  local update_product_body
  update_product_body=$(cat <<JSON
{
  "id": 999999,
  "name": "Forbidden Product Updated",
  "category": "MAKEUP",
  "brand": "GUCCI",
  "price": 1099.0
}
JSON
)
  request POST "/api/v1/products/update" "$update_product_body" "$CUSTOMER_TOKEN"
  assert_status 403 "POST /api/v1/products/update as customer"

  local bulk_body
  bulk_body=$(cat <<JSON
[
  {
    "name": "Bulk One",
    "brand": "PRADA",
    "category": "PERFUME",
    "price": 1500.0
  },
  {
    "name": "Bulk Two",
    "brand": "MUSCLEBLAZE",
    "category": "WELLNESS",
    "price": 1800.0
  }
]
JSON
)
  request POST "/api/v1/products/addBulk" "$bulk_body" "$CUSTOMER_TOKEN"
  assert_status 403 "POST /api/v1/products/addBulk as customer"

  request DELETE "/api/v1/products/delete/999999" "" "$CUSTOMER_TOKEN"
  assert_status 403 "DELETE /api/v1/products/delete/{id} as customer"
}

run_admin_flow() {
  log_step "Testing admin-only success flows"

  if [[ -z "$ADMIN_EMAIL" || -z "$ADMIN_PASSWORD" ]]; then
    skip "Admin credentials not provided; skipping admin success flow"
    return
  fi

  local admin_login_body
  admin_login_body=$(cat <<JSON
{
  "email": "$ADMIN_EMAIL",
  "password": "$ADMIN_PASSWORD"
}
JSON
)

  request POST "/api/v1/auth/login" "$admin_login_body"
  if [[ "$RESPONSE_STATUS" != "200" ]]; then
    skip "Admin login failed; skipping admin success flow"
    return
  fi

  ADMIN_TOKEN="$(json_get "$RESPONSE_BODY_FILE" "data.accessToken")"
  if [[ -z "$ADMIN_TOKEN" || "$ADMIN_TOKEN" == "null" ]]; then
    skip "Admin login did not return a token; skipping admin success flow"
    return
  fi
  pass "Admin login succeeded"

  request GET "/api/v1/auth/me" "" "$ADMIN_TOKEN"
  assert_status 200 "GET /api/v1/auth/me with admin token"

  local admin_user_email="e2e.admin.user.${TIMESTAMP}@example.com"
  local add_user_body
  add_user_body=$(cat <<JSON
{
  "name": "Admin Created User",
  "email": "$admin_user_email",
  "password": "Password123!",
  "address": "Bengaluru",
  "role": "CUSTOMER"
}
JSON
)

  request POST "/api/v1/users/add" "$add_user_body" "$ADMIN_TOKEN"
  assert_status 201 "POST /api/v1/users/add as admin"
  assert_json_equals "data.email" "$admin_user_email" "Admin-created user email should match"
  ADMIN_USER_ID="$(json_get "$RESPONSE_BODY_FILE" "data.id")"

  local add_product_body
  add_product_body=$(cat <<JSON
{
  "name": "Admin Product ${TIMESTAMP}",
  "brand": "PRADA",
  "category": "PERFUME",
  "price": 1999.0
}
JSON
)

  request POST "/api/v1/products/add" "$add_product_body" "$ADMIN_TOKEN"
  assert_status 201 "POST /api/v1/products/add as admin"
  assert_json_not_empty "data.id" "Admin-created product should return an id"
  ADMIN_PRODUCT_ID="$(json_get "$RESPONSE_BODY_FILE" "data.id")"

  local update_product_body
  update_product_body=$(cat <<JSON
{
  "id": $ADMIN_PRODUCT_ID,
  "name": "Admin Product Updated ${TIMESTAMP}",
  "category": "PERFUME",
  "brand": "GUCCI",
  "price": 2499.0
}
JSON
)

  request POST "/api/v1/products/update" "$update_product_body" "$ADMIN_TOKEN"
  assert_status 200 "POST /api/v1/products/update as admin"
  assert_json_equals "data.id" "$ADMIN_PRODUCT_ID" "Updated product id should match"

  local bulk_body
  bulk_body=$(cat <<JSON
[
  {
    "name": "Admin Bulk Product A ${TIMESTAMP}",
    "brand": "GUCCI",
    "category": "MAKEUP",
    "price": 999.0
  },
  {
    "name": "Admin Bulk Product B ${TIMESTAMP}",
    "brand": "MUSCLEBLAZE",
    "category": "WELLNESS",
    "price": 1499.0
  }
]
JSON
)

  request POST "/api/v1/products/addBulk" "$bulk_body" "$ADMIN_TOKEN"
  assert_status 201 "POST /api/v1/products/addBulk as admin"
  assert_json_not_empty "data[0].id" "Bulk product response should include created ids"
}

run_order_flow() {
  log_step "Testing Order and Cart flow"

  if [[ -z "$ADMIN_PRODUCT_ID" || "$ADMIN_PRODUCT_ID" == "null" ]]; then
    skip "Admin product was not created; skipping order tests"
    return
  fi

  local cart_add_body
  cart_add_body=$(cat <<JSON
{
  "productId": $ADMIN_PRODUCT_ID,
  "quantity": 2
}
JSON
)

  request POST "/api/v1/orders/cart/add" "$cart_add_body" "$CUSTOMER_TOKEN"
  assert_status 200 "POST /api/v1/orders/cart/add"
  assert_json_equals "data.status" "PENDING" "Cart status should be PENDING"
  assert_json_equals "data.items[0].productId" "$ADMIN_PRODUCT_ID" "Cart item should match added product"
  assert_json_equals "data.items[0].quantity" "2" "Cart item quantity should be 2"

  request GET "/api/v1/orders/cart" "" "$CUSTOMER_TOKEN"
  assert_status 200 "GET /api/v1/orders/cart"
  assert_json_equals "data.status" "PENDING" "Fetched cart status should be PENDING"
  assert_json_not_empty "data.items" "Fetched cart should have items"

  request POST "/api/v1/orders/place" "" "$CUSTOMER_TOKEN"
  assert_status 200 "POST /api/v1/orders/place"
  assert_json_equals "data.status" "SUCCESS" "Order status should transition to SUCCESS"

  # Now the cart should be empty (or not found) since the order was placed
  request GET "/api/v1/orders/cart" "" "$CUSTOMER_TOKEN"
  assert_status 400 "GET /api/v1/orders/cart after place (expect empty cart exception)"
}

cleanup_admin_resources() {
  log_step "Cleaning up admin resources"
  if [[ -n "$ADMIN_PRODUCT_ID" && "$ADMIN_PRODUCT_ID" != "null" ]]; then
    request DELETE "/api/v1/products/delete/$ADMIN_PRODUCT_ID" "" "$ADMIN_TOKEN"
    assert_status 200 "DELETE /api/v1/products/delete/{id} as admin"
  fi

  if [[ -n "$ADMIN_USER_ID" && "$ADMIN_USER_ID" != "null" ]]; then
    request DELETE "/api/v1/users/delete/$ADMIN_USER_ID" "" "$ADMIN_TOKEN"
    assert_status 200 "DELETE /api/v1/users/delete/{id} as admin"
  fi
}

print_summary() {
  printf '\nSummary: %s passed, %s skipped\n' "$PASS_COUNT" "$SKIP_COUNT"
}

main() {
  require_tool "$CURL_BIN"
  require_tool "$PYTHON_BIN"

  echo "Running Nykaa API end-to-end checks against $BASE_URL"
  echo "Customer email for this run: $CUSTOMER_EMAIL"

  assert_server_reachable
  run_public_flow
  run_customer_forbidden_flow
  run_admin_flow
  run_order_flow
  cleanup_admin_resources
  print_summary
}

main "$@"