-- Add profile fields to the existing MCP product table. Existing warranty and region data stay intact.
ALTER TABLE user_products
 ADD COLUMN postcode VARCHAR(5) NULL,
 ADD COLUMN address VARCHAR(160) NULL,
 ADD COLUMN address_detail VARCHAR(100) NULL,
 ADD COLUMN created_at TIMESTAMP(6) NULL,
 ADD COLUMN updated_at TIMESTAMP(6) NULL;
