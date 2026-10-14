-- Apply once after V003, with a DB administrator. Preserve existing rows.
-- Review these preflight results first; each must be empty.
SELECT id, category FROM chat_conversations
 WHERE category IS NOT NULL
   AND category NOT IN ('DISHWASHER', 'DRYER', 'REFRIGERATOR', 'WASHER');
SELECT id, category FROM user_products
 WHERE category NOT IN ('DISHWASHER', 'DRYER', 'REFRIGERATOR', 'WASHER');

-- Strict conversion fails on unsupported historical values instead of truncating them.
SET @homecare_previous_sql_mode = @@SESSION.sql_mode;
SET SESSION sql_mode = 'STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION';
ALTER TABLE chat_conversations
 MODIFY COLUMN category ENUM('DISHWASHER', 'DRYER', 'REFRIGERATOR', 'WASHER') NULL;
ALTER TABLE user_products
 ADD CONSTRAINT chk_user_products_category
 CHECK (category IN ('DISHWASHER', 'DRYER', 'REFRIGERATOR', 'WASHER'));
SET SESSION sql_mode = @homecare_previous_sql_mode;
