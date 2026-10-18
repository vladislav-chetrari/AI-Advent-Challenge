#!/usr/bin/env python3
"""Создать демо-тикеты поддержки в CallowFellow (GitHub Issues).

Тикеты — это CRM ассистента поддержки (Task 3): ночной workflow support-index.yml
выгружает их на VPS, сервер разбирает front-matter и кладёт в RAG-индекс.

Скрипт идемпотентен: перед созданием ищет Issue с тем же заголовком и
переиспользует существующий (номера тикетов стабильны между прогонами —
на них ссылается support/cases.json).

    python3 scripts/seed_support_tickets.py --dry-run
    python3 scripts/seed_support_tickets.py

Нужен gh CLI с авторизацией и правом issues:write.
"""

import argparse
import json
import subprocess
import sys

REPO = "vladislav-chetrari/CallowFellow"

# Лейблы создаются на лету: без них `gh issue create` падает на первом же тикете
# ("could not add label: 'support' not found"), и тикеты не появляются вовсе.
LABELS = {
    "support": ("1d76db", "обращение в поддержку — попадает в CRM ассистента"),
    "type:bug": ("d73a4a", "ошибка в приложении"),
    "type:question": ("0075ca", "вопрос по продукту"),
    "type:known-issue": ("5319e7", "известное ограничение, не баг"),
    "severity:critical": ("b60205", "блокирует работу"),
    "severity:major": ("d93f0b", "сильно мешает"),
    "severity:minor": ("fef2c0", "мелочь"),
    "status:open": ("c2e0c6", "зарегистрировано, работы ещё нет"),
    "status:in-progress": ("fbca04", "разработчик работает"),
    "status:needs-info": ("c2def0", "ждём детали от пользователя"),
    "status:resolved": ("0e8a16", "исправлено"),
    "component:models": ("bfd4f2", "загрузка и запуск моделей"),
    "component:cloud": ("bfd4f2", "облачный режим, DeepSeek"),
    "component:rag": ("bfd4f2", "база знаний и поиск"),
    "component:ui": ("bfd4f2", "интерфейс"),
}


def ensure_labels(repo):
    """Создаёт недостающие лейблы (--force не мешает правке цвета/описания)."""
    out = gh(["label", "list", "--repo", repo, "--limit", "100", "--json", "name"])
    have = {l["name"] for l in json.loads(out)}
    created = []
    for name, (color, desc) in LABELS.items():
        if name in have:
            continue
        gh(["label", "create", name, "--repo", repo, "--color", color, "--description", desc, "--force"])
        created.append(name)
    print(f"лейблы: создано {len(created)}" + (f" ({', '.join(created)})" if created else " (все уже были)"))

# Статусы распределены намеренно: бот обязан вести себя по-разному для
# «в работе» (успокоить + обходной путь), needs-info (запросить логи),
# resolved (сказать, что исправлено) и обычного вопроса (ответ по FAQ).
TICKETS = [
    {
        "title": "Модель 1.5 ГБ не скачивается: обрыв на ~80%",
        "labels": ["support", "type:bug", "severity:major", "status:in-progress", "component:models"],
        "body": """---
ticket:
  user:
    id: u17
    name: Ирина
    plan: pro
    device: Pixel 8
    os: Android 14
    app: 0.1.0
    locale: ru
  component: models
  assignee: ilya
  workaround: скачать Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.litertlm вручную с huggingface.co (организация litert-community) и положить в models/llm/
  fix: перезагрузка идёт без Range-заголовка, поэтому рвётся на долгих соединениях — переделали на докачку с Range
---

Привет! Третий день не могу скачать основную модель. Прогресс доходит примерно до 80% и сбрасывается, пробовал три раза, интернет хороший, на ноутбуке тот же файл качается за минуту.

Перезагрузка каждый раз начинается с нуля — это очень раздражает. Подскажите, что делать?""",
    },
    {
        "title": "Не нахожу, где ввести ключ DeepSeek",
        "labels": ["support", "type:question", "severity:minor", "status:resolved", "component:cloud"],
        "body": """---
ticket:
  user:
    id: u04
    name: Павел
    plan: free
    device: Galaxy S23
    os: Android 14
    app: 0.1.0
    locale: ru
  type: question
  component: cloud
  fix: в 0.1.0 облачный режим включается на вкладке Настройки -> Модель языка -> DeepSeek Chat, поле для ключа там же
---

Хочу попробовать облачную модель, но не понимаю, где вводить ключ. В настройках крутил — только выбор локальных моделей. Подскажите путь?""",
    },
    {
        "title": "На десктопе RAG ничего не находит",
        "labels": ["support", "type:bug", "severity:major", "status:resolved", "component:rag"],
        "body": """---
ticket:
  user:
    id: u22
    name: Олег
    plan: free
    device: macOS 14
    os: macOS
    app: 0.1.0
    locale: ru
  type: known-issue
  component: rag
  fix: это не баг — на JVM нет нативного движка эмбеддингов, эмбеддинги считаются хеш-векторами; качество поиска на десктопе оценивать нельзя, нужно проверять на Android
---

Запустил на ноутбуке, добавил три статьи Википедии, проиндексировалось, но на любой вопрос RAG отвечает «ничего не нашлось». На телефоне с той же базой работает нормально. Уже думаю, что сломалось. Подскажите?""",
    },
    {
        "title": "Сколько стоит подписка?",
        "labels": ["support", "type:question", "severity:minor", "status:resolved", "component:cloud"],
        "body": """---
ticket:
  user:
    id: u09
    name: Мария
    plan: free
    device: Galaxy A54
    os: Android 13
    app: 0.1.0
    locale: ru
  type: question
  component: cloud
  fix: подписки и оплаты в продукте нет вообще, все функции бесплатны
---

Хочу купить Pro-версию с облачной моделью и приоритетной поддержкой. Сколько стоит и как оплатить? Если мне не подходит, как вернуть?""",
    },
    {
        "title": "Падение при переключении между чатами",
        "labels": ["support", "type:bug", "severity:critical", "status:needs-info", "component:ui"],
        "body": """---
ticket:
  user:
    id: u31
    name: Дмитрий
    plan: pro
    device: Xiaomi 13
    os: Android 14
    app: 0.1.0
    locale: ru
  component: ui
  assignee: nadya
---

Приложение падает при переключении между чатами: нажимаю на второй диалог в списке — экран гаснет, приложение закрывается. На третьем телефоне у коллеги такого нет. Логи приложить не могу, не нашёл, где их взять.""",
    },
    {
        "title": "Долгий ответ на простых вопросах",
        "labels": ["support", "type:question", "severity:minor", "status:resolved", "component:ui"],
        "body": """---
ticket:
  user:
    id: u17
    name: Ирина
    plan: pro
    device: Pixel 8
    os: Android 14
    app: 0.1.0
    locale: ru
  type: question
  component: ui
  fix: переключение пресета генерации на Точный (иконка звезды в шапке чата) и лёгкая модель 0.6B ускоряют ответ в разы
---

Отвечает очень медленно, по одному слову в секунду. У меня средний телефон. Модель основная. Можно как-то быстрее?""",
    },
]


def gh(args):
    p = subprocess.run(["gh", *args], capture_output=True, text=True)
    if p.returncode != 0:
        print(f"gh {' '.join(args)} failed:\n{p.stderr}", file=sys.stderr)
        raise SystemExit(1)
    return p.stdout.strip()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=REPO)
    ap.add_argument("--dry-run", action="store_true", help="показать, что будет создано, и выйти")
    args = ap.parse_args()

    existing = json.loads(gh(["issue", "list", "--repo", args.repo, "--state", "all", "--limit", "200",
                              "--json", "number,title"]))
    by_title = {i["title"]: i["number"] for i in existing}

    if args.dry_run:
        missing = sorted({l for t in TICKETS for l in t["labels"]} - {l["name"] for l in json.loads(
            gh(["label", "list", "--repo", args.repo, "--limit", "100", "--json", "name"]))})
        if missing:
            print(f"отсутствующие лейблы (создаст скрипт): {', '.join(missing)}")
    else:
        ensure_labels(args.repo)

    for t in TICKETS:
        labels = ",".join(t["labels"])
        if t["title"] in by_title:
            print(f"skip  #{by_title[t['title']]} уже есть: {t['title']}")
            continue
        if args.dry_run:
            print(f"create [{labels}] {t['title']}")
            continue
        out = gh(["issue", "create", "--repo", args.repo, "--title", t["title"],
                  "--body", t["body"], "--label", labels])
        print(f"create {out}")

    print(f"\nвсего тикетов в сценарии: {len(TICKETS)}")
    print("дальше: запусти support-index workflow вручную (Actions -> support-index -> Run workflow)")


if __name__ == "__main__":
    main()