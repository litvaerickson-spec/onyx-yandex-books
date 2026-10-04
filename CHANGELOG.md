# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
