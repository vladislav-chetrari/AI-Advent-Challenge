-- V1: базовый чат. Flyway применит автоматически через DatabaseFactory.
CREATE TABLE IF NOT EXISTS conversation (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL DEFAULT 'default',
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS chat_message (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    conversation_id TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_msg_conv ON chat_message(conversation_id, id);
