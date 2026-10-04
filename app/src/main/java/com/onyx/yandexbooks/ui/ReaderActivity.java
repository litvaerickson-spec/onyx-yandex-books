package com.onyx.yandexbooks.ui;

import android.app.Activity;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.onyx.yandexbooks.R;
import com.onyx.yandexbooks.core.api.YandexBooksApiClient;
import com.onyx.yandexbooks.core.api.models.Book;
import com.onyx.yandexbooks.core.api.models.Chapter;
import com.onyx.yandexbooks.core.api.models.ReadingProgress;
import com.onyx.yandexbooks.core.auth.TokenStorage;
import com.onyx.yandexbooks.core.eink.EpdController;
import com.onyx.yandexbooks.core.eink.HardwareKeyHandler;
import com.onyx.yandexbooks.core.storage.CacheManager;
import com.onyx.yandexbooks.core.storage.DatabaseHelper;
import com.onyx.yandexbooks.core.sync.SyncManager;
import com.onyx.yandexbooks.core.typography.TextPaginator;
import com.onyx.yandexbooks.core.typography.TypographyConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран чтения с нативным Canvas-рендерером, аппаратным управлением Darwin и типографикой AlReader.
 */
public class ReaderActivity extends Activity {

    private ReaderCanvasView readerCanvas;
    private View menuOverlay;
    private TextView fontSizeLabel;
    private Button btnFontDecrease, btnFontIncrease, btnToggleHyphenation, btnToggleContrast, btnToggleMargins, btnForceRefresh;

    private String bookUuid;
    private String bookTitle;
    private List<Chapter> chapters = new ArrayList<>();
    private int currentChapterIndex = 0;
    private List<TextPaginator.Page> currentPages = new ArrayList<>();
    private int currentPageIndex = 0;
    private int pageTurnCounter = 0;

    private TypographyConfig typographyConfig;
    private TextPaginator paginator;
    private HardwareKeyHandler keyHandler;
    private CacheManager cacheManager;
    private SyncManager syncManager;
    private DatabaseHelper dbHelper;
    private YandexBooksApiClient apiClient;
    private com.onyx.yandexbooks.core.storage.AppSettings appSettings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        bookUuid = getIntent().getStringExtra("book_uuid");
        bookTitle = getIntent().getStringExtra("book_title");

        readerCanvas = (ReaderCanvasView) findViewById(R.id.reader_canvas);
        menuOverlay = findViewById(R.id.reader_menu_overlay);
        fontSizeLabel = (TextView) findViewById(R.id.font_size_label);
        btnFontDecrease = (Button) findViewById(R.id.btn_font_decrease);
        btnFontIncrease = (Button) findViewById(R.id.btn_font_increase);
        btnToggleHyphenation = (Button) findViewById(R.id.btn_toggle_hyphenation);
        btnToggleContrast = (Button) findViewById(R.id.btn_toggle_contrast);
        btnToggleMargins = (Button) findViewById(R.id.btn_toggle_margins);
        btnForceRefresh = (Button) findViewById(R.id.btn_force_refresh);

        appSettings = new com.onyx.yandexbooks.core.storage.AppSettings(this);
        typographyConfig = new TypographyConfig();
        int marginPx = appSettings.getMarginPaddingPx();
        typographyConfig.setPaddingLeftPx(marginPx);
        typographyConfig.setPaddingRightPx(marginPx);
        paginator = new TextPaginator();

        TokenStorage tokenStorage = new TokenStorage(this);
        apiClient = new YandexBooksApiClient(tokenStorage);
        cacheManager = new CacheManager(this, apiClient);
        dbHelper = DatabaseHelper.getInstance(this);
        syncManager = new SyncManager(this, apiClient);

        setupHardwareKeys();
        setupCanvasListeners();
        setupMenuControls();

        loadBookData();
    }

    private void setupHardwareKeys() {
        keyHandler = new HardwareKeyHandler(new HardwareKeyHandler.OnKeyActionListener() {
            @Override
            public void onPageForward() {
                flipPageForward();
            }

            @Override
            public void onPageBackward() {
                flipPageBackward();
            }

            @Override
            public void onLongPressRefresh() {
                forceEpdRefresh();
            }
        });
    }

    private void setupCanvasListeners() {
        readerCanvas.setInteractionListener(new ReaderCanvasView.OnReaderInteractionListener() {
            @Override
            public void onPageForward() {
                if (menuOverlay.getVisibility() == View.VISIBLE) {
                    menuOverlay.setVisibility(View.GONE);
                } else {
                    flipPageForward();
                }
            }

            @Override
            public void onPageBackward() {
                if (menuOverlay.getVisibility() == View.VISIBLE) {
                    menuOverlay.setVisibility(View.GONE);
                } else {
                    flipPageBackward();
                }
            }

            @Override
            public void onCenterTap() {
                int newVis = menuOverlay.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE;
                menuOverlay.setVisibility(newVis);
            }
        });
    }

    private void setupMenuControls() {
        fontSizeLabel.setText(String.valueOf(typographyConfig.getFontSizeSp()));

        btnFontIncrease.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                typographyConfig.setFontSizeSp(typographyConfig.getFontSizeSp() + 1);
                fontSizeLabel.setText(String.valueOf(typographyConfig.getFontSizeSp()));
                repaginateCurrentChapter();
            }
        });

        btnFontDecrease.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                typographyConfig.setFontSizeSp(typographyConfig.getFontSizeSp() - 1);
                fontSizeLabel.setText(String.valueOf(typographyConfig.getFontSizeSp()));
                repaginateCurrentChapter();
            }
        });

        btnToggleHyphenation.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean enabled = !typographyConfig.isHyphenationEnabled();
                typographyConfig.setHyphenationEnabled(enabled);
                btnToggleHyphenation.setText(enabled ? "Переносы: Вкл" : "Переносы: Выкл");
                repaginateCurrentChapter();
            }
        });

        btnToggleContrast.setText(typographyConfig.isBoldText() ? "Контраст: Высокий" : "Контраст: Обычный");
        btnToggleContrast.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean newBold = !typographyConfig.isBoldText();
                typographyConfig.setBoldText(newBold);
                btnToggleContrast.setText(newBold ? "Контраст: Высокий" : "Контраст: Обычный");
                readerCanvas.setTypographyConfig(typographyConfig);
                repaginateCurrentChapter();
            }
        });

        btnToggleMargins.setText(getMarginButtonText());
        btnToggleMargins.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleMarginMode();
            }
        });

        btnForceRefresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                forceEpdRefresh();
            }
        });

        Button btnCloseMenu = (Button) findViewById(R.id.btn_reader_close_menu);
        if (btnCloseMenu != null) {
            btnCloseMenu.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    menuOverlay.setVisibility(View.GONE);
                }
            });
        }

        Button btnToLibrary = (Button) findViewById(R.id.btn_reader_to_library);
        if (btnToLibrary != null) {
            btnToLibrary.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    finish();
                }
            });
        }
    }

    private String getMarginButtonText() {
        String mode = appSettings.getMarginMode();
        if (com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_MEDIUM.equalsIgnoreCase(mode)) {
            return "Поля: Средние";
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_WIDE.equalsIgnoreCase(mode)) {
            return "Поля: Широкие";
        } else {
            return "Поля: Узкие";
        }
    }

    private void cycleMarginMode() {
        String current = appSettings.getMarginMode();
        String nextMode;
        if (com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_NARROW.equalsIgnoreCase(current)) {
            nextMode = com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_MEDIUM;
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_MEDIUM.equalsIgnoreCase(current)) {
            nextMode = com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_WIDE;
        } else {
            nextMode = com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_NARROW;
        }
        appSettings.setMarginMode(nextMode);
        int px = appSettings.getMarginPaddingPx();
        typographyConfig.setPaddingLeftPx(px);
        typographyConfig.setPaddingRightPx(px);
        btnToggleMargins.setText(getMarginButtonText());
        readerCanvas.setTypographyConfig(typographyConfig);
        repaginateCurrentChapter();
    }

    private void loadBookData() {
        // Загрузка сохраненного локального прогресса из progress или books
        ReadingProgress progress = dbHelper.getProgress(bookUuid);
        if (progress != null && progress.getPercent() > 0) {
            currentChapterIndex = Math.max(0, progress.getChapterIndex());
            currentPageIndex = Math.max(0, progress.getPageIndex());
        } else {
            Book book = dbHelper.getBookByUuid(bookUuid);
            if (book != null && book.getPercent() > 0) {
                currentChapterIndex = Math.max(0, book.getCurrentChapterIndex());
                currentPageIndex = 0;
            }
        }

        cacheManager.ensureBookReady(bookUuid, bookTitle, new CacheManager.BookReadyCallback() {
            @Override
            public void onReady(List<Chapter> loadedChapters) {
                chapters = loadedChapters;
                if (!chapters.isEmpty()) {
                    resolveInitialPositionAndOpen();
                } else {
                    Toast.makeText(ReaderActivity.this, "Главы книги не найдены", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onError(String errorMessage) {
                chapters = dbHelper.getChapters(bookUuid);
                if (chapters != null && !chapters.isEmpty()) {
                    resolveInitialPositionAndOpen();
                } else {
                    Toast.makeText(ReaderActivity.this, "Книга не готова к чтению: " + errorMessage, Toast.LENGTH_LONG).show();
                }
            }
        });

        // Запрашиваем актуальный прогресс из облака Яндекса
        apiClient.getReadingProgress(bookUuid, new YandexBooksApiClient.ApiCallback<ReadingProgress>() {
            @Override
            public void onSuccess(final ReadingProgress cloudProgress) {
                if (cloudProgress == null || cloudProgress.getPercent() <= 0) return;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        applyCloudProgressIfNewer(cloudProgress);
                    }
                });
            }

            @Override
            public void onError(String errorMessage) {
                // Офлайн или сетевой сбой - продолжаем чтение по локальным данным
            }
        });
    }

    private void resolveInitialPositionAndOpen() {
        ReadingProgress p = dbHelper.getProgress(bookUuid);
        double percent = 0.0;
        int targetChapter = 0;
        int targetPage = 0;

        if (p != null && p.getPercent() > 0) {
            percent = p.getPercent();
            targetChapter = p.getChapterIndex();
            targetPage = p.getPageIndex();
        } else {
            Book b = dbHelper.getBookByUuid(bookUuid);
            if (b != null && b.getPercent() > 0) {
                percent = b.getPercent();
                targetChapter = b.getCurrentChapterIndex();
                targetPage = 0;
            }
        }

        if (percent > 0 && chapters != null && !chapters.isEmpty()) {
            if (targetChapter > 0 && targetChapter < chapters.size()) {
                currentChapterIndex = targetChapter;
            } else {
                int estChapter = (int) Math.floor((percent / 100.0) * chapters.size());
                if (estChapter >= chapters.size()) estChapter = chapters.size() - 1;
                currentChapterIndex = Math.max(0, estChapter);
            }
            currentPageIndex = Math.max(0, targetPage);
        }
        loadChapter(currentChapterIndex);
    }

    private void applyCloudProgressIfNewer(ReadingProgress cloudProgress) {
        if (chapters == null || chapters.isEmpty()) return;
        ReadingProgress local = dbHelper.getProgress(bookUuid);
        boolean isNewer = (local == null) ||
                (cloudProgress.getTimestamp() > local.getTimestamp()) ||
                (cloudProgress.getPercent() > (local.getPercent() + 0.5));

        if (isNewer) {
            dbHelper.saveProgress(cloudProgress);
            int targetCh = cloudProgress.getChapterIndex();
            if (targetCh <= 0 || targetCh >= chapters.size()) {
                targetCh = (int) Math.floor((cloudProgress.getPercent() / 100.0) * chapters.size());
                if (targetCh >= chapters.size()) targetCh = chapters.size() - 1;
            }
            targetCh = Math.max(0, targetCh);

            if (targetCh != currentChapterIndex || cloudProgress.getPageIndex() != currentPageIndex) {
                currentChapterIndex = targetCh;
                currentPageIndex = Math.max(0, cloudProgress.getPageIndex());
                loadChapter(currentChapterIndex);
                Toast.makeText(ReaderActivity.this, String.format("Синхронизировано: %.0f%% (Гл. %d)", cloudProgress.getPercent(), currentChapterIndex + 1), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void loadChapter(final int index) {
        if (chapters == null || chapters.isEmpty()) return;
        if (index < 0 || index >= chapters.size()) return;
        currentChapterIndex = index;
        final Chapter ch = chapters.get(index);

        String text = cacheManager.loadChapter(bookUuid, ch.getId());
        if (text != null && !text.isEmpty()) {
            displayChapterText(text, ch.getTitle());
        } else {
            Toast.makeText(ReaderActivity.this, "Текст главы не найден", Toast.LENGTH_SHORT).show();
        }
    }

    private void displayChapterText(String rawText, String title) {
        // Синхронизируем типографику с View перед замером строк
        readerCanvas.setTypographyConfig(typographyConfig);

        // ВАЖНО: Пагинатор использует точно такой же Paint и Typeface (Serif/Bold),
        // что гарантирует идеальное соответствие и исключает обрезку строк справа!
        Paint paint = readerCanvas.getTextPaint();

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        currentPages = paginator.paginate(rawText, screenWidth, screenHeight, paint, typographyConfig);

        if (currentPageIndex >= currentPages.size()) {
            currentPageIndex = 0;
        }

        renderCurrentPage();
    }

    private void repaginateCurrentChapter() {
        if (currentChapterIndex < chapters.size()) {
            String id = chapters.get(currentChapterIndex).getId();
            String text = cacheManager.loadChapter(bookUuid, id);
            if (text != null) {
                displayChapterText(text, chapters.get(currentChapterIndex).getTitle());
            }
        }
    }

    private double calculateCurrentGlobalPercent() {
        if (chapters == null || chapters.isEmpty()) return 0.0;
        double chapterWeight = 100.0 / chapters.size();
        double inChapterPercent = (currentPages == null || currentPages.isEmpty()) ? 0.0 : ((double) (currentPageIndex + 1) / currentPages.size());
        double percent = (currentChapterIndex * chapterWeight) + (inChapterPercent * chapterWeight);
        return Math.min(100.0, Math.max(0.0, percent));
    }

    private void renderCurrentPage() {
        if (!currentPages.isEmpty() && currentPageIndex < currentPages.size()) {
            String title = (chapters != null && currentChapterIndex < chapters.size()) ? chapters.get(currentChapterIndex).getTitle() : bookTitle;
            double percent = calculateCurrentGlobalPercent();
            int totalChapters = chapters != null ? chapters.size() : 1;
            readerCanvas.setPage(currentPages.get(currentPageIndex), currentPages.size(), title, currentChapterIndex, totalChapters, percent);
        }

        // Проверка на цикл очистки остаточных артефактов E-Ink
        pageTurnCounter++;
        if (pageTurnCounter >= typographyConfig.getEpdFullRefreshInterval()) {
            pageTurnCounter = 0;
            forceEpdRefresh();
        }

        saveProgress();
    }

    private void flipPageForward() {
        if (currentPageIndex + 1 < currentPages.size()) {
            currentPageIndex++;
            renderCurrentPage();
        } else if (currentChapterIndex + 1 < chapters.size()) {
            // Переход к следующей главе
            currentChapterIndex++;
            currentPageIndex = 0;
            loadChapter(currentChapterIndex);
        }
    }

    private void flipPageBackward() {
        if (currentPageIndex > 0) {
            currentPageIndex--;
            renderCurrentPage();
        } else if (currentChapterIndex > 0) {
            // Переход к предыдущей главе
            currentChapterIndex--;
            loadChapter(currentChapterIndex);
        }
    }

    private void forceEpdRefresh() {
        EpdController.requestFullRefresh(this, readerCanvas);
    }

    private void saveProgress() {
        double percent = calculateCurrentGlobalPercent();

        ReadingProgress progress = new ReadingProgress(
                bookUuid,
                percent,
                currentChapterIndex,
                0,
                currentPageIndex,
                System.currentTimeMillis()
        );

        // Реальная синхронизация с облаком Яндекса в фоновом режиме
        syncManager.saveAndSyncProgress(progress, true, null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProgress();
        syncManager.flushOfflineQueue();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyHandler.handleKeyDown(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
