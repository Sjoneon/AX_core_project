-- Apply once after V004, with a DB administrator. Only adds a new table; existing rows are untouched.
-- One row per tool call of an assistant message: allow-listed arguments, a short result summary and timing.
-- Customer text, email, tokens and Authorization headers are never stored here.
-- chat_messages.tool_results keeps the full tool envelopes for the UI; this table is the audit trail.
-- Rows are deleted with their conversation or message, and the backend purges rows older than 90 days.
CREATE TABLE chat_tool_calls (
 id BIGINT NOT NULL AUTO_INCREMENT,
 conversation_id BIGINT NOT NULL,
 message_id BIGINT NULL,
 call_order INT NOT NULL,
 tool_name VARCHAR(64) NOT NULL,
 arguments_json VARCHAR(1000) NULL,
 injected_fields VARCHAR(200) NULL,
 selection_source VARCHAR(30) NOT NULL,
 status VARCHAR(30) NOT NULL,
 error_code VARCHAR(40) NULL,
 result_summary VARCHAR(2000) NULL,
 elapsed_ms BIGINT NOT NULL,
 trace_id VARCHAR(36) NULL,
 created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY (id),
 KEY idx_tool_calls_conversation (conversation_id, id),
 KEY idx_tool_calls_created (created_at),
 CONSTRAINT fk_tool_calls_conversation FOREIGN KEY (conversation_id) REFERENCES chat_conversations (id) ON DELETE CASCADE,
 CONSTRAINT fk_tool_calls_message FOREIGN KEY (message_id) REFERENCES chat_messages (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
