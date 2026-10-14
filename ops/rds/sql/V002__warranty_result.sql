-- Apply once to an existing V001 database before deploying the warranty feature.
-- Only adds a nullable column; existing messages keep their original text and sources.
ALTER TABLE chat_messages ADD COLUMN warranty LONGTEXT NULL;
