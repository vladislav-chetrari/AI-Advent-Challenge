import json
import os
import re
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import datetime
from pathlib import Path

from dotenv import load_dotenv
from openai import OpenAI

# Загрузка переменных окружения из .env
load_dotenv()
API_KEY = os.getenv("DEEPSEEK_API_KEY")

if not API_KEY:
    raise ValueError("Не найден DEEPSEEK_API_KEY в .env файле")

# Инициализация клиента DeepSeek
client = OpenAI(
    api_key=API_KEY,
    base_url="https://api.deepseek.com"
)

MODEL = "deepseek-chat"

BASE_DIR = Path(__file__).resolve().parent
RESULTS_DIR = BASE_DIR / "results"


def get_response(prompt, system=None, temperature=0.7, max_retries=3):
    """Отправляет запрос к DeepSeek API и возвращает текст ответа."""
    messages = []
    if system:
        messages.append({"role": "system", "content": system})
    messages.append({"role": "user", "content": prompt})

    for attempt in range(max_retries):
        try:
            response = client.chat.completions.create(
                model=MODEL,
                messages=messages,
                temperature=temperature,
                stream=False
            )
            content = response.choices[0].message.content
            return (content or "").strip()
        except Exception as e:
            print(f"Ошибка при запросе (попытка {attempt + 1}): {e}")
            if attempt < max_retries - 1:
                time.sleep(2)
            else:
                return f"ERROR: {e}"


def validate_task(task):
    """Проверяет через API: подходит ли задача под темы (логическая / алгоритмическая / аналитическая).

    Возвращает True/False. Промпт требует от модели строго true либо false.
    """
    system = "Ты — строгий классификатор задач. Отвечай ТОЛЬКО одним словом: true или false. Без пояснений."
    prompt = (
        "Поддерживаемые темы задач: логическая, алгоритмическая, аналитическая. "
        "Другие темы НЕ поддерживаются.\n\n"
        f"Задача:\n{task}\n\n"
        "Эта задача относится хотя бы к одной из поддерживаемых тем "
        "(логическая, алгоритмическая, аналитическая)?\n"
        "Ответь строго одним словом: true — если да, false — если нет."
    )
    raw = get_response(prompt, system=system, temperature=0.0).strip().lower()
    # Строгое сравнение, с fallback на поиск слова в ответе
    if raw == "true":
        return True
    if raw == "false":
        return False
    if "true" in raw and "false" not in raw:
        return True
    return False


def get_expert_roles(task):
    """Одним API-вызовом получает список экспертных ролей для задачи."""
    system = "Ты — координатор группы экспертов. Отвечай ТОЛЬКО JSON-массивом строк. Без пояснений."
    prompt = (
        f"Задача:\n{task}\n\n"
        "Подбери 3-4 экспертные роли (например: аналитик, инженер-алгоритмист, критик-верификатор), "
        "которые лучше всего разберут эту задачу.\n"
        'Верни ТОЛЬКО JSON-массив строк, например: ["Аналитик", "Инженер", "Критик"]. '
        "Без markdown, без пояснений."
    )
    raw = get_response(prompt, system=system, temperature=0.5)

    # 1. Пробуем распарсить весь ответ как JSON
    try:
        data = json.loads(raw)
        if isinstance(data, list) and data:
            return [str(x).strip() for x in data if str(x).strip()]
    except (json.JSONDecodeError, TypeError):
        pass

    # 2. Ищем JSON-массив внутри ответа
    match = re.search(r"\[.*?\]", raw, re.DOTALL)
    if match:
        try:
            data = json.loads(match.group(0))
            if isinstance(data, list) and data:
                return [str(x).strip() for x in data if str(x).strip()]
        except (json.JSONDecodeError, TypeError):
            pass

    # 3. Fallback: построчный / через запятую список
    cleaned = re.sub(r"```.*?```", "", raw, flags=re.DOTALL).strip()
    parts = re.split(r"[\n,;]+", cleaned)
    roles = []
    for p in parts:
        p = re.sub(r"^[\d\-\*\.\)\s\"'\[\]]+", "", p).strip().strip("\"'")
        if p and len(p) < 80:
            roles.append(p)
    return roles[:4] if roles else ["Аналитик", "Инженер", "Критик"]


# ========== 4 способа решения (каждый — 1 API-вызов, независимы) ==========

def solve_direct(task):
    return get_response(task, temperature=0.7)


def solve_step_by_step(task):
    prompt = f"{task}\n\nИнструкция: решай пошагово, объясняя каждый шаг."
    return get_response(prompt, temperature=0.7)


def solve_with_generated_prompt(task):
    # Один вызов: модель сначала пишет промпт, затем тут же решает по нему
    prompt = (
        f"Задача:\n{task}\n\n"
        "Сначала составь короткий эффективный промпт для решения этой задачи (1-3 предложения) "
        "и выведи его под заголовком '## Сгенерированный промпт'. "
        "А затем сразу реши исходную задачу, строго следуя этому промпту, "
        "под заголовком '## Решение'."
    )
    return get_response(prompt, temperature=0.7)


def solve_with_experts(task, experts):
    experts_str = ", ".join(experts)
    prompt = (
        f"Задача:\n{task}\n\n"
        f"Ты — группа экспертов: {experts_str}. "
        "Каждый эксперт кратко высказывает своё решение "
        "(подзаголовок с именем роли). "
        "Затем вы вместе даёте один согласованный итоговый ответ "
        "под заголовком '## Итоговый ответ'."
    )
    return get_response(prompt, temperature=0.7)


def compare_solutions(task, solutions_dict):
    """Финальный API-вызов: сравнительный анализ всех решений."""
    solutions_text = ""
    for name, sol in solutions_dict.items():
        solutions_text += f"\n\n=== {name} ===\n{sol}\n"

    compare_prompt = (
        f"Дана задача:\n{task}\n\n"
        f"Ниже — четыре решения, полученные разными способами:\n{solutions_text}\n\n"
        "Сравни решения по критериям: правильность, полнота объяснения, ясность, отсутствие ошибок. "
        "Определи, какое решение наиболее точное и почему. "
        "Дай краткий анализ каждого решения и итоговый вердикт "
        "(например: \"Лучшее решение — способ 2, потому что...\")."
    )
    return get_response(compare_prompt, temperature=0.3)


def read_task_from_user():
    print("Тема задания — одна из: логическая, алгоритмическая или аналитическая.")
    print("Другие темы НЕ поддерживаются (это проверит API).")
    print()
    print("Вставьте текст задачи.")
    print("Завершите ввод строкой END на отдельной строке:")
    lines = []
    while True:
        try:
            line = input()
        except EOFError:
            break
        if line.strip() == "END":
            break
        lines.append(line)
    return "\n".join(lines).strip()


def save_markdown(path, title, task, body, extra_header=""):
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M")
    content = f"# {title}\n\nДата: {timestamp}\n\n## Задача\n\n{task}\n\n{extra_header}## Решение\n\n{body}\n"
    path.write_text(content, encoding="utf-8")
    return path


def main():
    task_text = read_task_from_user()
    if not task_text:
        print("Пустая задача. Завершаю работу.")
        return

    # Шаг 1: валидация темы через API (true/false)
    print("\n[1/4] Проверяю тему задачи через API (true/false)...")
    is_valid = validate_task(task_text)
    if not is_valid:
        print("Задача НЕ подходит: тема не логическая, не алгоритмическая и не аналитическая.")
        print("Другие темы не поддерживаются. Завершаю работу.")
        return
    print("Тема подходит: задача логическая / алгоритмическая / аналитическая.")

    # Шаг 2: список экспертов через API
    print("\n[2/4] Запрашиваю список экспертов через API...")
    experts = get_expert_roles(task_text)
    print(f"Для данной задачи подходят: {', '.join(experts)}")

    # Шаг 3: 4 решения параллельно (каждое — 1 API-вызов)
    print("\n[3/4] Решаю задачу 4 способами параллельно...")
    jobs = {
        "Прямой ответ": (solve_direct, (task_text,)),
        "Пошаговое решение": (solve_step_by_step, (task_text,)),
        "Сгенерированный промпт": (solve_with_generated_prompt, (task_text,)),
        "Группа экспертов": (solve_with_experts, (task_text, experts)),
    }
    for name in jobs:
        print(f"  ... запущен способ: {name}")

    solutions = {}
    with ThreadPoolExecutor(max_workers=4) as executor:
        future_to_name = {
            executor.submit(func, *args): name for name, (func, args) in jobs.items()
        }
        for future in as_completed(future_to_name):
            name = future_to_name[future]
            try:
                solutions[name] = future.result()
            except Exception as e:
                solutions[name] = f"ERROR: {e}"
            print(f"  [OK] способ готов: {name}")

    # Сохранение в markdown-файлы
    RESULTS_DIR.mkdir(exist_ok=True)
    files = {}
    file_map = {
        "Прямой ответ": "01_direct.md",
        "Пошаговое решение": "02_step_by_step.md",
        "Сгенерированный промпт": "03_generated_prompt.md",
        "Группа экспертов": "04_experts.md",
    }
    order = ["Прямой ответ", "Пошаговое решение", "Сгенерированный промпт", "Группа экспертов"]
    titles = {
        "Прямой ответ": "Способ 1 — Прямой ответ",
        "Пошаговое решение": "Способ 2 — Пошаговое решение",
        "Сгенерированный промпт": "Способ 3 — Сгенерированный промпт",
        "Группа экспертов": "Способ 4 — Группа экспертов",
    }
    print("\nСохраняю результаты в markdown-файлы...")
    for name in order:
        extra = f"Эксперты: {', '.join(experts)}\n\n" if name == "Группа экспертов" else ""
        path = RESULTS_DIR / file_map[name]
        save_markdown(path, titles[name], task_text, solutions[name], extra_header=extra)
        files[name] = path
        print(f"  записан: {path}")

    # Шаг 4: сравнительный анализ через API
    print("\n[4/4] Запрашиваю сравнительный анализ через API...")
    comparison = compare_solutions(task_text, {k: solutions[k] for k in order})
    comparison_path = RESULTS_DIR / "05_comparison.md"
    save_markdown(comparison_path, "Сравнительный анализ решений", task_text, comparison)
    print(f"  записан: {comparison_path}")

    print("\nГотово. Все результаты лежат в папке:")
    print(f"  {RESULTS_DIR}")
    for name in order:
        print(f"  - {files[name].name} ({name})")
    print(f"  - {comparison_path.name} (Сравнительный анализ)")


if __name__ == "__main__":
    main()
