-- V2: пример версионирования — какой моделью отвечен ассистент + system prompt диалога.
-- Демонстрирует, что схема evolves через Flyway, а не руками.
ALTER TABLE chat_message ADD COLUMN model TEXT NULL;
ALTER TABLE conversation ADD COLUMN system_prompt TEXT NULL;
