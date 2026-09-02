import os
import re
import json
from openai import OpenAI
from dotenv import load_dotenv

load_dotenv()

client = OpenAI(
    api_key=os.getenv("DEEPSEEK_API_KEY"),
    base_url="https://api.deepseek.com"
)

SUPPORTED_FORMATS = {
    "1": {
        "name": "Простой текст",
        "description": "Обычный текстовый ответ",
        "system_prompt": "Отвечай обычным текстом",
        "validation": lambda x: True
    },
    "2": {
        "name": "Маркированный список",
        "description": "Список с маркерами (-, •, *)",
        "system_prompt": "Отвечай маркированным списком, используя дефисы или звездочки",
        "validation": lambda x: any(line.strip().startswith(('-', '*', '•')) for line in x.split('\n'))
    },
    "3": {
        "name": "Нумерованный список",
        "description": "Список с номерами (1., 2., 3.)",
        "system_prompt": "Отвечай нумерованным списком (1., 2., 3.)",
        "validation": lambda x: any(line.strip()[0].isdigit() for line in x.split('\n') if line.strip())
    },
    "4": {
        "name": "JSON формат",
        "description": "Структурированный JSON",
        "system_prompt": "Отвечай строго в формате JSON. Не добавляй ничего кроме JSON.",
        "validation": lambda x: is_valid_json(x)
    },
    "5": {
        "name": "Код",
        "description": "Блок кода с markdown-разметкой",
        "system_prompt": "Отвечай в формате кода, используя markdown-разметку с тройными обратными кавычками",
        "validation": lambda x: '```' in x
    },
    "6": {
        "name": "Таблица",
        "description": "Markdown таблица",
        "system_prompt": "Отвечай в формате markdown таблицы с разделителями |",
        "validation": lambda x: '|' in x and '---' in x
    }
}

def is_valid_json(text):
    try:
        start = text.find('{')
        end = text.rfind('}') + 1
        if start != -1 and end > start:
            json_str = text[start:end]
            json.loads(json_str)
            return True
        return False
    except:
        return False

def count_words(text):
    text = text.strip()
    words = re.findall(r'\S+', text)
    return len(words)

def estimate_tokens(text):
    words = count_words(text)
    cyrillic_chars = len(re.findall(r'[а-яА-ЯёЁ]', text))
    latin_chars = len(re.findall(r'[a-zA-Z]', text))

    if cyrillic_chars > latin_chars:
        return int(words * 2.5)
    else:
        return int(words * 1.3)

def get_user_prompt():
    print("\n" + "=" * 60)
    print("📝 ШАГ 1: ОСНОВНОЙ ЗАПРОС")
    print("=" * 60)
    print("Введите текст запроса к LLM:")

    while True:
        prompt = input("> ").strip()
        if prompt:
            return prompt
        print("❌ Запрос не может быть пустым:")

def get_format_choice():
    print("\n" + "=" * 60)
    print("📋 ШАГ 2: ФОРМАТ ОТВЕТА")
    print("=" * 60)
    print("Выберите формат ответа:")
    print()

    for key, format_info in SUPPORTED_FORMATS.items():
        print(f"{key}. {format_info['name']}")
        print(f"   📄 {format_info['description']}")
        print()

    print("0. Без формата (свободный ответ)")

    while True:
        choice = input("\nВаш выбор (0-6): ").strip()
        if choice == "0":
            return None
        elif choice in SUPPORTED_FORMATS:
            return SUPPORTED_FORMATS[choice]
        print(f"❌ Неверный выбор. Введите число от 0 до {len(SUPPORTED_FORMATS)}")

def get_length_limit():
    print("\n" + "=" * 60)
    print("📏 ШАГ 3: ОГРАНИЧЕНИЕ ДЛИНЫ ОТВЕТА")
    print("=" * 60)
    print("Выберите тип ограничения:")
    print("1. В словах")
    print("2. В символах")
    print("3. В токенах")
    print("0. Без ограничения")

    while True:
        choice = input("\nВаш выбор (0-3): ").strip()

        if choice == "0":
            print("ℹ️ Без ограничения длины")
            return None, 4000

        elif choice == "1":
            while True:
                try:
                    max_words = int(input("Максимум слов: ").strip())
                    if max_words <= 0:
                        print("❌ Число должно быть положительным")
                        continue
                    max_tokens = min(max_words * 3 + 100, 4000)
                    print(f"✅ Лимит: {max_words} слов (≈ {max_tokens} токенов)")
                    return {"type": "words", "limit": max_words}, max_tokens
                except ValueError:
                    print("❌ Введите целое число")

        elif choice == "2":
            while True:
                try:
                    max_chars = int(input("Максимум символов: ").strip())
                    if max_chars <= 0:
                        print("❌ Число должно быть положительным")
                        continue
                    max_tokens = min(int(max_chars / 2) + 100, 4000)
                    print(f"✅ Лимит: {max_chars} символов (≈ {max_tokens} токенов)")
                    return {"type": "chars", "limit": max_chars}, max_tokens
                except ValueError:
                    print("❌ Введите целое число")

        elif choice == "3":
            while True:
                try:
                    max_tokens = int(input("Максимум токенов (до 4000): ").strip())
                    if max_tokens <= 0 or max_tokens > 4000:
                        print("❌ Введите число от 1 до 4000")
                        continue
                    print(f"✅ Лимит: {max_tokens} токенов")
                    return {"type": "tokens", "limit": max_tokens}, max_tokens
                except ValueError:
                    print("❌ Введите целое число")

        else:
            print("❌ Введите число от 0 до 3")

def get_stop_sequence():
    print("\n" + "=" * 60)
    print("🛑 ШАГ 4: СПОСОБ ЗАВЕРШЕНИЯ ОТВЕТА")
    print("=" * 60)
    print("Выберите способ завершения:")
    print("1. API stop (ответ обрежется на стоп-слове)")
    print("2. Инструкция в промпте (модель сама напишет стоп-слово)")
    print("3. Оба способа")
    print("0. Без стоп-слова")

    while True:
        choice = input("\nВаш выбор (0-3): ").strip()

        if choice == "0":
            print("ℹ️ Без стоп-слова")
            return None, None

        elif choice in ["1", "2", "3"]:
            stop_word = input("Введите стоп-слово: ").strip()
            if not stop_word:
                print("❌ Стоп-слово не может быть пустым")
                continue

            methods = {
                "1": {"use_api_stop": True, "use_prompt_instruction": False},
                "2": {"use_api_stop": False, "use_prompt_instruction": True},
                "3": {"use_api_stop": True, "use_prompt_instruction": True}
            }

            method = methods[choice]
            method["word"] = stop_word
            print(f"✅ Стоп-слово: '{stop_word}'")
            print(f"   Метод: {choice}")
            return stop_word, method

        else:
            print("❌ Введите число от 0 до 3")

def ask_deepseek(prompt, format_info=None, max_tokens=4000, stop_config=None,
                 length_limit=None, use_constraints=False):
    """
Отправляет запрос к DeepSeek
    """
    # Системный промпт
    if use_constraints and format_info:
        system_content = f"""Ты - полезный ассистент.
            {format_info['system_prompt']}
            Строго следуй указанному формату."""
    else:
        system_content = "Ты - полезный ассистент."

    # Пользовательский промпт
    user_content = prompt

    if use_constraints:
        instructions = []

        if format_info:
            instructions.append(f"Формат: {format_info['description']}")

        if length_limit:
            if length_limit["type"] == "words":
                instructions.append(f"Длина: не более {length_limit['limit']} слов")
            elif length_limit["type"] == "chars":
                instructions.append(f"Длина: не более {length_limit['limit']} символов")
            elif length_limit["type"] == "tokens":
                instructions.append(f"Длина: не более {length_limit['limit']} токенов")

        # Добавляем стоп-слово в промпт, если нужно
        if stop_config and stop_config.get("use_prompt_instruction"):
            instructions.append(f"Заверши ответ словом: '{stop_config['word']}'")

        if instructions:
            user_content += "\n\nТребования:\n" + "\n".join(f"- {i}" for i in instructions)

    # Параметры API
    api_params = {
        "model": "deepseek-chat",
        "messages": [
            {"role": "system", "content": system_content},
            {"role": "user", "content": user_content}
        ],
        "max_tokens": max_tokens,
        "stream": False
    }

    # Добавляем API stop, если нужно
    if stop_config and stop_config.get("use_api_stop"):
        api_params["stop"] = [stop_config["word"]]

    response = client.chat.completions.create(**api_params)

    return response.choices[0].message.content, response.choices[0].finish_reason

def display_response_with_stats(title, content, finish_reason, format_info=None,
                                length_limit=None, stop_config=None):
    """
Отображает ответ с подробной статистикой
    """
    print("\n" + "=" * 60)
    print(f"📌 {title}")
    print("=" * 60)
    print(content)

    words = count_words(content)
    chars = len(content)
    est_tokens = estimate_tokens(content)

    print("\n📊 СТАТИСТИКА:")
    print(f"   • Слов: {words}")
    print(f"   • Символов: {chars}")
    print(f"   • Примерно токенов: {est_tokens}")
    print(f"   • Причина завершения: {finish_reason}")

    if format_info or length_limit or stop_config:
        print("\n✅ ПРОВЕРКА ОГРАНИЧЕНИЙ:")

        if format_info:
            is_valid = format_info['validation'](content)
            status = "✅" if is_valid else "❌"
            print(f"   {status} Формат: {format_info['name']}")

        if length_limit:
            limit_type = length_limit["type"]
            limit_value = length_limit["limit"]

            if limit_type == "words":
                actual = words
                within = actual <= limit_value
                print(f"   {'✅' if within else '❌'} Слова: {actual}/{limit_value}")
            elif limit_type == "chars":
                actual = chars
                within = actual <= limit_value
                print(f"   {'✅' if within else '❌'} Символы: {actual}/{limit_value}")
            elif limit_type == "tokens":
                actual = est_tokens
                within = actual <= limit_value
                print(f"   {'✅' if within else '❌'} Токены (оценка): {actual}/{limit_value}")

        if stop_config:
            stop_word = stop_config["word"]
            use_api = stop_config.get("use_api_stop", False)
            use_prompt = stop_config.get("use_prompt_instruction", False)

            has_stop_in_content = stop_word in content

            if use_api and not use_prompt:
                # API stop - стоп-слово не должно быть в ответе
                print(f"   ℹ️ API stop: генерация остановлена ДО '{stop_word}'")
                if finish_reason == "stop":
                    print(f"   ✅ API корректно остановил генерацию")
                else:
                    print(f"   ⚠️ API не встретил стоп-слово")

            elif use_prompt and not use_api:
                # Только инструкция - стоп-слово должно быть в ответе
                status = "✅" if has_stop_in_content else "❌"
                print(f"   {status} Стоп-слово '{stop_word}' в ответе: {'да' if has_stop_in_content else 'нет'}")

            elif use_api and use_prompt:
                # Оба метода
                print(f"   ℹ️ Использованы оба метода")
                if has_stop_in_content:
                    print(f"   ✅ Стоп-слово '{stop_word}' присутствует (модель включила его)")
                else:
                    print(f"   ⚠️ Стоп-слово '{stop_word}' отсутствует (API остановил генерацию)")

        if finish_reason == "length":
            print("\n⚠️ ВНИМАНИЕ: Ответ был обрезан по лимиту токенов!")

def main():
    print("=" * 60)
    print("🤖 DeepSeek API - Контроль ответов")
    print("=" * 60)
    print("\nДемонстрация разных способов контроля ответа")

    while True:
        # Получаем параметры
        prompt = get_user_prompt()
        format_info = get_format_choice()
        length_limit, max_tokens = get_length_limit()
        stop_word, stop_config = get_stop_sequence()

        # Сводка
        print("\n" + "=" * 60)
        print("📋 СВОДКА ПАРАМЕТРОВ:")
        print("-" * 60)
        print(f"Запрос: {prompt[:50]}{'...' if len(prompt) > 50 else ''}")
        print(f"Формат: {format_info['name'] if format_info else 'Без формата'}")
        if length_limit:
            print(f"Ограничение: {length_limit['limit']} {length_limit['type']}")
        else:
            print("Ограничение: нет")

        if stop_config:
            method_desc = []
            if stop_config.get("use_api_stop"):
                method_desc.append("API")
            if stop_config.get("use_prompt_instruction"):
                method_desc.append("промпт")
            print(f"Стоп-слово: '{stop_word}' (метод: {', '.join(method_desc)})")
        else:
            print("Стоп-слово: нет")

        # Отправляем запросы
        print("\n" + "🚀" * 20)
        print("ОТПРАВКА ЗАПРОСОВ...")
        print("🚀" * 20)

        try:
            # Без ограничений
            print("\n⏳ Получение ответа без ограничений...")
            answer_free, finish_free = ask_deepseek(
                prompt=prompt,
                use_constraints=False
            )

            # С ограничениями
            print("⏳ Получение ответа с ограничениями...")
            answer_constrained, finish_constrained = ask_deepseek(
                prompt=prompt,
                format_info=format_info,
                max_tokens=max_tokens,
                stop_config=stop_config,
                length_limit=length_limit,
                use_constraints=True
            )

            # Выводим результаты
            display_response_with_stats("ОТВЕТ БЕЗ ОГРАНИЧЕНИЙ", answer_free, finish_free)

            display_response_with_stats(
                "ОТВЕТ С ОГРАНИЧЕНИЯМИ",
                answer_constrained,
                finish_constrained,
                format_info,
                length_limit,
                stop_config
            )

            # Сравнение
            print("\n" + "=" * 60)
            print("📈 ИТОГОВОЕ СРАВНЕНИЕ:")
            print("=" * 60)

            words_free = count_words(answer_free)
            words_constrained = count_words(answer_constrained)

            print(f"Без ограничений: {words_free} слов / {len(answer_free)} символов")
            print(f"С ограничениями: {words_constrained} слов / {len(answer_constrained)} символов")
            print(f"Разница: {abs(words_free - words_constrained)} слов")

        except Exception as e:
            print(f"\n❌ Ошибка: {e}")
            if "401" in str(e):
                print("Проверьте API ключ")
            elif "429" in str(e):
                print("Слишком много запросов")

        # Продолжение
        print("\n" + "=" * 60)
        while True:
            again = input("Еще раз? (да/нет): ").strip().lower()
            if again in ['да', 'yes', 'y', 'д', '+']:
                print("\n" + "🔄" * 20)
                break
            elif again in ['нет', 'no', 'n', 'н', '-']:
                print("\n👋 До свидания!")
                return
            else:
                print("❌ Введите 'да' или 'нет'")

if __name__ == "__main__":
    if not os.getenv("DEEPSEEK_API_KEY"):
        print("❌ API ключ не найден")
    else:
        main()