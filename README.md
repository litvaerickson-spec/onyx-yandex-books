# 📚 Яндекс Книги Lite для Onyx Boox (v1.2.9)

[![Release](https://img.shields.io/badge/Release-v1.2.9-black?style=for-the-badge&logo=github)](https://github.com/litvaerickson-spec/onyx-yandex-books/releases/tag/v1.2.9)
[![Android](https://img.shields.io/badge/Android-4.2%20--%204.4.4-gray?style=for-the-badge&logo=android)](https://github.com/litvaerickson-spec/onyx-yandex-books)
[![E-Ink](https://img.shields.io/badge/Screen-E--Ink%20Carta-white?style=for-the-badge)](https://github.com/litvaerickson-spec/onyx-yandex-books)
[![APK Size](https://img.shields.io/badge/APK%20Size-2.2%20MB-lightgrey?style=for-the-badge)](https://github.com/litvaerickson-spec/onyx-yandex-books/releases/download/v1.2.9/yandex-books-lite-v1.2.9.apk)

> **Автономный легковесный клиент сервиса «Яндекс Книги»** для электронных книг Onyx Boox (Darwin, Vasco da Gama, Faust, Monte Cristo, Livingstone и др.) на базе Android 4.2 / 4.4 с поддержкой современных протоколов TLS 1.3, облачной синхронизацией, **бесшовной интеграцией со штатной читалкой Onyx NeoReader** и эргономичным E-Ink интерфейсом по канонам ридеров.

---

### 📥 Быстрая загрузка и установка

* 🚀 **Официальный релиз v1.2.9 на GitHub**: [**Страница релиза v1.2.9**](https://github.com/litvaerickson-spec/onyx-yandex-books/releases/tag/v1.2.9)
* 📦 **Прямая ссылка на APK**: [**`yandex-books-lite-v1.2.9.apk`**](https://github.com/litvaerickson-spec/onyx-yandex-books/releases/download/v1.2.9/yandex-books-lite-v1.2.9.apk) *(2.2 МБ, цифровая подпись v1/v2/v3, готов к установке поверх предыдущей версии)*
* 🔨 **Скрипт сборки из исходников**: [`build_apk.sh`](build_apk.sh)

---

## 🌟 Главные новшества версии 1.2.9

1. **🔄 Полноценная облачная синхронизация прогресса со смартфона**:
   - Комплексный парсинг Bookmate API (`/profile/library_cards`, `last_reading_position`, `reading_position`, `position`, `percent`, `progress` на карточках и объектах книг).
   - Точная нормализация прогресса (доли `0.0..1.0` автоматически переводятся в `0..100%`).
   - Автоматическое сохранение прочитанного процента, номера главы и параграфа в таблицы SQLite `books` и `progress`.
   - При открытии книги читалка Lite автоматически вычисляет точный номер главы и открывает страницу на том месте, где пользователь остановился на телефоне.
2. **🎨 Гармоничный E-Ink редизайн меню читалки Lite (сетка 2×2)**:
   - Устранена проблема сплющенных кнопок. Настройки перестроены в просторную сетку 2×2: `[Переносы: Вкл]`, `[Контраст: Высокий]`, `[Поля: Узкие]`, `[Очистить экран]`.
   - Кнопки имеют достаточную ширину (160dp+), четкий стиль `@drawable/btn_eink`, крупный шрифт 12sp bold — слова больше никогда не разрываются по слогам на вертикальные строки.
   - Добавлены кнопки быстрого закрытия меню и кнопка «📚 В библиотеку».
3. **📋 Эргономичный вертикальный стек действий в окне «О книге»**:
   - Замена сжатых горизонтальных кнопок нативного диалога на полноразмерный вертикальный стек:
     `[📖 Читать в Onyx (NeoReader)]` (черная акцентная), `[⚡ Читать в читалке Lite]`, `[📥 Скачать EPUB в память]`, `[✕ Закрыть]`.
   - Скроллируемая аннотация с комфортным межстрочным интервалом, никаких наложений и сжатия.
4. **🏛️ Фиксированная нижняя статус-панель полок и поддержка физических кнопок Darwin**:
   - Внизу главного экрана закреплен аккуратный E-Ink статус-бар (30dp) со счетчиком книг `📚 Книг: X` и постраничной навигацией `[ ◀ Стр ]  1/1  [ Стр ▶ ]`.
   - Физические боковые кнопки ридера Darwin (`PAGE_UP`/`PAGE_DOWN` и `VOLUME_UP`/`VOLUME_DOWN`) листают список книг постранично.
5. **📱 Адаптивный QR-код в Яндекс ID без вертикального скролла**:
   - Периодическая CSS-инъекция и зум 70% скрывают лишний веб-футер Яндекса, уменьшают заголовки до 13px и центрируют QR-код строго в пределах экрана 758×1024.

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
    App["Яндекс Книги Lite (v1.2.9)"] --> Cloud["Облако Яндекс / Bookmate API"]
    Cloud --> Cache["Кэш & Экспорт (/sdcard/Books/YandexBooks/)"]
    Cache --> ModeSelect{"Режим читалки"}
    
    ModeSelect -->|По умолчанию: Onyx| OnyxReader["📖 Системный Onyx NeoReader / AlReader<br/>• Привычные шрифты и словари ридера Darwin<br/>• Системные жесты и закладки Onyx<br/>• Интеграция с библиотекой рабочего стола"]
    
    ModeSelect -->|Режим: Lite| LiteReader["⚡ Встроенный Canvas-ридер Lite<br/>• Сверхбыстрый запуск (15-20 МБ RAM)<br/>• Глубокий черный монохром Regal E-Ink<br/>• Регулировка полей (18 / 28 / 42 px)<br/>• TeX-переносы и физические кнопки листания"]
```

---

## 📲 Порядок установки и обновления

1. Загрузите файл **[`yandex-books-lite-v1.2.9.apk`](https://github.com/litvaerickson-spec/onyx-yandex-books/releases/download/v1.2.9/yandex-books-lite-v1.2.9.apk)**.
2. Скопируйте APK в память ридера (через USB или MicroSD-карту).
3. На ридере откройте **«Диспетчер файлов»** и нажмите на файл для установки.
4. Приложение обновится поверх существующей версии — авторизация и скачанные книги сохранятся.

---

## 📂 Структура проекта

```text
01_onyx_yandex_books/
├── README.md               # Главная страница и руководство проекта
├── CHANGELOG.md            # Детальная история версий (Keep a Changelog)
├── DEV_LOG.md              # Инженерный журнал разработки
├── build_apk.sh            # Скрипт сборки и подписи релизного APK
├── release_notes_v1.2.9.md # Описание релиза v1.2.9
├── app/
│   ├── build.gradle        # Конфигурация Android сборки
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/onyx/yandexbooks/
│       │   ├── core/api/       # Клиент Bookmate / Yandex API
│       │   ├── core/auth/      # Авторизация (Device Code, QR, TLS)
│       │   ├── core/eink/      # Контроллер обновления EPD и аппаратные кнопки
│       │   ├── core/network/   # Conscrypt TLS 1.3 и HTTP-клиент
│       │   ├── core/storage/   # SQLite БД, кэш EPUB, экспорт в Books, AppSettings
│       │   ├── core/sync/      # Двусторонняя облачная синхронизация прогресса
│       │   ├── core/typography/# TeX переносы, пагинация, шрифты и поля
│       │   └── ui/             # Активности, адаптеры и ReaderCanvasView
│       └── res/                # Разметка интерфейса и монохромные E-Ink ресурсы
└── docs/                       # Архитектурные спецификации и исследования
```

---

## 📄 Лицензия и статус

Проект развивается открыто для владельцев ридеров Onyx Boox. Все торговые марки «Яндекс» и «Яндекс Книги» принадлежат их законным правообладателям.
