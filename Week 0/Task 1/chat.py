from openai import OpenAI
from dotenv import load_dotenv
import os

load_dotenv()

client = OpenAI(
    api_key=os.getenv("DEEPSEEK_API_KEY"),
    base_url="https://api.deepseek.com"
)

history = []

print("Чат с DeepSeek. Для выхода введите 'exit'\n")

while True:
    user_input = input("Ты: ").strip()

    if user_input.lower() == "exit":
        print("Выход.")
        break

    if not user_input:
        continue

    history.append({"role": "user", "content": user_input})

    response = client.chat.completions.create(
        model="deepseek-chat",
        messages=history
    )

    reply = response.choices[0].message.content
    history.append({"role": "assistant", "content": reply})

    print(f"\nDeepSeek: {reply}\n")