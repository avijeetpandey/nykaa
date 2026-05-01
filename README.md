# [WIP] Nykaa clone :tada: :rocket:
Nykaa clone made using Java and SpringBoot.

## Authentication

The project now uses custom JWT-based authentication with Spring Security.

### Public endpoints

- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `GET /api/v1/products/all`

### Authenticated endpoints

- `GET /api/v1/auth/me`
- `POST /api/v1/users/update`

### Admin-only endpoints

- `POST /api/v1/users/add`
- `DELETE /api/v1/users/delete/{id}`
- `POST /api/v1/products/add`
- `POST /api/v1/products/update`
- `POST /api/v1/products/addBulk`
- `DELETE /api/v1/products/delete/{id}`

### Notes

- Passwords are BCrypt-hashed before persistence.
- Registration creates `CUSTOMER` users by default.
- JWT configuration lives in `src/main/resources/application.properties`.
- Access tokens are persisted in the `user_sessions` table (`access_token`, `token_id`, `issued_at`, `expires_at`, `revoked`).
- Test configuration uses H2 in-memory DB via `src/test/resources/application.properties`.
- Flyway manages schema and seed data from `src/main/resources/db/migration`.
- Seed data includes **50 users** (5 admins + 45 customers) and **50 products**.
- Default seeded admin login: `admin@example.com` / `adminPassword123`.
- Default seeded customer password: `Password123!`.

## Run tests

```bash
./mvnw test
```

## Run end-to-end API script

Customer/public flow only:

```bash
./scripts/e2e-auth-user-product.sh
```

Full flow including admin-only endpoints:

```bash
ADMIN_EMAIL="admin@example.com" \
ADMIN_PASSWORD="adminPassword123" \
./scripts/e2e-auth-user-product.sh
```

Optional overrides:

- `BASE_URL` defaults to `http://localhost:3000`
- `CUSTOMER_EMAIL` and `CUSTOMER_UPDATED_EMAIL` default to unique timestamped values
- `CUSTOMER_PASSWORD` defaults to `Password123!`

