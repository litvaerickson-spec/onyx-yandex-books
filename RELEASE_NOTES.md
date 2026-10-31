# Release Notes — Яндекс Книги Lite v1.5.13-beta

> [!NOTE]
> **Статус выпуска:** Предварительная бета-версия (Beta / Preview).  
> Данная сборка устраняет все 6 корневых причин, блокировавших двустороннюю синхронизацию чтения и автоматический перевод открытых книг из раздела «В планах» («Хочу почитать») в раздел «Читаю» как на ридере, так и в облаке Яндекс Книг (Bookmate API v5).

**Дата релиза:** 31 октября 2026 г.  
**Целевые платформы:** Onyx Boox Darwin 1 (Android 4.2.2 Jelly Bean, API 17, Rockchip RK3026), Darwin 3, 5, 6 (Android 4.4.4 KitKat, API 19, Freescale i.MX6), Onyx Boox Livingstone и все ридеры на базе Android с экранами E-Ink Carta.

---

## 🚀 Основные исправления и изменения в версии 1.5.13-beta

### 1. Исправление парсинга карточек и прогресса чтения из Bookmate API v5
- **Разблокировано чтение `reading_progress` и `updated_at` (`YandexBooksApiClient.extractProgress`):** В ответах `GET /api/v5/profile/library_cards` элементы массива `library_cards` содержат поля `reading_progress` (`0.0..1.0`), `updated_at` и `added_at` (в Unix-секундах), но не содержат поля `last_read_at`. Ранее проверка `(hasLastRead || card.has("library_card"))` отсекала `reading_progress` для всех книг библиотеки. Теперь прогресс и временные метки (`updated_at` с переводом секунд в миллисекунды) извлекаются из всех пользовательских карточек.
- **Извлечение реального идентификатора карточки `LibraryCard.uuid` (`extractLibraryCardId`):** В Bookmate API v5 идентификатор карточки хранится в поле `"uuid"` самой карточки (`card.optString("uuid")`), тогда как ранее проверялось только отсутствующее поле `"id"`. Теперь `book.getLibraryCardId()` всегда заполняется и сохраняется в колонку `books.card_id`.
- **Точное сопоставление карточки по `book.uuid` (`findMatchingLibraryCardInArray`):** В `getReadingProgress`, `removeBookFromLibrary` и `findLibraryCardUuidForBookSync` устранено слепое обращение к `arr.getJSONObject(0)` (первой карточке списка). Теперь выбирается строго та карточка, у которой вложенный `book.uuid` совпадает с `bookUuid`.

### 2. Гарантированный перевод книги из «В планах» в «Читаю» при открытии
- **Устранена гонка и перезапись полки в `DatabaseHelper.saveOrUpdateBookInternal`:**
  * Ранее переменная `effectiveTimestamp` перезаписывалась через `Math.max(bestLocalTs, effectiveTimestamp)` до проверки полки, из-за чего условие `bestLocalTs > effectiveTimestamp` было математически невыполнимым (`false`), и завершающийся фоновый запрос `getAllUserBooks()` откатывал только что открытую книгу из раздела «Читаю» (`reading`) обратно в «В планах» (`to_read`).
  * Теперь `cloudTimestamp` фиксируется до слияния, метод `updateBookShelf` записывает свежий `last_read_timestamp`, а метод `saveProgress` автоматически переводит `shelf_type` книги в `"reading"` (или `"done"` при завершении книги).
- **Мгновенное переключение UI на раздел «Читаю» (`MainActivity.ensureBookOnReadingShelf` и `ReaderActivity`):**
  * При открытии книги (как кликом по строке списка, так и через кнопки «Ридер Lite» / «NeoReader» в окне «О книге») книга сразу переносится в раздел «Читаю», активная вкладка переключается на «Читаю», а при первой отрисовке страницы во встроенном ридере сразу фиксируется стартовая запись прогресса и полки.

### 3. Атомарная синхронизация статуса полки и прогресса на сервере Яндекс Книг
- **Канонический статус `"want_to_read"` и обновление по `cardUuid`:** В `updateBookShelfState` и `sendReadingProgress` запросы `PUT` и `PATCH` теперь отправляются по настоящему `cardUuid` (`/api/v5/profile/library_cards/{cardUuid}`) с полным пакетом полей (`uuid`, `book_uuid`, `state`, `reading_progress`, `last_reading_position`, `lc`, `library_card`).
- **Автоматическое пересоздание карточки при залипании статуса на сервере:** Если карточка книги находилась на сервере в статусе `"want_to_read"` и `PUT`/`PATCH` не переключили её `state` в `"reading"`, клиент автоматически выполняет `DELETE /profile/library_cards/{cardUuid}` + `POST /profile/library_cards` со `state: "reading"` и актуальным прогрессом, гарантируя перевод книги в раздел «Читаю» в мобильном приложении на смартфоне.
- **Корректное удаление с полки (`removeBookFromLibrary`):** Запрос `DELETE` отправляется строго по `cardUuid`, а код `404` при обращении по `bookUuid` больше не принимается за ложный успех.

---

## 📦 Дистрибутив и контрольные суммы

| Файл | Размер | SHA-256 |
|---|---|---|
| `yandex-books-lite-v1.5.13-beta.apk` | 3,328,357 байт (~3.17 MB) | `86b06b6fe226ec707af06e7ae1bc8ca558f0c19ff1c8714b52a84974b5ebd816` |

**Цифровая подпись:** v1 (JAR Signature) + v2 (Full APK Signature Scheme v2) + v3 (APK Signature Scheme v3).  
**Совместимость:** Android 4.2.2 Jelly Bean (API 17) – Android 14+ (Onyx Boox Darwin 1/3/5/6, Livingstone, Monte Cristo, Faust, Robinson Crusoe, Poke, Page, Palma, Leaf, Nova, Note и др.).
