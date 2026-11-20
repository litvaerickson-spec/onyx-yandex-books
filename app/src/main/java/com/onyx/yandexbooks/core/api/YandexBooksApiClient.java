package com.onyx.yandexbooks.core.api;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.onyx.yandexbooks.core.api.models.Book;
import com.onyx.yandexbooks.core.api.models.Chapter;
import com.onyx.yandexbooks.core.api.models.Quote;
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
    private static final String REST_GATEWAY_URL = "https://api-gateway-rest.bookmate.yandex.net/api/v5";
    private static final String PRIMARY_GRAPHQL_URL = "https://api-gateway.bookmate.yandex.net/graphql";
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
        builder.addHeader("Accept-Language", "ru-RU,ru");
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
        if (url != null && url.contains("api-gateway.bookmate.yandex.net")) {
            builder.addHeader("X-App-Id", "ru.plus.bookmate");
            builder.addHeader("X-App-Platform", "android");
            builder.addHeader("X-App-Version", "6.83.0");
            builder.addHeader("User-Agent", "okhttp/3.12.13");
        } else {
            builder.addHeader("User-Agent", "Mozilla/5.0");
        }
        return builder;
    }

    /**
     * Выполняет GraphQL-запрос с приоритетом нативного шлюза PRIMARY_GRAPHQL_URL (api-gateway.bookmate.yandex.net)
     * и автоматическим fallback на веб-прокси GRAPHQL_URL (books.yandex.ru) при 403/5xx/HTML-капче/ошибке сети.
     */
    private String executeGraphQLWithFallbackSync(JSONObject gqlBody) {
        if (gqlBody == null) return null;
        String jsonStr = gqlBody.toString();
        String[] endpoints = new String[]{PRIMARY_GRAPHQL_URL, GRAPHQL_URL};
        for (int i = 0; i < endpoints.length; i++) {
            Response resp = null;
            try {
                Request req = createGraphQLRequestBuilder(endpoints[i])
                        .post(RequestBody.create(JSON_MEDIA_TYPE, jsonStr))
                        .build();
                resp = httpClient.newCall(req).execute();
                if (resp.isSuccessful() && resp.body() != null) {
                    String body = resp.body().string();
                    if (body != null) {
                        String trimmed = body.trim();
                        if (trimmed.startsWith("{")) {
                            return trimmed;
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (resp != null) {
                    try { resp.close(); } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    /**
     * Загрузка всех книг пользователя с полной постраничной пагинацией (независимо от лимита per_page сервера).
     */
    public void getAllUserBooks(final ApiCallback<List<Book>> callback) {
        final List<Book> allBooks = new ArrayList<>();
        final java.util.Set<String> seenUuids = new java.util.HashSet<>();
        loadLibraryPage(1, allBooks, seenUuids, callback);
    }

    private void loadLibraryPage(final int page, final List<Book> accumulated, final java.util.Set<String> seenUuids, final ApiCallback<List<Book>> callback) {
        String endpoint = BASE_URL + "/profile/library_cards?per_page=100&page=" + page;
        Request request = createAuthRequestBuilder(endpoint).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, final IOException e) {
                if (!accumulated.isEmpty()) {
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
                    String body = response.body() != null ? response.body().string() : "";
                    JSONObject json = new JSONObject(body);
                    JSONArray cardsArray = json.optJSONArray("library_cards");
                    if (cardsArray == null) cardsArray = json.optJSONArray("items");
                    JSONObject metaObj = json.optJSONObject("meta");
                    int totalExpected = (metaObj != null) ? metaObj.optInt("total", -1) : -1;

                    int countOnPage = 0;
                    int newlyAdded = 0;
                    if (cardsArray != null) {
                        countOnPage = cardsArray.length();
                        for (int i = 0; i < countOnPage; i++) {
                            JSONObject card = cardsArray.getJSONObject(i);
                            Book book = parseBookFromCard(card);
                            if (book != null && book.getUuid() != null && seenUuids.add(book.getUuid())) {
                                accumulated.add(book);
                                newlyAdded++;
                            }
                        }
                    }

                    // Сервер Bookmate v5 может ограничивать per_page на уровне 20/30/50 записей:
                    // продолжаем запрашивать следующие страницы до тех пор, пока приходят новые книги.
                    boolean hasMoreByMeta = (totalExpected > 0 && accumulated.size() < totalExpected);
                    boolean hasMoreByNewItems = (countOnPage > 0 && newlyAdded > 0);
                    if ((hasMoreByMeta || hasMoreByNewItems) && page < 40) {
                        loadLibraryPage(page + 1, accumulated, seenUuids, callback);
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
                } finally {
                    response.close();
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
        public final int charOffset;
        public final String opfItemId;
        public final String rawCfi;

        public CfiPosition(int chapterIndex, int paragraphIndex, String rawCfi) {
            this(chapterIndex, paragraphIndex, 0, "", rawCfi);
        }

        public CfiPosition(int chapterIndex, int paragraphIndex, int charOffset, String opfItemId, String rawCfi) {
            this.chapterIndex = chapterIndex;
            this.paragraphIndex = paragraphIndex;
            this.charOffset = Math.max(0, charOffset);
            this.opfItemId = opfItemId != null ? opfItemId : "";
            this.rawCfi = rawCfi;
        }
    }

    public static CfiPosition parseCfiPosition(String cfi) {
        if (cfi == null || cfi.trim().isEmpty()) {
            return new CfiPosition(0, 0, 0, "", "");
        }
        String clean = cfi.trim();
        String[] parts = clean.split("!");
        String spinePart = parts[0];
        String elemPart = parts.length > 1 ? parts[1] : "";

        int chapterIndex = 0;
        String opfItemId = "";
        Matcher mSpine = Pattern.compile("/(\\d+)(?:\\[([^\\]]+)\\])?").matcher(spinePart);
        int lastSpineNum = -1;
        while (mSpine.find()) {
            try {
                lastSpineNum = Integer.parseInt(mSpine.group(1));
                String idGroup = mSpine.group(2);
                if (idGroup != null && !idGroup.isEmpty()) {
                    opfItemId = idGroup;
                }
            } catch (Exception ignored) {}
        }
        if (lastSpineNum > 0) {
            chapterIndex = Math.max(0, (lastSpineNum / 2) - 1);
        }

        int paragraphIndex = 0;
        int charOffset = 0;
        if (!elemPart.isEmpty()) {
            int colonIdx = elemPart.indexOf(':');
            String elemPath = (colonIdx >= 0) ? elemPart.substring(0, colonIdx) : elemPart;
            if (colonIdx >= 0) {
                String offsetPart = elemPart.substring(colonIdx + 1).replaceAll("[^0-9]", "");
                if (!offsetPart.isEmpty()) {
                    try {
                        charOffset = Integer.parseInt(offsetPart);
                    } catch (Exception ignored) {}
                }
            }
            Matcher mElem = Pattern.compile("/(\\d+)").matcher(elemPath);
            int targetElemNum = -1;
            int stepCount = 0;
            while (mElem.find()) {
                try {
                    int n = Integer.parseInt(mElem.group(1));
                    stepCount++;
                    // Шаг 1 (/4) — <body>; чётные шаги (>= 2) — DOM-элементы внутри тела
                    // (берём последний чётный шаг, чтобы не застревать на обёртке <div class="section">/2)
                    if (stepCount >= 2 && (n % 2 == 0 || targetElemNum < 0)) {
                        targetElemNum = n;
                    }
                } catch (Exception ignored) {}
            }
            if (targetElemNum > 0) {
                paragraphIndex = Math.max(0, (targetElemNum / 2) - 1);
            }
        }

        return new CfiPosition(chapterIndex, paragraphIndex, charOffset, opfItemId, clean);
    }

    public static String buildCanonicalCfi(int originalOpfSpineIndex, String opfItemId, int paragraphDomIndex, int charOffset) {
        int spineCfiIndex = Math.max(2, (Math.max(0, originalOpfSpineIndex) + 1) * 2);
        int pCfiIndex = Math.max(2, (Math.max(0, paragraphDomIndex) + 1) * 2);
        int safeChar = Math.max(0, charOffset);
        if (opfItemId != null && !opfItemId.isEmpty()) {
            return String.format(Locale.US, "epubcfi(/6/%d[%s]!/4/%d/1:%d)",
                    spineCfiIndex, opfItemId, pCfiIndex, safeChar);
        }
        return String.format(Locale.US, "epubcfi(/6/%d!/4/%d:%d)",
                spineCfiIndex, pCfiIndex, safeChar);
    }

    public static String buildCanonicalCfiEnd(int originalOpfSpineIndex, String opfItemId, int paragraphDomIndex, int charOffset) {
        return buildCanonicalCfi(originalOpfSpineIndex, opfItemId, paragraphDomIndex, Math.max(0, charOffset) + 100);
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

        // 7. Защита от искажения 100% (только если позиция чтения пуста при state="reading" и chapter==0 и ровно >=99.9%)
        if (result.percent >= 99.9 && "reading".equalsIgnoreCase(state) && result.chapterIndex == 0 && !hasPosObject && !hasLastRead) {
            result.percent = 0.0;
        }
        if ("finished".equalsIgnoreCase(state) || "read".equalsIgnoreCase(state) || "completed".equalsIgnoreCase(state) || "done".equalsIgnoreCase(state)) {
            result.percent = 100.0;
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
        boolean isUploadedUserBook = card.optBoolean("is_user_book", false)
                || card.optBoolean("uploaded", false)
                || bObj.optBoolean("is_user_book", false)
                || bObj.optBoolean("uploaded", false)
                || "user_book".equalsIgnoreCase(card.optString("kind", ""))
                || "user_book".equalsIgnoreCase(bObj.optString("kind", ""))
                || !bObj.optString("file_url", "").isEmpty()
                || !card.optString("file_url", "").isEmpty();
        String mappedShelf;
        if (isUploadedUserBook) {
            mappedShelf = "user_books";
        } else if ("finished".equalsIgnoreCase(state) || "read".equalsIgnoreCase(state) || "completed".equalsIgnoreCase(state) || "done".equalsIgnoreCase(state) || pr.percent >= 99.5) {
            mappedShelf = "done";
            if (pr.percent >= 99.0) pr.percent = 100.0;
        } else if ("reading".equalsIgnoreCase(state) || (pr.percent > 0.0 && pr.percent < 99.5)) {
            mappedShelf = "reading";
        } else if ("want_to_read".equalsIgnoreCase(state) || "to_read".equalsIgnoreCase(state) || "planned".equalsIgnoreCase(state)) {
            mappedShelf = "to_read";
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

        AudioMeta audioMeta = extractLinkedAudioMeta(card, bObj);
        if (audioMeta != null && audioMeta.hasAudio()) {
            book.setLinkedAudioUuid(audioMeta.audioUuid);
            book.setAudioPercent(audioMeta.percent);
            book.setAudioUpdatedAt(audioMeta.updatedAt);
        }

        return book;
    }

    private static class AudioMeta {
        String audioUuid = "";
        double percent = 0.0;
        long updatedAt = 0L;

        boolean hasAudio() {
            return audioUuid != null && !audioUuid.isEmpty() && !"null".equalsIgnoreCase(audioUuid);
        }
    }

    private AudioMeta extractLinkedAudioMeta(JSONObject card, JSONObject bObj) {
        try {
            AudioMeta meta = new AudioMeta();
            JSONObject audioObj = null;
            JSONObject[] containers = new JSONObject[] { bObj, card };
            String[] objKeys = new String[] { "linked_audiobook", "linkedAudioBook", "audio_book", "audiobook" };
            for (JSONObject c : containers) {
                if (c == null) continue;
                for (String k : objKeys) {
                    JSONObject sub = c.optJSONObject(k);
                    if (sub != null) {
                        audioObj = sub;
                        break;
                    }
                }
                if (audioObj != null) break;
            }
            // Если сама карточка является текстовой книгой, аудиообъект внутри card.optJSONObject("audiobook")
            // или связанные поля audiobook_uuid / linked_audio_uuid могут присутствовать опционально.
            if (audioObj != null) {
                meta.audioUuid = audioObj.optString("uuid", audioObj.optString("id", ""));
                ParsedProgress apr = extractProgress(audioObj, audioObj.optJSONObject("progress"));
                meta.percent = apr.percent;
                meta.updatedAt = apr.timestamp;
            }
            if (!meta.hasAudio()) {
                String[] idKeys = new String[] { "linked_audio_uuid", "audiobook_uuid", "linked_audiobook_uuid" };
                for (JSONObject c : containers) {
                    if (c == null) continue;
                    for (String k : idKeys) {
                        String val = c.optString(k, "");
                        if (val != null && !val.isEmpty() && !"null".equalsIgnoreCase(val)) {
                            meta.audioUuid = val;
                            break;
                        }
                    }
                    if (meta.hasAudio()) break;
                }
            }
            if (meta.hasAudio() && meta.percent <= 0.0) {
                for (JSONObject c : containers) {
                    if (c == null) continue;
                    JSONObject ap = c.optJSONObject("audio_progress");
                    if (ap != null) {
                        double p = ap.optDouble("percent", ap.optDouble("progress", 0.0));
                        if (p > 0.0 && p <= 1.0) p *= 100.0;
                        meta.percent = Math.max(0.0, Math.min(100.0, p));
                        long ts = ap.optLong("updated_at", 0L);
                        if (ts <= 0L) ts = parseIsoTimestamp(ap.optString("updated_at", null));
                        if (ts > 0 && ts < 100000000000L) ts *= 1000L;
                        if (ts > 0L) meta.updatedAt = ts;
                        break;
                    }
                }
            }
            return meta.hasAudio() ? meta : null;
        } catch (Throwable ignored) {
            return null;
        }
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

    private JSONObject findLibraryCardObjectForBookSync(String bookUuid) {
        if (bookUuid == null || bookUuid.isEmpty()) return null;
        java.util.Set<String> seenCardKeys = new java.util.HashSet<>();
        for (int pass = 0; pass <= 15; pass++) {
            String url = (pass == 0)
                    ? (BASE_URL + "/profile/library_cards?book_uuid=" + bookUuid + "&per_page=100")
                    : (BASE_URL + "/profile/library_cards?per_page=100&page=" + pass);
            Response resp = null;
            try {
                Request req = createAuthRequestBuilder(url).get().build();
                resp = httpClient.newCall(req).execute();
                if (!resp.isSuccessful() || resp.body() == null) {
                    if (pass == 0) continue;
                    break;
                }
                String body = resp.body().string();
                if (body.isEmpty()) {
                    if (pass == 0) continue;
                    break;
                }
                JSONObject json = new JSONObject(body);
                JSONArray arr = json.optJSONArray("library_cards");
                if (arr == null) arr = json.optJSONArray("objects");
                if (arr == null) arr = json.optJSONArray("items");
                if (arr == null || arr.length() == 0) {
                    if (pass == 0) continue;
                    break;
                }
                JSONObject matched = findMatchingLibraryCardInArray(arr, bookUuid);
                if (matched != null) {
                    return matched;
                }
                if (pass > 0) {
                    int newOnPage = 0;
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject c = arr.optJSONObject(i);
                        if (c == null) continue;
                        String k = c.optString("uuid", c.optString("id", ""));
                        if (!k.isEmpty() && seenCardKeys.add(k)) {
                            newOnPage++;
                        }
                    }
                    if (newOnPage == 0) {
                        break;
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (resp != null) {
                    try { resp.close(); } catch (Exception ignored) {}
                }
            }
        }
        return null;
    }

    /**
     * Синхронный поиск UUID карточки библиотеки (library_card.uuid) для заданной книги.
     */
    private String findLibraryCardUuidForBookSync(String bookUuid) {
        JSONObject matched = findLibraryCardObjectForBookSync(bookUuid);
        if (matched != null) {
            String resolved = extractLibraryCardId(matched, matched.optJSONObject("book"), bookUuid);
            if (!resolved.isEmpty()) {
                return resolved;
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
                    // 1. Попытка запроса точной позиции чтения через GraphQL Gateway с автоматическим fallback
                    String gqlCfiStart = null;
                    try {
                        JSONObject gqlBody = new JSONObject();
                        gqlBody.put("operationName", "GetTextbookProgress");
                        gqlBody.put("query", GQL_GET_PROGRESS);
                        JSONObject vars = new JSONObject();
                        vars.put("uuid", bookUuid);
                        gqlBody.put("variables", vars);

                        String respStr = executeGraphQLWithFallbackSync(gqlBody);
                        if (respStr != null) {
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
                    } catch (Exception ignored) {}

                    // 2. Запрос карточки библиотеки для извлечения процента и cardId (с постраничным поиском)
                    double cardPercent = 0.0;
                    String cardId = "";
                    long cardTs = 0L;
                    int cardChapter = 0;
                    int cardParagraph = 0;
                    AudioMeta linkedAudioMeta = null;

                    try {
                        JSONObject card = findLibraryCardObjectForBookSync(bookUuid);
                        if (card != null) {
                            JSONObject bObj = card.optJSONObject("book");
                            ParsedProgress pr = extractProgress(card, bObj);
                            cardPercent = pr.percent;
                            cardChapter = pr.chapterIndex;
                            cardParagraph = pr.paragraphIndex;
                            cardTs = pr.timestamp;
                            cardId = extractLibraryCardId(card, bObj, bookUuid);
                            linkedAudioMeta = extractLinkedAudioMeta(card, bObj);
                        }
                    } catch (Exception ignored) {}

                    // 3. Синтез наиболее точного прогресса
                    int finalChapter = cardChapter;
                    int finalParagraph = cardParagraph;
                    if (gqlCfiStart != null && !gqlCfiStart.isEmpty()) {
                        CfiPosition pos = parseCfiPosition(gqlCfiStart);
                        finalChapter = pos.chapterIndex;
                        finalParagraph = pos.paragraphIndex;
                    }

                    if (gqlCfiStart != null || cardPercent > 0.0 || !cardId.isEmpty()) {
                        // Не подставляем System.currentTimeMillis() при отсутствии серверного времени — оставляем 0L,
                        // чтобы облачный прогресс без таймштампа не перезаписывал свежее локальное чтение
                        final ReadingProgress progress = new ReadingProgress(
                                bookUuid, cardPercent, finalChapter, finalParagraph, 0,
                                cardTs > 0 ? cardTs : 0L
                        );
                        if (!cardId.isEmpty()) {
                            progress.setLibraryCardId(cardId);
                        }
                        if (gqlCfiStart != null && !gqlCfiStart.isEmpty()) {
                            progress.setCfi(gqlCfiStart);
                        }
                        if (linkedAudioMeta != null && linkedAudioMeta.hasAudio()) {
                            progress.setLinkedAudioUuid(linkedAudioMeta.audioUuid);
                            progress.setAudioPercent(linkedAudioMeta.percent);
                            progress.setAudioUpdatedAt(linkedAudioMeta.updatedAt);
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
     * Загрузка списка пользовательских загруженных файлов (Модуль 8: User Uploads).
     * Последовательно опрашивает реальные эндпоинты Bookmate v5:
     * /profile/uploads -> /profile/books -> /users/{login}/books (с поддержкой постраничной пагинации).
     */
    public void fetchUserUploadedBooks(final ApiCallback<List<Book>> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Book> collected = new ArrayList<>();
                    java.util.Set<String> seenUuids = new java.util.HashSet<>();

                    // 1. Основной эндпоинт личных загрузок Bookmate v5: /profile/uploads
                    boolean uploadsEndpointWorked = fetchPaginatedUserBooksSync(BASE_URL + "/profile/uploads", false, collected, seenUuids);

                    // 2. Если /profile/uploads пуст или вернул ошибку, проверяем /profile/books (фильтруя личные/загруженные файлы)
                    if (!uploadsEndpointWorked || collected.isEmpty()) {
                        fetchPaginatedUserBooksSync(BASE_URL + "/profile/books", true, collected, seenUuids);
                    }

                    // 3. Также пробуем /users/{login}/books, если логин профиля известен
                    if (collected.isEmpty()) {
                        String login = ensureUserLoginSync();
                        if (login != null && !login.isEmpty()) {
                            String encLogin = java.net.URLEncoder.encode(login, "UTF-8").replace("+", "%20");
                            fetchPaginatedUserBooksSync(BASE_URL + "/users/" + encLogin + "/books", true, collected, seenUuids);
                        }
                    }

                    postSuccess(callback, collected);
                } catch (Exception e) {
                    Log.e(TAG, "Error loading user uploaded books", e);
                    postError(callback, "Ошибка загрузки «Мои файлы»: " + e.getMessage());
                }
            }
        }).start();
    }

    private boolean fetchPaginatedUserBooksSync(String baseEndpoint, boolean filterPersonalOnly,
                                                List<Book> out, java.util.Set<String> seenUuids) {
        boolean anyOk = false;
        for (int page = 1; page <= 20; page++) {
            String url = baseEndpoint + "?per_page=100&page=" + page;
            Response resp = null;
            try {
                Request req = createAuthRequestBuilder(url).get().build();
                resp = httpClient.newCall(req).execute();
                if (!resp.isSuccessful()) {
                    return anyOk;
                }
                anyOk = true;
                String body = resp.body() != null ? resp.body().string() : "";
                List<Book> pageItems = parseUserUploadedBooksJson(body, filterPersonalOnly);
                int added = 0;
                for (Book b : pageItems) {
                    if (b != null && b.getUuid() != null && seenUuids.add(b.getUuid())) {
                        out.add(b);
                        added++;
                    }
                }
                if (pageItems.isEmpty() || added == 0) {
                    break;
                }
            } catch (Exception e) {
                return anyOk;
            } finally {
                if (resp != null) try { resp.close(); } catch (Exception ignored) {}
            }
        }
        return anyOk;
    }

    private String ensureUserLoginSync() {
        String cached = tokenStorage != null ? tokenStorage.getUserLogin() : "";
        if (cached != null && !cached.trim().isEmpty()) {
            return cached.trim();
        }
        Response resp = null;
        try {
            Request req = createAuthRequestBuilder(BASE_URL + "/profile").get().build();
            resp = httpClient.newCall(req).execute();
            if (resp.isSuccessful() && resp.body() != null) {
                String body = resp.body().string();
                JSONObject json = new JSONObject(body);
                JSONObject u = json.optJSONObject("user");
                if (u == null) u = json;
                String login = u.optString("login", u.optString("username", ""));
                if (login != null && !login.trim().isEmpty() && !"null".equalsIgnoreCase(login)) {
                    if (tokenStorage != null) {
                        tokenStorage.setUserLogin(login.trim());
                    }
                    return login.trim();
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (resp != null) try { resp.close(); } catch (Exception ignored) {}
        }
        return "";
    }

    public List<Book> parseUserUploadedBooksJson(String jsonBody) throws org.json.JSONException {
        return parseUserUploadedBooksJson(jsonBody, false);
    }

    public List<Book> parseUserUploadedBooksJson(String jsonBody, boolean filterPersonalOnly) throws org.json.JSONException {
        List<Book> result = new ArrayList<>();
        if (jsonBody == null || jsonBody.trim().isEmpty()) return result;
        JSONObject json = new JSONObject(jsonBody);
        JSONArray arr = json.optJSONArray("uploads");
        if (arr == null) arr = json.optJSONArray("user_books");
        if (arr == null) arr = json.optJSONArray("documents");
        if (arr == null) arr = json.optJSONArray("files");
        if (arr == null) arr = json.optJSONArray("books");
        if (arr == null) arr = json.optJSONArray("items");
        if (arr == null) arr = json.optJSONArray("library_cards");
        if (arr == null) return result;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            JSONObject bObj = item.optJSONObject("book");
            String fileUrl = item.optString("file_url", item.optString("download_url", item.optString("document_url", "")));
            if (fileUrl.isEmpty() && bObj != null) {
                fileUrl = bObj.optString("file_url", bObj.optString("download_url", bObj.optString("document_url", "")));
            }
            if ("null".equalsIgnoreCase(fileUrl)) fileUrl = "";

            boolean looksPersonal = !fileUrl.isEmpty()
                    || item.optBoolean("is_user_book", false)
                    || item.optBoolean("uploaded", false)
                    || item.optBoolean("is_private", false)
                    || "user_book".equalsIgnoreCase(item.optString("kind", ""))
                    || "upload".equalsIgnoreCase(item.optString("kind", ""))
                    || (bObj != null && (bObj.optBoolean("is_user_book", false)
                    || bObj.optBoolean("uploaded", false)
                    || bObj.optBoolean("is_private", false)
                    || "user_book".equalsIgnoreCase(bObj.optString("kind", ""))));

            if (filterPersonalOnly && !looksPersonal) {
                continue;
            }

            Book book = parseBookFromCard(item);
            if (book == null) continue;
            book.setShelfType("user_books");
            if (!fileUrl.isEmpty()) {
                book.setFileUrl(fileUrl);
            }
            if (book.getAuthor() == null || book.getAuthor().trim().isEmpty()) {
                book.setAuthor("Личный файл");
            }
            result.add(book);
        }
        return result;
    }

    /**
     * Прямое синхронное скачивание книги в текущем потоке (для фонового пакетного загрузчика).
     */
    public DownloadResult downloadBookEpubSync(final String bookUuid, final File destFile) {
        return downloadBookEpubSync(bookUuid, null, destFile);
    }

    public DownloadResult downloadBookEpubSync(final String bookUuid, final String directFileUrl, final File destFile) {
        // 0. Если это пользовательский файл (user_books) с прямым file_url — качаем напрямую
        if (directFileUrl != null && !directFileUrl.trim().isEmpty()) {
            DownloadResult directRes = tryDownloadSync(directFileUrl.trim(), destFile);
            if (directRes.success) return directRes;
        }
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
        downloadBookEpub(bookUuid, null, destFile, callback);
    }

    public void downloadBookEpub(final String bookUuid, final String directFileUrl, final File destFile, final ApiCallback<File> callback) {
        if (directFileUrl != null && !directFileUrl.trim().isEmpty()) {
            Request directReq = createAuthRequestBuilder(directFileUrl.trim())
                    .header("Accept", "*/*")
                    .get()
                    .build();
            httpClient.newCall(directReq).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    attemptDownloadBookEpub(bookUuid, destFile, callback, false);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        response.close();
                        attemptDownloadBookEpub(bookUuid, destFile, callback, false);
                        return;
                    }
                    saveResponseBodyToFile(response, destFile, callback, new Runnable() {
                        @Override
                        public void run() {
                            attemptDownloadBookEpub(bookUuid, destFile, callback, false);
                        }
                    });
                }
            });
            return;
        }
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

                        String respStr = executeGraphQLWithFallbackSync(gqlBody);
                        if (respStr != null) {
                            JSONObject rj = new JSONObject(respStr);
                            if (rj.has("data") && !rj.isNull("data")) {
                                anySuccess = true;
                            } else if (rj.has("errors")) {
                                Log.w(TAG, "GraphQL ProgressUpdated returned errors: " + rj.optJSONArray("errors"));
                            }
                        }
                    } catch (Exception ignored) {}

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

    public static class CatalogCategory {
        public final String uuid;
        public final String title;

        public CatalogCategory(String uuid, String title) {
            this.uuid = uuid;
            this.title = title;
        }
    }

    public static final String DEFAULT_CATALOG_SHELF_UUID = "ySg3hPn4";
    public static final String AUTO_POPULAR_SHELF_UUID = "__popular__";
    public static final String TOPIC_CATEGORY_PREFIX = "topic:";
    private static final long CATALOG_CATEGORIES_CACHE_TTL_MS = 30L * 60L * 1000L;
    private volatile List<CatalogCategory> cachedCatalogCategories;
    private volatile long cachedCatalogCategoriesAtMs = 0L;

    private static boolean isExcludedCatalogTopicSlug(String slug) {
        if (slug == null) return false;
        String s = slug.toLowerCase(Locale.US);
        return s.contains("podkast") || s.contains("podcast")
                || s.contains("audiospektakl") || s.contains("audiobook")
                || s.contains("rasskazchik") || s.contains("yandexart")
                || s.contains("sinhronizirovano") || s.contains("inoagent")
                || "luch-ru".equals(s) || "kniga-kino-ru".equals(s)
                || "predreliz-ru".equals(s) || "serial-ru".equals(s)
                || "biblii-ru".equals(s) || "normativno-pravovye-akty-ru".equals(s)
                || "antichnaya-literatura-ru".equals(s) || "drevnerusskaya-literatura-ru".equals(s)
                || "svyaschennye-teksty-ru".equals(s) || "nezavisimye-avtory-ru".equals(s)
                || "podrostkovyy-non-fikshn-ru".equals(s) || "spravochniki-ru".equals(s)
                || "kollektsionirovanie-ru".equals(s) || "sobraniya-sochineniy-ru".equals(s)
                || "transport-ru".equals(s) || "ezoterika-ru".equals(s);
    }

    /**
     * Возвращает полный актуальный список категорий, жанров и динамических подборок каталога Яндекс Книг:
     * /popular_searches/ru + /topics/PNZcAxtW/bookshelves + все актуальные темы/поджанры из /api/v5/catalog.
     */
    public void getCatalogCategories(final ApiCallback<List<CatalogCategory>> callback) {
        List<CatalogCategory> cached = cachedCatalogCategories;
        if (cached != null && !cached.isEmpty()
                && (System.currentTimeMillis() - cachedCatalogCategoriesAtMs) < CATALOG_CATEGORIES_CACHE_TTL_MS) {
            postSuccess(callback, new ArrayList<>(cached));
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<CatalogCategory> categories = new ArrayList<>();
                java.util.Set<String> seenUuids = new java.util.HashSet<>();

                // 1. Первая рубрика — актуальные рекомендации с витрины /api/v5/popular_searches/ru
                addCategoryUnique(categories, seenUuids, AUTO_POPULAR_SHELF_UUID, "Популярное");

                // 2. Динамические редакционные подборки напрямую из /api/v5/popular_searches/ru
                Response popResp = null;
                try {
                    Request req = createAuthRequestBuilder(BASE_URL + "/popular_searches/ru").get().build();
                    popResp = httpClient.newCall(req).execute();
                    if (popResp.isSuccessful() && popResp.body() != null) {
                        JSONObject json = new JSONObject(popResp.body().string());
                        JSONObject pop = json.optJSONObject("popular_searches");
                        if (pop != null) {
                            JSONArray res = pop.optJSONArray("resources");
                            if (res != null) {
                                for (int i = 0; i < res.length(); i++) {
                                    JSONObject item = res.optJSONObject(i);
                                    if (item == null) continue;
                                    String url = item.optString("url", "");
                                    String title = item.optString("title", "").replace('\u00A0', ' ').trim();
                                    if (url.contains("/bookshelves/") && !title.isEmpty()) {
                                        String[] parts = url.split("/bookshelves/");
                                        if (parts.length > 1) {
                                            String u = parts[1].trim();
                                            if (u.contains("?")) u = u.substring(0, u.indexOf('?'));
                                            addCategoryUnique(categories, seenUuids, u, title);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (popResp != null) try { popResp.close(); } catch (Exception ignored) {}
                }

                // 3. Актуальные тематические полки каталога (/api/v5/topics/PNZcAxtW/bookshelves)
                Response shelvesResp = null;
                try {
                    Request req = createAuthRequestBuilder(BASE_URL + "/topics/PNZcAxtW/bookshelves?per_page=30").get().build();
                    shelvesResp = httpClient.newCall(req).execute();
                    if (shelvesResp.isSuccessful() && shelvesResp.body() != null) {
                        JSONObject json = new JSONObject(shelvesResp.body().string());
                        JSONArray shelves = json.optJSONArray("bookshelves");
                        if (shelves != null) {
                            for (int i = 0; i < shelves.length(); i++) {
                                JSONObject sh = shelves.optJSONObject(i);
                                if (sh == null) continue;
                                if (sh.optInt("books_count", 1) <= 0) continue;
                                String u = sh.optString("uuid", "").trim();
                                String t = sh.optString("title", "").replace('\u00A0', ' ').trim();
                                addCategoryUnique(categories, seenUuids, u, t);
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (shelvesResp != null) try { shelvesResp.close(); } catch (Exception ignored) {}
                }

                // 4. Все актуальные жанры, рубрики и поджанры напрямую с сервера (/api/v5/catalog)
                Response catResp = null;
                try {
                    Request req = createAuthRequestBuilder(BASE_URL + "/catalog").get().build();
                    catResp = httpClient.newCall(req).execute();
                    if (catResp.isSuccessful() && catResp.body() != null) {
                        JSONObject json = new JSONObject(catResp.body().string());
                        JSONArray topics = json.optJSONArray("topics");
                        if (topics != null) {
                            java.util.Set<String> seenTopicTitles = new java.util.HashSet<>();
                            for (int i = 0; i < topics.length(); i++) {
                                JSONObject tObj = topics.optJSONObject(i);
                                if (tObj == null) continue;
                                String slug = tObj.optString("slug", "").trim();
                                if (isExcludedCatalogTopicSlug(slug)) continue;
                                String tUuid = tObj.optString("uuid", "").trim();
                                String tTitle = tObj.optString("title", "").replace('\u00A0', ' ').trim();
                                JSONArray subtopics = tObj.optJSONArray("subtopics");
                                boolean hasSub = subtopics != null && subtopics.length() > 0;
                                boolean hasLegacyBg = !tObj.isNull("background");
                                if (hasLegacyBg && !hasSub) continue;
                                if (!hasSub && "proza-ru".equalsIgnoreCase(slug)) continue;
                                if (!tUuid.isEmpty() && !tTitle.isEmpty()) {
                                    String normTitle = tTitle.toLowerCase(Locale.US);
                                    if (seenTopicTitles.add(normTitle)) {
                                        addCategoryUnique(categories, seenUuids, TOPIC_CATEGORY_PREFIX + tUuid, tTitle);
                                    }
                                    if (hasSub) {
                                        for (int j = 0; j < subtopics.length(); j++) {
                                            JSONObject sub = subtopics.optJSONObject(j);
                                            if (sub == null) continue;
                                            String sSlug = sub.optString("slug", "").trim();
                                            if (isExcludedCatalogTopicSlug(sSlug)) continue;
                                            String sUuid = sub.optString("uuid", "").trim();
                                            String sTitle = sub.optString("title", "").replace('\u00A0', ' ').trim();
                                            if (!sUuid.isEmpty() && !sTitle.isEmpty()) {
                                                addCategoryUnique(categories, seenUuids,
                                                        TOPIC_CATEGORY_PREFIX + sUuid, "  — " + sTitle);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (catResp != null) try { catResp.close(); } catch (Exception ignored) {}
                }

                // 5. Резервные проверенные полки API v5 на случай недоступности сети/части эндпоинтов
                addCategoryUnique(categories, seenUuids, "ySg3hPn4", "Только в Яндекс Книгах");
                addCategoryUnique(categories, seenUuids, "ACX4QPxo", "Главные новинки");
                addCategoryUnique(categories, seenUuids, "L6asjavK", "Подборка редакции");
                addCategoryUnique(categories, seenUuids, "oRidVXsN", "Лучшие триллеры XXI века");
                addCategoryUnique(categories, seenUuids, "JUzuIVzn", "Нобелевская премия");
                addCategoryUnique(categories, seenUuids, "XLLytQg2", "Славянское фэнтези");
                addCategoryUnique(categories, seenUuids, "byugcjMZ", "Новинки Яндекс Книг");

                if (categories.size() > 8) {
                    cachedCatalogCategories = new ArrayList<>(categories);
                    cachedCatalogCategoriesAtMs = System.currentTimeMillis();
                }
                postSuccess(callback, categories);
            }
        }).start();
    }

    private static void addCategoryUnique(List<CatalogCategory> list, java.util.Set<String> seen, String uuid, String title) {
        if (uuid == null || uuid.trim().isEmpty() || title == null || title.trim().isEmpty()) return;
        if (seen.add(uuid.trim())) {
            list.add(new CatalogCategory(uuid.trim(), title.trim()));
        }
    }

    public void getCatalogBooksByShelf(String shelfUuid, final ApiCallback<List<Book>> callback) {
        if (shelfUuid == null || shelfUuid.trim().isEmpty() || AUTO_POPULAR_SHELF_UUID.equals(shelfUuid.trim())) {
            getRecommendations(callback);
            return;
        }
        String trimmed = shelfUuid.trim();
        if (trimmed.startsWith(TOPIC_CATEGORY_PREFIX)) {
            String topicUuid = trimmed.substring(TOPIC_CATEGORY_PREFIX.length()).trim();
            if (topicUuid.isEmpty()) {
                getRecommendations(callback);
            } else {
                loadTopicBooks(topicUuid, callback);
            }
            return;
        }
        loadBookshelfByUuid(trimmed, true, callback);
    }

    private void loadTopicBooks(final String topicUuid, final ApiCallback<List<Book>> callback) {
        String endpoint = BASE_URL + "/topics/" + topicUuid + "/books?per_page=50";
        Request request = createAuthRequestBuilder(endpoint).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                loadTopicFirstBookshelfFallback(topicUuid, callback);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    response.close();
                    loadTopicFirstBookshelfFallback(topicUuid, callback);
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
                    if (books.isEmpty()) {
                        loadTopicFirstBookshelfFallback(topicUuid, callback);
                        return;
                    }
                    postSuccess(callback, books);
                } catch (Exception e) {
                    loadTopicFirstBookshelfFallback(topicUuid, callback);
                }
            }
        });
    }

    private void loadTopicFirstBookshelfFallback(final String topicUuid, final ApiCallback<List<Book>> callback) {
        String endpoint = BASE_URL + "/topics/" + topicUuid + "/bookshelves?per_page=10";
        Request request = createAuthRequestBuilder(endpoint).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                loadBookshelfByUuid(DEFAULT_CATALOG_SHELF_UUID, false, callback);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    response.close();
                    loadBookshelfByUuid(DEFAULT_CATALOG_SHELF_UUID, false, callback);
                    return;
                }
                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONArray shelves = json.optJSONArray("bookshelves");
                    if (shelves != null) {
                        for (int i = 0; i < shelves.length(); i++) {
                            JSONObject sh = shelves.optJSONObject(i);
                            if (sh == null) continue;
                            String shUuid = sh.optString("uuid", "").trim();
                            if (!shUuid.isEmpty() && sh.optInt("books_count", 1) > 0) {
                                loadBookshelfByUuid(shUuid, true, callback);
                                return;
                            }
                        }
                    }
                    loadBookshelfByUuid(DEFAULT_CATALOG_SHELF_UUID, false, callback);
                } catch (Exception e) {
                    loadBookshelfByUuid(DEFAULT_CATALOG_SHELF_UUID, false, callback);
                }
            }
        });
    }

    /**
     * Загрузка рекомендаций и популярных книг каталога Яндекс Книг.
     */
    public void getRecommendations(final ApiCallback<List<Book>> callback) {
        String endpoint = BASE_URL + "/popular_searches/ru";
        Request request = createAuthRequestBuilder(endpoint).get().build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                loadBookshelfByUuid(DEFAULT_CATALOG_SHELF_UUID, false, callback);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    response.close();
                    loadBookshelfByUuid(DEFAULT_CATALOG_SHELF_UUID, false, callback);
                    return;
                }

                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    JSONObject pop = json.optJSONObject("popular_searches");
                    String shelfUuid = DEFAULT_CATALOG_SHELF_UUID;
                    if (pop != null) {
                        JSONArray res = pop.optJSONArray("resources");
                        if (res != null && res.length() > 0) {
                            for (int i = 0; i < res.length(); i++) {
                                JSONObject item = res.getJSONObject(i);
                                String url = item.optString("url", "");
                                if (url.contains("/bookshelves/")) {
                                    String[] parts = url.split("/bookshelves/");
                                    if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                                        String cand = parts[1].trim();
                                        if (cand.contains("?")) cand = cand.substring(0, cand.indexOf('?'));
                                        if (!cand.isEmpty()) {
                                            shelfUuid = cand;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    loadBookshelfByUuid(shelfUuid, true, callback);
                } catch (Exception e) {
                    loadBookshelfByUuid(DEFAULT_CATALOG_SHELF_UUID, false, callback);
                }
            }
        });
    }

    private void loadBookshelfByUuid(final String shelfUuid, final boolean allowFallback, final ApiCallback<List<Book>> callback) {
        String endpoint = BASE_URL + "/bookshelves/" + shelfUuid + "/books?per_page=50";
        Request request = createAuthRequestBuilder(endpoint).get().build();

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
                    if (allowFallback && !DEFAULT_CATALOG_SHELF_UUID.equals(shelfUuid)) {
                        loadBookshelfByUuid(DEFAULT_CATALOG_SHELF_UUID, false, callback);
                        return;
                    }
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
                    if (books.isEmpty() && allowFallback && !DEFAULT_CATALOG_SHELF_UUID.equals(shelfUuid)) {
                        loadBookshelfByUuid(DEFAULT_CATALOG_SHELF_UUID, false, callback);
                        return;
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
            Request request = createGraphQLRequestBuilder(PRIMARY_GRAPHQL_URL)
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

    /**
     * Загружает логин/имя текущего авторизованного профиля для отображения в меню и фильтрации личных данных.
     */
    public void fetchCurrentUserLogin(final ApiCallback<String> callback) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String login = ensureUserLoginSync();
                postSuccess(callback, login != null ? login : "");
            }
        }).start();
    }

    /**
     * Загрузка ЛИЧНЫХ облачных цитат пользователя для конкретной книги (Модуль 13).
     * НИКОГДА не обращается к публичной ленте /books/{uuid}/quotes, чтобы исключить утечку чужих цитат!
     */
    public void getBookQuotes(final String bookUuid, final ApiCallback<List<Quote>> callback) {
        if (bookUuid == null || bookUuid.isEmpty()) {
            postSuccess(callback, new ArrayList<Quote>());
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Quote> mine = new ArrayList<>();
                    java.util.Set<String> seen = new java.util.HashSet<>();

                    // 1. Приоритетно запрашиваем личные цитаты профиля (/profile/quotes)
                    boolean profileEndpointOk = fetchPersonalQuotesPagedSync(
                            BASE_URL + "/profile/quotes?book_uuid=" + bookUuid, bookUuid, mine, seen);

                    // 2. Fallback: эндпоинт личных цитат текущего пользователя (/users/{login}/quotes)
                    if (!profileEndpointOk) {
                        String login = ensureUserLoginSync();
                        if (login != null && !login.isEmpty()) {
                            String encLogin = java.net.URLEncoder.encode(login, "UTF-8").replace("+", "%20");
                            fetchPersonalQuotesPagedSync(
                                    BASE_URL + "/users/" + encLogin + "/quotes", bookUuid, mine, seen);
                        }
                    }

                    postSuccess(callback, mine);
                } catch (Exception e) {
                    postError(callback, "Ошибка загрузки личных цитат: " + e.getMessage());
                }
            }
        }).start();
    }

    private boolean fetchPersonalQuotesPagedSync(String baseUrl, String targetBookUuid,
                                                 List<Quote> out, java.util.Set<String> seenKeys) {
        String myLogin = tokenStorage != null ? tokenStorage.getUserLogin() : "";
        boolean anyOk = false;
        for (int page = 1; page <= 10; page++) {
            String sep = baseUrl.contains("?") ? "&" : "?";
            String url = baseUrl + sep + "per_page=100&page=" + page;
            Response resp = null;
            try {
                Request req = createAuthRequestBuilder(url).get().build();
                resp = httpClient.newCall(req).execute();
                if (!resp.isSuccessful()) {
                    return anyOk;
                }
                anyOk = true;
                String body = resp.body() != null ? resp.body().string() : "";
                List<Quote> parsed = parseQuotesJson(body, targetBookUuid, myLogin, true);
                JSONObject json = body.trim().isEmpty() ? null : new JSONObject(body);
                JSONArray rawArr = json != null ? (json.optJSONArray("quotes") != null ? json.optJSONArray("quotes") : json.optJSONArray("items")) : null;
                int rawLen = rawArr != null ? rawArr.length() : 0;
                for (Quote q : parsed) {
                    String key = (q.quoteUuid != null && !q.quoteUuid.isEmpty())
                            ? q.quoteUuid
                            : (q.chapterIndex + "|" + q.text);
                    if (seenKeys.add(key)) {
                        out.add(q);
                    }
                }
                if (rawLen < 20) {
                    break;
                }
            } catch (Exception e) {
                return anyOk;
            } finally {
                if (resp != null) try { resp.close(); } catch (Exception ignored) {}
            }
        }
        return anyOk;
    }

    public List<Quote> parseQuotesJson(String body, String bookUuid) throws org.json.JSONException {
        return parseQuotesJson(body, bookUuid, null, false);
    }

    public List<Quote> parseQuotesJson(String body, String bookUuid, String expectedUserLogin, boolean filterByBookAndOwner) throws org.json.JSONException {
        List<Quote> result = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) return result;
        JSONObject json = new JSONObject(body);
        JSONArray arr = json.optJSONArray("quotes");
        if (arr == null) arr = json.optJSONArray("items");
        if (arr == null) return result;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;

            if (filterByBookAndOwner) {
                // Строгая фильтрация чужих цитат: отсекаем любые записи других пользователей
                if (obj.has("is_mine") && !obj.optBoolean("is_mine", true)) continue;
                if (obj.has("mine") && !obj.optBoolean("mine", true)) continue;
                JSONObject uObj = obj.optJSONObject("user");
                if (uObj != null && expectedUserLogin != null && !expectedUserLogin.isEmpty()) {
                    String qLogin = uObj.optString("login", uObj.optString("username", ""));
                    if (!qLogin.isEmpty() && !expectedUserLogin.equalsIgnoreCase(qLogin)) {
                        continue;
                    }
                }

                // Фильтрация по целевой книге (т.к. /profile/quotes или /users/{login}/quotes могут вернуть цитаты всех книг)
                String itemBookUuid = obj.optString("item_uuid", obj.optString("book_uuid", ""));
                if (itemBookUuid.isEmpty()) {
                    JSONObject bObj = obj.optJSONObject("book");
                    if (bObj != null) itemBookUuid = bObj.optString("uuid", "");
                }
                if (bookUuid != null && !bookUuid.isEmpty() && !itemBookUuid.isEmpty() && !bookUuid.equals(itemBookUuid)) {
                    continue;
                }
            }

            String text = obj.optString("content", obj.optString("text", ""));
            if (text == null || text.trim().isEmpty() || "null".equals(text)) continue;
            Quote q = new Quote();
            q.bookUuid = bookUuid;
            q.quoteUuid = obj.optString("uuid", obj.optString("id", ""));
            q.text = text.trim();
            String note = obj.optString("note", obj.optString("comment", ""));
            q.note = (note != null && !"null".equalsIgnoreCase(note)) ? note : "";
            q.cfi = obj.optString("cfi", "");
            CfiPosition pos = parseCfiPosition(q.cfi);
            q.chapterIndex = (pos != null && pos.chapterIndex >= 0) ? pos.chapterIndex : 0;
            q.startOffset = Math.max(0, obj.optInt("start_offset", 0));
            q.endOffset = Math.max(q.startOffset, obj.optInt("end_offset", q.startOffset + q.text.length()));
            long ts = obj.optLong("created_at", 0L);
            if (ts <= 0L) ts = parseIsoTimestamp(obj.optString("created_at", null));
            if (ts > 0L && ts < 1000000000000L) ts = ts * 1000L;
            q.createdAt = ts > 0 ? ts : System.currentTimeMillis();
            q.synced = true;
            result.add(q);
        }
        return result;
    }

    /**
     * Отправка новой цитаты в облако Яндекса (Модуль 13).
     */
    public void createBookQuote(final Quote quote, final ApiCallback<String> callback) {
        if (quote == null || quote.bookUuid == null || quote.bookUuid.isEmpty()) {
            postError(callback, "Empty quote");
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("content", quote.text != null ? quote.text : "");
            if (quote.cfi != null && !quote.cfi.isEmpty()) {
                payload.put("cfi", quote.cfi);
            }
            if (quote.note != null && !quote.note.trim().isEmpty()) {
                payload.put("note", quote.note.trim());
            }
            RequestBody reqBody = RequestBody.create(MediaType.parse("application/json; charset=utf-8"), payload.toString());
            Request req = createAuthRequestBuilder(BASE_URL + "/books/" + quote.bookUuid + "/quotes")
                    .post(reqBody)
                    .build();
            httpClient.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    postError(callback, e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    try {
                        if (!response.isSuccessful()) {
                            postError(callback, "HTTP " + response.code());
                            return;
                        }
                        String respStr = response.body() != null ? response.body().string() : "";
                        String qUuid = "";
                        if (!respStr.isEmpty()) {
                            JSONObject root = new JSONObject(respStr);
                            JSONObject qObj = root.optJSONObject("quote");
                            if (qObj != null) {
                                qUuid = qObj.optString("uuid", qObj.optString("id", ""));
                            } else {
                                qUuid = root.optString("uuid", root.optString("id", ""));
                            }
                        }
                        postSuccess(callback, qUuid);
                    } catch (Exception e) {
                        postError(callback, e.getMessage());
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
     * Удаление цитаты из облака Яндекса.
     */
    public void deleteBookQuote(final String quoteUuid, final ApiCallback<Boolean> callback) {
        if (quoteUuid == null || quoteUuid.isEmpty()) {
            postSuccess(callback, true);
            return;
        }
        Request req = createAuthRequestBuilder(BASE_URL + "/quotes/" + quoteUuid)
                .delete()
                .build();
        httpClient.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                postError(callback, e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (response.isSuccessful() || response.code() == 404) {
                        postSuccess(callback, true);
                    } else {
                        postError(callback, "HTTP " + response.code());
                    }
                } finally {
                    response.close();
                }
            }
        });
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
