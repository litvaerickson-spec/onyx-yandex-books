# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [v1.3.1] - 2026-10-04

### Features
- **Фоновая асинхронная пагинация (Async Pagination)**:
  - Пагинация вынесена в фоновый `ExecutorService` с версионированием задач (`paginationTaskId`). UI-поток полностью разгружен, что исключает зависания и ошибку Android ANR («Приложение не отвечает»).
  - Оптимизирован алгоритм расчета ширины строк в `TextPaginator` до $O(N)$ (линейное суммирование ширины слов без повторного переизмерения строки).
  - Внедрен потокобезопасный LRU-кэш слоговых переносов в `TeXHyphenator` для частотных русских слов.
  - Сохранение точной позиции чтения (`startCharOffset`) при изменении размера шрифта или полей страницы.
- **Устранение остаточных артефактов E-Ink (Ghosting Fix)**:
  - Замена анимированных спиннеров загрузки `ProgressDialog` на статические E-Ink диалоги без 60fps круговой анимации.
  - Автоматический аппаратный сброс дисплея `EpdController.requestFullRefresh` при закрытии диалогов, в `onResume` и при открытии первой страницы книги.
- **Селектор цвета текста кнопок меню ридера (`btn_eink_text_color`)**:
  - При нажатии кнопок меню текст становится контрастно белым на черном фоне, исключая эффект залипания «черным-по-черному».
- **Защита от наложения текста на нижний колонтитул (Footer Margin Protection)**:
  - Резервирование высоты `footerReservedHeightPx = 44px` в `TypographyConfig` и жесткий барьер отрисовки `maxAllowedTextBottom` в `ReaderCanvasView`.
  - Гарантированный зазор между текстом книги и колонтитулом (номер страницы, глава, процент) составляет > 75 пикселей.

## [v1.3.0] - 2026-10-04

### Features
- **Встроенное обновление приложения с GitHub (OTA Self-Update)**:
  - Автоматическая и ручная проверка наличия свежих релизов в GitHub Releases (`litvaerickson-spec/onyx-yandex-books`)
  - Прямое скачивание APK с прогресс-баром и запуск системного PackageInstaller
  - Полное сохранение базы данных SQLite, авторизации, токенов и кэша скачанных книг при обновлении (без сброса сессии)
  - Кнопка `[ ⬆ Обн. ]` / `[ ⬆ vX.Y.Z ]` в шапке и кликабельный индикатор версии в нижней панели
- **Полная эргономическая переработка диалогового окна «О книге»**:
  - Диалог расширен до 92% ширины и 85% высоты дисплея
  - Аннотация книги занимает более 60% высоты окна (14-16 видимых строк вместо прежних 3) с комфортным межстрочным интервалом 1.2
  - Название книги и метаданные сжаты в аккуратную компактную шапку
  - Блок действий переведен в 2 компактные горизонтальные строки:
    - Строка 1: кнопки чтения бок о бок (50% / 50%): `[ 📖 Onyx Reader ]` и `[ ⚡ Читалка Lite ]`
    - Строка 2: кнопки скачивания/статуса и закрытия: `[ 📥 Скачать EPUB ]` / `✔ В памяти` и `[ ✕ Закрыть ]`

## [v1.2.9] - 2026-10-04

### Features
- **Полноценная облачная синхронизация прогресса с телефона**:
  - Комплексный парсинг Bookmate API (`/profile/library_cards`, `last_reading_position`, `reading_position`, `position`, `percent`, `progress` на карточках и объектах книг)
  - Автоматическое сохранение прочитанного процента, номера главы и параграфа в таблицы SQLite `books` и `progress`
  - Точное возобновление чтения книги с актуального места, где пользователь остановился на смартфоне
- **Редизайн меню читалки Lite по стандартам E-Ink**:
  - Замена сплюснутого ряда из 4 узких кнопок на гармоничную сетку 2×2 с контрастным стилем `@drawable/btn_eink`
  - Высота кнопок 38dp, крупный читаемый шрифт 12sp bold, отсутствие вертикального переноса слов по слогам
  - Добавлены кнопки быстрого закрытия меню и прямого возврата в библиотеку `[📚 В библиотеку]`
- **Эргономичное модальное окно «О книге»**:
  - Полноразмерный вертикальный стек действий: `[📖 Читать в Onyx (NeoReader)]`, `[⚡ Читать в читалке Lite]`, `[📥 Скачать EPUB в память]`, `[✕ Закрыть]`
  - Полное устранение горизонтального сжатия и наложения кнопок диалога на узких экранах ридеров
  - Скроллируемая аннотация с комфортным межстрочным интервалом

### Bug Fixes
- Устранена проблема открытия книги с нулевой главы при наличии облачного или локального прогресса чтения
- Исправлено наложение статуса `• ☁ Сеть` и кнопок действий в карточках списка книг
- Исправлена многоступенчатая выборка позиции чтения из API при сетевых задержках

## [v1.2.8] - 2026-10-04

### Features
- Добавлена постоянная нижняя статус-панель полок (Footer Bar: 30dp) со счетчиком книг `📚 Книг: X`, индикатором страниц и кнопками постраничного перехода `[ ◀ Стр ]` / `[ Стр ▶ ]` — полностью устранена «пустота внизу экрана»
- Поддержка физических кнопок перелистывания Darwin (`PAGE_UP`/`PAGE_DOWN`, `VOLUME_UP`/`VOLUME_DOWN`) для постраничной прокрутки списка книг на главном экране
- Обновлена карточка книги: обложка 62×90dp, аннотация расширена до 3 строк с приятным межстрочным интервалом 1.15, статус книги компактно форматируется в одну строку без обрезки
- Шапка приложения и вкладки сжаты до 58dp (28dp заголовок + 28dp вкладки), устраняя избыточные вертикальные поля

### Bug Fixes
- Устранена вертикальная обрезка текста в кнопках «← Назад», «Клавиатура», «Ввод текста» в шапке браузера авторизации
- Исправлено позиционирование QR-кода в Яндекс ID: периодическая CSS-инъекция и зум 70% скрывают веб-футер, сжимают громоздкие заголовки и центрируют QR-код на экране 758×1024 без скролла
- Устранено усечение бейджа памяти книги до `• ☁ В с...`

## [v1.2.7] - 2026-10-04

### Features
- Полная интеграция со штатной читалкой Onyx Boox (NeoReader, AlReader, OReader) через системный Intent и автоматическую регистрацию в MediaScanner
- Переключатель читалки по умолчанию в шапке приложения: `[📖 Onyx ▾]` / `[⚡ Lite ▾]`
- Раздельные кнопки открытия в модальном окне «О книге»: «📖 В Onyx (NeoReader)» и «⚡ В читалке Lite», плюс прямая кнопка скачивания EPUB
- Регулировка полей в меню чтения: переключатель «Поля: Узкие (18px) / Средние (28px) / Широкие (42px)»
- Ультракомпактная верхняя панель и вкладки (уменьшены в 2 раза по высоте, подняты к верхней кромке экрана)
- Полный парсинг ФИО авторов (Имя, Отчество, Фамилия) из всех структур данных Bookmate API

### Bug Fixes
- Устранено обнуление прогресса чтения: локальная БД защищена от перезаписи нулевыми значениями из облака
- Исправлена облачная синхронизация: корректная нормализация процентов (0.0..1.0) и автоматическая фоновая отправка позиции на сервер Яндекса
- Устранено набегание/пересечение текста главы и номера страниц в нижнем колонтитуле
- Исправлено отображение QR-кода на экране авторизации: масштабирование WebView и CSS-инъекция гарантируют свободное размещение QR-кода на экране
- Исправлен вертикальный перенос статуса книги по буквам в карточках полок

## [v1.2.6] - 2026-10-04

### Features
- Deep black monochrome rendering via Software Skia (`hardwareAccelerated="false"`) matching Regal E-Ink mode
- Contrast toggle in reader settings menu: `[ Контраст: Высокий / Обычный ]`
- Automatic bidirectional reading progress synchronization with Yandex Cloud API
- Detailed reader footer displaying current chapter title (`Гл. X/Y • Название`) and global reading percentage (`Стр. P/N (Z%)`)
- Two-line book annotations and offline memory tags (`✔ В памяти` / `☁ В сети`) in shelf cards
- Modal dialog «О книге» with full scrollable annotation, metadata, and actions

### Bug Fixes
- Fixed text clipping on the right edge by matching pagination measurement font metrics with Canvas drawing Paint (Serif Bold)
- Increased physical margins to comfortable 42px on left/right and 36px/44px top/bottom
- Optimized auth screen layout and resized QR code to 170dp, preventing vertical scroll on 758x1024 screens
- Fixed chapter position recovery on initial opening of in-progress books

## [v1.2.5] - 2026-10-02

### Features
- Native EPUB streaming and offline downloading from Bookmate/Yandex Books API (`/content/v4`)
- Lightweight `EpubParser` using `java.util.zip.ZipFile` with OPF spine ordering
- Automatic export to `/sdcard/Books/YandexBooks/` for integration with Onyx system library
- High-contrast E-Ink Carta UI styling with dark active tab markers and 2px borders
- Async cover loader `CoverLoader` with disk and memory caching

### Bug Fixes
- Fixed multi-page user library loading (`per_page=100`)
- Fixed author name parsing from JSON object arrays

## [v1.2.4] - 2026-10-02

### Features
- Multi-channel auth: in-reader browser (Granny/Domik mode), QR code, cloud bridge (ntfy.sh), local Wi-Fi, manual token input
- Custom `Tls12SocketFactory` and X509TrustManager for Android 4.4 KitKat TLS 1.2/1.3 compatibility

## [v1.2.0] - 2026-10-01

### Features
- Initial release of Yandex Books Lite for Onyx Boox Darwin (3, 5, 6)
- Native Canvas rendering engine with low memory footprint (~15-20 MB RAM)
- TeX Hyphenation (Franklin-Liang algorithm) for Russian and English
- Justified text layout without word spacing artifacts
- Hardware page turn key interception for Onyx Boox Darwin
- EPD anti-ghosting waveform refresh control
