# Postman cURLs for Nykaa API

## Auth Endpoints

### Register User
```bash
curl --location 'http://localhost:3000/api/v1/auth/register' \
--header 'Content-Type: application/json' \
--data '{
  "name": "Test User",
  "email": "testuser@example.com",
  "password": "Password123!",
  "address": "Test Address"
}'
```

### Login User
```bash
curl --location 'http://localhost:3000/api/v1/auth/login' \
--header 'Content-Type: application/json' \
--data '{
  "email": "testuser@example.com",
  "password": "Password123!"
}'
```

### Get Current User (Me)
```bash
curl --location 'http://localhost:3000/api/v1/auth/me' \
--header 'Authorization: Bearer <YOUR_TOKEN>'
```

---

## User Endpoints (Admin Only, Except Update)

### Add User (Admin Only)
```bash
curl --location 'http://localhost:3000/api/v1/users/add' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer <ADMIN_TOKEN>' \
--data '{
  "name": "New User",
  "email": "newuser@example.com",
  "password": "Password123!",
  "address": "New Address",
  "role": "CUSTOMER"
}'
```

### Update User
```bash
curl --location 'http://localhost:3000/api/v1/users/update' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer <YOUR_TOKEN>' \
--data '{
  "id": 1,
  "name": "Updated Name",
  "email": "testuser@example.com",
  "address": "Updated Address"
}'
```

### Delete User (Admin Only)
```bash
curl --location --request DELETE 'http://localhost:3000/api/v1/users/delete/1' \
--header 'Authorization: Bearer <ADMIN_TOKEN>'
```

---

## Product Endpoints

### Get All Products (Public)
```bash
curl --location 'http://localhost:3000/api/v1/products/all?pageNo=0&pageSize=10&sortBy=id&sortDir=asc'
```

### Search Products (Public)
```bash
# Search by partial name
curl --location 'http://localhost:3000/api/v1/products/search?name=Perfume&pageNo=0&pageSize=10'

# Search by exact category
curl --location 'http://localhost:3000/api/v1/products/search?category=PERFUME&pageNo=0&pageSize=10'

# Search by exact brand
curl --location 'http://localhost:3000/api/v1/products/search?brand=PRADA&pageNo=0&pageSize=10'
```

### Add Product (Admin Only)
```bash
curl --location 'http://localhost:3000/api/v1/products/add' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer <ADMIN_TOKEN>' \
--data '{
  "name": "New Perfume",
  "brand": "PRADA",
  "category": "PERFUME",
  "price": 1500.0
}'
```

### Update Product (Admin Only)
```bash
curl --location 'http://localhost:3000/api/v1/products/update' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer <ADMIN_TOKEN>' \
--data '{
  "id": 1,
  "name": "Updated Perfume Name",
  "brand": "PRADA",
  "category": "PERFUME",
  "price": 1600.0
}'
```

### Add Bulk Products (Admin Only)
```bash
curl --location 'http://localhost:3000/api/v1/products/addBulk' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer <ADMIN_TOKEN>' \
--data '[
  {
    "name": "Bulk Product 1",
    "brand": "GUCCI",
    "category": "MAKEUP",
    "price": 999.0
  },
  {
    "name": "Bulk Product 2",
    "brand": "MUSCLEBLAZE",
    "category": "WELLNESS",
    "price": 1499.0
  }
]'
```

### Delete Product (Admin Only)
```bash
curl --location --request DELETE 'http://localhost:3000/api/v1/products/delete/1' \
--header 'Authorization: Bearer <ADMIN_TOKEN>'
```

---

## Order Endpoints

### Add to Cart
```bash
curl --location 'http://localhost:3000/api/v1/orders/cart/add' \
--header 'Content-Type: application/json' \
--header 'Authorization: Bearer <YOUR_TOKEN>' \
--data '{
  "productId": 1,
  "quantity": 2
}'
```

### Get Cart
```bash
curl --location 'http://localhost:3000/api/v1/orders/cart' \
--header 'Authorization: Bearer <YOUR_TOKEN>'
```

### Place Order
```bash
curl --location --request POST 'http://localhost:3000/api/v1/orders/place' \
--header 'Authorization: Bearer <YOUR_TOKEN>'
```