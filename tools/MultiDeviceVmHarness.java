package com.onyx.vmtest;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ScrollView;

import com.onyx.yandexbooks.core.api.models.Book;
import com.onyx.yandexbooks.core.api.models.Chapter;
import com.onyx.yandexbooks.core.api.models.ReadingProgress;
import com.onyx.yandexbooks.core.eink.EinkScrollView;
import com.onyx.yandexbooks.core.eink.EinkWebView;
import com.onyx.yandexbooks.core.eink.EpdController;
import com.onyx.yandexbooks.core.eink.HardwareKeyHandler;
import com.onyx.yandexbooks.core.epub.EpubParser;
import com.onyx.yandexbooks.core.typography.TeXHyphenator;
import com.onyx.yandexbooks.core.typography.TextPaginator;
import com.onyx.yandexbooks.core.typography.TypographyConfig;

import java.io.File;
import java.util.List;

/**
 * Физический стенд исполнения скомпилированного байткода (.class) в виртуальной машине Java
 * с полной эмуляцией подсистем Android 4.2.2 (API 17, Onyx Boox Darwin 1, Rockchip RK3026)
 * и Android 4.4.4 (API 19, Onyx Boox Darwin 3, Darwin 5, Darwin 6, Freescale i.MX6).
 */
public class MultiDeviceVmHarness {

    public static class DeviceProfile {
        public final String name;
        public final int apiLevel;
        public final String soc;
        public final int width;
        public final int height;
        public final float density;
        public final boolean hasRockchipEpd;
        public final boolean hasOnyxSdkEpd;

        public DeviceProfile(String name, int apiLevel, String soc, int width, int height,
                             float density, boolean hasRockchipEpd, boolean hasOnyxSdkEpd) {
            this.name = name;
            this.apiLevel = apiLevel;
            this.soc = soc;
            this.width = width;
            this.height = height;
            this.density = density;
            this.hasRockchipEpd = hasRockchipEpd;
            this.hasOnyxSdkEpd = hasOnyxSdkEpd;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            throw new IllegalArgumentException("Usage: MultiDeviceVmHarness <path-to-test.epub>");
        }
        File epubFile = new File(args[0]);
        if (!epubFile.exists()) {
            throw new IllegalStateException("Test EPUB file not found: " + epubFile.getAbsolutePath());
        }

        DeviceProfile[] profiles = new DeviceProfile[] {
                new DeviceProfile("Onyx Boox Darwin 1 (Android 4.2.2 Jelly Bean)", 17, "Rockchip RK3026", 758, 1024, 1.33f, true, false),
                new DeviceProfile("Onyx Boox Darwin 3 (Android 4.4.4 KitKat)", 19, "Freescale i.MX6", 758, 1024, 1.33f, false, true),
                new DeviceProfile("Onyx Boox Darwin 5 (Android 4.4.4 KitKat)", 19, "Freescale i.MX6", 758, 1024, 1.33f, false, true),
                new DeviceProfile("Onyx Boox Darwin 6 (Android 4.4.4 KitKat, Carta Plus)", 19, "Freescale i.MX6", 1072, 1448, 1.88f, false, true)
        };

        System.out.println("======================================================================");
        System.out.println("⚙️ ЗАПУСК ФИЗИЧЕСКОГО БАЙТКОД-СТЕНДА JVM ПО 4 ВИРТУАЛЬНЫМ МАШИНАМ");
        System.out.println("======================================================================");

        for (DeviceProfile profile : profiles) {
            runProfileSuite(profile, epubFile);
        }

        System.out.println("\n======================================================================");
        System.out.println("✅ ВСЕ 4 ВИРТУАЛЬНЫЕ МАШИНЫ (DARWIN 1, 3, 5, 6) ПРОШЛИ 100% ПРОВЕРОК!");
        System.out.println("======================================================================");
    }

    private static void runProfileSuite(DeviceProfile p, File epubFile) throws Exception {
        System.out.println("\n----------------------------------------------------------------------");
        System.out.println("🖥️ ВМ: " + p.name + " | API " + p.apiLevel + " | SoC: " + p.soc + " | Экран: " + p.width + "x" + p.height + " (density=" + p.density + ")");
        System.out.println("----------------------------------------------------------------------");

        // Настраиваем глобальные параметры эмулятора Android View/EPD под конкретный профиль устройства
        View.SIMULATED_API_LEVEL = p.apiLevel;
        View.ROCKCHIP_EPD_ENABLED = p.hasRockchipEpd;
        com.onyx.android.sdk.device.EpdController.ONYX_SDK_ENABLED = p.hasOnyxSdkEpd;

        Context context = new Context();

        // 1. Демонстрация бага стандартного ScrollView на Android 4.2.2 (API 17) и проверка EinkScrollView
        verifyScrollViewDirtyRects(context, p);

        // 2. Проверка EinkWebView (окно считывания QR/ШК авторизации, скролл и боковые кнопки)
        verifyEinkWebViewDirtyRectsAndPaging(context, p);

        // 3. Проверка EpdController.configureEinkListView (списки книг на полках и оглавление TOC)
        verifyEinkListViewConfiguration(context, p);

        // 4. Проверка физического парсера EPUB (EpubParser.class) на реальном EPUB-файле
        EpubParser.ParseResult epubResult = verifyEpubParserBytecode(epubFile, p);

        // 5. Проверка пагинатора (TextPaginator.class), переносов (TeXHyphenator.class) и типографики (TypographyConfig.class)
        verifyPaginatorAndHyphenator(epubResult, p);

        // 6. Проверка обработчика аппаратных боковых кнопок (HardwareKeyHandler.class)
        verifyHardwareKeys(p);
    }

    private static void verifyScrollViewDirtyRects(Context ctx, DeviceProfile p) {
        ViewGroup decorView = new ViewGroup(ctx);
        decorView.setBounds(0, 0, p.width, p.height);

        // А) Проверяем поведение стандартного ScrollView при скролле (80, 260) на API 17
        ScrollView rawScroll = new ScrollView(ctx);
        rawScroll.setBounds(0, 0, p.width, p.height);
        View rawContent = new View(ctx);
        rawContent.setBounds(0, 0, p.width, p.height * 2);
        rawScroll.addView(rawContent);
        decorView.addView(rawScroll);

        rawScroll.scrollTo(80, 260);
        decorView.clearDirty();
        rawScroll.invalidate();
        Rect rawDirty = decorView.getDirtyRect();

        if (p.apiLevel == 17) {
            int expectedW = p.width - 80;
            int expectedH = p.height - 260;
            if (rawDirty.right != expectedW || rawDirty.bottom != expectedH) {
                throw new AssertionError("Ожидалось воспроизведение бага API 17 на сыром ScrollView, получено: " + rawDirty);
            }
            System.out.println("  [1a] Воспроизведение бага API 17 на сыром ScrollView при scroll=(80,260): "
                    + "обновился только угол [" + rawDirty.left + "," + rawDirty.top + " - " + rawDirty.right + "," + rawDirty.bottom + "] ("
                    + String.format("%.1f%%", (rawDirty.width() * rawDirty.height() * 100.0) / (p.width * p.height)) + " экрана)");
        }

        // Б) Проверяем наш скомпилированный EinkScrollView.class в тех же условиях
        decorView.removeAllViews();
        EinkScrollView einkScroll = new EinkScrollView(ctx);
        einkScroll.setBounds(0, 0, p.width, p.height);
        View einkContent = new View(ctx);
        einkContent.setBounds(0, 0, p.width, p.height * 2);
        einkScroll.addView(einkContent);
        decorView.addView(einkScroll);

        int[][] scrollPositions = new int[][] { {0, 180}, {0, 420}, {80, 260} };
        for (int[] pos : scrollPositions) {
            int sx = pos[0];
            int sy = pos[1];
            einkScroll.scrollTo(sx, sy);

            // Тест 1: invalidate() без параметров
            decorView.clearDirty();
            einkScroll.invalidate();
            assertFullScreenDirty(decorView.getDirtyRect(), p, "EinkScrollView.invalidate() @ (" + sx + "," + sy + ")");

            // Тест 2: invalidate(Rect)
            decorView.clearDirty();
            einkScroll.invalidate(new Rect(10, 10, 50, 50));
            assertFullScreenDirty(decorView.getDirtyRect(), p, "EinkScrollView.invalidate(Rect) @ (" + sx + "," + sy + ")");

            // Тест 3: invalidate(l, t, r, b)
            decorView.clearDirty();
            einkScroll.invalidate(0, 0, 100, 100);
            assertFullScreenDirty(decorView.getDirtyRect(), p, "EinkScrollView.invalidate(l,t,r,b) @ (" + sx + "," + sy + ")");

            // Тест 4: Завершение касания пальцем (MotionEvent.ACTION_UP)
            decorView.clearDirty();
            View.resetEpdCounters();
            com.onyx.android.sdk.device.EpdController.sdkRefreshCount = 0;
            ctx.clearBroadcasts();
            einkScroll.onTouchEvent(new MotionEvent(MotionEvent.ACTION_UP));
            assertFullScreenDirty(decorView.getDirtyRect(), p, "EinkScrollView.onTouchEvent(ACTION_UP)");
            assertEpdHardwareRefreshTriggered(ctx, p, "EinkScrollView ACTION_UP");
        }

        // Тест 5: Дискретное листание боковыми кнопками pageScrollEink(down=true / false)
        einkScroll.scrollTo(0, 0);
        decorView.clearDirty();
        View.resetEpdCounters();
        com.onyx.android.sdk.device.EpdController.sdkRefreshCount = 0;
        ctx.clearBroadcasts();
        boolean pagedDown = einkScroll.pageScrollEink(true);
        int expectedStep = (int) (p.height * 0.85f);
        if (!pagedDown || einkScroll.getScrollY() != expectedStep) {
            throw new AssertionError("Сбой EinkScrollView.pageScrollEink(true): scrollY=" + einkScroll.getScrollY() + ", expected=" + expectedStep);
        }
        assertFullScreenDirty(decorView.getDirtyRect(), p, "EinkScrollView.pageScrollEink(true)");
        assertEpdHardwareRefreshTriggered(ctx, p, "EinkScrollView.pageScrollEink(true)");

        boolean pagedUp = einkScroll.pageScrollEink(false);
        if (!pagedUp || einkScroll.getScrollY() != 0) {
            throw new AssertionError("Сбой EinkScrollView.pageScrollEink(false): scrollY=" + einkScroll.getScrollY());
        }

        System.out.println("  [1b] EinkScrollView.class (Выбор шрифта, Вход, Карточка книги, Обновление): 100% экрана [" + p.width + "x" + p.height + "] перерисовано: OK");
    }

    private static void verifyEinkWebViewDirtyRectsAndPaging(Context ctx, DeviceProfile p) {
        ViewGroup decorView = new ViewGroup(ctx);
        decorView.setBounds(0, 0, p.width, p.height);

        EinkWebView webView = new EinkWebView(ctx);
        int webH = p.height - 56;
        webView.setBounds(0, 56, p.width, p.height);
        decorView.addView(webView);

        // Проверяем скролл окна считывания QR/ШК авторизации
        webView.scrollTo(80, 260);

        decorView.clearDirty();
        webView.invalidate();
        Rect d1 = decorView.getDirtyRect();
        if (d1.width() != p.width || d1.height() < webH) {
            throw new AssertionError("EinkWebView.invalidate() не покрыл область экрана: " + d1);
        }

        // Проверяем движение пальцем (ACTION_MOVE) и отпускание (ACTION_UP) в окне авторизации
        decorView.clearDirty();
        webView.onTouchEvent(new MotionEvent(MotionEvent.ACTION_MOVE));
        assertFullScreenDirty(decorView.getDirtyRect(), p, "EinkWebView.onTouchEvent(ACTION_MOVE)");

        decorView.clearDirty();
        View.resetEpdCounters();
        com.onyx.android.sdk.device.EpdController.sdkRefreshCount = 0;
        ctx.clearBroadcasts();
        webView.onTouchEvent(new MotionEvent(MotionEvent.ACTION_UP));
        assertFullScreenDirty(decorView.getDirtyRect(), p, "EinkWebView.onTouchEvent(ACTION_UP)");
        assertEpdHardwareRefreshTriggered(ctx, p, "EinkWebView ACTION_UP");

        // Проверяем дискретное листание боковыми кнопками в окне WebView
        webView.scrollTo(0, 0);
        decorView.clearDirty();
        View.resetEpdCounters();
        com.onyx.android.sdk.device.EpdController.sdkRefreshCount = 0;
        ctx.clearBroadcasts();
        boolean moved = webView.pageScrollEink(true);
        int expectedStep = (int) (webH * 0.75f);
        if (!moved || webView.getScrollY() != expectedStep) {
            throw new AssertionError("Сбой EinkWebView.pageScrollEink(true): scrollY=" + webView.getScrollY() + ", expected=" + expectedStep);
        }
        assertFullScreenDirty(decorView.getDirtyRect(), p, "EinkWebView.pageScrollEink(true)");
        assertEpdHardwareRefreshTriggered(ctx, p, "EinkWebView.pageScrollEink(true)");

        System.out.println("  [2]  EinkWebView.class (Окно ШК/QR авторизации): 100% перерисовка при скролле и кнопках + сброс E-Ink: OK");
    }

    private static void verifyEinkListViewConfiguration(Context ctx, DeviceProfile p) {
        ViewGroup decorView = new ViewGroup(ctx);
        decorView.setBounds(0, 0, p.width, p.height);

        AbsListView listView = new AbsListView(ctx);
        listView.setBounds(0, 0, p.width, p.height);
        decorView.addView(listView);

        EpdController.configureEinkListView(ctx, listView);

        if (listView.isScrollingCacheEnabled() || listView.isDrawingCacheEnabled()) {
            throw new AssertionError("configureEinkListView не отключил scrollingCache / drawingCache!");
        }
        if (listView.getOverScrollMode() != View.OVER_SCROLL_NEVER) {
            throw new AssertionError("configureEinkListView не установил OVER_SCROLL_NEVER!");
        }

        listView.scrollTo(0, 240);
        decorView.clearDirty();
        listView.simulateScroll(3, 8, 25);
        assertFullScreenDirty(decorView.getDirtyRect(), p, "AbsListView.onScroll");

        decorView.clearDirty();
        View.resetEpdCounters();
        com.onyx.android.sdk.device.EpdController.sdkRefreshCount = 0;
        ctx.clearBroadcasts();
        listView.simulateScrollStateChanged(AbsListView.OnScrollListener.SCROLL_STATE_IDLE);
        assertFullScreenDirty(decorView.getDirtyRect(), p, "AbsListView.SCROLL_STATE_IDLE");
        assertEpdHardwareRefreshTriggered(ctx, p, "AbsListView SCROLL_STATE_IDLE");

        System.out.println("  [3]  EpdController.configureEinkListView (Полки и Оглавление): кэши отключены, 100% перерисовка + полный рефреш: OK");
    }

    private static EpubParser.ParseResult verifyEpubParserBytecode(File epubFile, DeviceProfile p) {
        EpubParser.ParseResult result = EpubParser.parseEpubFull(epubFile);
        if (result == null || result.chapters == null || result.chapters.size() < 3) {
            throw new AssertionError("EpubParser.parseEpubFull вернул недостаточно глав: "
                    + (result == null ? "null" : result.chapters.size()));
        }
        EpubParser.ChapterData coverCh = result.chapters.get(0);
        if (!"Обложка".equals(coverCh.title) || !coverCh.textContent.contains("[IMG:")) {
            throw new AssertionError("Некорректная глава обложки: " + coverCh.title + " / " + coverCh.textContent);
        }
        EpubParser.ChapterData ch1 = result.chapters.get(1);
        if (!ch1.textContent.contains("Чтение на E-Ink дисплее комфортно для глаз")) {
            throw new AssertionError("Текст главы 1 поврежден при декодировании UTF-8: " + ch1.textContent);
        }
        EpubParser.ChapterData ch2 = result.chapters.get(2);
        if (!ch2.textContent.contains("[IMG:OPS/images/chart1.png]")) {
            throw new AssertionError("Маркер иллюстрации не извлечен в главе 2: " + ch2.textContent);
        }
        if (result.tocTree == null || result.tocTree.size() < 2) {
            throw new AssertionError("Дерево оглавления TOC пусто!");
        }
        EpubParser.TocNode root1 = result.tocTree.get(0);
        if (!root1.hasChildren || root1.children.isEmpty()) {
            throw new AssertionError("Подглава оглавления не привязана к родителю!");
        }
        EpubParser.TocNode sub1 = root1.children.get(0);
        if (sub1.charOffset <= 0) {
            throw new AssertionError("Якорное смещение charOffset для #step1 должно быть > 0, получено: " + sub1.charOffset);
        }

        System.out.println("  [4]  EpubParser.class (физический разбор EPUB): глав=" + result.chapters.size()
                + ", обложка=" + result.coverImagePath + ", узлов TOC=" + result.tocTree.size()
                + ", anchor charOffset=" + sub1.charOffset + ": OK");
        return result;
    }

    private static void verifyPaginatorAndHyphenator(EpubParser.ParseResult epubResult, DeviceProfile p) {
        TeXHyphenator hyphenator = TeXHyphenator.getInstance();
        List<Integer> pts = hyphenator.getHyphenationPoints("электронная");
        if (pts == null || pts.isEmpty()) {
            throw new AssertionError("TeXHyphenator не нашел точки переноса для слова 'электронная'!");
        }

        TypographyConfig config = new TypographyConfig();
        String[] fonts = new String[] { "literata", "charis_sil", "pt_serif", "pt_sans", "serif", "sans-serif", "monospace" };
        for (String font : fonts) {
            config.setFontFamily(font);
            if (!font.equals(config.getFontFamily())) {
                throw new AssertionError("Сбой установки шрифта в TypographyConfig: " + font);
            }
        }

        Paint paint = new Paint();
        paint.setTextSize(18f * p.density);
        TextPaginator paginator = new TextPaginator();

        // Пагинируем главу с текстом и главу с иллюстрацией под физическое разрешение экрана устройства
        List<TextPaginator.Page> ch1Pages = paginator.paginate(epubResult.chapters.get(1).textContent, p.width, p.height, paint, config);
        List<TextPaginator.Page> ch2Pages = paginator.paginate(epubResult.chapters.get(2).textContent, p.width, p.height, paint, config);

        if (ch1Pages == null || ch1Pages.isEmpty() || ch2Pages == null || ch2Pages.size() < 2) {
            throw new AssertionError("Сбой пагинации TextPaginator на " + p.name);
        }

        Book b = new Book("uuid-123", "Тестовая книга", "Автор", null);
        b.setShelfType("reading");
        b.setPercent(42.5);
        b.setDownloaded(true);
        Chapter ch = new Chapter("0", "uuid-123", 0, "Глава 1");
        ReadingProgress rp = new ReadingProgress("uuid-123", 42.5, 1, 3, 120, System.currentTimeMillis());

        if (!b.isDownloaded() || rp.getChapterIndex() != 1 || ch.getChapterIndex() != 0) {
            throw new AssertionError("Сбой моделей данных Book / Chapter / ReadingProgress!");
        }

        System.out.println("  [5]  TextPaginator.class + TeXHyphenator.class + TypographyConfig.class (7 шрифтов, точек переноса="
                + pts.size() + ", страниц гл.1=" + ch1Pages.size() + ", гл.2 с картинкой=" + ch2Pages.size() + "): OK");
    }

    private static void verifyHardwareKeys(DeviceProfile p) {
        final int[] counters = new int[3];
        HardwareKeyHandler handler = new HardwareKeyHandler(new HardwareKeyHandler.OnKeyActionListener() {
            @Override
            public void onPageForward() { counters[0]++; }
            @Override
            public void onPageBackward() { counters[1]++; }
            @Override
            public void onLongPressRefresh() { counters[2]++; }
        });

        handler.handleKeyDown(KeyEvent.KEYCODE_PAGE_DOWN, new KeyEvent(0, false));
        handler.handleKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, new KeyEvent(0, false));
        handler.handleKeyDown(KeyEvent.KEYCODE_PAGE_UP, new KeyEvent(0, false));
        handler.handleKeyDown(KeyEvent.KEYCODE_VOLUME_UP, new KeyEvent(0, false));

        // Автоповтор при удержании кнопки (repeatCount > 0) должен игнорироваться
        handler.handleKeyDown(KeyEvent.KEYCODE_PAGE_DOWN, new KeyEvent(4, false));
        handler.handleKeyDown(KeyEvent.KEYCODE_PAGE_UP, new KeyEvent(2, false));

        // Долгое нажатие -> принудительный сброс экрана E-Ink
        handler.handleKeyDown(KeyEvent.KEYCODE_PAGE_DOWN, new KeyEvent(0, true));

        if (counters[0] != 2 || counters[1] != 2 || counters[2] != 1) {
            throw new AssertionError("Сбой HardwareKeyHandler: forward=" + counters[0]
                    + ", backward=" + counters[1] + ", refresh=" + counters[2]);
        }
        System.out.println("  [6]  HardwareKeyHandler.class (боковые кнопки Darwin + защита от залипания + long-press E-Ink refresh): OK");
    }

    private static void assertFullScreenDirty(Rect dirty, DeviceProfile p, String contextLabel) {
        if (dirty.left != 0 || dirty.top != 0 || dirty.right != p.width || dirty.bottom != p.height) {
            throw new AssertionError("Сбой полной перерисовки в " + contextLabel + " на " + p.name
                    + ": ожидалось [0,0 - " + p.width + "," + p.height + "], получено ["
                    + dirty.left + "," + dirty.top + " - " + dirty.right + "," + dirty.bottom + "]");
        }
    }

    private static void assertEpdHardwareRefreshTriggered(Context ctx, DeviceProfile p, String contextLabel) {
        if (p.hasRockchipEpd && View.rockchipEpdFullCount <= 0) {
            throw new AssertionError("На " + p.name + " (RK3026) не вызван View.requestEpdMode(EPD_FULL) в " + contextLabel);
        }
        if (p.hasOnyxSdkEpd && com.onyx.android.sdk.device.EpdController.sdkRefreshCount <= 0) {
            throw new AssertionError("На " + p.name + " (i.MX6) не вызван Onyx SDK EpdController в " + contextLabel);
        }
        if (ctx.getBroadcastCount() < 6) {
            throw new AssertionError("Не отправлены системные интенты обновления экрана Onyx в " + contextLabel);
        }
    }
}
