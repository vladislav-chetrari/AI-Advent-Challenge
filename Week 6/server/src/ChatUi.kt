package server

// Страница чата поддержки: её открывает конечный пользователь.
// Никаких ключей и токенов — только номер обращения и текст сообщения.
// Один HTML-файл без внешних зависимостей: грузится мгновенно и работает офлайн
// (кроме самого запроса к /support/chat).
val CHAT_HTML = """
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>CallowFellow — поддержка</title>
<style>
  :root {
    --bg: #0f1115; --card: #171a21; --line: #262b36; --text: #e6e8ee; --dim: #9aa3b2;
    --accent: #4f8cff; --you: #1d3a63; --bot: #1b1f27;
  }
  * { box-sizing: border-box; }
  body { margin: 0; font: 15px/1.55 -apple-system, "Segoe UI", Roboto, sans-serif; background: var(--bg); color: var(--text); }
  .wrap { max-width: 760px; margin: 0 auto; padding: 24px 16px 32px; }
  header { display: flex; align-items: center; gap: 12px; margin-bottom: 18px; }
  .logo { width: 38px; height: 38px; border-radius: 10px; background: var(--accent); display: grid; place-items: center; font-weight: 700; }
  h1 { font-size: 17px; margin: 0; }
  .sub { color: var(--dim); font-size: 13px; }
  .ticket { display: flex; gap: 8px; align-items: center; margin-bottom: 14px; font-size: 13px; }
  .ticket b { font-size: 14px; }
  .ticket a { color: var(--dim); }
  .ticket label { color: var(--dim); font-size: 13px; }
  input { background: var(--card); border: 1px solid var(--line); color: var(--text); border-radius: 9px; padding: 9px 12px; font: inherit; }
  #chatId { width: 110px; }
  .log { display: flex; flex-direction: column; gap: 10px; min-height: 320px; padding: 4px 0 16px; }
  .msg { max-width: 86%; padding: 11px 14px; border-radius: 14px; white-space: pre-wrap; }
  .msg.you { align-self: flex-end; background: var(--you); border-bottom-right-radius: 4px; }
  .msg.bot { align-self: flex-start; background: var(--bot); border: 1px solid var(--line); border-bottom-left-radius: 4px; }
  .meta { color: var(--dim); font-size: 12px; margin-top: 6px; }
  .src { margin-top: 8px; padding-top: 8px; border-top: 1px dashed var(--line); color: var(--dim); font-size: 12.5px; }
  .src div { margin-top: 3px; }
  form { display: flex; gap: 10px; align-items: flex-end; }
  #msg { flex: 1; resize: vertical; min-height: 46px; max-height: 160px; }
  button { background: var(--accent); color: #fff; border: 0; border-radius: 9px; padding: 13px 20px; font: inherit; font-weight: 600; cursor: pointer; }
  button:disabled { opacity: .5; cursor: default; }
  .hint { color: var(--dim); font-size: 12.5px; margin-top: 12px; }
  .dot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: #3ddc84; margin-right: 6px; }
</style>
</head>
<body>
<div class="wrap">
  <header>
    <div class="logo">CF</div>
    <div>
      <h1>Поддержка CallowFellow</h1>
      <div class="sub"><span class="dot"></span>ассистент отвечает по документации и вашему обращению</div>
    </div>
  </header>

  <div class="log" id="log"></div>

  <form id="f">
    <textarea id="msg" placeholder="Опишите проблему…" rows="2"></textarea>
    <button type="submit" id="send">Отправить</button>
  </form>

  <div class="hint">Ответы строятся по документации продукта и по вашему обращению — с цитатами. Если проблема в работе, ассистент скажет, что она уже в работе, и даст обходной путь.</div>
</div>
<script>
  const log = document.getElementById('log');
  const form = document.getElementById('f');
  const input = document.getElementById('msg');
  const send = document.getElementById('send');
  // Пользователь ни о чём не спрашивает, кроме сути проблемы: номер обращения
  // ему знать не нужно — ассистент сам находит подходящее обращение в CRM.
  // chatId — просто идентификатор сессии (нужен, чтобы помнить переписку).
  const chatId = (() => {
    const KEY = 'cf-support-chat';
    let id = localStorage.getItem(KEY);
    if (!id) {
      id = 'web-' + Math.random().toString(36).slice(2, 10);
      localStorage.setItem(KEY, id);
    }
    return id;
  })();

  function add(role, text, sources, meta) {
    const div = document.createElement('div');
    div.className = 'msg ' + role;
    div.textContent = text;
    if (meta) {
      const m = document.createElement('div');
      m.className = 'meta';
      m.textContent = meta;
      div.appendChild(m);
    }
    if (sources && sources.length) {
      const s = document.createElement('div');
      s.className = 'src';
      s.textContent = 'Источники:';
      sources.forEach(r => {
        const d = document.createElement('div');
        d.textContent = '[' + r.index + '] ' + r.title + (r.section ? ' / ' + r.section : '');
        s.appendChild(d);
      });
      div.appendChild(s);
    }
    log.appendChild(div);
    log.scrollTop = log.scrollHeight;
    return div;
  }

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const text = input.value.trim();
    if (!text) return;
    add('you', text);
    input.value = '';
    send.disabled = true;
    const pending = add('bot', 'Ассистент печатает…');
    try {
      const res = await fetch('/support/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ chatId: chatId, message: text })
      });
      const data = await res.json();
      pending.textContent = data.text || ('Ошибка: ' + (data.warning || res.status));
      const meta = [];
      if (data.usedTicket) {
        meta.push('обращение #' + data.ticketId + ' учтено в ответе');
        if (data.ticketStatus) meta.push(data.ticketStatus);
      }
      if (meta.length) {
        const m = document.createElement('div');
        m.className = 'meta';
        m.textContent = meta.join(' · ');
        pending.appendChild(m);
      }
      if (data.faqRefs && data.faqRefs.length) {
        const s = document.createElement('div');
        s.className = 'src';
        s.textContent = 'Источники:';
        data.faqRefs.forEach(r => {
          const d = document.createElement('div');
          d.textContent = '[' + r.index + '] ' + r.title + (r.section ? ' / ' + r.section : '');
          s.appendChild(d);
        });
        pending.appendChild(s);
      }
    } catch (err) {
      pending.textContent = 'Не удалось связаться с поддержкой: ' + err;
    }
    send.disabled = false;
    input.focus();
  });

  add('bot', 'Здравствуйте! Опишите проблему — подскажу, что делать, и если она уже в работе, скажу, когда ждать.');
</script>
</body>
</html>
"""
