-- V5: реестр веток диалога (Task 5, Branching) — граф через parent_id.
-- Сообщения веток лежат в chat_message и ссылаются через conversation_id
-- (main -> 'default', ветки -> 'branch:<id>'), отдельных миграций под них не надо.
CREATE TABLE IF NOT EXISTS branch (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    parent_id TEXT REFERENCES branch(id),
    conversation_id TEXT NOT NULL,
    from_size INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_branch_parent ON branch(parent_id);
