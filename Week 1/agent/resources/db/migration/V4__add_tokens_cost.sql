-- V4: токены и стоимость у каждого сообщения (Task 3). Старые сообщения получают 0.
ALTER TABLE chat_message ADD COLUMN tokens INTEGER NOT NULL DEFAULT 0;
ALTER TABLE chat_message ADD COLUMN cost_usd REAL NOT NULL DEFAULT 0.0;
