-- V3: токены по факту ответа API (Task 3, День 8). Старые сообщения получают 0.
ALTER TABLE chat_message ADD COLUMN prompt_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_message ADD COLUMN completion_tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_message ADD COLUMN total_tokens INTEGER NOT NULL DEFAULT 0;
