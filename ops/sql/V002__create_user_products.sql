CREATE TABLE user_products (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    category ENUM('DISHWASHER', 'DRYER', 'REFRIGERATOR', 'WASHER') NOT NULL,
    name VARCHAR(40),
    model VARCHAR(80),
    purchase_date DATE,
    postcode VARCHAR(5),
    address VARCHAR(160),
    address_detail VARCHAR(100),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_user_products_user_created (user_id, created_at, id),
    CONSTRAINT fk_products_user FOREIGN KEY (user_id) REFERENCES app_users (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
