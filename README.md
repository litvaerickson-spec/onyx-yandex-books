# 📚 Яндекс Книги Lite для Onyx Boox (v1.3.3)

[![Release](https://img.shields.io/badge/Release-v1.3.3-black?style=for-the-badge&logo=github)](https://github.com/litvaerickson-spec/onyx-yandex-books/releases/tag/v1.3.3)
[![Android](https://img.shields.io/badge/Android-4.2%20--%204.4.4-gray?style=for-the-badge&logo=android)](https://github.com/litvaerickson-spec/onyx-yandex-books)
[![E-Ink](https://img.shields.io/badge/Screen-E--Ink%20Carta-white?style=for-the-badge)](https://github.com/litvaerickson-spec/onyx-yandex-books)
[![APK Size](https://img.shields.io/badge/APK%20Size-2.2%20MB-lightgrey?style=for-the-badge)](https://github.com/litvaerickson-spec/onyx-yandex-books/releases/download/v1.3.3/yandex-books-lite-v1.3.3.apk)

> **Автономный легковесный клиент сервиса «Яндекс Книги»** для электронных книг Onyx Boox (Darwin, Vasco da Gama, Faust, Monte Cristo, Livingstone и др.) на базе Android 4.2 / 4.4 с поддержкой современных протоколов TLS 1.3, облачной синхронизацией, **каталогом и глобальным поиском**, **бесшовным встроенным OTA-обновлением с GitHub**, интеграцией со штатной читалкой Onyx NeoReader и строгим E-Ink интерфейсом по канонам ридеров без смайликов и визуального шума.

---

### 📥 Быстрая загрузка и установка

* 🚀 **Официальный релиз v1.3.3 на GitHub**: [**Страница релиза v1.3.3**](https://github.com/litvaerickson-spec/onyx-yandex-books/releases/tag/v1.3.3)
* 📦 **Прямая ссылка на APK**: [**`yandex-books-lite-v1.3.3.apk`**](https://github.com/litvaerickson-spec/onyx-yandex-books/releases/download/v1.3.3/yandex-books-lite-v1.3.3.apk) *(2.2 МБ, цифровая подпись v1/v2/v3, готов к установке поверх предыдущей версии)*
* 🔄 **Встроенное OTA-обновление**: прямо из приложения в один клик через кнопку `[ Обновить ]` в шапке или клик по версии внизу!
* 🔨 **Скрипт сборки из исходников**: [`build_apk.sh`](build_apk.sh)

---

## 🌟 Главные новшества версии 1.3.3

1. **🚀 Бесперебойное чтение книг из Каталога и Поиска**:
   - Полностью устранена ошибка скачивания (HTTP 403 / 422) для книг, открываемых прямо из Каталога или Поиска.
   - Внедрена автоматическая привязка книги к профилю пользователя (`POST /profile/library_cards`) перед загрузкой и сохранение метаданных в локальную БД.
   - Исправлена отправка заголовка `Accept: */*` для гарантированной отдачи бинарных EPUB-файлов серверами Bookmate.
   - Реализован отказоустойчивый многоуровневый механизм загрузки (`/content/v4` -> авто-привязка -> `/content` -> `/file`).
2. **📖 100% полное отображение названий и авторов**:
   - Во всех списках (Полки, Каталог, Поиск) и модальных окнах сняты ограничения по строкам (`maxLines`) и многоточия (`ellipsize`).
   - Названия любой длины и полные ФИО авторов видны полностью от первого до последнего слова.
3. **🎯 Чистый E-Ink интерфейс списков**:
   - Аннотации скрыты из строк списка книг, что исключает визуальный шум и нагромождение текста.
   - Подробная аннотация доступна в полноэкранном модальном окне «О книге».
4. **🔄 Сохранение контекста при возврате из читалки**:
   - Списки книг из Каталога и результатов Поиска не сбрасываются при возврате в `MainActivity.onResume`.
   - Аудиокниги (`AudioBook`) автоматически отфильтрованы из поисковой выдачи.
5. **📚 Каталог рекомендаций, поиск Bookmate и добавление «В планы»** (из v1.3.2):
   - 4 равные вкладки: `[ Читаю ]`, `[ В планах ]`, `[ Каталог ]`, `[ Поиск ]`.
   - Строгий дизайн без эмодзи, переключатель читалки `[ Onyx ]` / `[ Онлайн ]`, OTA-обновление.

---

## 📱 Поддерживаемые устройства

| Линейка устройств | ОС Android | Экран | Управление |
|---|---|---|---|
| **Onyx Boox Darwin (1, 2, 3)** | Android 4.2.2 (API 17) | 1024×758 E-Ink Carta | Сенсорный экран + физические боковые кнопки листания |
| **Onyx Boox Darwin (4, 5, 6)** | Android 4.4.4 (API 19) | 1024×758 / 1448×1072 Carta | Сенсорный экран + физические боковые кнопки листания |
| **Onyx Boox Vasco da Gama (1, 2, 3)** | Android 4.4.4 (API 19) | 1024×758 E-Ink Carta | Сенсорный экран + боковые кнопки |
| **Onyx Boox Faust, Monte Cristo, Caesar** | Android 4.2 / 4.4 | E-Ink Carta | Сенсорный экран / кнопки |
| **Любые ридеры и планшеты Android 4.2–9.0+** | Android 4.2+ (API 17+) | Любое разрешение | Сенсор или аппаратные клавиши |

---

## 🎯 Архитектура двух читалок: выбор под любые задачи

```mermaid
flowchart TD
    App["Яндекс Книги Lite (v1.3.3)"] --> Cloud["Облако Яндекс / Bookmate API"]
    Cloud --> Cache["Кэш & Экспорт (/sdcard/Books/YandexBooks/)"]
    Cache --> ModeSelect{"Режим читалки"}
    
    ModeSelect -->|По умолчанию: Onyx| OnyxReader["Системный Onyx NeoReader / AlReader<br/>• Привычные шрифты и словари ридера Darwin<br/>• Системные жесты и закладки Onyx<br/>• Интеграция с библиотекой рабочего стола"]
    
    ModeSelect -->|Режим: Онлайн| OnlineReader["Встроенный Canvas-ридер Онлайн<br/>• Сверхбыстрый запуск (15-20 МБ RAM)<br/>• Глубокий черный монохром Regal E-Ink<br/>• Регулировка полей (18 / 28 / 42 px)<br/>• TeX-переносы и физические кнопки листания"]
```

---

## 📲 Порядок установки и обновления

1. Загрузите файл **[`yandex-books-lite-v1.3.3.apk`](https://github.com/litvaerickson-spec/onyx-yandex-books/releases/download/v1.3.3/yandex-books-lite-v1.3.3.apk)**.
2. Скопируйте APK в память ридера (через USB или MicroSD-карту).
3. На ридере откройте **«Диспетчер файлов»** и нажмите на файл для установки.
4. Приложение обновится поверх существующей версии — авторизация и скачанные книги сохранятся.
   *(Или воспользуйтесь кнопкой обновления прямо внутри приложения!)*

---

## 📂 Структура проекта

```text
01_onyx_yandex_books/
├── README.md               # Главная страница и руководство проекта
├── CHANGELOG.md            # Детальная история версий (Keep a Changelog)
├── DEV_LOG.md              # Инженерный журнал разработки
├── build_apk.sh            # Скрипт сборки и подписи релизного APK
├── release_notes_v1.3.2.md # Описание релиза v1.3.2
├── app/
│   ├── build.gradle        # Конфигурация Android сборки
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/onyx/yandexbooks/
│       │   ├── core/api/       # Клиент Bookmate / Yandex API (каталог, поиск, полки)
│       │   ├── core/auth/      # Авторизация (Device Code, QR, TLS)
│       │   ├── core/eink/      # Контроллер обновления EPD и аппаратные кнопки
│       │   ├── core/network/   # Conscrypt TLS 1.3 и HTTP-клиент
│       │   ├── core/storage/   # SQLite БД, кэш EPUB, экспорт в Books, AppSettings
│       │   ├── core/sync/      # Двусторонняя облачная синхронизация прогресса
│       │   ├── core/typography/# TeX переносы, пагинация, шрифты и поля
│       │   ├── core/update/    # Автономное обновление через GitHub Releases
│       │   └── ui/             # Активности, адаптеры и ReaderCanvasView
│       └── res/                # Разметка интерфейса и монохромные E-Ink ресурсы
└── docs/                       # Архитектурные спецификации и исследования
```

---

## 📄 Лицензия и статус

Проект развивается открыто для владельцев ридеров Onyx Boox. Все торговые марки «Яндекс» и «Яндекс Книги» принадлежат их законным правообладателям.
