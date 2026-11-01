package com.onyx.yandexbooks.core.api;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.onyx.yandexbooks.core.api.models.Book;
import com.onyx.yandexbooks.core.api.models.Chapter;
import com.onyx.yandexbooks.core.api.models.ReadingProgress;
import com.onyx.yandexbooks.core.auth.TokenStorage;
import com.onyx.yandexbooks.core.network.HttpClientFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Клиент API сервиса Яндекс Книги (Bookmate).
 */
public class YandexBooksApiClient {

    private static final String TAG = "YandexBooksApi";
    private static final String BASE_URL = "https://api.bookmate.yandex.net/api/v5";
    private static final String GRAPHQL_URL = "https://books.yandex.ru/node-api/p-graphql/";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    public static final String GQL_GET_PROGRESS =
            "\nquery GetTextbookProgress($uuid: ID!) {\n" +
            "  textBook(uuid: $uuid) {\n" +
            "    document {\n" +
            "      currentPosition {\n" +
            "        cfi {\n" +
            "          start\n" +
            "          end\n" +
            "        }\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "}\n";

    public static final String GQL_PROGRESS_UPDATED =
            "\nmutation ProgressUpdated(\n" +
            "    $textbookProgresses: [TextbookProgressUpdatedInput!],\n" +
            "    $textSerialProgresses: [TextSerialProgressUpdatedInput!],\n" +
            "    $audiobookProgresses: [AudiobookProgressUpdatedInput!]\n" +
            ") {\n" +
            "    progressUpdated(\n" +
            "        textbookProgresses: $textbookProgresses,\n" +
            "        textSerialProgresses: $textSerialProgresses,\n" +
            "        audiobookProgresses: $audiobookProgresses\n" +
            "    ) {\n" +
            "        value\n" +
            "    }\n" +
            "}\n";

    public interface ApiCallback<T> {
        void onSuccess(T result);
        void onError(String errorMessage);
    }

    private final OkHttpClient httpClient;
    private final TokenStorage tokenStorage;
    private final Handler mainHandler;

    public YandexBooksApiClient(TokenStorage tokenStorage) {
        this.httpClient = HttpClientFactory.getClient();
        this.tokenStorage = tokenStorage;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    private Request.Builder createAuthRequestBuilder(String url) {
        String token = tokenStorage.getAccessToken();
        Request.Builder builder = new Request.Builder().url(url);
        if (token != null && !token.isEmpty()) {
            builder.addHeader("Auth-Token", token);
            builder.addHeader("Authorization", "OAuth " + token);
        }
        builder.addHeader("App-Platform", "android");
        builder.addHeader("App-Language", "ru");
        builder.addHeader("App-Locale", "ru");
        builder.addHeader("Bookmate-Version", "20200305");
        builder.addHeader("Device-Os", "Android");
        builder.addHeader("App-User-Agent", "Samsung/Galaxy_A51 Android/12 Bookmate/3.7.3");
        builder.addHeader("Accept", "application/json");
        builder.addHeader("User-Agent", "okhttp/3.12.13");
        return builder;
    }

    private Request.Builder createGraphQLRequestBuilder(String url) {
        String token = tokenStorage.getAccessToken();
        Request.Builder builder = new Request.Builder().url(url);
        if (token != null && !token.isEmpty()) {
            builder.addHeader("Auth-Token", token);
            builder.addHeader("Authorization", "OAuth " + token);
        }
        builder.addHeader("Apollo-Require-Preflight", "true");
        builder.addHeader("Content-Type", "application/json");
        builder.addHeader("Accept", "application/json");
        builder.addHeader("App-Platform", "android");
        builder.addHeader("App-Language", "ru");
        builder.addHeader("User-Agent", "Mozilla/5.0");
        return builder;
    }

    /**
     * Загрузка всех книг пользователя с пагинацией (до 100 книг на страницу).
     */
    public void getAllUserBooks(final ApiCallback<List<Book>> callback) {
        final List<Book> allBooks = new ArrayList<>();
        loadLibraryPage(1, allBooks, callback);
    }

    private void loadLibraryPage(final int page, final List<Book> accumulated, final ApiCallback<List<Book>> callback) {
        String endpoint = BASE_URL + "/profile/library_cards?per_page=100&page=" + page;
        Request request = createAuthRequestBuilder(endpoint).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, final IOException e) {
                if (!accumulated.isEmpty()) {
                    // Если хоть какие-то страницы успели загрузиться, отдаем их
                    postSuccess(callback, accumulated);
                } else {
                    postError(callback, "Ошибка сети: " + e.getMessage());
                }
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    int code = response.code();
                    response.close();
                    if (!accumulated.isEmpty()) {
                        postSuccess(callback, accumulated);
                    } else {
                        postError(callback, "Ошибка загрузки библиотеки: HTTP " + code);
                    }
                    return;
                }

                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONArray cardsArray = json.optJSONArray("library_cards");

                    int countOnPage = 0;
                    if (cardsArray != null) {
                        countOnPage = cardsArray.length();
                        for (int i = 0; i < countOnPage; i++) {
                            JSONObject card = cardsArray.getJSONObject(i);
                            Book book = parseBookFromCard(card);
                            if (book != null) {
                                accumulated.add(book);
                            }
                        }
                    }

                    // Если пришло 100 элементов, возможно есть следующая страница
                    if (countOnPage >= 100 && page < 10) {
                        loadLibraryPage(page + 1, accumulated, callback);
                    } else {
                        postSuccess(callback, accumulated);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing library page " + page, e);
                    if (!accumulated.isEmpty()) {
                        postSuccess(callback, accumulated);
                    } else {
                        postError(callback, "Ошибка парсинга: " + e.getMessage());
                    }
                }
            }
        });
    }

    /**
     * Загрузка списка книг для заданной полки (reading, to_read, done).
     */
    public void getShelfBooks(final String shelfType, final ApiCallback<List<Book>> callback) {
        getAllUserBooks(new ApiCallback<List<Book>>() {
            @Override
            public void onSuccess(List<Book> allBooks) {
                if (callback == null) return;
                List<Book> filtered = new ArrayList<>();
                for (Book b : allBooks) {
                    if (shelfType.equals(b.getShelfType())) {
                        filtered.add(b);
                    }
                }
                postSuccess(callback, filtered);
            }

            @Override
            public void onError(String errorMessage) {
                postError(callback, errorMessage);
            }
        });
    }

    public static class ParsedProgress {
        public double percent = 0.0;
        public int chapterIndex = 0;
        public int paragraphIndex = 0;
        public long timestamp = 0L;
        public String cfi = "";
        public String documentUuid = "";
    }

    public static class CfiPosition {
        public final int chapterIndex;
        public final int paragraphIndex;
        public final String rawCfi;

        public CfiPosition(int chapterIndex, int paragraphIndex, String rawCfi) {
            this.chapterIndex = chapterIndex;
            this.paragraphIndex = paragraphIndex;
            this.rawCfi = rawCfi;
        }
    }

    public static CfiPosition parseCfiPosition(String cfi) {
        if (cfi == null || cfi.trim().isEmpty()) {
            return new CfiPosition(0, 0, "");
        }
        String clean = cfi.trim();
        String[] parts = clean.split("!");
        String spinePart = parts[0];
        String elemPart = parts.length > 1 ? parts[1] : "";

        int chapterIndex = 0;
        Matcher mSpine = Pattern.compile("/(\\d+)").matcher(spinePart);
        int lastSpineNum = -1;
        while (mSpine.find()) {
            try {
                lastSpineNum = Integer.parseInt(mSpine.group(1));
            } catch (Exception ignored) {}
        }
        if (lastSpineNum > 0) {
            chapterIndex = Math.max(0, (lastSpineNum / 2) - 1);
        }

        int paragraphIndex = 0;
        if (!elemPart.isEmpty()) {
            String elemPath = elemPart.contains(":") ? elemPart.substring(0, elemPart.indexOf(':')) : elemPart;
            Matcher mElem = Pattern.compile("/(\\d+)").matcher(elemPath);
            int lastElemNum = -1;
            while (mElem.find()) {
                try {
                    lastElemNum = Integer.parseInt(mElem.group(1));
                } catch (Exception ignored) {}
            }
            if (lastElemNum > 0) {
                paragraphIndex = Math.max(0, (lastElemNum / 2) - 1);
            }
        }

        return new CfiPosition(chapterIndex, paragraphIndex, clean);
    }

    public static String generateCfi(int chapterIndex, int paragraphIndex) {
        int itemNum = Math.max(2, (chapterIndex + 1) * 2);
        int pNum = Math.max(2, (paragraphIndex + 1) * 2);
        return "/6/" + itemNum + "!/4/" + pNum + ":0";
    }

    public static String generateCfiEnd(int chapterIndex, int paragraphIndex) {
        int itemNum = Math.max(2, (chapterIndex + 1) * 2);
        int pNum = Math.max(2, (paragraphIndex + 1) * 2);
        return "/6/" + itemNum + "!/4/" + pNum + ":100";
    }

    public static String formatIsoWithTimezone(long timestamp) {
        long effectiveTs = timestamp > 0 ? timestamp : System.currentTimeMillis();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        TimeZone tz = TimeZone.getDefault();
        sdf.setTimeZone(tz);
        String base = sdf.format(new Date(effectiveTs));
        int offsetMillis = tz.getOffset(effectiveTs);
        int offsetHours = Math.abs(offsetMillis) / (3600 * 1000);
        int offsetMins = (Math.abs(offsetMillis) % (3600 * 1000)) / (60 * 1000);
        String sign = offsetMillis >= 0 ? "+" : "-";
        return String.format(Locale.US, "%s%s%02d:%02d", base, sign, offsetHours, offsetMins);
    }

    public static String generateSessionId() {
        Random r = new Random();
        StringBuilder sb = new StringBuilder(16);
        for (int i = 0; i < 16; i++) {
            sb.append(Integer.toHexString(r.nextInt(16)));
        }
        return sb.toString();
    }

    public String fetchDocumentUuidSync(String bookUuid) {
        if (bookUuid == null || bookUuid.isEmpty()) return "";
        Response response = null;
        try {
            Request req = createAuthRequestBuilder(BASE_URL + "/books/" + bookUuid + "/metadata/v4").get().build();
            response = httpClient.newCall(req).execute();
            if (response.isSuccessful() && response.body() != null) {
                String body = response.body().string();
                JSONObject json = new JSONObject(body);
                String docUuid = json.optString("document_uuid", json.optString("uuid", ""));
                if (!docUuid.isEmpty()) {
                    return docUuid;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (response != null) {
                try { response.close(); } catch (Exception ignored) {}
            }
        }
        return bookUuid;
    }

    public static String extractLibraryCardId(JSONObject card, JSONObject bObj, String bookUuid) {
        if (card == null) return "";
        String resolvedBookUuid = bookUuid;
        if ((resolvedBookUuid == null || resolvedBookUuid.isEmpty()) && bObj != null && bObj != card) {
            resolvedBookUuid = bObj.optString("uuid", "");
        }

        // 1. Проверяем вложенные обертки library_card / lc
        JSONObject[] wrappers = new JSONObject[] {
            card.optJSONObject("library_card"),
            card.optJSONObject("lc"),
            card
        };
        boolean hasNestedBookObject = card.has("book") || card.has("audiobook") || card.has("comicbook") || card.has("text_book");

        for (JSONObject obj : wrappers) {
            if (obj == null) continue;
            for (String key : new String[]{"library_card_uuid", "library_card_id", "card_uuid", "card_id"}) {
                String v = obj.optString(key, "");
                if (!v.isEmpty() && !"null".equalsIgnoreCase(v)) {
                    return v;
                }
            }
            if (obj != card || hasNestedBookObject) {
                String u = obj.optString("uuid", "");
                if (!u.isEmpty() && !"null".equalsIgnoreCase(u)) {
                    return u;
                }
            } else {
                String u = obj.optString("uuid", "");
                if (!u.isEmpty() && !"null".equalsIgnoreCase(u) && resolvedBookUuid != null && !resolvedBookUuid.isEmpty() && !u.equals(resolvedBookUuid)) {
                    return u;
                }
            }
            String idStr = obj.optString("id", "");
            if (idStr.isEmpty() || "null".equalsIgnoreCase(idStr)) {
                long numId = obj.optLong("id", -1L);
                if (numId > 0) idStr = String.valueOf(numId);
            }
            if (!idStr.isEmpty() && !"null".equalsIgnoreCase(idStr)) {
                if (obj != card || hasNestedBookObject || resolvedBookUuid == null || resolvedBookUuid.isEmpty() || !idStr.equals(resolvedBookUuid)) {
                    return idStr;
                }
            }
        }
        return "";
    }

    public static ParsedProgress extractProgress(JSONObject card, JSONObject bObj) {
        ParsedProgress result = new ParsedProgress();
        if (card == null) return result;

        JSONObject lcObj = card.optJSONObject("library_card");
        if (lcObj == null) lcObj = card.optJSONObject("lc");

        String state = card.optString("state", "");
        if ((state.isEmpty() || "null".equalsIgnoreCase(state)) && lcObj != null) {
            state = lcObj.optString("state", "");
        }

        String lastReadAt = card.optString("last_read_at", "");
        boolean hasLastRead = !lastReadAt.isEmpty() && !"null".equalsIgnoreCase(lastReadAt);
        boolean hasUpdatedOrAdded = card.has("updated_at") || card.has("added_at")
                || (lcObj != null && (lcObj.has("updated_at") || lcObj.has("added_at")));
        boolean hasPosObject = card.has("last_reading_position") || card.has("reading_position") || card.has("position")
                || (lcObj != null && (lcObj.has("last_reading_position") || lcObj.has("reading_position") || lcObj.has("position")));
        boolean hasNestedBook = card.has("book") || card.has("audiobook") || card.has("comicbook") || card.has("text_book");
        boolean isUserCard = hasLastRead || hasPosObject || card.has("library_card") || card.has("lc")
                || (hasNestedBook && (hasUpdatedOrAdded || card.has("reading_progress")))
                || (!state.isEmpty() && !"null".equalsIgnoreCase(state) && !"catalog".equalsIgnoreCase(state) && !"search".equalsIgnoreCase(state));

        // Если это не карточка пользователя (а элемент каталога или публичной полки), прогресс строго нулевой
        if (!isUserCard) {
            return result;
        }

        // 1. Приоритетные вложенные объекты позиции чтения (как в мобильном приложении на телефоне)
        JSONObject[] positionObjects = new JSONObject[] {
            card.optJSONObject("last_reading_position"),
            card.optJSONObject("reading_position"),
            card.optJSONObject("position"),
            lcObj != null ? lcObj.optJSONObject("last_reading_position") : null,
            lcObj != null ? lcObj.optJSONObject("reading_position") : null,
            lcObj != null ? lcObj.optJSONObject("position") : null
        };

        // 1. Поиск процента прочитанного в специализированных объектах позиции
        for (JSONObject obj : positionObjects) {
            if (obj == null) continue;
            double p = -1.0;
            if (obj.has("percent")) p = obj.optDouble("percent", -1.0);
            else if (obj.has("reading_progress")) p = obj.optDouble("reading_progress", -1.0);
            else if (obj.has("progress_percent")) p = obj.optDouble("progress_percent", -1.0);
            else if (obj.has("progress")) p = obj.optDouble("progress", -1.0);

            if (p > 0.0) {
                if (p < 1.0) {
                    result.percent = p * 100.0;
                } else if (p == 1.0) {
                    // 1.0: если книга явно прочитана, то 100%, иначе 1.0%
                    if ("finished".equalsIgnoreCase(state) || "read".equalsIgnoreCase(state) || "completed".equalsIgnoreCase(state) || "done".equalsIgnoreCase(state)) {
                        result.percent = 100.0;
                    } else {
                        result.percent = 1.0;
                    }
                } else {
                    result.percent = Math.min(100.0, p);
                }
                break;
            }
        }

        // 2. Если во вложенных объектах процент не найден, проверяем свойства самой пользовательской карточки LibraryCard
        // В Bookmate API v5 GET /profile/library_cards возвращает карточки с полем reading_progress (0.0..1.0) и updated_at (unix sec) без last_read_at!
        boolean isPlannedOnly = ("to_read".equalsIgnoreCase(state) || "want_to_read".equalsIgnoreCase(state) || "planned".equalsIgnoreCase(state));
        if (result.percent <= 0.0 && (!isPlannedOnly || hasPosObject)) {
            JSONObject[] cardCandidates = new JSONObject[] { lcObj, card };
            for (JSONObject cObj : cardCandidates) {
                if (cObj == null) continue;
                double p = -1.0;
                if (cObj.has("reading_progress") && !cObj.isNull("reading_progress")) p = cObj.optDouble("reading_progress", -1.0);
                else if (cObj.has("percent") && !cObj.isNull("percent")) p = cObj.optDouble("percent", -1.0);
                else if (cObj.has("progress_percent") && !cObj.isNull("progress_percent")) p = cObj.optDouble("progress_percent", -1.0);
                else if (cObj.has("progress") && !cObj.isNull("progress")) p = cObj.optDouble("progress", -1.0);

                if (p > 0.0) {
                    if (p < 1.0) {
                        result.percent = p * 100.0;
                    } else if (p == 1.0) {
                        if ("finished".equalsIgnoreCase(state) || "read".equalsIgnoreCase(state) || "completed".equalsIgnoreCase(state) || "done".equalsIgnoreCase(state)) {
                            result.percent = 100.0;
                        } else {
                            result.percent = 1.0;
                        }
                    } else {
                        result.percent = Math.min(100.0, p);
                    }
                    break;
                }
            }
        }

        // 3. Статус завершенности книги
        if (result.percent <= 0.0) {
            if ("finished".equalsIgnoreCase(state) || "read".equalsIgnoreCase(state) || "completed".equalsIgnoreCase(state) || "done".equalsIgnoreCase(state)) {
                result.percent = 100.0;
            }
        }

        // 4. Поиск индекса главы в объектах позиции чтения и самой карточке (но никогда не в метаданных книги bObj!)
        JSONObject[] allCandidates = new JSONObject[] {
            positionObjects[0], positionObjects[1], positionObjects[2],
            positionObjects[3], positionObjects[4], positionObjects[5],
            lcObj, (card != bObj ? card : null)
        };

        for (JSONObject obj : allCandidates) {
            if (obj == null) continue;
            int ch = -1;
            if (obj.has("chapter_index")) ch = obj.optInt("chapter_index", -1);
            else if (obj.has("chapter")) ch = obj.optInt("chapter", -1);
            else if (obj.has("chap_index")) ch = obj.optInt("chap_index", -1);
            else if (obj.has("chapter_number")) ch = obj.optInt("chapter_number", -1);

            if (ch >= 0) {
                result.chapterIndex = ch;
                break;
            }
        }

        // 5. Поиск параграфа / смещения
        for (JSONObject obj : allCandidates) {
            if (obj == null) continue;
            int par = -1;
            if (obj.has("paragraph_index")) par = obj.optInt("paragraph_index", -1);
            else if (obj.has("paragraph")) par = obj.optInt("paragraph", -1);
            else if (obj.has("point")) par = obj.optInt("point", -1);
            else if (obj.has("offset")) par = obj.optInt("offset", -1);

            if (par >= 0) {
                result.paragraphIndex = par;
                break;
            }
        }

        // 6. Поиск временной метки (long timestamp, unix seconds или ISO-8601 строка) в объектах позиции и самой карточке
        JSONObject[] tsCandidates = new JSONObject[] {
            positionObjects[0], positionObjects[1], positionObjects[2],
            positionObjects[3], positionObjects[4], positionObjects[5],
            lcObj, card
        };
        for (JSONObject obj : tsCandidates) {
            if (obj == null) continue;
            long ts = 0L;
            if (obj.has("timestamp") && !obj.isNull("timestamp")) {
                ts = obj.optLong("timestamp", 0L);
                if (ts == 0L) ts = parseIsoTimestamp(obj.optString("timestamp", null));
            }
            if (ts <= 0L && obj.has("last_read_at") && !obj.isNull("last_read_at")) {
                ts = obj.optLong("last_read_at", 0L);
                if (ts == 0L) ts = parseIsoTimestamp(obj.optString("last_read_at", null));
            }
            if (ts <= 0L && obj.has("updated_at") && !obj.isNull("updated_at")) {
                ts = obj.optLong("updated_at", 0L);
                if (ts == 0L) ts = parseIsoTimestamp(obj.optString("updated_at", null));
            }
            if (ts <= 0L && obj.has("added_at") && !obj.isNull("added_at")) {
                ts = obj.optLong("added_at", 0L);
                if (ts == 0L) ts = parseIsoTimestamp(obj.optString("added_at", null));
            }

            if (ts > 0) {
                if (ts < 100000000000L) ts *= 1000L;
                result.timestamp = ts;
                break;
            }
        }
        // ВАЖНО: Если сервер не прислал timestamp, оставляем 0L, а НЕ System.currentTimeMillis()!
        // Иначе чужая карточка со свежим текущим временем затрет реальный локальный прогресс чтения.
        if (result.timestamp <= 0) {
            result.timestamp = 0L;
        }

        // 7. Защита от искажения 100%: если книга читается (reading) и глава 0, процент не может быть 100%
        if (result.percent >= 99.0 && "reading".equalsIgnoreCase(state) && result.chapterIndex == 0) {
            result.percent = 0.0;
        }

        return result;
    }

    private Book parseBookFromCard(JSONObject card) {
        if (card == null) return null;

        JSONObject bObj = card.optJSONObject("book");
        if (bObj == null) bObj = card.optJSONObject("audiobook");
        if (bObj == null) bObj = card.optJSONObject("comicbook");
        if (bObj == null) bObj = card.optJSONObject("text_book");
        if (bObj == null) bObj = card;

        String uuid = bObj.optString("uuid", card.optString("book_uuid", card.optString("uuid", "")));
        if (uuid == null || uuid.isEmpty()) return null;

        String title = bObj.optString("title", bObj.optString("name", "Без названия"));

        // Комплексный парсинг полного ФИО авторов
        String authorText = parseAuthorString(bObj, card);

        // Корректный парсинг обложки
        String coverUrl = "";
        JSONObject coverObj = bObj.optJSONObject("cover");
        if (coverObj != null) {
            coverUrl = coverObj.optString("medium", coverObj.optString("large", coverObj.optString("small", coverObj.optString("thumbnail", ""))));
        }
        if (coverUrl.isEmpty()) {
            coverUrl = bObj.optString("cover_url", bObj.optString("cover", ""));
        }

        // Комплексный парсинг прогресса и позиции чтения из всех возможных полей Bookmate
        ParsedProgress pr = extractProgress(card, bObj);

        // Определение полки по состоянию Bookmate
        String state = card.optString("state", "");
        if (state.isEmpty() && card.has("library_card")) {
            JSONObject lc = card.optJSONObject("library_card");
            if (lc != null) state = lc.optString("state", "");
        }
        String mappedShelf;
        if ("reading".equalsIgnoreCase(state) || (pr.percent > 0.0 && pr.percent < 99.0)) {
            mappedShelf = "reading";
        } else if ("want_to_read".equalsIgnoreCase(state) || "to_read".equalsIgnoreCase(state) || "planned".equalsIgnoreCase(state)) {
            mappedShelf = "to_read";
        } else if ("finished".equalsIgnoreCase(state) || "read".equalsIgnoreCase(state) || "completed".equalsIgnoreCase(state) || "done".equalsIgnoreCase(state) || (pr.percent >= 99.0 && pr.chapterIndex > 0)) {
            mappedShelf = "done";
        } else {
            mappedShelf = "to_read";
        }

        // Парсинг аннотации
        String rawAnn = bObj.optString("annotation", bObj.optString("editor_annotation", bObj.optString("description", "")));
        String cleanAnn = "";
        if (rawAnn != null && !rawAnn.trim().isEmpty() && !rawAnn.equals("null")) {
            cleanAnn = android.text.Html.fromHtml(rawAnn).toString().trim().replaceAll("\\s+", " ");
        }

        Book book = new Book();
        book.setUuid(uuid);

        String cardId = extractLibraryCardId(card, bObj, uuid);
        if (!cardId.isEmpty()) {
            book.setLibraryCardId(cardId);
        }

        String docUuid = bObj.optString("document_uuid", card.optString("document_uuid", ""));
        if (!docUuid.isEmpty()) {
            book.setDocumentUuid(docUuid);
        }
        if (pr.cfi != null && !pr.cfi.isEmpty()) {
            book.setCfi(pr.cfi);
        }

        book.setTitle(title);
        book.setAuthor(authorText);
        book.setAnnotation(cleanAnn);
        book.setCoverUrl(coverUrl);
        book.setPercent(pr.percent);
        book.setCurrentChapterIndex(pr.chapterIndex);
        book.setCurrentParagraphIndex(pr.paragraphIndex);
        book.setShelfType(mappedShelf);
        book.setLastReadTimestamp(pr.timestamp);

        return book;
    }

    private String parseAuthorString(JSONObject bObj, JSONObject card) {
        // 1. Проверяем authors_text
        String authorsText = bObj != null ? bObj.optString("authors_text", "") : "";
        if (authorsText.isEmpty() && card != null) {
            authorsText = card.optString("authors_text", "");
        }

        // 2. Извлекаем авторов из массива authors / authors_objects
        JSONArray arr = bObj != null ? bObj.optJSONArray("authors") : null;
        if (arr == null && bObj != null) arr = bObj.optJSONArray("authors_objects");
        if (arr == null && card != null) arr = card.optJSONArray("authors");

        String fullFromArr = "";
        if (arr != null && arr.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                Object item = arr.opt(i);
                String name = extractPersonName(item);
                if (!name.isEmpty()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(name);
                }
            }
            fullFromArr = sb.toString().trim();
        }

        // 3. Извлекаем автора из одиночного объекта author
        JSONObject aObj = bObj != null ? bObj.optJSONObject("author") : null;
        if (aObj == null && card != null) aObj = card.optJSONObject("author");
        String fullFromObj = "";
        if (aObj != null) {
            fullFromObj = extractPersonName(aObj);
        }

        // Выбираем наиболее информативное имя
        if (!fullFromArr.isEmpty()) {
            return fullFromArr;
        }
        if (!fullFromObj.isEmpty()) {
            return fullFromObj;
        }
        if (isValidAuthor(authorsText)) {
            return authorsText.trim();
        }

        String singleAuthor = bObj != null ? bObj.optString("author", "") : "";
        if (isValidAuthor(singleAuthor)) {
            return singleAuthor.trim();
        }

        if (bObj != null && bObj.has("authors") && bObj.opt("authors") instanceof String) {
            String sAuthors = bObj.optString("authors", "");
            if (isValidAuthor(sAuthors)) {
                return sAuthors.trim();
            }
        }

        return "Автор не указан";
    }

    private String extractPersonName(Object item) {
        if (item == null) return "";
        if (item instanceof String) {
            return ((String) item).trim();
        }
        if (item instanceof JSONObject) {
            JSONObject obj = (JSONObject) item;
            String fn = obj.optString("first_name", "").trim();
            String mn = obj.optString("middle_name", "").trim();
            String ln = obj.optString("last_name", "").trim();
            String full = obj.optString("full_name", obj.optString("name", "")).trim();

            if (!ln.isEmpty() && !fn.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append(fn).append(" ");
                if (!mn.isEmpty()) sb.append(mn).append(" ");
                sb.append(ln);
                return sb.toString().trim();
            }
            if (!full.isEmpty()) {
                if (!ln.isEmpty() && !full.contains(ln)) {
                    return (full + " " + ln).trim();
                }
                return full;
            }
            if (!ln.isEmpty()) return ln;
            if (!fn.isEmpty()) return fn;
        }
        return "";
    }

    private static long parseIsoTimestamp(String iso) {
        if (iso == null || iso.trim().isEmpty()) return 0L;
        try {
            String clean = iso.trim();
            SimpleDateFormat sdf;
            if (clean.contains(".")) {
                sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            } else {
                sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            }
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = sdf.parse(clean);
            return (d != null) ? d.getTime() : 0L;
        } catch (Exception e) {
            try {
                String fallbackStr = iso.replace("Z", "+0000");
                if (fallbackStr.length() > 6 && fallbackStr.charAt(fallbackStr.length() - 3) == ':') {
                    fallbackStr = fallbackStr.substring(0, fallbackStr.length() - 3) + fallbackStr.substring(fallbackStr.length() - 2);
                }
                SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
                Date d = sdf2.parse(fallbackStr);
                return (d != null) ? d.getTime() : 0L;
            } catch (Exception ignored) {
                return 0L;
            }
        }
    }

    private boolean isValidAuthor(String text) {
        if (text == null) return false;
        String t = text.trim();
        return !t.isEmpty() && !"null".equalsIgnoreCase(t) && !"undefined".equalsIgnoreCase(t);
    }

    /**
     * Ищет в массиве library_cards карточку, принадлежащую конкретной книге bookUuid.
     * В Bookmate API v5 GET /profile/library_cards?book_uuid=... может вернуть общий список всех карточек,
     * поэтому брать arr.getJSONObject(0) без сверки book.uuid нельзя!
     */
    public static JSONObject findMatchingLibraryCardInArray(JSONArray arr, String bookUuid) {
        if (arr == null || arr.length() == 0) return null;
        if (bookUuid == null || bookUuid.isEmpty()) return arr.optJSONObject(0);

        for (int i = 0; i < arr.length(); i++) {
            JSONObject card = arr.optJSONObject(i);
            if (card == null) continue;
            JSONObject bObj = card.optJSONObject("book");
            if (bObj == null) bObj = card.optJSONObject("audiobook");
            if (bObj == null) bObj = card.optJSONObject("comicbook");
            if (bObj == null) bObj = card.optJSONObject("text_book");

            String candidateBookUuid = bObj != null ? bObj.optString("uuid", "") : "";
            if (candidateBookUuid.isEmpty()) {
                candidateBookUuid = card.optString("book_uuid", "");
            }
            if (bookUuid.equals(candidateBookUuid)) {
                return card;
            }
            // На случай если сама карточка индексирована по bookUuid
            if (bObj == null && bookUuid.equals(card.optString("uuid", ""))) {
                return card;
            }
        }
        return null;
    }

    /**
     * Синхронный поиск UUID карточки библиотеки (library_card.uuid) для заданной книги.
     */
    private String findLibraryCardUuidForBookSync(String bookUuid) {
        if (bookUuid == null || bookUuid.isEmpty()) return "";
        String[] urls = new String[] {
            BASE_URL + "/profile/library_cards?book_uuid=" + bookUuid + "&per_page=100",
            BASE_URL + "/profile/library_cards?per_page=100&page=1",
            BASE_URL + "/profile/library_cards?per_page=100&page=2"
        };
        for (int uIdx = 0; uIdx < urls.length; uIdx++) {
            Response resp = null;
            try {
                Request req = createAuthRequestBuilder(urls[uIdx]).get().build();
                resp = httpClient.newCall(req).execute();
                if (!resp.isSuccessful()) {
                    continue;
                }
                String body = resp.body() != null ? resp.body().string() : "";
                if (body.isEmpty()) continue;
                JSONObject json = new JSONObject(body);
                JSONArray arr = json.optJSONArray("library_cards");
                if (arr == null) arr = json.optJSONArray("objects");
                if (arr == null) arr = json.optJSONArray("items");
                if (arr == null || arr.length() == 0) {
                    break;
                }
                JSONObject matched = findMatchingLibraryCardInArray(arr, bookUuid);
                if (matched != null) {
                    String resolved = extractLibraryCardId(matched, matched.optJSONObject("book"), bookUuid);
                    if (!resolved.isEmpty()) {
                        return resolved;
                    }
                }
                if (arr.length() < 100) {
                    break;
                }
            } catch (Exception ignored) {
            } finally {
                if (resp != null) {
                    try { resp.close(); } catch (Exception ignored) {}
                }
            }
        }
        return "";
    }

    /**
     * Запрос актуальной позиции чтения из облака Яндекса с поддержкой GraphQL (EPUB CFI) и Bookmate API.
     */
    public void getReadingProgress(final String bookUuid, final ApiCallback<ReadingProgress> callback) {
        if (bookUuid == null || bookUuid.isEmpty()) {
            postError(callback, "Пустой UUID книги");
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1. Попытка запроса точной позиции чтения через GraphQL (GetTextbookProgress)
                    String gqlCfiStart = null;
                    Response gqlResp = null;
                    try {
                        JSONObject gqlBody = new JSONObject();
                        gqlBody.put("operationName", "GetTextbookProgress");
                        gqlBody.put("query", GQL_GET_PROGRESS);
                        JSONObject vars = new JSONObject();
                        vars.put("uuid", bookUuid);
                        gqlBody.put("variables", vars);

                        Request gqlReq = createGraphQLRequestBuilder(GRAPHQL_URL)
                                .post(RequestBody.create(JSON_MEDIA_TYPE, gqlBody.toString()))
                                .build();
                        gqlResp = httpClient.newCall(gqlReq).execute();
                        if (gqlResp.isSuccessful() && gqlResp.body() != null) {
                            String respStr = gqlResp.body().string();
                            JSONObject rj = new JSONObject(respStr);
                            JSONObject data = rj.optJSONObject("data");
                            if (data != null) {
                                JSONObject tb = data.optJSONObject("textBook");
                                if (tb != null) {
                                    JSONObject doc = tb.optJSONObject("document");
                                    if (doc != null) {
                                        JSONObject curPos = doc.optJSONObject("currentPosition");
                                        if (curPos != null) {
                                            JSONObject cfiObj = curPos.optJSONObject("cfi");
                                            if (cfiObj != null) {
                                                gqlCfiStart = cfiObj.optString("start", null);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (gqlResp != null) {
                            try { gqlResp.close(); } catch (Exception ignored) {}
                        }
                    }

                    // 2. Запрос карточки библиотеки для извлечения процента и cardId
                    double cardPercent = 0.0;
                    String cardId = "";
                    long cardTs = 0L;
                    int cardChapter = 0;
                    int cardParagraph = 0;

                    Response cardResp = null;
                    try {
                        String endpoint = BASE_URL + "/profile/library_cards?book_uuid=" + bookUuid + "&per_page=100";
                        Request cardReq = createAuthRequestBuilder(endpoint).get().build();
                        cardResp = httpClient.newCall(cardReq).execute();
                        if (cardResp.isSuccessful() && cardResp.body() != null) {
                            String body = cardResp.body().string();
                            JSONObject json = new JSONObject(body);
                            JSONArray arr = json.optJSONArray("library_cards");
                            if (arr != null && arr.length() > 0) {
                                JSONObject card = findMatchingLibraryCardInArray(arr, bookUuid);
                                if (card != null) {
                                    JSONObject bObj = card.optJSONObject("book");
                                    ParsedProgress pr = extractProgress(card, bObj);
                                    cardPercent = pr.percent;
                                    cardChapter = pr.chapterIndex;
                                    cardParagraph = pr.paragraphIndex;
                                    cardTs = pr.timestamp;
                                    cardId = extractLibraryCardId(card, bObj, bookUuid);
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (cardResp != null) {
                            try { cardResp.close(); } catch (Exception ignored) {}
                        }
                    }

                    // 3. Синтез наиболее точного прогресса
                    int finalChapter = cardChapter;
                    int finalParagraph = cardParagraph;
                    if (gqlCfiStart != null && !gqlCfiStart.isEmpty()) {
                        CfiPosition pos = parseCfiPosition(gqlCfiStart);
                        finalChapter = pos.chapterIndex;
                        finalParagraph = pos.paragraphIndex;
                    }

                    if (gqlCfiStart != null || cardPercent > 0.0 || !cardId.isEmpty()) {
                        final ReadingProgress progress = new ReadingProgress(
                                bookUuid, cardPercent, finalChapter, finalParagraph, 0,
                                cardTs > 0 ? cardTs : System.currentTimeMillis()
                        );
                        if (!cardId.isEmpty()) {
                            progress.setLibraryCardId(cardId);
                        }
                        if (gqlCfiStart != null && !gqlCfiStart.isEmpty()) {
                            progress.setCfi(gqlCfiStart);
                        }
                        progress.setSyncedWithServer(true);
                        postSuccess(callback, progress);
                        return;
                    }

                    // 4. Если ничего не найдено, запускаем fallback
                    getReadingProgressFallback(bookUuid, callback);
                } catch (Exception e) {
                    getReadingProgressFallback(bookUuid, callback);
                }
            }
        }).start();
    }

    private void getReadingProgressFallback(final String bookUuid, final ApiCallback<ReadingProgress> callback) {
        String endpoint = BASE_URL + "/books/" + bookUuid + "/state";
        Request request = createAuthRequestBuilder(endpoint).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                getReadingProgressFallbackCards(bookUuid, callback);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    response.close();
                    getReadingProgressFallbackCards(bookUuid, callback);
                    return;
                }
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONObject card = json.optJSONObject("library_card");
                    if (card == null) card = json;
                    ParsedProgress pr = extractProgress(card, card.optJSONObject("book"));
                    final ReadingProgress progress = new ReadingProgress(bookUuid, pr.percent, pr.chapterIndex, pr.paragraphIndex, 0, pr.timestamp);
                    String cardId = extractLibraryCardId(card, card.optJSONObject("book"), bookUuid);
                    if (!cardId.isEmpty()) {
                        progress.setLibraryCardId(cardId);
                    }
                    progress.setSyncedWithServer(true);
                    postSuccess(callback, progress);
                } catch (Exception e) {
                    getReadingProgressFallbackCards(bookUuid, callback);
                } finally {
                    response.close();
                }
            }
        });
    }

    private void getReadingProgressFallbackCards(final String bookUuid, final ApiCallback<ReadingProgress> callback) {
        String endpoint = BASE_URL + "/profile/library_cards/" + bookUuid;
        Request request = createAuthRequestBuilder(endpoint).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postError(callback, "Сетевая ошибка: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    int code = response.code();
                    response.close();
                    postError(callback, "HTTP " + code);
                    return;
                }
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONObject card = json.optJSONObject("library_card");
                    if (card == null) card = json;
                    ParsedProgress pr = extractProgress(card, card.optJSONObject("book"));
                    final ReadingProgress progress = new ReadingProgress(bookUuid, pr.percent, pr.chapterIndex, pr.paragraphIndex, 0, pr.timestamp);
                    String cardId = extractLibraryCardId(card, card.optJSONObject("book"), bookUuid);
                    if (!cardId.isEmpty()) {
                        progress.setLibraryCardId(cardId);
                    }
                    progress.setSyncedWithServer(true);
                    postSuccess(callback, progress);
                } catch (Exception e) {
                    postError(callback, "Ошибка парсинга fallback прогресса: " + e.getMessage());
                } finally {
                    response.close();
                }
            }
        });
    }

    public static class DownloadResult {
        public final boolean success;
        public final String errorMessage;
        public final File file;

        public DownloadResult(boolean success, String errorMessage, File file) {
            this.success = success;
            this.errorMessage = errorMessage;
            this.file = file;
        }

        public static DownloadResult ok(File file) {
            return new DownloadResult(true, null, file);
        }

        public static DownloadResult error(String msg) {
            return new DownloadResult(false, msg, null);
        }
    }

    /**
     * Прямое синхронное скачивание книги в текущем потоке (для фонового пакетного загрузчика).
     */
    public DownloadResult downloadBookEpubSync(final String bookUuid, final File destFile) {
        // 1. Попытка v4: /books/{uuid}/content/v4
        DownloadResult res = tryDownloadSync(BASE_URL + "/books/" + bookUuid + "/content/v4", destFile);
        if (res.success) return res;

        // 2. Если ошибка (например 403 при отсутствии карточки в библиотеке), регистрируем в профиле
        tryAddBookToLibrarySync(bookUuid);

        // Повторная попытка v4
        res = tryDownloadSync(BASE_URL + "/books/" + bookUuid + "/content/v4", destFile);
        if (res.success) return res;

        // 3. Fallback: /books/{uuid}/file
        res = tryDownloadSync(BASE_URL + "/books/" + bookUuid + "/file", destFile);
        if (res.success) return res;

        // 4. Fallback: /books/{uuid}/content
        res = tryDownloadSync(BASE_URL + "/books/" + bookUuid + "/content", destFile);
        if (res.success) return res;

        // 5. Fallback: /books/{uuid}/download
        res = tryDownloadSync(BASE_URL + "/books/" + bookUuid + "/download", destFile);
        if (res.success) return res;

        return DownloadResult.error(res.errorMessage != null ? res.errorMessage : "Не удалось загрузить книгу");
    }

    private DownloadResult tryDownloadSync(String url, File destFile) {
        Response response = null;
        try {
            Request request = createAuthRequestBuilder(url)
                    .header("Accept", "*/*")
                    .get()
                    .build();
            response = httpClient.newCall(request).execute();
            if (!response.isSuccessful()) {
                int code = response.code();
                response.close();
                return DownloadResult.error("HTTP " + code);
            }

            String contentType = response.header("Content-Type", "");
            if (contentType != null && (contentType.contains("json") || contentType.contains("html"))) {
                String bodyStr = response.body().string(); // Закрывает response.body()
                try {
                    JSONObject json = new JSONObject(bodyStr);
                    String redirectUrl = json.optString("url", null);
                    if (redirectUrl == null) redirectUrl = json.optString("download_url", null);
                    if (redirectUrl == null) redirectUrl = json.optString("file_url", null);
                    if (redirectUrl == null) redirectUrl = json.optString("content_url", null);
                    if (redirectUrl == null && json.has("file")) {
                        JSONObject fileObj = json.optJSONObject("file");
                        if (fileObj != null) redirectUrl = fileObj.optString("url", null);
                    }
                    if (redirectUrl == null && json.has("data")) {
                        JSONObject dataObj = json.optJSONObject("data");
                        if (dataObj != null) redirectUrl = dataObj.optString("url", null);
                    }

                    if (redirectUrl != null && (redirectUrl.startsWith("http://") || redirectUrl.startsWith("https://"))) {
                        Request redirReq = new Request.Builder()
                                .url(redirectUrl)
                                .header("User-Agent", "okhttp/3.12.13")
                                .get()
                                .build();
                        Response redirResp = httpClient.newCall(redirReq).execute();
                        if (redirResp.isSuccessful()) {
                            return saveSyncResponseBody(redirResp, destFile);
                        } else {
                            int rCode = redirResp.code();
                            redirResp.close();
                            return DownloadResult.error("Редирект HTTP " + rCode);
                        }
                    }
                } catch (Exception ignored) {}
                return DownloadResult.error("Сервер вернул сообщение: " + (bodyStr.length() > 60 ? bodyStr.substring(0, 60) : bodyStr));
            }

            return saveSyncResponseBody(response, destFile);
        } catch (Exception e) {
            if (response != null) {
                try { response.close(); } catch (Exception ignored) {}
            }
            return DownloadResult.error("Ошибка сети: " + e.getMessage());
        }
    }

    private DownloadResult saveSyncResponseBody(Response response, File destFile) {
        try {
            File parent = destFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            File tempFile = new File(destFile.getAbsolutePath() + ".tmp");
            InputStream in = null;
            FileOutputStream out = null;
            try {
                in = response.body().byteStream();
                out = new FileOutputStream(tempFile);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } catch (Exception e) {
                if (tempFile.exists()) tempFile.delete();
                return DownloadResult.error("Ошибка записи: " + e.getMessage());
            } finally {
                if (out != null) {
                    try { out.close(); } catch (Exception ignored) {}
                }
                if (in != null) {
                    try { in.close(); } catch (Exception ignored) {}
                }
            }

            if (tempFile.exists() && tempFile.length() > 500) {
                if (destFile.exists()) {
                    destFile.delete();
                }
                boolean renamed = tempFile.renameTo(destFile);
                if (renamed && destFile.exists()) {
                    return DownloadResult.ok(destFile);
                } else {
                    return DownloadResult.error("Не удалось сохранить файл книги");
                }
            } else {
                if (tempFile.exists()) tempFile.delete();
                return DownloadResult.error("Файл книги пуст (< 500 байт)");
            }
        } finally {
            if (response != null) {
                try { response.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void tryAddBookToLibrarySync(String bookUuid) {
        Response resp = null;
        try {
            String endpoint = BASE_URL + "/profile/library_cards";
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("book_uuid", bookUuid);
            bodyJson.put("state", "want_to_read");
            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString());
            Request request = createAuthRequestBuilder(endpoint).post(body).build();
            resp = httpClient.newCall(request).execute();
        } catch (Exception ignored) {
        } finally {
            if (resp != null) {
                try { resp.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Скачивание файла книги в формате EPUB с автоматической привязкой к библиотеке и каскадным fallback.
     */
    public void downloadBookEpub(final String bookUuid, final File destFile, final ApiCallback<File> callback) {
        attemptDownloadBookEpub(bookUuid, destFile, callback, false);
    }

    private void attemptDownloadBookEpub(final String bookUuid, final File destFile, final ApiCallback<File> callback, final boolean isRetryAfterAdd) {
        String urlV4 = BASE_URL + "/books/" + bookUuid + "/content/v4";
        Request request = createAuthRequestBuilder(urlV4)
                .header("Accept", "*/*")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                downloadBookEpubFallback(bookUuid, destFile, callback, e.getMessage(), isRetryAfterAdd);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    int code = response.code();
                    response.close();
                    downloadBookEpubFallback(bookUuid, destFile, callback, "HTTP " + code, isRetryAfterAdd);
                    return;
                }

                saveResponseBodyToFile(response, destFile, callback, new Runnable() {
                    @Override
                    public void run() {
                        downloadBookEpubFallback(bookUuid, destFile, callback, "v4 вернул некорректный файл", isRetryAfterAdd);
                    }
                });
            }
        });
    }

    private void downloadBookEpubFallback(final String bookUuid, final File destFile, final ApiCallback<File> callback, final String initialError, final boolean isRetryAfterAdd) {
        if (!isRetryAfterAdd) {
            // Если ошибка (например 403 Forbidden при отсутствии карточки в библиотеке),
            // регистрируем книгу в профиле пользователя и повторяем запрос
            addBookToLibrary(bookUuid, new ApiCallback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {
                    attemptDownloadBookEpub(bookUuid, destFile, callback, true);
                }

                @Override
                public void onError(String addError) {
                    tryAlternativeEndpoints(bookUuid, destFile, callback, initialError);
                }
            });
            return;
        }

        tryAlternativeEndpoints(bookUuid, destFile, callback, initialError);
    }

    private void tryAlternativeEndpoints(final String bookUuid, final File destFile, final ApiCallback<File> callback, final String initialError) {
        String fallbackUrl = BASE_URL + "/books/" + bookUuid + "/content";
        Request request = createAuthRequestBuilder(fallbackUrl)
                .header("Accept", "*/*")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                tryFileEndpoint(bookUuid, destFile, callback, initialError + " -> " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    int code = response.code();
                    response.close();
                    tryFileEndpoint(bookUuid, destFile, callback, initialError + " -> HTTP " + code);
                    return;
                }
                saveResponseBodyToFile(response, destFile, callback, new Runnable() {
                    @Override
                    public void run() {
                        tryFileEndpoint(bookUuid, destFile, callback, initialError + " -> content returned non-epub");
                    }
                });
            }
        });
    }

    private void tryFileEndpoint(final String bookUuid, final File destFile, final ApiCallback<File> callback, final String initialError) {
        String fileUrl = BASE_URL + "/books/" + bookUuid + "/file";
        Request request = createAuthRequestBuilder(fileUrl)
                .header("Accept", "*/*")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postError(callback, "Ошибка загрузки книги: " + initialError + " -> " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    int code = response.code();
                    response.close();
                    postError(callback, "Ошибка скачивания книги: " + initialError + " -> HTTP " + code);
                    return;
                }
                saveResponseBodyToFile(response, destFile, callback, null);
            }
        });
    }

    private void saveResponseBodyToFile(Response response, final File destFile, final ApiCallback<File> callback, final Runnable onFallback) {
        try {
            String contentType = response.header("Content-Type", "");
            if (contentType != null && (contentType.contains("json") || contentType.contains("html"))) {
                String bodyStr = response.body().string();
                try {
                    JSONObject json = new JSONObject(bodyStr);
                    String redirectUrl = json.optString("url", null);
                    if (redirectUrl == null) redirectUrl = json.optString("download_url", null);
                    if (redirectUrl == null) redirectUrl = json.optString("file_url", null);
                    if (redirectUrl == null) redirectUrl = json.optString("content_url", null);
                    if (redirectUrl == null && json.has("file")) {
                        JSONObject fileObj = json.optJSONObject("file");
                        if (fileObj != null) redirectUrl = fileObj.optString("url", null);
                    }
                    if (redirectUrl == null && json.has("data")) {
                        JSONObject dataObj = json.optJSONObject("data");
                        if (dataObj != null) redirectUrl = dataObj.optString("url", null);
                    }

                    if (redirectUrl != null && (redirectUrl.startsWith("http://") || redirectUrl.startsWith("https://"))) {
                        Request redirReq = new Request.Builder()
                                .url(redirectUrl)
                                .header("User-Agent", "okhttp/3.12.13")
                                .get()
                                .build();
                        httpClient.newCall(redirReq).enqueue(new Callback() {
                            @Override
                            public void onFailure(Call call, IOException e) {
                                if (onFallback != null) {
                                    onFallback.run();
                                } else {
                                    postError(callback, "Ошибка загрузки по редиректу: " + e.getMessage());
                                }
                            }

                            @Override
                            public void onResponse(Call call, Response redirResp) throws IOException {
                                if (!redirResp.isSuccessful()) {
                                    int code = redirResp.code();
                                    redirResp.close();
                                    if (onFallback != null) {
                                        onFallback.run();
                                    } else {
                                        postError(callback, "Редирект HTTP " + code);
                                    }
                                    return;
                                }
                                saveResponseBodyToFile(redirResp, destFile, callback, onFallback);
                            }
                        });
                        return;
                    }
                } catch (Exception ignored) {}

                if (onFallback != null) {
                    onFallback.run();
                } else {
                    postError(callback, "Сервер вернул сообщение вместо книги: " + (bodyStr.length() > 60 ? bodyStr.substring(0, 60) : bodyStr));
                }
                return;
            }

            File parent = destFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            File tempFile = new File(destFile.getAbsolutePath() + ".tmp");
            InputStream in = null;
            FileOutputStream out = null;
            try {
                in = response.body().byteStream();
                out = new FileOutputStream(tempFile);
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
                out.flush();
            } finally {
                if (out != null) {
                    try { out.close(); } catch (Exception ignored) {}
                }
                if (in != null) {
                    try { in.close(); } catch (Exception ignored) {}
                }
            }

            if (tempFile.exists() && tempFile.length() > 500) {
                if (destFile.exists()) {
                    destFile.delete();
                }
                boolean renamed = tempFile.renameTo(destFile);
                if (renamed && destFile.exists()) {
                    postSuccess(callback, destFile);
                } else {
                    if (onFallback != null) {
                        onFallback.run();
                    } else {
                        postError(callback, "Не удалось сохранить файл книги");
                    }
                }
            } else {
                if (tempFile.exists()) tempFile.delete();
                if (onFallback != null) {
                    onFallback.run();
                } else {
                    postError(callback, "Файл книги пуст (< 500 байт)");
                }
            }
        } catch (Exception e) {
            if (onFallback != null) {
                onFallback.run();
            } else {
                postError(callback, "Ошибка записи файла книги: " + e.getMessage());
            }
        } finally {
            if (response != null) {
                try { response.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Формирование полного объекта карточки библиотеки для синхронизации прогресса Bookmate v5 API.
     */
    private JSONObject buildReadingProgressCardJson(ReadingProgress progress) throws Exception {
        double rawPercent = progress.getPercent();
        double normalizedFraction = Math.min(1.0, Math.max(0.0, rawPercent / 100.0));
        long ts = progress.getTimestamp() > 0 ? progress.getTimestamp() : System.currentTimeMillis();

        JSONObject cardObj = new JSONObject();
        if (progress.getLibraryCardId() != null && !progress.getLibraryCardId().isEmpty()) {
            cardObj.put("id", progress.getLibraryCardId());
            cardObj.put("uuid", progress.getLibraryCardId());
        }
        cardObj.put("book_uuid", progress.getBookUuid());

        JSONObject bookInner = new JSONObject();
        bookInner.put("uuid", progress.getBookUuid());
        cardObj.put("book", bookInner);

        cardObj.put("state", "reading");
        if (rawPercent >= 99.0 && progress.getChapterIndex() > 0) {
            cardObj.put("state", "finished");
        }
        cardObj.put("percent", rawPercent);
        cardObj.put("reading_progress", normalizedFraction);
        cardObj.put("percentage", rawPercent);
        cardObj.put("chapter_index", progress.getChapterIndex());
        cardObj.put("chapter", progress.getChapterIndex());
        cardObj.put("paragraph_index", progress.getParagraphIndex());
        cardObj.put("paragraph", progress.getParagraphIndex());
        cardObj.put("point", progress.getParagraphIndex());
        cardObj.put("timestamp", ts);

        SimpleDateFormat isoFmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        isoFmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        String isoDate = isoFmt.format(new Date(ts));
        cardObj.put("updated_at", isoDate);
        cardObj.put("last_read_at", isoDate);

        JSONObject posObj = new JSONObject();
        posObj.put("chapter", progress.getChapterIndex());
        posObj.put("chapter_index", progress.getChapterIndex());
        posObj.put("paragraph", progress.getParagraphIndex());
        posObj.put("paragraph_index", progress.getParagraphIndex());
        posObj.put("point", progress.getParagraphIndex());
        posObj.put("percent", rawPercent);
        posObj.put("reading_progress", normalizedFraction);
        posObj.put("timestamp", ts);
        posObj.put("updated_at", isoDate);
        posObj.put("last_read_at", isoDate);

        if (progress.getCfi() != null && !progress.getCfi().isEmpty()) {
            cardObj.put("cfi", progress.getCfi());
            posObj.put("cfi", progress.getCfi());
        }
        if (progress.getDocumentUuid() != null && !progress.getDocumentUuid().isEmpty()) {
            cardObj.put("document_uuid", progress.getDocumentUuid());
            posObj.put("document_uuid", progress.getDocumentUuid());
        }

        cardObj.put("position", posObj);
        cardObj.put("reading_position", posObj);
        cardObj.put("last_reading_position", posObj);

        JSONObject lcInner = new JSONObject(cardObj.toString());
        cardObj.put("lc", lcInner);
        cardObj.put("library_card", new JSONObject(lcInner.toString()));

        return cardObj;
    }

    private boolean isCardStateMatchingResponse(String respBody, String expectedState) {
        if (respBody == null || respBody.isEmpty() || expectedState == null) return false;
        try {
            JSONObject json = new JSONObject(respBody);
            JSONObject card = json.optJSONObject("library_card");
            if (card == null) card = json.optJSONObject("lc");
            if (card == null) card = json;
            String actualState = card.optString("state", "");
            if (actualState.isEmpty()) return true;
            if (expectedState.equalsIgnoreCase(actualState)) return true;
            if ("want_to_read".equalsIgnoreCase(expectedState) && "to_read".equalsIgnoreCase(actualState)) return true;
            if ("to_read".equalsIgnoreCase(expectedState) && "want_to_read".equalsIgnoreCase(actualState)) return true;
            if ("finished".equalsIgnoreCase(expectedState) && ("done".equalsIgnoreCase(actualState) || "read".equalsIgnoreCase(actualState))) return true;
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * Отправка позиции чтения в облако Яндекса с поддержкой формата Bookmate.
     */
    public void sendReadingProgress(final ReadingProgress progress, final ApiCallback<Boolean> callback) {
        if (progress == null || progress.getBookUuid() == null || progress.getBookUuid().isEmpty()) {
            postError(callback, "Некорректные данные прогресса чтения");
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String bookUuid = progress.getBookUuid();
                    String cardId = progress.getLibraryCardId();
                    if (cardId == null || cardId.isEmpty() || cardId.equals(bookUuid)) {
                        String resolved = findLibraryCardUuidForBookSync(bookUuid);
                        if (resolved != null && !resolved.isEmpty()) {
                            cardId = resolved;
                            progress.setLibraryCardId(cardId);
                        }
                    }

                    final String targetState = (progress.getPercent() >= 99.0 && progress.getChapterIndex() > 0)
                            ? "finished" : "reading";

                    // Если карточка еще не создана в облаке, создаем ее через POST /profile/library_cards
                    if (cardId == null || cardId.isEmpty() || cardId.equals(bookUuid)) {
                        Response postInitResp = null;
                        try {
                            JSONObject initBody = new JSONObject();
                            initBody.put("book_uuid", bookUuid);
                            initBody.put("state", targetState);
                            Request reqInit = createAuthRequestBuilder(BASE_URL + "/profile/library_cards")
                                    .post(RequestBody.create(JSON_MEDIA_TYPE, initBody.toString()))
                                    .build();
                            postInitResp = httpClient.newCall(reqInit).execute();
                            if (postInitResp.body() != null) {
                                String initStr = postInitResp.body().string();
                                JSONObject initJson = new JSONObject(initStr);
                                String extracted = extractLibraryCardId(initJson, initJson.optJSONObject("book"), bookUuid);
                                if (!extracted.isEmpty()) {
                                    cardId = extracted;
                                    progress.setLibraryCardId(cardId);
                                }
                            }
                        } catch (Exception ignored) {
                        } finally {
                            if (postInitResp != null) {
                                try { postInitResp.close(); } catch (Exception ignored) {}
                            }
                        }
                    }

                    final JSONObject cardObj = buildReadingProgressCardJson(progress);
                    boolean anySuccess = false;
                    boolean stateConfirmed = false;

                    // 1. Официальный GraphQL-запрос ProgressUpdated с CFI для мобильного ридера смартфона
                    String docUuid = progress.getDocumentUuid();
                    if (docUuid == null || docUuid.isEmpty()) {
                        docUuid = fetchDocumentUuidSync(bookUuid);
                        if (docUuid != null && !docUuid.isEmpty()) {
                            progress.setDocumentUuid(docUuid);
                        } else {
                            docUuid = bookUuid;
                            progress.setDocumentUuid(docUuid);
                        }
                    }

                    String startCfi = progress.getCfi();
                    if (startCfi == null || startCfi.isEmpty()) {
                        startCfi = generateCfi(progress.getChapterIndex(), progress.getParagraphIndex());
                        progress.setCfi(startCfi);
                    }
                    String endCfi = generateCfiEnd(progress.getChapterIndex(), progress.getParagraphIndex());

                    Response gqlResp = null;
                    try {
                        JSONObject gqlBody = new JSONObject();
                        gqlBody.put("operationName", "ProgressUpdated");
                        gqlBody.put("query", GQL_PROGRESS_UPDATED);

                        JSONObject item = new JSONObject();
                        item.put("uuid", bookUuid);
                        item.put("documentUuid", (docUuid != null && !docUuid.isEmpty()) ? docUuid : bookUuid);

                        JSONObject cfiObj = new JSONObject();
                        cfiObj.put("start", startCfi);
                        cfiObj.put("end", endCfi);
                        item.put("cfi", cfiObj);

                        item.put("consumptionType", "CONSUMPTION");
                        long nowTs = progress.getTimestamp() > 0 ? progress.getTimestamp() : System.currentTimeMillis();
                        item.put("readAt", formatIsoWithTimezone(nowTs));
                        item.put("timezoneId", TimeZone.getDefault().getID());
                        item.put("sessionId", generateSessionId());
                        item.put("timespent", 15000);

                        JSONArray progArr = new JSONArray();
                        progArr.put(item);

                        JSONObject vars = new JSONObject();
                        vars.put("textbookProgresses", progArr);
                        gqlBody.put("variables", vars);

                        Request gqlReq = createGraphQLRequestBuilder(GRAPHQL_URL)
                                .post(RequestBody.create(JSON_MEDIA_TYPE, gqlBody.toString()))
                                .build();
                        gqlResp = httpClient.newCall(gqlReq).execute();
                        if (gqlResp.isSuccessful() && gqlResp.body() != null) {
                            String respStr = gqlResp.body().string();
                            JSONObject rj = new JSONObject(respStr);
                            if (rj.has("data") && !rj.isNull("data")) {
                                anySuccess = true;
                            } else if (rj.has("errors")) {
                                Log.w(TAG, "GraphQL ProgressUpdated returned errors: " + rj.optJSONArray("errors"));
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (gqlResp != null) {
                            try { gqlResp.close(); } catch (Exception ignored) {}
                        }
                    }

                    // 2. Обновление конкретной карточки по ее настоящему UUID (PUT и PATCH /profile/library_cards/{cardUuid})
                    if (cardId != null && !cardId.isEmpty() && !cardId.equals(bookUuid)) {
                        String cardUrl = BASE_URL + "/profile/library_cards/" + cardId;
                        Response putResp = null;
                        try {
                            Request reqPut = createAuthRequestBuilder(cardUrl)
                                    .put(RequestBody.create(JSON_MEDIA_TYPE, cardObj.toString()))
                                    .build();
                            putResp = httpClient.newCall(reqPut).execute();
                            if (putResp.isSuccessful()) {
                                anySuccess = true;
                                String rb = putResp.body() != null ? putResp.body().string() : "";
                                if (isCardStateMatchingResponse(rb, targetState)) {
                                    stateConfirmed = true;
                                }
                            }
                        } catch (Exception ignored) {
                        } finally {
                            if (putResp != null) {
                                try { putResp.close(); } catch (Exception ignored) {}
                            }
                        }

                        Response patchResp = null;
                        try {
                            Request reqPatch = createAuthRequestBuilder(cardUrl)
                                    .patch(RequestBody.create(JSON_MEDIA_TYPE, cardObj.toString()))
                                    .build();
                            patchResp = httpClient.newCall(reqPatch).execute();
                            if (patchResp.isSuccessful()) {
                                anySuccess = true;
                                String rb = patchResp.body() != null ? patchResp.body().string() : "";
                                if (isCardStateMatchingResponse(rb, targetState)) {
                                    stateConfirmed = true;
                                }
                            }
                        } catch (Exception ignored) {
                        } finally {
                            if (patchResp != null) {
                                try { patchResp.close(); } catch (Exception ignored) {}
                            }
                        }

                        // Если карточка на сервере застряла в "want_to_read" и PUT/PATCH не переключили ее в "reading",
                        // выполняем создание/актуализацию карточки со статусом "reading" и актуальным прогрессом!
                        if (!stateConfirmed) {
                            Response recreateResp = null;
                            try {
                                Request reqRecreate = createAuthRequestBuilder(BASE_URL + "/profile/library_cards")
                                        .post(RequestBody.create(JSON_MEDIA_TYPE, cardObj.toString()))
                                        .build();
                                recreateResp = httpClient.newCall(reqRecreate).execute();
                                if (recreateResp.isSuccessful()) {
                                    anySuccess = true;
                                    String rb = recreateResp.body() != null ? recreateResp.body().string() : "";
                                    if (!rb.isEmpty()) {
                                        JSONObject rj = new JSONObject(rb);
                                        String newCardUuid = extractLibraryCardId(rj, rj.optJSONObject("book"), bookUuid);
                                        if (!newCardUuid.isEmpty()) {
                                            cardId = newCardUuid;
                                            progress.setLibraryCardId(newCardUuid);
                                            cardObj.put("id", newCardUuid);
                                            cardObj.put("uuid", newCardUuid);
                                        }
                                    }
                                }
                            } catch (Exception ignored) {
                            } finally {
                                if (recreateResp != null) {
                                    try { recreateResp.close(); } catch (Exception ignored) {}
                                }
                            }
                        }
                    }

                    // 3. Официальный эндпоинт Bookmate API v5: bulk PATCH
                    String endpointBulk = BASE_URL + "/profile/library_cards/bulk";
                    JSONObject bulkBody = new JSONObject();
                    JSONArray cardsArray = new JSONArray();
                    cardsArray.put(cardObj);
                    bulkBody.put("library_cards", cardsArray);

                    Response bulkResp = null;
                    try {
                        RequestBody bulkReqBody = RequestBody.create(JSON_MEDIA_TYPE, bulkBody.toString());
                        Request requestBulk = createAuthRequestBuilder(endpointBulk).patch(bulkReqBody).build();
                        bulkResp = httpClient.newCall(requestBulk).execute();
                        if (bulkResp.isSuccessful()) {
                            anySuccess = true;
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (bulkResp != null) {
                            try { bulkResp.close(); } catch (Exception ignored) {}
                        }
                    }

                    if (anySuccess) {
                        postSuccess(callback, true);
                    } else {
                        sendReadingProgressFallbackPost(progress, cardObj, callback);
                    }
                } catch (Exception e) {
                    postError(callback, e.getMessage());
                }
            }
        }).start();
    }

    private void sendReadingProgressFallbackPost(final ReadingProgress progress, final JSONObject cardObj, final ApiCallback<Boolean> callback) {
        // 2. Fallback 1: POST /profile/library_cards
        String endpointPost = BASE_URL + "/profile/library_cards";
        try {
            RequestBody postReqBody = RequestBody.create(JSON_MEDIA_TYPE, cardObj.toString());
            Request requestPost = createAuthRequestBuilder(endpointPost).post(postReqBody).build();

            httpClient.newCall(requestPost).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    sendReadingProgressFallbackPatchCard(progress, cardObj, callback);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.isSuccessful() || response.code() == 409 || response.code() == 422) {
                            postSuccess(callback, true);
                        } else {
                            sendReadingProgressFallbackPatchCard(progress, cardObj, callback);
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            sendReadingProgressFallbackPatchCard(progress, cardObj, callback);
        }
    }

    private void sendReadingProgressFallbackPatchCard(final ReadingProgress progress, final JSONObject cardObj, final ApiCallback<Boolean> callback) {
        // 3. Fallback 2: PATCH /profile/library_cards/{cardId_or_bookUuid}
        String targetId = (progress.getLibraryCardId() != null && !progress.getLibraryCardId().isEmpty())
                ? progress.getLibraryCardId()
                : progress.getBookUuid();
        String endpointPatch = BASE_URL + "/profile/library_cards/" + targetId;

        try {
            RequestBody patchReqBody = RequestBody.create(JSON_MEDIA_TYPE, cardObj.toString());
            Request requestPatch = createAuthRequestBuilder(endpointPatch).patch(patchReqBody).build();

            httpClient.newCall(requestPatch).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    sendReadingProgressFallbackPutCard(progress, cardObj, callback);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.isSuccessful()) {
                            postSuccess(callback, true);
                        } else {
                            sendReadingProgressFallbackPutCard(progress, cardObj, callback);
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            sendReadingProgressFallbackPutCard(progress, cardObj, callback);
        }
    }

    private void sendReadingProgressFallbackPutCard(final ReadingProgress progress, final JSONObject cardObj, final ApiCallback<Boolean> callback) {
        // 4. Fallback 3: PUT /profile/library_cards/bulk
        String endpointPut = BASE_URL + "/profile/library_cards/bulk";
        try {
            JSONObject bulkBody = new JSONObject();
            JSONArray cardsArray = new JSONArray();
            cardsArray.put(cardObj);
            bulkBody.put("library_cards", cardsArray);

            RequestBody putReqBody = RequestBody.create(JSON_MEDIA_TYPE, bulkBody.toString());
            Request requestPut = createAuthRequestBuilder(endpointPut).put(putReqBody).build();

            httpClient.newCall(requestPut).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postError(callback, "Ошибка отправки прогресса: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.isSuccessful()) {
                            postSuccess(callback, true);
                        } else {
                            postError(callback, "Ошибка сервера при синхронизации прогресса: HTTP " + response.code());
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            postError(callback, e.getMessage());
        }
    }

    private static final String GQL_SEARCH =
            "query Search($query: SearchParamsInput!) {\n" +
            "    search(query: $query) {\n" +
            "        page {\n" +
            "            __typename\n" +
            "            ...searchSnippetAudioBookFragment\n" +
            "            ...searchSnippetTextBookFragment\n" +
            "            ...searchSnippetComicBookFragment\n" +
            "            ...searchSnippetTextSerialFragment\n" +
            "            ...bookshelfFragment\n" +
            "            ...personFragment\n" +
            "            ...publisherFragment\n" +
            "            ...seriesFragment\n" +
            "            ...topicFragment\n" +
            "            ...userFragment\n" +
            "        }\n" +
            "        cursor\n" +
            "        rankedFilter { filterType }\n" +
            "        misspell { correctedText correctionType }\n" +
            "    }\n" +
            "}\n" +
            "fragment coverFragment on Cover { url ratio backgroundColorHex }\n" +
            "fragment personFragment on Person { avatar { __typename ...coverFragment } name uuid worksCount roles }\n" +
            "fragment bookFragment on Book { annotation name cover { __typename ...coverFragment } uuid authors { __typename ...personFragment } ageRestriction editorAnnotation }\n" +
            "fragment publisherFragment on Publisher { avatar { __typename ...coverFragment } name uuid worksCount }\n" +
            "fragment publisherBookFragment on Book { publisher { __typename ...publisherFragment } }\n" +
            "fragment translatorsBookFragment on Book { translators { __typename ...personFragment } }\n" +
            "fragment topicsBookFragment on Book { topics { name totalBook uuid } }\n" +
            "fragment subscriptionLevelsFragment on Book { subscriptionLevels }\n" +
            "fragment snippetBookFragment on Book { __typename ...bookFragment ...publisherBookFragment ...translatorsBookFragment ...topicsBookFragment ...subscriptionLevelsFragment }\n" +
            "fragment bookTagFragment on Tag { name value }\n" +
            "fragment narratorsAudioBookFragment on AudioBook { narrators { __typename ...personFragment } }\n" +
            "fragment progressFragment on Progress { finished inLibrary progress isPublic }\n" +
            "fragment progressAudioBookFragment on AudioBook { progress { __typename ...progressFragment } }\n" +
            "fragment listenersCountAudioBookFragment on AudioBook { listenersCount }\n" +
            "fragment searchSnippetAudioBookFragment on AudioBook { __typename book { __typename ...snippetBookFragment tags { __typename ...bookTagFragment } } ...narratorsAudioBookFragment ...progressAudioBookFragment ...listenersCountAudioBookFragment }\n" +
            "fragment progressTextBookFragment on TextBook { progress { __typename ...progressFragment } }\n" +
            "fragment readersCountTextBookFragment on TextBook { readersCount }\n" +
            "fragment searchSnippetTextBookFragment on TextBook { __typename book { __typename ...snippetBookFragment tags { __typename ...bookTagFragment } } ...progressTextBookFragment ...readersCountTextBookFragment }\n" +
            "fragment progressComicBookFragment on ComicBook { progress { __typename ...progressFragment } }\n" +
            "fragment readersCountComicBookFragment on ComicBook { readersCount }\n" +
            "fragment searchSnippetComicBookFragment on ComicBook { __typename book { __typename ...snippetBookFragment tags { __typename ...bookTagFragment } } ...progressComicBookFragment ...readersCountComicBookFragment }\n" +
            "fragment textSerialFragment on TextSerial { book { __typename ...bookFragment } }\n" +
            "fragment episodesTextSerialFragment on TextSerial { episodes { total } }\n" +
            "fragment readersCountTextSerialFragment on TextSerial { readersCount }\n" +
            "fragment searchSnippetTextSerialFragment on TextSerial { __typename book { __typename ...snippetBookFragment tags { __typename ...bookTagFragment } } ...textSerialFragment ...episodesTextSerialFragment ...readersCountTextSerialFragment }\n" +
            "fragment userFragment on User { avatar { __typename ...coverFragment } name uuid followersCount login }\n" +
            "fragment bookshelfFragment on Bookshelf { cover { __typename ...coverFragment } name uuid user { __typename ...userFragment } posts { total } followersCount description }\n" +
            "fragment seriesFragment on Series { authors { __typename ...personFragment } cover { __typename ...coverFragment } name uuid items { followersCount total } }\n" +
            "fragment topicFragment on Topic { name slug totalBook uuid parent { name slug totalBook uuid } }\n";

    /**
     * Загрузка рекомендаций и популярных книг каталога Яндекс Книг.
     */
    public void getRecommendations(final ApiCallback<List<Book>> callback) {
        String endpoint = BASE_URL + "/popular_searches/ru";
        Request request = createAuthRequestBuilder(endpoint).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                loadBookshelfByUuid("byugcjMZ", callback);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    response.close();
                    loadBookshelfByUuid("byugcjMZ", callback);
                    return;
                }

                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONObject pop = json.optJSONObject("popular_searches");
                    String shelfUuid = "byugcjMZ";
                    if (pop != null) {
                        JSONArray res = pop.optJSONArray("resources");
                        if (res != null && res.length() > 0) {
                            for (int i = 0; i < res.length(); i++) {
                                JSONObject item = res.getJSONObject(i);
                                String url = item.optString("url", "");
                                if (url.contains("/bookshelves/")) {
                                    String[] parts = url.split("/bookshelves/");
                                    if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                                        shelfUuid = parts[1].trim();
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    loadBookshelfByUuid(shelfUuid, callback);
                } catch (Exception e) {
                    loadBookshelfByUuid("byugcjMZ", callback);
                }
            }
        });
    }

    private void loadBookshelfByUuid(String shelfUuid, final ApiCallback<List<Book>> callback) {
        String endpoint = BASE_URL + "/bookshelves/" + shelfUuid + "/books";
        Request request = createAuthRequestBuilder(endpoint).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postError(callback, "Ошибка загрузки каталога: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    int code = response.code();
                    response.close();
                    postError(callback, "Ошибка загрузки каталога: HTTP " + code);
                    return;
                }

                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONArray booksArray = json.optJSONArray("books");
                    List<Book> books = new ArrayList<>();
                    if (booksArray != null) {
                        for (int i = 0; i < booksArray.length(); i++) {
                            JSONObject bObj = booksArray.getJSONObject(i);
                            Book book = parseBookFromCard(bObj);
                            if (book != null) {
                                book.setPercent(0.0);
                                book.setCurrentChapterIndex(0);
                                book.setCurrentParagraphIndex(0);
                                book.setShelfType("catalog");
                                books.add(book);
                            }
                        }
                    }
                    postSuccess(callback, books);
                } catch (Exception e) {
                    postError(callback, "Ошибка парсинга каталога: " + e.getMessage());
                }
            }
        });
    }

    /**
     * Глобальный поиск книг по базе Яндекс Книг (GraphQL API).
     */
    public void searchBooks(final String query, final ApiCallback<List<Book>> callback) {
        if (query == null || query.trim().isEmpty()) {
            postSuccess(callback, new ArrayList<Book>());
            return;
        }

        try {
            JSONObject variables = new JSONObject();
            JSONObject queryObj = new JSONObject();
            queryObj.put("query", query.trim());
            queryObj.put("noMisspell", false);
            queryObj.put("cursor", "");
            variables.put("query", queryObj);

            JSONObject payload = new JSONObject();
            payload.put("operationName", "Search");
            payload.put("variables", variables);
            payload.put("query", GQL_SEARCH);

            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, payload.toString());
            Request request = createAuthRequestBuilder("https://api-gateway.bookmate.yandex.net/graphql")
                    .post(body)
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postError(callback, "Ошибка сети: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        int code = response.code();
                        response.close();
                        postError(callback, "Ошибка поиска: HTTP " + code);
                        return;
                    }

                    try {
                        String bodyStr = response.body().string();
                        JSONObject json = new JSONObject(bodyStr);
                        JSONObject data = json.optJSONObject("data");
                        if (data == null) {
                            postSuccess(callback, new ArrayList<Book>());
                            return;
                        }
                        JSONObject search = data.optJSONObject("search");
                        if (search == null) {
                            postSuccess(callback, new ArrayList<Book>());
                            return;
                        }
                        JSONArray page = search.optJSONArray("page");
                        List<Book> books = new ArrayList<>();
                        if (page != null) {
                            for (int i = 0; i < page.length(); i++) {
                                JSONObject item = page.getJSONObject(i);
                                String type = item.optString("__typename", "");
                                JSONObject bObj = null;
                                if ("TextBook".equals(type) || "ComicBook".equals(type) || "TextSerial".equals(type)) {
                                    bObj = item.optJSONObject("book");
                                } else if ("Book".equals(type)) {
                                    bObj = item;
                                }
                                if (bObj != null) {
                                    String uuid = bObj.optString("uuid", "");
                                    if (uuid.isEmpty()) continue;
                                    String title = bObj.optString("name", bObj.optString("title", "Без названия"));

                                    String authorText = "";
                                    JSONArray authorsArr = bObj.optJSONArray("authors");
                                    if (authorsArr != null && authorsArr.length() > 0) {
                                        StringBuilder sb = new StringBuilder();
                                        for (int a = 0; a < authorsArr.length(); a++) {
                                            JSONObject aObj = authorsArr.optJSONObject(a);
                                            if (aObj != null) {
                                                String aName = aObj.optString("name", "");
                                                if (!aName.isEmpty()) {
                                                    if (sb.length() > 0) sb.append(", ");
                                                    sb.append(aName);
                                                }
                                            }
                                        }
                                        authorText = sb.toString();
                                    }
                                    if (authorText.isEmpty()) {
                                        authorText = parseAuthorString(bObj, null);
                                    }

                                    String coverUrl = "";
                                    JSONObject coverObj = bObj.optJSONObject("cover");
                                    if (coverObj != null) {
                                        coverUrl = coverObj.optString("url", "");
                                    }

                                    String rawAnn = bObj.optString("annotation", bObj.optString("editorAnnotation", ""));
                                    String cleanAnn = "";
                                    if (!rawAnn.isEmpty()) {
                                        cleanAnn = android.text.Html.fromHtml(rawAnn).toString().trim().replaceAll("\\s+", " ");
                                    }

                                    Book book = new Book();
                                    book.setUuid(uuid);
                                    book.setTitle(title);
                                    book.setAuthor(authorText);
                                    book.setAnnotation(cleanAnn);
                                    book.setCoverUrl(coverUrl);
                                    book.setPercent(0.0);
                                    book.setCurrentChapterIndex(0);
                                    book.setCurrentParagraphIndex(0);
                                    book.setShelfType("search");
                                    books.add(book);
                                }
                            }
                        }
                        postSuccess(callback, books);
                    } catch (Exception e) {
                        postError(callback, "Ошибка обработки результатов: " + e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            postError(callback, "Ошибка запроса поиска: " + e.getMessage());
        }
    }

    /**
     * Добавление книги в личную библиотеку пользователя (на полку «В планах»).
     */
    public void addBookToLibrary(final String bookUuid, final ApiCallback<Boolean> callback) {
        String endpoint = BASE_URL + "/profile/library_cards";
        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("book_uuid", bookUuid);
            bodyJson.put("state", "reading");
            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString());
            Request request = createAuthRequestBuilder(endpoint).post(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postError(callback, "Ошибка сети: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.isSuccessful() || response.code() == 409 || response.code() == 422) {
                            postSuccess(callback, true);
                        } else {
                            postError(callback, "Ошибка сервера: HTTP " + response.code());
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            postError(callback, "Ошибка отправки: " + e.getMessage());
        }
    }

    /**
     * Перемещение книги на заданную полку («reading», «to_read», «finished»).
     */
    public void updateBookShelfState(final String bookUuid, final String shelfState, final ApiCallback<Boolean> callback) {
        updateBookShelfState(bookUuid, null, shelfState, callback);
    }

    public void updateBookShelfState(final String bookUuid, final String cardIdHint, final String shelfState, final ApiCallback<Boolean> callback) {
        final String apiState;
        if ("done".equalsIgnoreCase(shelfState) || "finished".equalsIgnoreCase(shelfState) || "read".equalsIgnoreCase(shelfState) || "completed".equalsIgnoreCase(shelfState)) {
            apiState = "finished";
        } else if ("reading".equalsIgnoreCase(shelfState)) {
            apiState = "reading";
        } else {
            apiState = "want_to_read";
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String resolvedCardId = (cardIdHint != null && !cardIdHint.isEmpty() && !cardIdHint.equals(bookUuid))
                            ? cardIdHint : findLibraryCardUuidForBookSync(bookUuid);

                    JSONObject bodyJson = new JSONObject();
                    if (resolvedCardId != null && !resolvedCardId.isEmpty()) {
                        bodyJson.put("id", resolvedCardId);
                        bodyJson.put("uuid", resolvedCardId);
                    }
                    bodyJson.put("book_uuid", bookUuid);
                    bodyJson.put("state", apiState);
                    if ("want_to_read".equals(apiState)) {
                        bodyJson.put("reading_progress", 0.0);
                        bodyJson.put("percent", 0.0);
                    }
                    JSONObject innerLc = new JSONObject();
                    if (resolvedCardId != null && !resolvedCardId.isEmpty()) {
                        innerLc.put("id", resolvedCardId);
                        innerLc.put("uuid", resolvedCardId);
                    }
                    innerLc.put("book_uuid", bookUuid);
                    innerLc.put("state", apiState);
                    if ("want_to_read".equals(apiState)) {
                        innerLc.put("reading_progress", 0.0);
                        innerLc.put("percent", 0.0);
                    }
                    bodyJson.put("lc", innerLc);
                    bodyJson.put("library_card", new JSONObject(innerLc.toString()));

                    boolean stateConfirmed = false;
                    boolean anyHttpSuccess = false;

                    if (resolvedCardId != null && !resolvedCardId.isEmpty() && !resolvedCardId.equals(bookUuid)) {
                        String cardEndpoint = BASE_URL + "/profile/library_cards/" + resolvedCardId;

                        Response putResp = null;
                        try {
                            Request putReq = createAuthRequestBuilder(cardEndpoint)
                                    .put(RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString()))
                                    .build();
                            putResp = httpClient.newCall(putReq).execute();
                            if (putResp.isSuccessful()) {
                                anyHttpSuccess = true;
                                String rb = putResp.body() != null ? putResp.body().string() : "";
                                if (isCardStateMatchingResponse(rb, apiState)) {
                                    stateConfirmed = true;
                                }
                            }
                        } catch (Exception ignored) {
                        } finally {
                            if (putResp != null) {
                                try { putResp.close(); } catch (Exception ignored) {}
                            }
                        }

                        if (!stateConfirmed) {
                            Response patchResp = null;
                            try {
                                Request patchReq = createAuthRequestBuilder(cardEndpoint)
                                        .patch(RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString()))
                                        .build();
                                patchResp = httpClient.newCall(patchReq).execute();
                                if (patchResp.isSuccessful()) {
                                    anyHttpSuccess = true;
                                    String rb = patchResp.body() != null ? patchResp.body().string() : "";
                                    if (isCardStateMatchingResponse(rb, apiState)) {
                                        stateConfirmed = true;
                                    }
                                }
                            } catch (Exception ignored) {
                            } finally {
                                if (patchResp != null) {
                                    try { patchResp.close(); } catch (Exception ignored) {}
                                }
                            }
                        }

                        // Если существующая карточка не сменила state через PUT/PATCH, пересоздаем карточку с нужным state
                        if (!stateConfirmed) {
                            Response delResp = null;
                            try {
                                Request delReq = createAuthRequestBuilder(cardEndpoint).delete().build();
                                delResp = httpClient.newCall(delReq).execute();
                            } catch (Exception ignored) {
                            } finally {
                                if (delResp != null) {
                                    try { delResp.close(); } catch (Exception ignored) {}
                                }
                            }

                            Response postResp = null;
                            try {
                                JSONObject postBody = new JSONObject();
                                postBody.put("book_uuid", bookUuid);
                                postBody.put("state", apiState);
                                Request postReq = createAuthRequestBuilder(BASE_URL + "/profile/library_cards")
                                        .post(RequestBody.create(JSON_MEDIA_TYPE, postBody.toString()))
                                        .build();
                                postResp = httpClient.newCall(postReq).execute();
                                if (postResp.isSuccessful()) {
                                    anyHttpSuccess = true;
                                    stateConfirmed = true;
                                }
                            } catch (Exception ignored) {
                            } finally {
                                if (postResp != null) {
                                    try { postResp.close(); } catch (Exception ignored) {}
                                }
                            }
                        }
                    } else {
                        // Карточки еще не было или ее UUID не был найден заранее — вызываем POST /profile/library_cards
                        Response postResp = null;
                        String postBodyStr = "";
                        try {
                            JSONObject postJson = new JSONObject();
                            postJson.put("book_uuid", bookUuid);
                            postJson.put("state", apiState);
                            Request postReq = createAuthRequestBuilder(BASE_URL + "/profile/library_cards")
                                    .post(RequestBody.create(JSON_MEDIA_TYPE, postJson.toString()))
                                    .build();
                            postResp = httpClient.newCall(postReq).execute();
                            if (postResp.isSuccessful()) {
                                anyHttpSuccess = true;
                                postBodyStr = postResp.body() != null ? postResp.body().string() : "";
                                if (isCardStateMatchingResponse(postBodyStr, apiState)) {
                                    stateConfirmed = true;
                                }
                            }
                        } catch (Exception ignored) {
                        } finally {
                            if (postResp != null) {
                                try { postResp.close(); } catch (Exception ignored) {}
                            }
                        }

                        // Если POST вернул уже существовавшую карточку со старым статусом (например want_to_read вместо reading)
                        if (!stateConfirmed && !postBodyStr.isEmpty()) {
                            try {
                                JSONObject rj = new JSONObject(postBodyStr);
                                String existingCardId = extractLibraryCardId(rj, rj.optJSONObject("book"), bookUuid);
                                if (!existingCardId.isEmpty() && !existingCardId.equals(bookUuid)) {
                                    Response delResp = null;
                                    try {
                                        Request delReq = createAuthRequestBuilder(BASE_URL + "/profile/library_cards/" + existingCardId).delete().build();
                                        delResp = httpClient.newCall(delReq).execute();
                                    } finally {
                                        if (delResp != null) {
                                            try { delResp.close(); } catch (Exception ignored) {}
                                        }
                                    }
                                    Response recreateResp = null;
                                    try {
                                        JSONObject recreateJson = new JSONObject();
                                        recreateJson.put("book_uuid", bookUuid);
                                        recreateJson.put("state", apiState);
                                        Request recreateReq = createAuthRequestBuilder(BASE_URL + "/profile/library_cards")
                                                .post(RequestBody.create(JSON_MEDIA_TYPE, recreateJson.toString()))
                                                .build();
                                        recreateResp = httpClient.newCall(recreateReq).execute();
                                        if (recreateResp.isSuccessful()) {
                                            anyHttpSuccess = true;
                                            stateConfirmed = true;
                                        }
                                    } finally {
                                        if (recreateResp != null) {
                                            try { recreateResp.close(); } catch (Exception ignored) {}
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }

                    if (stateConfirmed || anyHttpSuccess) {
                        postSuccess(callback, true);
                    } else {
                        final String targetPathId = (resolvedCardId != null && !resolvedCardId.isEmpty()) ? resolvedCardId : bookUuid;
                        updateBookShelfFallbackPut(targetPathId, bookUuid, apiState, callback);
                    }
                } catch (Exception e) {
                    updateBookShelfFallbackPost(bookUuid, apiState, callback);
                }
            }
        }).start();
    }

    private void updateBookShelfFallbackPut(final String targetPathId, final String bookUuid, final String apiState, final ApiCallback<Boolean> callback) {
        String endpoint = BASE_URL + "/profile/library_cards/" + targetPathId;
        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("book_uuid", bookUuid);
            bodyJson.put("state", apiState);
            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString());
            Request request = createAuthRequestBuilder(endpoint).put(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    updateBookShelfFallbackPost(bookUuid, apiState, callback);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.isSuccessful()) {
                            postSuccess(callback, true);
                        } else {
                            updateBookShelfFallbackPost(bookUuid, apiState, callback);
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            updateBookShelfFallbackPost(bookUuid, apiState, callback);
        }
    }

    private void updateBookShelfFallbackPost(final String bookUuid, final String apiState, final ApiCallback<Boolean> callback) {
        String endpoint = BASE_URL + "/profile/library_cards";
        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("book_uuid", bookUuid);
            bodyJson.put("state", apiState);
            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString());
            Request request = createAuthRequestBuilder(endpoint).post(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postError(callback, "Ошибка сети: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.isSuccessful() || response.code() == 409 || response.code() == 422) {
                            postSuccess(callback, true);
                        } else {
                            postError(callback, "Ошибка сервера при смене полки: HTTP " + response.code());
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            postError(callback, e.getMessage());
        }
    }

    /**
     * Удаление книги из личной библиотеки («Убрать с полки»).
     */
    public void removeBookFromLibrary(final String bookUuid, final ApiCallback<Boolean> callback) {
        removeBookFromLibrary(bookUuid, null, callback);
    }

    public void removeBookFromLibrary(final String bookUuid, final String cardIdHint, final ApiCallback<Boolean> callback) {
        if (cardIdHint != null && !cardIdHint.isEmpty() && !cardIdHint.equals(bookUuid)) {
            executeDeleteLibraryCard(cardIdHint, cardIdHint, bookUuid, callback);
            return;
        }

        // Запрашиваем карточку книги для точного определения cardUuid (с фильтрацией по book.uuid!)
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String resolvedCardUuid = findLibraryCardUuidForBookSync(bookUuid);
                    if (resolvedCardUuid != null && !resolvedCardUuid.isEmpty()) {
                        executeDeleteLibraryCard(resolvedCardUuid, resolvedCardUuid, bookUuid, callback);
                    } else {
                        executeDeleteLibraryCard(bookUuid, null, bookUuid, callback);
                    }
                } catch (Exception e) {
                    executeDeleteLibraryCard(bookUuid, null, bookUuid, callback);
                }
            }
        }).start();
    }

    private void executeDeleteLibraryCard(final String cardId, final String cardUuid, final String bookUuid, final ApiCallback<Boolean> callback) {
        final String preferredCardId = (cardUuid != null && !cardUuid.isEmpty()) ? cardUuid : cardId;
        final String targetPathId = (preferredCardId != null && !preferredCardId.isEmpty()) ? preferredCardId : bookUuid;
        String endpoint = BASE_URL + "/profile/library_cards/" + targetPathId;
        Request request = createAuthRequestBuilder(endpoint).delete().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                removeBookFallbackNone(targetPathId, cardUuid, bookUuid, callback);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (response.isSuccessful() || (response.code() == 404 && !targetPathId.equals(bookUuid))) {
                        postSuccess(callback, true);
                    } else {
                        removeBookFallbackNone(targetPathId, cardUuid, bookUuid, callback);
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    private void removeBookFallbackNone(final String targetPathId, final String cardUuid, final String bookUuid, final ApiCallback<Boolean> callback) {
        // Fallback 1: DELETE по cardUuid если он отличается
        if (cardUuid != null && !cardUuid.isEmpty() && !cardUuid.equals(targetPathId)) {
            String uuidEndpoint = BASE_URL + "/profile/library_cards/" + cardUuid;
            Request uuidRequest = createAuthRequestBuilder(uuidEndpoint).delete().build();
            httpClient.newCall(uuidRequest).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    removeBookFallbackPatchNone(targetPathId, bookUuid, callback);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.isSuccessful() || response.code() == 404) {
                            postSuccess(callback, true);
                        } else {
                            removeBookFallbackPatchNone(targetPathId, bookUuid, callback);
                        }
                    } finally {
                        response.close();
                    }
                }
            });
            return;
        }

        removeBookFallbackPatchNone(targetPathId, bookUuid, callback);
    }

    private void removeBookFallbackPatchNone(final String targetPathId, final String bookUuid, final ApiCallback<Boolean> callback) {
        // Fallback 2: PATCH с состоянием "none" (открепление книги от полок пользователя в Bookmate)
        String endpoint = BASE_URL + "/profile/library_cards/" + targetPathId;
        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("book_uuid", bookUuid);
            bodyJson.put("state", "none");
            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString());
            Request request = createAuthRequestBuilder(endpoint).patch(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    removeBookFallbackPostNone(bookUuid, callback);
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.isSuccessful() || response.code() == 404) {
                            postSuccess(callback, true);
                        } else {
                            removeBookFallbackPostNone(bookUuid, callback);
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            removeBookFallbackPostNone(bookUuid, callback);
        }
    }

    private void removeBookFallbackPostNone(final String bookUuid, final ApiCallback<Boolean> callback) {
        // Fallback 3: POST /profile/library_cards со state: "none"
        String endpoint = BASE_URL + "/profile/library_cards";
        try {
            JSONObject bodyJson = new JSONObject();
            bodyJson.put("book_uuid", bookUuid);
            bodyJson.put("state", "none");
            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, bodyJson.toString());
            Request request = createAuthRequestBuilder(endpoint).post(body).build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postError(callback, "Ошибка сети: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try {
                        if (response.isSuccessful() || response.code() == 404 || response.code() == 409 || response.code() == 422) {
                            postSuccess(callback, true);
                        } else {
                            postError(callback, "Ошибка сервера при удалении: HTTP " + response.code());
                        }
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            postError(callback, e.getMessage());
        }
    }

    private <T> void postSuccess(final ApiCallback<T> callback, final T result) {
        if (callback == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    callback.onSuccess(result);
                } catch (Throwable t) {
                    Log.e(TAG, "Unhandled exception in ApiCallback.onSuccess", t);
                }
            }
        });
    }

    private <T> void postError(final ApiCallback<T> callback, final String message) {
        if (callback == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    callback.onError(message);
                } catch (Throwable t) {
                    Log.e(TAG, "Unhandled exception in ApiCallback.onError", t);
                }
            }
        });
    }
}
