"""Задание 4 (Week 0): сравнение temperature 0 / 0.7 / 1.2 на DeepSeek API.

Сценарий:
1. Консоль считывает один промпт.
2. Параллельно отправляются 3 запроса (temperature=0, 0.7, 1.2).
3. Ответы сохраняются в папку results/ как markdown-файлы.
4. Отправляется 4-й запрос — сводка-сравнение по критериям:
   точность, креативность, разнообразие + вывод, для каких задач
   каждая настройка подходит лучше.
"""

import os
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path

from dotenv import load_dotenv
from openai import OpenAI

# .env лежит на уровень выше (Week 0/.env), но поддержим и локальный.
BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env")
load_dotenv(BASE_DIR.parent / ".env")
load_dotenv()

api_key = os.getenv("DEEPSEEK_API_KEY")
if not api_key:
    raise SystemExit("❌ DEEPSEEK_API_KEY не найден. Проверьте Week 0/.env")

client = OpenAI(api_key=api_key, base_url="https://api.deepseek.com")

MODEL = "deepseek-chat"
TEMPERATURES = [0.0, 0.7, 1.2]
RESULTS_DIR = BASE_DIR / "results"


def query_deepseek(prompt: str, temperature: float) -> str:
    """Один запрос к DeepSeek Chat API."""
    response = client.chat.completions.create(
        model=MODEL,
        messages=[{"role": "user", "content": prompt}],
        temperature=temperature,
    )
    return response.choices[0].message.content


def safe_filename(temperature: float) -> str:
    label = ("%.1f" % temperature).replace(".", "_").rstrip("0_").rstrip("_")
    # 0.0 -> "0", 0.7 -> "0_7", 1.2 -> "1_2"
    if label == "":
        label = "0"
    return f"temp_{label}.md"


def save_markdown(path: Path, prompt: str, temperature: float, answer: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    content = (
        f"# Ответ DeepSeek (temperature={temperature})\n\n"
        f"**Дата:** {timestamp}\n\n"
        f"**Модель:** {MODEL}\n\n"
        f"## Промпт\n\n{prompt}\n\n"
        f"## Ответ\n\n{answer}\n"
    )
    path.write_text(content, encoding="utf-8")


def build_summary_prompt(prompt: str, answers: dict[float, str]) -> str:
    blocks = "\n\n".join(
        f"--- ОТВЕТ temperature={t} ---\n{answers[t]}" for t in TEMPERATURES
    )
    return (
        "Сравни три ответа одной и той же языковой модели (DeepSeek) "
        "на один и тот же запрос пользователя, но с разными temperature.\n\n"
        f"ИСХОДНЫЙ ПРОМПТ ПОЛЬЗОВАТЕЛЯ:\n{prompt}\n\n"
        f"{blocks}\n\n"
        "Дай небольшую структурированную сводку (до ~250 слов) по критериям:\n"
        "1. Точность\n"
        "2. Креативность\n"
        "3. Разнообразие\n\n"
        "И в конце сформулируй вывод: для каких задач лучше подходит "
        "каждая настройка (temperature 0 / 0.7 / 1.2). "
        "Отвечай на русском языке, формат — markdown."
    )


def run_once(prompt: str) -> None:
    RESULTS_DIR.mkdir(exist_ok=True)

    print(f"\n⏳ Параллельно отправляю 3 запроса: {TEMPERATURES} ...\n")

    # 1-3. Три запроса параллельно
    answers: dict[float, str] = {}
    with ThreadPoolExecutor(max_workers=3) as pool:
        futures = {t: pool.submit(query_deepseek, prompt, t) for t in TEMPERATURES}
        for t in TEMPERATURES:
            try:
                answers[t] = futures[t].result()
                print(f"✅ Получен ответ temperature={t}")
            except Exception as e:  # noqa: BLE001 — показать ошибку в консоль
                answers[t] = f"⚠️ Ошибка запроса: {e}"
                print(f"❌ Ошибка temperature={t}: {e}")

    # Сохраняем 3 markdown-файла
    for t in TEMPERATURES:
        filepath = RESULTS_DIR / safe_filename(t)
        save_markdown(filepath, prompt, t, answers[t])
        print(f"💾 Сохранено: {filepath}")

    # 4. Запрос-сводка
    print("\n⏳ Запрашиваю сводку-сравнение ...\n")
    summary_prompt = build_summary_prompt(prompt, answers)
    try:
        summary = query_deepseek(summary_prompt, temperature=0.3)
    except Exception as e:  # noqa: BLE001
        summary = f"⚠️ Ошибка запроса сводки: {e}"
        print(f"❌ {summary}")

    summary_path = RESULTS_DIR / "summary.md"
    timestamp = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    summary_path.write_text(
        f"# Сводка: сравнение temperature 0 / 0.7 / 1.2\n\n"
        f"**Дата:** {timestamp}\n\n"
        f"## Исходный промпт\n\n{prompt}\n\n"
        f"## Сравнение (точность, креативность, разнообразие)\n\n{summary}\n",
        encoding="utf-8",
    )
    print(f"💾 Сохранено: {summary_path}")


def main() -> None:
    print("Сравнение temperature DeepSeek: 0 / 0.7 / 1.2")
    print("Введите промпт (или 'exit' для выхода). Результаты -> папка results/\n")
    while True:
        try:
            user_prompt = input("Промпт: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nВыход.")
            break
        if not user_prompt:
            continue
        if user_prompt.lower() == "exit":
            print("Выход.")
            break
        run_once(user_prompt)


if __name__ == "__main__":
    main()
