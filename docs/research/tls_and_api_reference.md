# Инженерное исследование: TLS на Android 4.x и API Яндекс Книг

**Дата**: 2026-10-01  
**Область исследования**: Системные ограничения Android 4.2/4.4, преодоление SSL/TLS барьеров, протокол авторизации и эндпоинты Яндекс Книг (Bookmate).

---

## 1. Проблема TLS на Android 4.2 (API 17) и 4.4 (API 19)

### Корневая причина
В Android 4.2–4.4 системный криптографический провайдер основан на старой версии Android OpenSSL (2012–2013 годов).
- По умолчанию включен только **TLS 1.0**.
- Протоколы **TLS 1.2** присутствуют в Android 4.4, но по умолчанию выключены в `SSLSocketFactory` и не поддерживают современные наборы шифров:
  - `TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256`
  - `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`
  - `TLS_CHACHA20_POLY1305_SHA256`
- Современные серверы Яндекса (`passport.yandex.ru`, `books.yandex.ru`, `api.bookmate.com`) требуют обязательной поддержки TLS 1.2+ с GCM-шифрами и Server Name Indication (SNI).

### Решение: Google Conscrypt JNI
Внедрение библиотеки `Conscrypt` (разработка Google на базе BoringSSL):
```java
// Вызов в Application.onCreate() до любых сетевых обращений:
try {
    Security.insertProviderAt(Conscrypt.newProvider(), 1);
} catch (Throwable t) {
    Log.e("Security", "Failed to install Conscrypt provider", t);
}
```

В сочетании с сетевым клиентом **OkHttp 3.12.13** (последний релиз OkHttp с поддержкой Android API 14+):
```java
ConnectionSpec modernTlsSpec = new ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
    .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
    .cipherSuites(
        CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
        CipherSuite.TLS_DHE_RSA_WITH_AES_128_GCM_SHA256
    )
    .build();

OkHttpClient client = new OkHttpClient.Builder()
    .connectionSpecs(Collections.singletonList(modernTlsSpec))
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build();
```

---

## 2. Яндекс OAuth: Device Code Flow (RFC 8628)

Данный протокол официально используется умными телевизорами (Яндекс ТВ), медиаплеерами и Кинопоиском на приставках.

### Шаг 1: Запрос кода устройства
**POST** `https://oauth.yandex.ru/device/code`  
Параметры (form-urlencoded):
- `client_id`: идентификатор клиентского приложения
- `scope`: `login:email login:info` (и права книжного сервиса)

**Ответ (200 OK)**:
```json
{
  "device_code": "4a7b9c2d-e8f0-...",
  "user_code": "KXYZ-9821",
  "verification_url": "https://ya.ru/device",
  "interval": 5,
  "expires_in": 600
}
```

### Шаг 2: Генерация QR-кода на читалке
На читалке генерируется QR-код, кодирующий URL:
`https://ya.ru/device?user_code=KXYZ-9821`
Пользователь может просто навести камеру смартфона, либо перейти вручную и ввести код.

### Шаг 3: Опрос статуса подтверждения (Polling)
Каждые `interval` секунд приложение отправляет:  
**POST** `https://oauth.yandex.ru/token`  
Параметры:
- `grant_type`: `device_code`
- `device_code`: `4a7b9c2d-e8f0-...`
- `client_id`: `<ID>`

Коды ответов:
- `400 Bad Request` с `{"error": "authorization_pending"}` — пользователь еще не подтвердил вход (продолжаем ожидание).
- `200 OK`:
```json
{
  "access_token": "y0_AgAAAA...",
  "refresh_token": "1:...",
  "token_type": "bearer",
  "expires_in": 31536000
}
```

---

## 3. Эндпоинты API Яндекс Книг (Bookmate v5 Architecture)

Современная инфраструктура Яндекс Книг перенесена с устаревшего домена `api.bookmate.com` на защищенный кластер `api.bookmate.yandex.net/api/v5`. Запросы к старому домену возвращают HTTP 404.

### Обязательные заголовки запросов:
```http
Auth-Token: <access_token>
Authorization: OAuth <access_token>
App-Platform: android
App-Language: ru
App-Locale: ru
Bookmate-Version: 20200305
Device-Os: Android
Accept: application/json
User-Agent: okhttp/3.12.13
```

### 1. Получение библиотеки и карточек книг (полки пользователя)
- **GET** `https://api.bookmate.yandex.net/api/v5/profile/library_cards?per_page=50`
  - Возвращает массив карточек `library_cards`.
  - Поле `state`:
    - `reading` $\to$ полка «Читаю сейчас»
    - `want_to_read` $\to$ полка «Буду читать»
    - `finished` / `read` $\to$ полка «Прочитано»
  - Вложенный объект книги: `book` (или `audiobook`), содержащий `uuid`, `title`, `authors_text`, `cover_url`.
  - Поле `reading_progress`: процент прочитанного (0.0 .. 1.0 или 0 .. 100).

### 2. Получение структуры и контента книги
- **Манифест книги**: **GET** `https://api.bookmate.yandex.net/api/v5/books/{uuid}/manifest`
  - Содержит порядок глав (`spine`), оглавление (`toc`) и идентификаторы частей.
- **Глава книги**: **GET** `https://api.bookmate.yandex.net/api/v5/books/{uuid}/documents/{doc_id}`
  - Возвращает разметку главы (структурированный HTML/XHTML).

### 3. Синхронизация прогресса
- **POST** `https://api.bookmate.yandex.net/api/v5/books/{uuid}/progress`
```json
{
  "percent": 45.2,
  "chapter_index": 3,
  "paragraph_index": 12,
  "device_id": "onyx_darwin",
  "timestamp": 1740000000
}
```

---

## 4. Особенности контроллера EPD на Onyx Boox

Устройства серии Darwin используют чипы Rockchip RK3026 (Darwin 3) и RK3128 (Darwin 5/6) со специализированным контроллером дисплея EPD (Electronic Paper Display).

### Способы вызова полного обновления E-Ink:
1. **Через системный Broadcast Onyx**:
   ```java
   Intent intent = new Intent("android.intent.action.FULL_SCREEN_REFRESH");
   context.sendBroadcast(intent);
   ```
2. **Через Onyx SDK / View Invalidation**:
   ```java
   EpdController.setUpdateMode(view, EpdController.UPDATE_MODE_GC);
   view.invalidate();
   ```
3. **Софтверная инверсия (Fallback)**:
   Кратковременная (на 50 мс) смена цвета фона на черный с последующей перерисовкой белым, что аппаратно заставляет частицы чернил полностью перезанять полярность, удаляя остаточный текст.
