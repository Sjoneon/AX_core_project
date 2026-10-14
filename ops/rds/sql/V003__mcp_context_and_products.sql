-- Apply once after V002. Keep existing users, conversations and messages.
CREATE TABLE user_products (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 user_id BIGINT NOT NULL,
 category VARCHAR(30) NOT NULL,
 display_name VARCHAR(100),
 warranty_input LONGTEXT NOT NULL,
 delivery_sido VARCHAR(20) NOT NULL,
 delivery_sigungu VARCHAR(30) NOT NULL,
 revision BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_product_owner FOREIGN KEY (user_id) REFERENCES app_users(id),
 KEY idx_product_owner (user_id,category,id)
);
ALTER TABLE chat_conversations
 ADD COLUMN product_id BIGINT NULL,
 ADD COLUMN latest_input LONGTEXT NULL,
 ADD COLUMN consultation_context LONGTEXT NULL,
 ADD COLUMN manual_warranty LONGTEXT NULL,
 ADD COLUMN context_revision BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE chat_messages ADD COLUMN tool_results LONGTEXT NULL;
