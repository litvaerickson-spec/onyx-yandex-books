# Архитектура мониторинга упоминаний (Social Listening & Pain Detection)

Для быстрого реагирования на появление пользователей с острой болью (*«не работают яндекс книги на читалке»*, *«букмейт вылетает на onyx boox»*) настраивается автоматизированный контур перехвата поисковых запросов и сообщений.

```
┌────────────────────────────────────────────────────────┐
│             Источники мониторинга (24/7)               │
├──────────────────┬──────────────────┬──────────────────┤
│ Telegram (TGStat)│  4PDA (RSS веток)│ Google / Reddit  │
└────────┬─────────┴────────┬─────────┴────────┬─────────┘
         │                  │                  │
         └──────────────┐   │   ┌──────────────┘
                        ▼   ▼   ▼
     ┌──────────────────────────────────────────────┐
     │  Фильтр ключевых слов и стоп-слов (Python)   │
     │  • + «яндекс книги», «букмейт», «читалка»    │
     │  • + «onyx boox», «дарвин», «e-ink»         │
     └──────────────────────┬───────────────────────┘
                            ▼
     ┌──────────────────────────────────────────────┐
     │  Оповещение в закрытый Telegram-чат команды  │
     │  «Обнаружен тред с болью! Ссылка: ...»       │
     └──────────────────────────────────────────────┘
```

---

## 1. Настройка TGStat Alerts (Telegram-каналы и чаты)

TGStat Alerts позволяет бесплатно отслеживать появление ключевых фраз во всех публичных каналах и чатах СНГ:

1. Откройте бота: `@TGStat_Alerts_Bot` в Telegram.
2. Отправьте команду создания поискового правила:
   ```text
   /add ("яндекс книги" | "букмейт") & ("onyx" | "оникс" | "ридер" | "читалка" | "darwin" | "дарвин" | "e-ink")
   ```
3. Выберите периодичность: **Мгновенно (Real-time)**.
4. При появлении вопроса в профильных чатах ридеров бот присылает прямую ссылку на сообщение в течение 1–2 минут.

---

## 2. Google Alerts и Talkwalker (Индексация веба и форумов)

Google Alerts отслеживает появление тем на новых сайтах, форумах и в статьях:

* **Поисковый запрос 1:** `"яндекс книги" AND ("onyx boox" OR "электронная книга" OR "ридер")`
* **Поисковый запрос 2:** `"букмейт" AND ("darwin" OR "android 4.2" OR "android 4.4")`
* **Параметры:**
  * Частота отправки: *По мере появления результатов*.
  * Источники: *Блоги, Новости, Обсуждения*.
  * Язык: *Русский*.
  * Доставка: *на почту или RSS-ленту*.

---

## 3. Мониторинг профильных веток 4PDA через RSS

Каждая ветка форума 4PDA отдает стандартный RSS-поток:
* Формат URL: `https://4pda.to/forum/index.php?act=rss&t=[ID_ТЕМЫ]`

### Ключевые ветки для мониторинга:
* Ветка «Яндекс Книги (Букмейт)»: отслеживание по ключевикам *onyx*, *ридер*, *e-ink*, *вылетает*.
* Ветка «Onyx Boox Darwin (1-9)»: отслеживание по ключевикам *книги*, *яндекс*, *подписка*.
* Ветка «Onyx Boox - Клуб владельцев».

---

## 4. Готовый Python-скрипт мониторинга RSS и отправки в Telegram

Скрипт можно запустить на любом бесплатном сервере или локально по крону:

```python
#!/usr/bin/env python3
# monitor_mentions.py - Легковесный сканер RSS веток для выявления пользователей с проблемами
import feedparser
import urllib.parse
import os
import requests
import time

TELEGRAM_BOT_TOKEN = os.getenv("TELEGRAM_BOT_TOKEN", "YOUR_BOT_TOKEN")
TELEGRAM_ADMIN_CHAT = os.getenv("TELEGRAM_ADMIN_CHAT", "YOUR_CHAT_ID")

KEYWORDS = ["яндекс", "букмейт", "книги", "подписк", "yandex", "bookmate"]
DEVICE_KEYWORDS = ["onyx", "оникс", "дарвин", "darwin", "ридер", "читалк", "e-ink", "вылетает", "висит"]

# Список RSS фидов веток 4PDA и Reddit
FEEDS = [
    # Reddit search RSS
    "https://www.reddit.com/r/Onyx_Boox/search.rss?q=yandex+OR+bookmate&sort=new",
    "https://www.reddit.com/r/ereader/search.rss?q=yandex+OR+bookmate&sort=new",
]

SEEN_POSTS = set()

def check_feeds():
    for feed_url in FEEDS:
        try:
            feed = feedparser.parse(feed_url)
            for entry in feed.entries:
                if entry.id in SEEN_POSTS:
                    continue
                
                content = (entry.title + " " + entry.get("summary", "")).lower()
                has_book = any(k in content for k in KEYWORDS)
                has_dev = any(k in content for k in DEVICE_KEYWORDS)

                if has_book and has_dev:
                    msg = (
                        f"🚨 *Обнаружен вопрос по теме!*\n\n"
                        f"📌 *Заголовок:* {entry.title}\n"
                        f"🔗 *Ссылка:* {entry.link}\n"
                    )
                    send_alert(msg)
                
                SEEN_POSTS.add(entry.id)
        except Exception as e:
            print(f"Ошибка парсинга {feed_url}: {e}")

def send_alert(text):
    if TELEGRAM_BOT_TOKEN == "YOUR_BOT_TOKEN":
        print(text)
        return
    url = f"https://api.telegram.org/bot{TELEGRAM_BOT_TOKEN}/sendMessage"
    payload = {"chat_id": TELEGRAM_ADMIN_CHAT, "text": text, "parse_mode": "Markdown"}
    requests.post(url, json=payload)

if __name__ == "__main__":
    print("Запуск мониторинга упоминаний...")
    check_feeds()
```
