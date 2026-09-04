from openai import OpenAI
from dotenv import load_dotenv
import os

load_dotenv()

client = OpenAI(
    api_key=os.getenv("DEEPSEEK_API_KEY"),
    base_url="https://api.deepseek.com"
)

MIN_TEMP = 0.0
MAX_TEMP = 2.0


def parse_temperature(raw: str):
    """Пробует распарсить температуру. Возвращает float или None."""
    raw = raw.strip().replace(",", ".")
    try:
        value = float(raw)
    except ValueError:
        return None
    if not (MIN_TEMP <= value <= MAX_TEMP):
        return None
    return value


def ask_temperature(prompt="Введите temperature (0.0-2.0): "):
    while True:
        raw = input(prompt).strip()
        value = parse_temperature(raw)
        if value is not None:
            return value
        print(f"❌ Температура должна быть числом от {MIN_TEMP} до {MAX_TEMP}. Попробуйте снова.")


history = []

print("Чат с DeepSeek. Для выхода введите 'exit'")
print("Смена температуры в чате: /temp <значение>")
print()

temperature = ask_temperature()
print(f"✅ Текущая temperature: {temperature}\n")

while True:
    user_input = input("Ты: ").strip()

    if user_input.lower() == "exit":
        print("Выход.")
        break

    if not user_input:
        continue

    # Смена температуры командой /temp
    if user_input.lower().startswith("/temp"):
        parts = user_input.split(maxsplit=1)
        if len(parts) == 1:
            temperature = ask_temperature("Новая temperature (0.0-2.0): ")
        else:
            value = parse_temperature(parts[1])
            if value is None:
                print(f"❌ Температура должна быть числом от {MIN_TEMP} до {MAX_TEMP}.")
                continue
            temperature = value
        print(f"✅ Текущая temperature: {temperature}\n")
        continue

    history.append({"role": "user", "content": user_input})

    response = client.chat.completions.create(
        model="deepseek-chat",
        messages=history,
        temperature=temperature
    )

    reply = response.choices[0].message.content
    history.append({"role": "assistant", "content": reply})

    print(f"\nDeepSeek [temp={temperature}]: {reply}\n")
