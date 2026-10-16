-- Upgrade the deployed V001/V002 schema without recreating products or changing applied SQL.
-- Keep the original product columns and defaults so the previous application can still read/write them.
ALTER TABLE user_products
 MODIFY COLUMN name VARCHAR(100) NULL,
 MODIFY COLUMN model VARCHAR(100) NULL,
 ADD COLUMN warranty_input LONGTEXT NULL,
 ADD COLUMN delivery_sido VARCHAR(20) NOT NULL DEFAULT '',
 ADD COLUMN delivery_sigungu VARCHAR(30) NOT NULL DEFAULT '',
 ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;

-- Keep historical categories, including retired AIR_CONDITIONER conversations, for history access.
ALTER TABLE chat_conversations
 ADD COLUMN product_id BIGINT NULL,
 ADD COLUMN latest_input LONGTEXT NULL,
 ADD COLUMN consultation_context LONGTEXT NULL,
 ADD COLUMN manual_warranty LONGTEXT NULL,
 ADD COLUMN context_revision BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;

ALTER TABLE chat_messages
 ADD COLUMN warranty LONGTEXT NULL,
 ADD COLUMN tool_results LONGTEXT NULL;
