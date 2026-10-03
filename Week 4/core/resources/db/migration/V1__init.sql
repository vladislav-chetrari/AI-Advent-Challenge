-- Базовая схема RAG-индекса (Day 21).
-- Старые БД до Flyway не мигрируем: DbMigrations удаляет legacy-файл
-- (там только перестраиваемые чанки), дальше схема живёт только здесь.
CREATE TABLE chunk (
    id TEXT NOT NULL,
    strategy TEXT NOT NULL,
    source TEXT NOT NULL,
    title TEXT NOT NULL,
    section TEXT NOT NULL DEFAULT '',
    ord INTEGER NOT NULL,
    text TEXT NOT NULL,
    tokens INTEGER NOT NULL,
    embedding BLOB NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (id, strategy)
);
CREATE INDEX idx_chunk_strategy ON chunk(strategy);
CREATE INDEX idx_chunk_source ON chunk(source);
CREATE INDEX idx_chunk_strategy_source ON chunk(strategy, source);

-- Документы базы знаний: выбранная при добавлении стратегия чанкинга
-- (fixed | structure | both) + статус индексации для сайдбара.
CREATE TABLE document (
    source TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    strategy TEXT NOT NULL DEFAULT 'structure',
    status TEXT NOT NULL DEFAULT 'ready',
    error TEXT NOT NULL DEFAULT '',
    updated_at INTEGER NOT NULL
);
