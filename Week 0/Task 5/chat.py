"""Задание 5 (Week 0): слабая / средняя / сильная модель, один промпт.

Схема:
- слабая  — локальная Qwen2.5-1.5B через Ollama (http://localhost:11434/v1), бесплатно, RTX 3060 12GB
- средняя  — deepseek-v4-flash через DeepSeek API (быстрая/дешевая)
- сильная  — deepseek-v4-pro через DeepSeek API (медленнее/дороже/сильнее)

Сценарий:
1. Консоль считывает один промпт.
2. Параллельно отправляются 3 запроса, замеряются: время, токены, стоимость.
3. Ответы сохраняются в папку results/ как markdown-файлы (модель + ссылка внутри).
4. Отправляется 4-й запрос (через DeepSeek) — короткий вывод о различиях:
   качество, скорость, ресурсоемкость + ссылки.
"""

import os
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path

from dotenv import load_dotenv
from openai import OpenAI

BASE_DIR = Path(__file__).resolve().parent
load_dotenv(BASE_DIR / ".env")
load_dotenv(BASE_DIR.parent / ".env")
load_dotenv()

api_key = os.getenv("DEEPSEEK_API_KEY")
if not api_key:
    raise SystemExit("DEEPSEEK_API_KEY не найден. Проверьте Week 0/.env")

deepseek = OpenAI(api_key=api_key, base_url="https://api.deepseek.com")
local = OpenAI(api_key="ollama", base_url="http://localhost:11434/v1")

# --- Модели и ссылки (для отчета "Результат: вывод + ссылки") ---
WEAK_MODEL = "qwen2.5:1.5b"
WEAK_INFO = [
    "https://ollama.com/library/qwen2.5",
    "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct",
]
MEDIUM_MODEL = "deepseek-v4-flash"
MEDIUM_INFO = [
    "https://api-docs.deepseek.com/quick_start/pricing",
    "https://api-docs.deepseek.com/updates",
]
STRONG_MODEL = "deepseek-v4-pro"
STRONG_INFO = [
    "https://api-docs.deepseek.com/quick_start/pricing",
    "https://api-docs.deepseek.com/updates",
]

# Цены DeepSeek API за 1M токенов (cache miss), USD.
# Источник: https://api-docs.deepseek.com/quick_start/pricing (проверяйте перед отчетом).
PRICING = {
    MEDIUM_MODEL: {"input": 0.14, "output": 0.28},
    STRONG_MODEL: {"input": 0.435, "output": 0.87},
}

RESULTS_DIR = BASE_DIR / "results"


def calc_cost(model: str, prompt_tokens: int, completion_tokens: int) -> float:
    p = PRICING.get(model)
    if not p:
        return 0.0
    return prompt_tokens / 1_000_000 * p["input"] + completion_tokens / 1_000_000 * p["output"]


def query_weak(prompt: str) -> dict:
    """Локальная слабая модель через Ollama OpenAI-совместимый endpoint."""
    t0 = time.time()
    resp = local.chat.completions.create(
        model=WEAK_MODEL,
        messages=[{"role": "user", "content": prompt}],
    )
    dt = time.time() - t0
    usage = getattr(resp, "usage", None)
    pt = getattr(usage, "prompt_tokens", 0) or 0
    ct = getattr(usage, "completion_tokens", 0) or 0
    return {
        "label": "weak",
        "model": WEAK_MODEL,
        "answer": resp.choices[0].message.content,
        "prompt_tokens": pt,
        "completion_tokens": ct,
        "total_tokens": (pt + ct) or 0,
        "time_s": dt,
        "cost_usd": 0.0,
        "resources": "Локально: RTX 3060 12GB, вес ~986MB, 100% GPU (ollama ps), 0 USD",
        "links": WEAK_INFO,
    }


def query_deepseek(prompt: str, model: str, label: str, resources: str) -> dict:
    t0 = time.time()
    resp = deepseek.chat.completions.create(
        model=model,
        messages=[{"role": "user", "content": prompt}],
    )
    dt = time.time() - t0
    usage = resp.usage
    pt = usage.prompt_tokens if usage else 0
    ct = usage.completion_tokens if usage else 0
    return {
        "label": label,
        "model": model,
        "answer": resp.choices[0].message.content,
        "prompt_tokens": pt,
        "completion_tokens": ct,
        "total_tokens": (usage.total_tokens if usage else pt + ct),
        "time_s": dt,
        "cost_usd": calc_cost(model, pt, ct),
        "resources": resources,
        "links": MEDIUM_INFO if label == "medium" else STRONG_INFO,
    }


def save_markdown(path: Path, prompt: str, r: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    tok_s = (r["completion_tokens"] / r["time_s"]) if r["time_s"] > 0 else 0
    links = "\n".join(f"- {u}" for u in r["links"])
    content = (
        f"# Ответ ({r['label']}: {r['model']})\n\n"
        f"**Дата:** {ts}\n\n"
        f"**Модель:** {r['model']}\n\n"
        f"**Ссылки:**\n{links}\n\n"
        f"## Метрики\n\n"
        f"- Время ответа: {r['time_s']:.1f} сек\n"
        f"- Токены: prompt={r['prompt_tokens']}, completion={r['completion_tokens']}, "
        f"total={r['total_tokens']}\n"
        f"- Скорость генерации: ~{tok_s:.1f} ток/сек (completion/время)\n"
        f"- Стоимость: ${r['cost_usd']:.6f}\n"
        f"- Ресурсоемкость: {r['resources']}\n\n"
        f"## Промпт\n\n{prompt}\n\n"
        f"## Ответ\n\n{r['answer']}\n"
    )
    path.write_text(content, encoding="utf-8")


def build_summary_prompt(prompt: str, results: list[dict]) -> str:
    blocks = []
    for r in results:
        blocks.append(
            f"--- {r['label'].upper()} ({r['model']}) ---\n"
            f"Время: {r['time_s']:.1f}с, токены: {r['total_tokens']} "
            f"(in={r['prompt_tokens']}, out={r['completion_tokens']}), "
            f"стоимость: ${r['cost_usd']:.6f}, ресурсы: {r['resources']}\n"
            f"Ответ:\n{r['answer']}"
        )
    return (
        "Сравни три ответа на один и тот же запрос пользователя, "
        "полученные от слабой (локальная Qwen2.5-1.5B), средней (deepseek-v4-flash) "
        "и сильной (deepseek-v4-pro) моделей.\n\n"
        f"ИСХОДНЫЙ ПРОМПТ:\n{prompt}\n\n"
        + "\n\n".join(blocks)
        + "\n\nДай короткий вывод (до ~250 слов, markdown, на русском) по критериям:\n"
        "1. Качество ответов\n"
        "2. Скорость\n"
        "3. Ресурсоемкость (включая время, токены, стоимость)\n"
        "В конце — одной строкой: когда какую модель выбирать."
    )


def run_once(prompt: str) -> None:
    RESULTS_DIR.mkdir(exist_ok=True)
    print("\nПараллельно отправляю 3 запроса: weak / medium / strong ...\n")

    with ThreadPoolExecutor(max_workers=3) as pool:
        f_weak = pool.submit(query_weak, prompt)
        f_med = pool.submit(
            query_deepseek, prompt, MEDIUM_MODEL, "medium",
            "DeepSeek API, shared cloud GPU, тариф $0.14/$0.28 за 1M токенов",
        )
        f_strong = pool.submit(
            query_deepseek, prompt, STRONG_MODEL, "strong",
            "DeepSeek API, shared cloud GPU, тариф $0.435/$0.87 за 1M токенов",
        )
        results = []
        for f, name in ((f_weak, "weak"), (f_med, "medium"), (f_strong, "strong")):
            try:
                r = f.result()
                print(
                    f"OK {name} ({r['model']}): {r['time_s']:.1f}с, "
                    f"токены={r['total_tokens']}, ${r['cost_usd']:.6f}"
                )
                results.append(r)
            except Exception as e:  # noqa: BLE001
                print(f"ОШИБКА {name}: {e}")
                results.append({
                    "label": name, "model": name, "answer": f"Ошибка запроса: {e}",
                    "prompt_tokens": 0, "completion_tokens": 0, "total_tokens": 0,
                    "time_s": 0.0, "cost_usd": 0.0, "resources": "n/a",
                    "links": [],
                })

    for r in results:
        fp = RESULTS_DIR / f"{r['label']}_{r['model'].replace(':', '_').replace('/', '_')}.md"
        save_markdown(fp, prompt, r)
        print(f"Сохранено: {fp}")

    print("\nЗапрашиваю сводку-сравнение через DeepSeek ...\n")
    try:
        summary = deepseek.chat.completions.create(
            model=MEDIUM_MODEL,  # сводка — дешевой средней моделью
            messages=[{"role": "user", "content": build_summary_prompt(prompt, results)}],
            temperature=0.3,
        ).choices[0].message.content
    except Exception as e:  # noqa: BLE001
        summary = f"Ошибка запроса сводки: {e}"
        print(summary)

    sp = RESULTS_DIR / "summary.md"
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    sp.write_text(
        "# Сводка: слабая / средняя / сильная\n\n"
        f"**Дата:** {ts}\n\n"
        f"**Модели:** weak={WEAK_MODEL}, medium={MEDIUM_MODEL}, strong={STRONG_MODEL}\n\n"
        f"**Ссылки:**\n"
        f"- weak: {', '.join(WEAK_INFO)}\n"
        f"- medium/strong: {', '.join(MEDIUM_INFO)}\n"
        f"- Тарифы: https://api-docs.deepseek.com/quick_start/pricing\n\n"
        f"## Исходный промпт\n\n{prompt}\n\n"
        f"## Вывод (качество, скорость, ресурсоемкость)\n\n{summary}\n",
        encoding="utf-8",
    )
    print(f"Сохранено: {sp}")


def main() -> None:
    print("Сравнение моделей: weak (Ollama) / medium (Flash) / strong (Pro)")
    print("Введите промпт (или 'exit'). Результаты -> папка results/\n")
    while True:
        try:
            p = input("Промпт: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nВыход.")
            break
        if not p:
            continue
        if p.lower() == "exit":
            print("Выход.")
            break
        run_once(p)


if __name__ == "__main__":
    main()
