INSERT INTO users (name, email, password, address, role, created_at, updated_at)
VALUES
    ('Admin 1', 'admin@example.com', '$2a$10$JlnwnyOLUgIdj6TZFqzMjecp2PxioGUvPmVGazKJQWSfj7ym3RDoW', 'Admin Address 1', 'ADMIN', TIMESTAMP '2026-01-01 10:01:00', TIMESTAMP '2026-01-01 10:01:00'),
    ('Admin 2', 'admin2@example.com', '$2a$10$JlnwnyOLUgIdj6TZFqzMjecp2PxioGUvPmVGazKJQWSfj7ym3RDoW', 'Admin Address 2', 'ADMIN', TIMESTAMP '2026-01-01 10:02:00', TIMESTAMP '2026-01-01 10:02:00'),
    ('Admin 3', 'admin3@example.com', '$2a$10$JlnwnyOLUgIdj6TZFqzMjecp2PxioGUvPmVGazKJQWSfj7ym3RDoW', 'Admin Address 3', 'ADMIN', TIMESTAMP '2026-01-01 10:03:00', TIMESTAMP '2026-01-01 10:03:00'),
    ('Admin 4', 'admin4@example.com', '$2a$10$JlnwnyOLUgIdj6TZFqzMjecp2PxioGUvPmVGazKJQWSfj7ym3RDoW', 'Admin Address 4', 'ADMIN', TIMESTAMP '2026-01-01 10:04:00', TIMESTAMP '2026-01-01 10:04:00'),
    ('Admin 5', 'admin5@example.com', '$2a$10$JlnwnyOLUgIdj6TZFqzMjecp2PxioGUvPmVGazKJQWSfj7ym3RDoW', 'Admin Address 5', 'ADMIN', TIMESTAMP '2026-01-01 10:05:00', TIMESTAMP '2026-01-01 10:05:00');

INSERT INTO users (name, email, password, address, role, created_at, updated_at)
SELECT
    'Customer ' || gs,
    'customer' || gs || '@example.com',
    '$2a$10$tio0nQIpdzm9EXX4Vyv8juIqPQ784ArlQYltIydBY.w0sU6w7V9a6',
    'Customer Address ' || gs,
    'CUSTOMER',
    TIMESTAMP '2026-01-02 11:00:00' + (gs * INTERVAL '1 minute'),
    TIMESTAMP '2026-01-02 11:00:00' + (gs * INTERVAL '1 minute')
FROM generate_series(1, 45) gs;

INSERT INTO products (name, price, category, brand, created_at, updated_at)
SELECT
    'Product ' || gs,
    500.0 + (gs * 37),
    CASE ((gs - 1) % 7)
        WHEN 0 THEN 'MAKEUP'
        WHEN 1 THEN 'CLOTHING'
        WHEN 2 THEN 'COSMETICS'
        WHEN 3 THEN 'PERFUME'
        WHEN 4 THEN 'WELLNESS'
        WHEN 5 THEN 'LOTION'
        ELSE 'DETAN'
    END,
    CASE ((gs - 1) % 6)
        WHEN 0 THEN 'GUCCI'
        WHEN 1 THEN 'PRADA'
        WHEN 2 THEN 'TOMFORD'
        WHEN 3 THEN 'HILLFIGER'
        WHEN 4 THEN 'SUGAR_COSMETICS'
        ELSE 'MUSCLEBLAZE'
    END,
    TIMESTAMP '2026-01-03 12:00:00' + (gs * INTERVAL '1 minute'),
    TIMESTAMP '2026-01-03 12:00:00' + (gs * INTERVAL '1 minute')
FROM generate_series(1, 50) gs;

