import os
import time
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

MODEL = "deepseek-chat"  # быстрая модель

# Список задач (5 задач)
TASKS = {
    1: """Парадокс Монти Холла.
Вы на игровом шоу. Перед вами три двери, за одной — автомобиль, за двумя другими — козы.
Вы выбираете дверь №1. Ведущий, который знает, где автомобиль, открывает одну из оставшихся дверей,
скажем №3, за которой коза. Затем он спрашивает: «Хотите ли вы поменять свой выбор на дверь №2?»
Стоит ли менять выбор и почему? Обоснуйте свой ответ.""",

    2: """12 монет и фальшивая.
Есть 12 монет, одна из них фальшивая. Она может быть легче или тяжелее настоящей (но это неизвестно).
У вас есть чашечные весы без гирь. Как за три взвешивания гарантированно найти фальшивую монету
и определить, легче она или тяжелее? Опишите алгоритм.""",

    3: """Парадокс дней рождения.
Сколько нужно случайно выбранных людей, чтобы вероятность того, что хотя бы у двух из них
день рождения совпадает (день и месяц), превысила 50%? Дайте ответ и объясните вычисления.""",

    4: """Улитка на столбе.
Улитка ползёт вверх по столбу высотой 10 метров. За день она поднимается на 3 метра,
а за ночь сползает вниз на 2 метра. За сколько дней она достигнет вершины столба? Обоснуйте ответ.""",

    5: """Переправа через реку.
Крестьянину нужно перевезти через реку волка, козу и капусту. В лодке может поместиться только крестьянин
и один объект. Если оставить волка с козой без крестьянина, волк съест козу. Если оставить козу с капустой,
коза съест капусту. Как крестьянину перевезти всех на другой берег, не допустив потерь? Опишите последовательность действий."""
}

def get_response(prompt, temperature=0.7, max_retries=3):
    """Отправляет запрос к DeepSeek API и возвращает текст ответа."""
    for attempt in range(max_retries):
        try:
            response = client.chat.completions.create(
                model=MODEL,
                messages=[{"role": "user", "content": prompt}],
                temperature=temperature,
                stream=False
            )
            return response.choices[0].message.content.strip()
        except Exception as e:
            print(f"Ошибка при запросе (попытка {attempt+1}): {e}")
            if attempt < max_retries - 1:
                time.sleep(2)
            else:
                return f"ERROR: {e}"

# ========== Метод 1: Прямой ответ ==========
def solve_direct(task):
    prompt = task
    return get_response(prompt)

# ========== Метод 2: Пошаговое решение ==========
def solve_step_by_step(task):
    prompt = f"{task}\n\nПожалуйста, решай пошагово, объясняя каждый шаг."
    return get_response(prompt)

# ========== Метод 3: Сгенерированный промпт ==========
def solve_with_generated_prompt(task):
    # Шаг 1: попросить модель составить эффективный промпт для решения этой задачи
    meta_prompt = f"""Ты — эксперт по составлению промптов для LLM.
Вот задача: {task}

Составь максимально эффективный промпт, который поможет другой LLM правильно и полно решить эту задачу.
Верни только текст промпта, без пояснений."""
    generated_prompt = get_response(meta_prompt)

    # Шаг 2: использовать сгенерированный промпт (добавив саму задачу для контекста)
    final_prompt = f"Задача: {task}\n\n{generated_prompt}"
    solution = get_response(final_prompt)

    # Возвращаем и сгенерированный промпт, и решение (чтобы показать пользователю)
    return generated_prompt, solution

# ========== Метод 4: Группа экспертов ==========
def solve_with_experts(task):
    # Шаг 1: сгенерировать список экспертов
    experts_prompt = f"""Ты — координатор группы экспертов. Для решения следующей задачи предложи состав экспертов (3-4 человека),
перечисли их роли и кратко опиши, какой вклад каждый может внести.
Задача: {task}

Формат ответа: список экспертов с описанием."""
    experts_description = get_response(experts_prompt)

    # Шаг 2: попросить экспертов обсудить задачу и дать ответ
    discussion_prompt = f"""Ты — координатор группы экспертов. Твоя группа состоит из следующих экспертов:
{experts_description}

Задача: {task}

Проведи обсуждение: каждый эксперт высказывает своё мнение по решению задачи,
затем критик анализирует ответы, и в конце вы даёте общий согласованный ответ.
Выведи мнение каждого эксперта, затем итоговый ответ."""
    solution = get_response(discussion_prompt)

    return experts_description, solution

# ========== Сравнение решений ==========
def compare_solutions(task, solutions_dict):
    """Отправляет все решения модели для сравнительного анализа."""
    solutions_text = ""
    for name, sol in solutions_dict.items():
        solutions_text += f"\n\n=== {name} ===\n{sol}\n"

    compare_prompt = f"""Дана задача: {task}

Ниже представлены четыре решения, полученные разными способами:
{solutions_text}

Сравни эти решения по критериям: правильность, полнота объяснения, ясность, отсутствие ошибок.
Определи, какое решение наиболее точное и почему. Дай краткий анализ каждого решения и итоговый вердикт
(например: "Лучшее решение - способ 2, потому что...").
"""
    return get_response(compare_prompt, temperature=0.3)

def main():
    print("Доступные задачи:")
    for num, task in TASKS.items():
        print(f"\n{num}. {task}")

    while True:
        try:
            choice = int(input("Введите номер задачи (1-5): "))
            if choice in TASKS:
                break
            else:
                print("Пожалуйста, введите число от 1 до 5.")
        except ValueError:
            print("Некорректный ввод. Попробуйте ещё раз.")

    task_text = TASKS[choice]
    print("\n" + "="*50)
    print(f"Выбрана задача #{choice}:")
    print(task_text)
    print("="*50 + "\n")

    # Словарь для хранения решений
    solutions = {}

    # 1. Прямой ответ
    print("1. Получение прямого ответа...")
    solutions["Прямой ответ"] = solve_direct(task_text)
    print("Готово.\n")

    # 2. Пошаговое решение
    print("2. Получение пошагового решения...")
    solutions["Пошаговое решение"] = solve_step_by_step(task_text)
    print("Готово.\n")

    # 3. Сгенерированный промпт
    print("3. Генерация оптимального промпта и решение...")
    generated_prompt, solution3 = solve_with_generated_prompt(task_text)
    solutions["Сгенерированный промпт"] = solution3
    print(f"Сгенерированный промпт:\n{generated_prompt}\n")
    print("Готово.\n")

    # 4. Группа экспертов
    print("4. Работа группы экспертов...")
    experts_desc, solution4 = solve_with_experts(task_text)
    solutions["Группа экспертов"] = solution4
    print(f"Эксперты:\n{experts_desc}\n")
    print("Готово.\n")

    # Вывод всех решений
    print("\n" + "#"*50)
    print("РЕЗУЛЬТАТЫ РЕШЕНИЙ")
    print("#"*50)
    for name, sol in solutions.items():
        print(f"\n--- {name} ---")
        print(sol)
        print("-"*50)

    # Сравнение через API
    print("\nСравнение решений...")
    comparison = compare_solutions(task_text, solutions)
    print("\n" + "="*50)
    print("СРАВНИТЕЛЬНЫЙ АНАЛИЗ ОТ МОДЕЛИ")
    print("="*50)
    print(comparison)

if __name__ == "__main__":
    main()