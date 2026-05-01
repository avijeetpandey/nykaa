# NexusStock — Production-Grade E-Commerce Backend

A Spring Boot 4 distributed e-commerce backend showcasing event-driven architecture, SAGA choreography, CDC pipelines, distributed rate limiting, and end-to-end distributed tracing.

---

## Architecture Overview

```
                        ┌─────────────────────────────────────────────────────┐
                        │                  Spring Boot App                      │
                        │                                                       │
  HTTP Client ──────────►  RateLimitInterceptor (Redis Token Bucket)           │
                        │          │                                            │
                        │  ┌───────▼────────┐  ┌─────────────┐                │
                        │  │  OrderService   │  │ PaymentSvc  │                │
                        │  │  (Tracer span)  │  │(Tracer span)│                │
                        │  └───────┬────────┘  └──────┬──────┘                │
                        │          │                   │                        │
                        └──────────┼───────────────────┼────────────────────── ┘
                                   │   Kafka            │
          ┌────────────────────────┼────────────────────┘
          │                        │
   ┌──────▼──────┐        ┌────────▼──────────────────────────────────────┐
   │  PostgreSQL  │        │                  Kafka Topics                  │
   │  (source of  │        │  nykaa.order.created     → InventorySaga      │
   │    truth)    │        │  nykaa.inventory.reserved → PaymentSaga        │
   └──────┬───────┘        │  nykaa.payment.processed → OrderSaga          │
          │ WAL             │  nykaa.payment.failed    → OrderSaga          │
          ▼                 │  nykaa.order.cancelled   → OrderSaga          │
   ┌─────────────┐          │  nykaa.product.changes   → ProductCdcConsumer │
   │  Debezium   │──────────►                                               │
   │  (CDC)      │          └───────────────────────────────────────────────┘
   └─────────────┘                           │
                                             ▼
                                    ┌─────────────────┐
                                    │  Elasticsearch   │
                                    │  (search index)  │
                                    └─────────────────┘
```

### Saga Choreography Flow

```
POST /orders/place
      │
      ▼
  Order(PENDING) ──► nykaa.order.created
                              │
              ┌───────────────▼────────────────┐
              │       InventorySagaConsumer      │
              │  Redis lock + SELECT FOR UPDATE  │
              └──┬────────────────────────┬─────┘
                 │ SUCCESS                │ FAIL
                 ▼                        ▼
    nykaa.inventory.reserved    nykaa.order.cancelled
                 │                        │
    ┌────────────▼────────────┐  ┌────────▼────────────┐
    │    PaymentSagaConsumer   │  │  OrderSagaConsumer  │
    │  Mock charge + idempotent│  │   Order → FAILED    │
    │  Redis key               │  └─────────────────────┘
    └──┬──────────────────┬────┘
       │ SUCCESS          │ FAIL
       ▼                  ▼
  nykaa.payment.processed  nykaa.payment.failed
       │                        │
  ┌────▼────────────┐  ┌────────▼──────────────────┐
  │ OrderSagaConsumer│  │   OrderSagaConsumer        │
  │  Order → SUCCESS│  │  Rollback inventory        │
  └─────────────────┘  │  Order → FAILED            │
                        └───────────────────────────┘
```

---

## Technology Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 4.0.6, Java 17 |
| Database | PostgreSQL 16 (WAL logical replication) |
| Cache / Locks | Redis 7.4 (Lettuce) |
| Messaging | Apache Kafka 7.7.1 (Confluent) |
| CDC | Debezium 3.0 (PostgreSQL connector) |
| Search | Elasticsearch 9.0.0 |
| Tracing | Micrometer Tracing + Brave + Zipkin 3.4 |
| Auth | JWT (JJWT 0.12.6) + Spring Security |
| Migrations | Flyway |
| Tests | JUnit 5 + Testcontainers + Awaitility |

---

## Running Locally

### Prerequisites
- Docker and Docker Compose
- Java 17
- Maven 3.9+

### Start infrastructure
```bash
docker-compose up -d
```

Services started:

| Service | Port |
|---|---|
| PostgreSQL | 5432 |
| Elasticsearch | 9200 |
| Redis | 6379 |
| Kafka | 9092 |
| Zookeeper | 2181 |
| Debezium Connect | 8083 |
| Zipkin | 9411 |

### Start the application
```bash
mvn spring-boot:run
```

App starts on `http://localhost:3000`

### Run tests
```bash
mvn test
```

---

## API Reference and cURL Examples

Replace `$TOKEN` with the JWT returned from the login endpoint.
Replace `$ADMIN_TOKEN` with the JWT of an `ADMIN` user.

---

### Authentication

#### Register
```bash
curl -s -X POST http://localhost:3000/api/v1/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Alice",
    "email": "alice@nykaa.com",
    "password": "secret123",
    "role": "CUSTOMER"
  }' | jq .
```

#### Login
```bash
curl -s -X POST http://localhost:3000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "alice@nykaa.com",
    "password": "secret123"
  }' | jq .

export TOKEN="<paste token here>"
```

Seeded admin credentials: `admin@example.com` / `adminPassword123`

#### Whoami
```bash
curl -s http://localhost:3000/api/v1/auth/me \
  -H "Authorization: Bearer $TOKEN" | jq .
```

---

### Products

#### Add a product (Admin)
```bash
curl -s -X POST http://localhost:3000/api/v1/products/add \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{
    "name": "Chanel No.5",
    "brand": "CHANEL",
    "category": "PERFUME",
    "price": 12999.0,
    "stockQuantity": 50
  }' | jq .
```

#### Bulk add (Admin)
```bash
curl -s -X POST http://localhost:3000/api/v1/products/addBulk \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '[
    {"name":"MAC Lipstick","brand":"MAC","category":"MAKEUP","price":1499.0,"stockQuantity":100},
    {"name":"Dior Mascara","brand":"DIOR","category":"MAKEUP","price":3299.0,"stockQuantity":75}
  ]' | jq .
```

#### Update a product (Admin)
```bash
curl -s -X POST http://localhost:3000/api/v1/products/update \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"id": 1, "price": 11999.0, "stockQuantity": 40}' | jq .
```

#### Delete a product (Admin)
```bash
curl -s -X DELETE http://localhost:3000/api/v1/products/delete/1 \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .
```

#### List all products (public, paginated)
```bash
curl -s "http://localhost:3000/api/v1/products/all?pageNo=0&pageSize=10&sortBy=price&sortDir=asc" | jq .
```

#### Search products via Elasticsearch (public)
```bash
# By name (full-text)
curl -s "http://localhost:3000/api/v1/products/search?name=lipstick" | jq .

# By category
curl -s "http://localhost:3000/api/v1/products/search?category=MAKEUP" | jq .

# By brand
curl -s "http://localhost:3000/api/v1/products/search?brand=CHANEL" | jq .
```

---

### Cart

#### Add item
```bash
curl -s -X POST http://localhost:3000/api/v1/orders/cart/add \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"productId": 1, "quantity": 2}' | jq .
```

#### View cart
```bash
curl -s http://localhost:3000/api/v1/orders/cart \
  -H "Authorization: Bearer $TOKEN" | jq .
```

#### Remove item
```bash
curl -s -X DELETE http://localhost:3000/api/v1/orders/cart/remove/1 \
  -H "Authorization: Bearer $TOKEN" | jq .
```

---

### Orders

#### Place an order — starts the Saga
```bash
curl -s -X POST http://localhost:3000/api/v1/orders/place \
  -H "Authorization: Bearer $TOKEN" | jq .
# Returns HTTP 202 Accepted. Order status is PENDING.
# Rate limited: 10 requests per minute per user.
```

#### Poll order status
```bash
curl -s http://localhost:3000/api/v1/orders/42 \
  -H "Authorization: Bearer $TOKEN" | jq '.data.status'
# Transitions: PENDING → SUCCESS or FAILED
```

#### Order history
```bash
curl -s http://localhost:3000/api/v1/orders/history \
  -H "Authorization: Bearer $TOKEN" | jq .
```

---

### Payment Webhook

Public endpoint — no JWT required. Simulates the payment gateway callback.

#### Successful payment
```bash
curl -s -X POST http://localhost:3000/api/v1/payments/webhook \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": 42,
    "userId": 1,
    "paymentId": "PAY-gateway-abc123",
    "status": "SUCCESS",
    "idempotencyKey": "idem-42-success-001",
    "totalAmount": 2998.0
  }' | jq .
# Rate limited: 20 requests per minute per IP.
```

#### Failed payment
```bash
curl -s -X POST http://localhost:3000/api/v1/payments/webhook \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": 43,
    "userId": 1,
    "paymentId": "PAY-gateway-xyz999",
    "status": "FAILURE",
    "idempotencyKey": "idem-43-fail-001",
    "totalAmount": 1499.0,
    "reason": "Insufficient funds"
  }' | jq .
```

#### Duplicate webhook (idempotency check)
```bash
# Send the same request twice — second response shows processingStatus: "DUPLICATE"
curl -s -X POST http://localhost:3000/api/v1/payments/webhook \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": 42,
    "userId": 1,
    "paymentId": "PAY-gateway-abc123",
    "status": "SUCCESS",
    "idempotencyKey": "idem-42-success-001",
    "totalAmount": 2998.0
  }' | jq .
```

---

### Users (Admin)

#### Add user
```bash
curl -s -X POST http://localhost:3000/api/v1/users/add \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -d '{"name":"Bob","email":"bob@nykaa.com","password":"pass123","role":"CUSTOMER"}' | jq .
```

#### Update self
```bash
curl -s -X PUT http://localhost:3000/api/v1/users/update \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name":"Alice Updated","address":"123 Main St"}' | jq .
```

#### Delete user (Admin)
```bash
curl -s -X DELETE http://localhost:3000/api/v1/users/delete/2 \
  -H "Authorization: Bearer $ADMIN_TOKEN" | jq .
```

---

## End-to-End Happy Path

```bash
# 1. Login
export TOKEN=$(curl -s -X POST http://localhost:3000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"alice@nykaa.com","password":"secret123"}' | jq -r '.data.token')

# 2. Add to cart (product ID 1 must exist — seed data has 50 products)
curl -s -X POST http://localhost:3000/api/v1/orders/cart/add \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"productId": 1, "quantity": 2}'

# 3. Place order
export ORDER_ID=$(curl -s -X POST http://localhost:3000/api/v1/orders/place \
  -H "Authorization: Bearer $TOKEN" | jq -r '.data.orderId')

echo "Order $ORDER_ID placed — status: PENDING"

# 4. Simulate payment success webhook
curl -s -X POST http://localhost:3000/api/v1/payments/webhook \
  -H "Content-Type: application/json" \
  -d "{
    \"orderId\": $ORDER_ID,
    \"userId\": 1,
    \"paymentId\": \"PAY-$(date +%s)\",
    \"status\": \"SUCCESS\",
    \"idempotencyKey\": \"idem-$ORDER_ID-$(date +%s)\",
    \"totalAmount\": 2998.0
  }"

# 5. Poll for result (typically resolves within 3 seconds)
for i in $(seq 1 10); do
  STATUS=$(curl -s http://localhost:3000/api/v1/orders/$ORDER_ID \
    -H "Authorization: Bearer $TOKEN" | jq -r '.data.status')
  echo "[$i] Order $ORDER_ID status: $STATUS"
  [ "$STATUS" != "PENDING" ] && break
  sleep 1
done
```

---

## Observability

### Zipkin — Distributed Traces
Open `http://localhost:9411`

Custom spans emitted:
| Span name | Tags |
|---|---|
| `order.place` | `user.id`, `order.id`, `order.total`, `order.items` |
| `inventory.reserve` | `product.id`, `quantity` |
| `payment.webhook` | `order.id`, `payment.id`, `payment.status` |

### Elasticsearch Health
```bash
curl -s http://localhost:9200/_cluster/health | jq .status
curl -s http://localhost:9200/products/_count | jq .count
```

### Debezium Connector
```bash
# Status
curl -s http://localhost:8083/connectors/nykaa-products-connector/status | jq .

# Re-register (app does this on startup automatically)
curl -s -X DELETE http://localhost:8083/connectors/nykaa-products-connector
# then restart the app
```

---

## Rate Limiting

Implemented via Redis token-bucket (Lua script — atomic, no distributed locking needed).

| Endpoint | Limit | Scope |
|---|---|---|
| `POST /api/v1/orders/place` | 10 req/min | per authenticated user |
| `POST /api/v1/payments/webhook` | 20 req/min | per IP address |
| All other `/api/**` endpoints | 200 req/min | per authenticated user |

When exceeded:
```
HTTP 429 Too Many Requests
Retry-After: 60
{"isError":true,"message":"Rate limit exceeded for 'user:alice@nykaa.com'. Max 10 requests per minute."}
```

---

## Database Migrations

| Migration | Description |
|---|---|
| `V1__create_core_schema.sql` | users, products, user_sessions tables |
| `V2__seed_users_products.sql` | 5 admins + 45 customers, 50 products |
| `V3__create_order_tables.sql` | orders, order_items tables |
| `V4__add_stock_quantity.sql` | `products.stock_quantity` column + index |

Default seeded admin: `admin@example.com` / `adminPassword123`
Default seeded customer password: `Password123!`
