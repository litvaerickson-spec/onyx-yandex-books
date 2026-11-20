package com.onyx.yandexbooks.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import java.io.File;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.text.TextUtils;

import com.onyx.yandexbooks.core.api.models.Quote;
import java.text.SimpleDateFormat;
import java.util.Date;

import com.onyx.yandexbooks.R;
import com.onyx.yandexbooks.core.api.YandexBooksApiClient;
import com.onyx.yandexbooks.core.api.models.Book;
import com.onyx.yandexbooks.core.api.models.Chapter;
import com.onyx.yandexbooks.core.api.models.ReadingProgress;
import com.onyx.yandexbooks.core.auth.TokenStorage;
import com.onyx.yandexbooks.core.eink.EinkListView;
import com.onyx.yandexbooks.core.eink.EinkScrollView;
import com.onyx.yandexbooks.core.eink.EpdController;
import com.onyx.yandexbooks.core.eink.HardwareKeyHandler;
import com.onyx.yandexbooks.core.epub.EpubFormatting;
import com.onyx.yandexbooks.core.epub.EpubParser;
import com.onyx.yandexbooks.core.storage.CacheManager;
import com.onyx.yandexbooks.core.storage.DatabaseHelper;
import com.onyx.yandexbooks.core.sync.SyncManager;
import com.onyx.yandexbooks.core.typography.FontHelper;
import com.onyx.yandexbooks.core.ui.EinkNotice;
import com.onyx.yandexbooks.core.typography.TextPaginator;
import com.onyx.yandexbooks.core.typography.TypographyConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Экран чтения в стиле Onyx NeoReader с нативным Canvas-рендерером,
 * аппаратным управлением Darwin, интерактивным слайдером и типографикой AlReader.
 */
public class ReaderActivity extends Activity {

    private static final String TAG = "ReaderActivity";

    private ReaderCanvasView readerCanvas;

    // Панели управления в стиле Onyx NeoReader
    private View readerTopBar;
    private View readerTopDivider;
    private View readerBottomBar;
    private View readerFormatPanel;
    private View readerFormatScroll;

    // Верхняя панель
    private Button btnReaderBack;
    private TextView readerBookTitleTop;
    private Button btnReaderRefresh;
    private Button btnReaderCloseOverlay;

    // Нижняя панель: навигация по главам
    private TextView readerChapterTitleView;
    private Button btnPrevChapter;
    private Button btnNextChapter;

    // Слайдер страниц
    private Button btnSliderPageBack;
    private SeekBar readerPageSeekbar;
    private Button btnSliderPageForward;
    private TextView readerPageCounter;

    // Три главные вкладки NeoReader
    private Button btnTabToc;
    private Button btnTabProgress;
    private Button btnTabFormat;

    // Подвкладки панели Формат
    private Button btnSubtabView;
    private Button btnSubtabFormat;
    private Button btnSubtabSpacing;
    private View layoutSubtabViewContainer;
    private View layoutSubtabFormatContainer;
    private View layoutSubtabSpacingContainer;
    private String activeSubtab = "view"; // "view", "format", "spacing"

    // Элементы подвкладки «Вид»
    private TextView fontSizeLabel;
    private Button btnFontDecrease;
    private Button btnFontIncrease;
    private SeekBar seekbarFontSize;
    private Button btnToggleFontFamily;
    private Button btnToggleIndent;
    private Button btnToggleTapZones;
    private Button btnToggleEdgeBrightness;
    private Button btnToggleFullscreen;
    private View rowToggleAudioSync;
    private Button btnToggleAudioSync;
    private boolean hasLinkedAudio = false;
    private boolean audioSyncPromptShown = false;

    // Элементы подвкладки «Формат»
    private Button btnToggleTextAlign; // Выравнивание текста
    private Button btnToggleContrast; // Утолщение текста
    private Button btnToggleHyphenation; // Переносы слов

    private static final int REQUEST_IMPORT_FONT = 7001;
    private double pendingRepagAnchorFraction = -1.0;
    private int pendingRepagAnchorCharOffset = -1;
    private Runnable pendingRepagRunnable = null;
    private Runnable pendingPrepagRunnable = null;
    private Button btnToggleEinkContrast; // Контраст E-Ink
    private Button btnToggleNightMode; // Цветовая тема (Светлая / Тёмная)
    private Button btnToggleEpubFonts; // Шрифт книги из EPUB (v1.7.0)

    // Элементы подвкладки «Между строк»
    private Button btnToggleLineSpacing;
    private Button btnToggleMargins;
    private Button btnToggleVertMargins;
    private Button btnToggleFooterOffset;
    private Button btnToggleOrientation;

    private String bookUuid;
    private String bookTitle;
    private List<Chapter> chapters = new ArrayList<>();
    private int currentChapterIndex = 0;
    private List<TextPaginator.Page> currentPages = new ArrayList<>();
    private int currentPageIndex = 0;
    private int pageTurnCounter = 0;
    private boolean hasPerformedInitialRefresh = false;

    // Взвешенный расчет прогресса по длине глав (исключает ложные перескоки в конец книги)
    private long[] chapterLengths;
    private long totalBookLength = 0;
    private boolean isInitialLoading = true; // Защита от перезаписи облачного прогресса при старте
    private boolean userHasInteracted = false; // Отслеживает факт реального листания/взаимодействия пользователем

    // Сквозной подсчет страниц книги от общего объема
    private int[] chapterPageCounts;
    private boolean[] chapterPageExact;
    private int totalBookPages = 1;
    private int globalPageIndex = 1;
    private double avgCharsPerPage = 750.0;

    // Фоновый исполнитель пагинации: полностью исключает блокировку UI-потока и ANR
    private final ExecutorService paginationExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService prepaginationExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong paginationTaskId = new AtomicLong(0);
    private final AtomicLong prepaginationTaskId = new AtomicLong(0);
    private String currentPrepagConfigSig = "";
    private volatile boolean isPaginating = false;
    private int lastPaginatedWidth = 0;
    private int lastPaginatedHeight = 0;

    // Индикатор подготовки книги
    private View readerLoadingOverlay;
    private TextView readerLoadingText;

    // Внутрипамятный кэш страниц и оглавления для мгновенного перелистывания (0 мс)
    private final Map<Integer, List<TextPaginator.Page>> chapterPagesMemoryCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Integer, String> chapterTextMemoryCache = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile List<EpubParser.TocNode> cachedTocTree = null;

    private TypographyConfig typographyConfig;
    private TextPaginator paginator;
    private HardwareKeyHandler keyHandler;
    private CacheManager cacheManager;
    private SyncManager syncManager;
    private DatabaseHelper dbHelper;
    private YandexBooksApiClient apiClient;
    private com.onyx.yandexbooks.core.storage.AppSettings appSettings;

    // Debounced автосохранение прогресса в облако во время непрерывного чтения
    private final Handler autoSaveHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoSaveRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                if (userHasInteracted) {
                    saveProgress();
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error in autoSaveRunnable", t);
            }
        }
    };

    private void scheduleAutoSaveProgress() {
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        autoSaveHandler.postDelayed(autoSaveRunnable, 2500L);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        com.onyx.yandexbooks.core.storage.AppSettings settings = new com.onyx.yandexbooks.core.storage.AppSettings(this);
        com.onyx.yandexbooks.core.ui.UiScaleHelper.applyScale(this, settings.getUiScale());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reader);

        bookUuid = getIntent().getStringExtra("book_uuid");
        bookTitle = getIntent().getStringExtra("book_title");
        String intentAudioUuid = getIntent().getStringExtra("linked_audio_uuid");
        if (intentAudioUuid != null && !intentAudioUuid.trim().isEmpty()) {
            hasLinkedAudio = true;
        }

        readerCanvas = (ReaderCanvasView) findViewById(R.id.reader_canvas);
        readerLoadingOverlay = findViewById(R.id.reader_loading_overlay);
        readerLoadingText = (TextView) findViewById(R.id.reader_loading_text);

        readerTopBar = findViewById(R.id.reader_top_bar);
        readerTopDivider = findViewById(R.id.reader_top_divider);
        readerBottomBar = findViewById(R.id.reader_bottom_bar);
        readerFormatPanel = findViewById(R.id.reader_format_panel);
        readerFormatScroll = findViewById(R.id.reader_format_scroll);

        btnReaderBack = (Button) findViewById(R.id.btn_reader_back);
        readerBookTitleTop = (TextView) findViewById(R.id.reader_book_title_top);
        btnReaderRefresh = (Button) findViewById(R.id.btn_reader_refresh);
        btnReaderCloseOverlay = (Button) findViewById(R.id.btn_reader_close_overlay);

        readerChapterTitleView = (TextView) findViewById(R.id.reader_chapter_title_view);
        btnPrevChapter = (Button) findViewById(R.id.btn_prev_chapter);
        btnNextChapter = (Button) findViewById(R.id.btn_next_chapter);

        btnSliderPageBack = (Button) findViewById(R.id.btn_slider_page_back);
        readerPageSeekbar = (SeekBar) findViewById(R.id.reader_page_seekbar);
        btnSliderPageForward = (Button) findViewById(R.id.btn_slider_page_forward);
        readerPageCounter = (TextView) findViewById(R.id.reader_page_counter);

        btnTabToc = (Button) findViewById(R.id.btn_tab_toc);
        btnTabProgress = (Button) findViewById(R.id.btn_tab_progress);
        btnTabFormat = (Button) findViewById(R.id.btn_tab_format);

        // Подвкладки панели Формат
        btnSubtabView = (Button) findViewById(R.id.btn_subtab_view);
        btnSubtabFormat = (Button) findViewById(R.id.btn_subtab_format);
        btnSubtabSpacing = (Button) findViewById(R.id.btn_subtab_spacing);

        layoutSubtabViewContainer = findViewById(R.id.layout_subtab_view_container);
        layoutSubtabFormatContainer = findViewById(R.id.layout_subtab_format_container);
        layoutSubtabSpacingContainer = findViewById(R.id.layout_subtab_spacing_container);

        // Контролы подвкладки «Вид»
        fontSizeLabel = (TextView) findViewById(R.id.font_size_label);
        btnFontDecrease = (Button) findViewById(R.id.btn_font_decrease);
        btnFontIncrease = (Button) findViewById(R.id.btn_font_increase);
        seekbarFontSize = (SeekBar) findViewById(R.id.seekbar_font_size);
        btnToggleFontFamily = (Button) findViewById(R.id.btn_toggle_font_family);
        btnToggleIndent = (Button) findViewById(R.id.btn_toggle_indent);
        btnToggleTapZones = (Button) findViewById(R.id.btn_toggle_tap_zones);
        btnToggleEdgeBrightness = (Button) findViewById(R.id.btn_toggle_edge_brightness);
        btnToggleFullscreen = (Button) findViewById(R.id.btn_toggle_fullscreen);
        rowToggleAudioSync = findViewById(R.id.row_toggle_audio_sync);
        btnToggleAudioSync = (Button) findViewById(R.id.btn_toggle_audio_sync);
        if (rowToggleAudioSync != null) {
            updateAudioSyncRowVisibility();
        }

        // Контролы подвкладки «Формат»
        btnToggleTextAlign = (Button) findViewById(R.id.btn_toggle_text_align);
        btnToggleContrast = (Button) findViewById(R.id.btn_toggle_contrast);
        btnToggleHyphenation = (Button) findViewById(R.id.btn_toggle_hyphenation);
        btnToggleEinkContrast = (Button) findViewById(R.id.btn_toggle_eink_contrast);
        btnToggleNightMode = (Button) findViewById(R.id.btn_toggle_night_mode);
        btnToggleEpubFonts = (Button) findViewById(R.id.btn_toggle_epub_fonts);

        // Контролы подвкладки «Между строк»
        btnToggleLineSpacing = (Button) findViewById(R.id.btn_toggle_line_spacing);
        btnToggleMargins = (Button) findViewById(R.id.btn_toggle_margins);
        btnToggleVertMargins = (Button) findViewById(R.id.btn_toggle_vert_margins);
        btnToggleFooterOffset = (Button) findViewById(R.id.btn_toggle_footer_offset);
        btnToggleOrientation = (Button) findViewById(R.id.btn_toggle_orientation);

        if (readerBookTitleTop != null) {
            readerBookTitleTop.setText(bookTitle != null ? bookTitle : "Яндекс Книги");
        }

        appSettings = new com.onyx.yandexbooks.core.storage.AppSettings(this);
        applyOrientation();
        applyFullscreenMode();
        typographyConfig = new TypographyConfig();
        typographyConfig.setFontSizeSp(appSettings.getFontSizeSp());
        typographyConfig.setFontFamily(appSettings.getFontFamily());
        typographyConfig.setLineSpacingMultiplier(appSettings.getLineSpacingMultiplier());
        typographyConfig.setParagraphIndentPx(appSettings.getParagraphIndentPx());
        typographyConfig.setHyphenationEnabled(appSettings.isHyphenationEnabled());
        typographyConfig.setTextAlignMode(appSettings.getTextAlignMode());
        typographyConfig.setBoldText(appSettings.isBoldText());
        typographyConfig.setContrastMode(appSettings.getContrastMode());
        typographyConfig.setVerticalMarginMode(appSettings.getVerticalMarginMode());
        typographyConfig.setNightMode(appSettings.isNightMode());
        typographyConfig.setTapZoneMode(appSettings.getTapZoneMode());
        typographyConfig.setCustomTapZones(appSettings.getCustomTapZones());
        typographyConfig.setUseEpubFonts(appSettings.isUseEpubFonts());
        typographyConfig.setDensityScale(getResources().getDisplayMetrics().density);
        applyFooterOffsetToConfig();

        int marginPx = appSettings.getMarginPaddingPx();
        typographyConfig.setPaddingLeftPx(marginPx);
        typographyConfig.setPaddingRightPx(marginPx);

        paginator = new TextPaginator();

        if (readerCanvas != null) {
            readerCanvas.setTypographyConfig(typographyConfig);
        }

        TokenStorage tokenStorage = new TokenStorage(this);
        apiClient = new YandexBooksApiClient(tokenStorage);
        cacheManager = new CacheManager(this, apiClient);
        dbHelper = DatabaseHelper.getInstance(this);
        dbHelper.purgeLegacyCloudQuotesIfNeeded(appSettings);
        syncManager = new SyncManager(this, apiClient);

        if (readerCanvas != null && bookUuid != null) {
            readerCanvas.setEpubFontPaths(cacheManager.loadBookEpubFonts(bookUuid));
        }

        if (readerCanvas != null) {
            readerCanvas.setImageLoader(new ReaderCanvasView.ImageLoader() {
                @Override
                public Bitmap loadImage(String imagePath, int reqWidth, int reqHeight) {
                    return cacheManager.loadImageFromEpub(bookUuid, imagePath, reqWidth, reqHeight);
                }

                @Override
                public Bitmap loadCover(int reqWidth, int reqHeight) {
                    return cacheManager.getCoverBitmap(bookUuid, reqWidth, reqHeight);
                }
            });
        }

        setupHardwareKeys();
        setupCanvasListeners();
        setupNeoReaderControls();

        if (readerCanvas != null) {
            readerCanvas.post(new Runnable() {
                @Override
                public void run() {
                    loadBookData();
                }
            });
        } else {
            loadBookData();
        }
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
                if (isMenuOverlayVisible()) {
                    hideMenuOverlay();
                } else {
                    flipPageForward();
                }
            }

            @Override
            public void onPageBackward() {
                if (isMenuOverlayVisible()) {
                    hideMenuOverlay();
                } else {
                    flipPageBackward();
                }
            }

            @Override
            public void onCenterTap() {
                toggleMenuOverlay();
            }
        });

        readerCanvas.setOnCanvasSizeChangeListener(new ReaderCanvasView.OnCanvasSizeChangeListener() {
            @Override
            public void onCanvasSizeChanged(int width, int height) {
                if (width > 0 && height > 0) {
                    if (lastPaginatedWidth > 0 && lastPaginatedHeight > 0 &&
                            (width != lastPaginatedWidth || height != lastPaginatedHeight)) {
                        repaginateCurrentChapter();
                    }
                }
            }
        });

        readerCanvas.setEdgeBrightnessEnabled(appSettings.isEdgeBrightnessSwipeEnabled());
        int[] initialFl = EpdController.readFrontlightPercent(this);
        if (initialFl != null && initialFl.length >= 2) {
            appSettings.setEdgeFrontlightCold(initialFl[0]);
            appSettings.setEdgeFrontlightWarm(initialFl[1]);
        }
        readerCanvas.setInitialFrontlightLevels(appSettings.getEdgeFrontlightCold(), appSettings.getEdgeFrontlightWarm());

        readerCanvas.setOnFootnoteTapListener(new ReaderCanvasView.OnFootnoteTapListener() {
            @Override
            public void onFootnoteTap(String marker, String noteText) {
                EinkNotice.showReadable(ReaderActivity.this, "Сноска " + marker, noteText, getReadablePopupTextSizeSp());
            }
        });

        readerCanvas.setOnLinkTapListener(new ReaderCanvasView.OnLinkTapListener() {
            @Override
            public void onLinkTap(final String target, final String label) {
                handleCanvasLinkTap(target, label);
            }
        });

        readerCanvas.setOnExtraTapActionListener(new ReaderCanvasView.OnExtraTapActionListener() {
            @Override
            public void onOpenToc() {
                showTableOfContentsDialog();
            }

            @Override
            public void onToggleBookmark() {
                addQuickBookmarkAtCurrentPosition();
            }

            @Override
            public void onForceRefresh() {
                EpdController.applyGCOnce(readerCanvas);
                EpdController.requestFullRefresh(ReaderActivity.this, readerCanvas);
            }
        });

        readerCanvas.setOnTextSelectionListener(new ReaderCanvasView.OnTextSelectionListener() {
            @Override
            public void onTextSelected(String selectedText, int startOffset, int endOffset) {
                showTextSelectionActionDialog(selectedText, startOffset, endOffset);
            }
        });

        readerCanvas.setOnEdgeBrightnessGestureListener(new ReaderCanvasView.OnEdgeBrightnessGestureListener() {
            @Override
            public void onEdgeBrightnessChanged(boolean isWarmEdge, int newPercent) {
                if (isWarmEdge) {
                    appSettings.setEdgeFrontlightWarm(newPercent);
                } else {
                    appSettings.setEdgeFrontlightCold(newPercent);
                }
                int cold = appSettings.getEdgeFrontlightCold();
                int warm = appSettings.getEdgeFrontlightWarm();
                boolean applied = EpdController.isOnyxDevice()
                        && EpdController.setFrontlight(ReaderActivity.this, cold, warm, isWarmEdge);
                if (!applied && getWindow() != null) {
                    WindowManager.LayoutParams lp = getWindow().getAttributes();
                    lp.screenBrightness = Math.max(0.05f, Math.min(1.0f, newPercent / 100f));
                    getWindow().setAttributes(lp);
                }
            }
        });
    }

    private boolean isMenuOverlayVisible() {
        return readerBottomBar != null && readerBottomBar.getVisibility() == View.VISIBLE;
    }

    private void toggleMenuOverlay() {
        if (isMenuOverlayVisible()) {
            hideMenuOverlay();
        } else {
            showMenuOverlay();
        }
    }

    private void showMenuOverlay() {
        if (readerTopBar != null) readerTopBar.setVisibility(View.VISIBLE);
        if (readerTopDivider != null) readerTopDivider.setVisibility(View.VISIBLE);
        if (readerBottomBar != null) readerBottomBar.setVisibility(View.VISIBLE);
        updateMenuControls();
        EpdController.requestFullRefresh(this, null);
    }

    private void hideMenuOverlay() {
        if (readerTopBar != null) readerTopBar.setVisibility(View.GONE);
        if (readerTopDivider != null) readerTopDivider.setVisibility(View.GONE);
        if (readerBottomBar != null) readerBottomBar.setVisibility(View.GONE);
        if (readerFormatPanel != null) readerFormatPanel.setVisibility(View.GONE);
        forceEpdRefresh();
    }

    private void setupNeoReaderControls() {
        // Верхняя панель
        if (btnReaderBack != null) {
            btnReaderBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveProgress();
                    syncManager.flushOfflineQueue();
                    finish();
                }
            });
        }

        if (btnReaderRefresh != null) {
            btnReaderRefresh.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    forceEpdRefresh();
                }
            });
        }

        if (btnReaderCloseOverlay != null) {
            btnReaderCloseOverlay.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    hideMenuOverlay();
                }
            });
        }

        // Переход по главам
        if (btnPrevChapter != null) {
            btnPrevChapter.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (isPaginating) return;
                    if (chapters != null && currentChapterIndex > 0) {
                        currentChapterIndex--;
                        currentPageIndex = 0;
                        isInitialLoading = false;
                        userHasInteracted = true;
                        loadChapter(currentChapterIndex);
                    } else {
                        EinkNotice.show(ReaderActivity.this, "Главы книги", "Это первая глава книги.");
                    }
                }
            });
        }

        if (btnNextChapter != null) {
            btnNextChapter.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (isPaginating) return;
                    if (chapters != null && currentChapterIndex + 1 < chapters.size()) {
                        currentChapterIndex++;
                        currentPageIndex = 0;
                        isInitialLoading = false;
                        userHasInteracted = true;
                        loadChapter(currentChapterIndex);
                    } else {
                        EinkNotice.show(ReaderActivity.this, "Главы книги", "Это последняя глава книги.");
                    }
                }
            });
        }

        // Кнопки и SeekBar листания страниц
        if (btnSliderPageBack != null) {
            btnSliderPageBack.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    flipPageBackward();
                }
            });
        }

        if (btnSliderPageForward != null) {
            btnSliderPageForward.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    flipPageForward();
                }
            });
        }

        if (readerPageSeekbar != null) {
            readerPageSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser && chapters != null && !chapters.isEmpty()) {
                        int targetGlobal = progress;
                        int acc = 0;
                        for (int i = 0; i < chapters.size(); i++) {
                            int chPages = (chapterPageCounts != null && i < chapterPageCounts.length) ? chapterPageCounts[i] : 1;
                            if (targetGlobal < acc + chPages || i == chapters.size() - 1) {
                                break;
                            }
                            acc += chPages;
                        }
                        globalPageIndex = Math.max(1, Math.min(totalBookPages, progress + 1));
                        if (readerPageCounter != null) {
                            double percent = calculateCurrentGlobalPercent();
                            readerPageCounter.setText(String.format(Locale.getDefault(), "%d/%d (%.0f%%)", globalPageIndex, totalBookPages, percent));
                        }
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    if (chapters != null && !chapters.isEmpty()) {
                        int targetGlobal = seekBar.getProgress();
                        int acc = 0;
                        int targetCh = 0;
                        int targetP = 0;
                        for (int i = 0; i < chapters.size(); i++) {
                            int chPages = (chapterPageCounts != null && i < chapterPageCounts.length) ? chapterPageCounts[i] : 1;
                            if (targetGlobal < acc + chPages || i == chapters.size() - 1) {
                                targetCh = i;
                                targetP = Math.max(0, targetGlobal - acc);
                                break;
                            }
                            acc += chPages;
                        }
                        isInitialLoading = false;
                        userHasInteracted = true;
                        if (targetCh == currentChapterIndex) {
                            currentPageIndex = Math.min(targetP, (currentPages != null && !currentPages.isEmpty()) ? currentPages.size() - 1 : 0);
                            renderCurrentPage();
                        } else {
                            currentChapterIndex = targetCh;
                            currentPageIndex = targetP;
                            loadChapter(targetCh, targetP);
                        }
                        EpdController.requestFullRefresh(ReaderActivity.this, readerCanvas);
                    }
                }
            });
        }

        // 3 главные вкладки управления NeoReader
        if (btnTabToc != null) {
            btnTabToc.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTableOfContentsDialog();
                }
            });
        }

        if (btnTabProgress != null) {
            btnTabProgress.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showProgressDialog();
                }
            });
        }

        if (btnTabFormat != null) {
            btnTabFormat.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (readerFormatPanel != null) {
                        int vis = (readerFormatPanel.getVisibility() == View.VISIBLE) ? View.GONE : View.VISIBLE;
                        readerFormatPanel.setVisibility(vis);
                        if (vis == View.VISIBLE) {
                            adjustFormatPanelHeight();
                        }
                        EpdController.requestFullRefresh(ReaderActivity.this, null);
                    }
                }
            });
        }

        // Переключение подвкладок панели Формат: [ Вид ] | [ Формат ] | [ Между строк ]
        if (btnSubtabView != null) {
            btnSubtabView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchFormatSubtab("view");
                }
            });
        }
        if (btnSubtabFormat != null) {
            btnSubtabFormat.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchFormatSubtab("format");
                }
            });
        }
        if (btnSubtabSpacing != null) {
            btnSubtabSpacing.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchFormatSubtab("spacing");
                }
            });
        }
        switchFormatSubtab("view");

        // Подвкладка 1: Вид (Размер шрифта, Гарнитура, Абзацный отступ)
        if (fontSizeLabel != null) {
            fontSizeLabel.setText(String.valueOf(typographyConfig.getFontSizeSp()));
        }

        if (seekbarFontSize != null) {
            seekbarFontSize.setMax(48); // 12sp .. 60sp
            seekbarFontSize.setProgress(Math.max(0, Math.min(48, typographyConfig.getFontSizeSp() - 12)));
            final long[] lastDragPreviewMs = new long[]{0L};
            seekbarFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        int size = 12 + progress;
                        typographyConfig.setFontSizeSp(size);
                        if (fontSizeLabel != null) fontSizeLabel.setText(String.valueOf(size));
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastDragPreviewMs[0] >= 140L) {
                            lastDragPreviewMs[0] = now;
                            if (pendingRepagAnchorCharOffset < 0 && currentPages != null && !currentPages.isEmpty() && currentPageIndex < currentPages.size()) {
                                pendingRepagAnchorCharOffset = Math.max(0, currentPages.get(currentPageIndex).startCharOffset);
                                pendingRepagAnchorFraction = (double) currentPageIndex / (double) Math.max(1, currentPages.size());
                            }
                            renderInstantViewportPreview(Math.max(0, pendingRepagAnchorCharOffset));
                        }
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    int size = 12 + seekBar.getProgress();
                    typographyConfig.setFontSizeSp(size);
                    appSettings.setFontSizeSp(size);
                    if (fontSizeLabel != null) fontSizeLabel.setText(String.valueOf(size));
                    scheduleDebouncedRepaginate();
                }
            });
        }

        if (btnFontIncrease != null) {
            btnFontIncrease.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int cur = typographyConfig.getFontSizeSp();
                    if (cur < 60) {
                        int next = cur + 1;
                        typographyConfig.setFontSizeSp(next);
                        appSettings.setFontSizeSp(next);
                        if (fontSizeLabel != null) fontSizeLabel.setText(String.valueOf(next));
                        if (seekbarFontSize != null) seekbarFontSize.setProgress(Math.max(0, Math.min(48, next - 12)));
                        scheduleDebouncedRepaginate();
                    }
                }
            });
        }

        if (btnFontDecrease != null) {
            btnFontDecrease.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int cur = typographyConfig.getFontSizeSp();
                    if (cur > 12) {
                        int next = cur - 1;
                        typographyConfig.setFontSizeSp(next);
                        appSettings.setFontSizeSp(next);
                        if (fontSizeLabel != null) fontSizeLabel.setText(String.valueOf(next));
                        if (seekbarFontSize != null) seekbarFontSize.setProgress(next - 12);
                        scheduleDebouncedRepaginate();
                    }
                }
            });
        }

        updateFontFamilyButtonText();
        if (btnToggleFontFamily != null) {
            btnToggleFontFamily.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showFontSelectionDialog();
                }
            });
        }

        updateIndentButtonText();
        if (btnToggleIndent != null) {
            btnToggleIndent.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cycleIndent();
                }
            });
        }

        updateTapZonesButtonText();
        if (btnToggleTapZones != null) {
            btnToggleTapZones.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTapZoneSettingsDialog();
                }
            });
            btnToggleTapZones.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showCustomTapZoneGridDialog();
                    return true;
                }
            });
        }

        updateFullscreenButtonText();
        if (btnToggleFullscreen != null) {
            btnToggleFullscreen.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean next = !appSettings.isFullscreenReader();
                    appSettings.setFullscreenReader(next);
                    applyFullscreenMode();
                    updateFullscreenButtonText();
                }
            });
        }

        updateEdgeBrightnessButtonText();
        if (btnToggleEdgeBrightness != null) {
            btnToggleEdgeBrightness.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean next = !appSettings.isEdgeBrightnessSwipeEnabled();
                    appSettings.setEdgeBrightnessSwipeEnabled(next);
                    if (readerCanvas != null) {
                        readerCanvas.setEdgeBrightnessEnabled(next);
                    }
                    updateEdgeBrightnessButtonText();
                    EinkNotice.show(ReaderActivity.this, "Подсветка по краям",
                            next
                                    ? "Включена: вертикальный свайп по левому узкому краю регулирует тёплый свет, по правому — холодный.\n\nКороткие касания и зоны перелистывания работают как прежде."
                                    : "Отключена: вертикальные движения по краям экрана игнорируются.");
                }
            });
        }

        updateAudioSyncButtonText();
        if (btnToggleAudioSync != null) {
            btnToggleAudioSync.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean next = !appSettings.isAudioSyncEnabled();
                    appSettings.setAudioSyncEnabled(next);
                    updateAudioSyncButtonText();
                }
            });
        }

        // Подвкладка 2: Формат (Жирный/Обычный, Переносы слов TeX, Контраст E-Ink)
        updateContrastButtonText();
        updateTextAlignButtonText();
        if (btnToggleTextAlign != null) {
            btnToggleTextAlign.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cycleTextAlignMode();
                }
            });
        }

        if (btnToggleContrast != null) {
            btnToggleContrast.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean bold = !typographyConfig.isBoldText();
                    typographyConfig.setBoldText(bold);
                    appSettings.setBoldText(bold);
                    updateContrastButtonText();
                    if (readerCanvas != null) readerCanvas.setTypographyConfig(typographyConfig);
                    repaginateCurrentChapter();
                }
            });
        }

        updateHyphenationButtonText();
        if (btnToggleHyphenation != null) {
            btnToggleHyphenation.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean enabled = !typographyConfig.isHyphenationEnabled();
                    typographyConfig.setHyphenationEnabled(enabled);
                    appSettings.setHyphenationEnabled(enabled);
                    updateHyphenationButtonText();
                    repaginateCurrentChapter();
                }
            });
        }

        updateEinkContrastButtonText();
        if (btnToggleEinkContrast != null) {
            btnToggleEinkContrast.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String cur = typographyConfig.getContrastMode();
                    String next = "high".equalsIgnoreCase(cur) ? "normal" : "high";
                    typographyConfig.setContrastMode(next);
                    appSettings.setContrastMode(next);
                    updateEinkContrastButtonText();
                    if (readerCanvas != null) {
                        readerCanvas.setTypographyConfig(typographyConfig);
                        readerCanvas.invalidate();
                    }
                    forceEpdRefresh();
                }
            });
        }

        updateNightModeButtonText();
        if (btnToggleNightMode != null) {
            btnToggleNightMode.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean next = !typographyConfig.isNightMode();
                    typographyConfig.setNightMode(next);
                    appSettings.setNightMode(next);
                    updateNightModeButtonText();
                    if (readerCanvas != null) {
                        readerCanvas.setTypographyConfig(typographyConfig);
                        readerCanvas.invalidate();
                    }
                    forceEpdRefresh();
                }
            });
        }

        updateEpubFontsButtonText();
        if (btnToggleEpubFonts != null) {
            btnToggleEpubFonts.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean next = !typographyConfig.isUseEpubFonts();
                    typographyConfig.setUseEpubFonts(next);
                    appSettings.setUseEpubFonts(next);
                    updateEpubFontsButtonText();
                    Map<String, String> bookFonts = (cacheManager != null && bookUuid != null)
                            ? cacheManager.loadBookEpubFonts(bookUuid) : null;
                    if (readerCanvas != null) {
                        readerCanvas.setEpubFontPaths(bookFonts);
                        readerCanvas.setTypographyConfig(typographyConfig);
                    }
                    if (next && (bookFonts == null || bookFonts.isEmpty())) {
                        EinkNotice.show(ReaderActivity.this, "Шрифт книги (EPUB)",
                                "Режим включён, однако в этом файле EPUB не обнаружены встроенные шрифты (.ttf / .otf) — используется выбранный шрифт из настроек.");
                    }
                    repaginateCurrentChapter();
                }
            });
        }

        // Подвкладка 3: Между строк (Межстрочный интервал, Боковые поля, Верх/низ поля)
        updateLineSpacingButtonText();
        if (btnToggleLineSpacing != null) {
            btnToggleLineSpacing.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cycleLineSpacing();
                }
            });
        }

        if (btnToggleMargins != null) {
            btnToggleMargins.setText(getMarginButtonText());
            btnToggleMargins.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cycleMarginMode();
                }
            });
        }

        updateVertMarginButtonText();
        if (btnToggleVertMargins != null) {
            btnToggleVertMargins.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cycleVertMarginMode();
                }
            });
        }

        updateFooterOffsetButtonText();
        if (btnToggleFooterOffset != null) {
            btnToggleFooterOffset.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cycleFooterOffsetMode();
                }
            });
        }

        updateOrientationButtonText();
        if (btnToggleOrientation != null) {
            btnToggleOrientation.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cycleOrientation();
                }
            });
        }

        // Защита от утечки кликов на readerCanvas под панелями
        if (readerTopBar != null) {
            readerTopBar.setClickable(true);
            readerTopBar.setFocusable(true);
        }
        if (readerBottomBar != null) {
            readerBottomBar.setClickable(true);
            readerBottomBar.setFocusable(true);
        }
        if (readerFormatPanel != null) {
            readerFormatPanel.setClickable(true);
            readerFormatPanel.setFocusable(true);
        }

        // Полноразмерные зоны касания (Full-Row Clickable Targets для E-Ink тачскринов)
        setupRowClick(R.id.row_toggle_font_family, btnToggleFontFamily);
        setupRowClick(R.id.row_toggle_indent, btnToggleIndent);
        setupRowClick(R.id.row_toggle_tap_zones, btnToggleTapZones);
        setupRowClick(R.id.row_toggle_edge_brightness, btnToggleEdgeBrightness);
        setupRowClick(R.id.row_toggle_fullscreen, btnToggleFullscreen);
        setupRowClick(R.id.row_toggle_audio_sync, btnToggleAudioSync);
        setupRowClick(R.id.row_toggle_text_align, btnToggleTextAlign);
        setupRowClick(R.id.row_toggle_contrast, btnToggleContrast);
        setupRowClick(R.id.row_toggle_hyphenation, btnToggleHyphenation);
        setupRowClick(R.id.row_toggle_eink_contrast, btnToggleEinkContrast);
        setupRowClick(R.id.row_toggle_night_mode, btnToggleNightMode);
        setupRowClick(R.id.row_toggle_epub_fonts, btnToggleEpubFonts);
        setupRowClick(R.id.row_toggle_line_spacing, btnToggleLineSpacing);
        setupRowClick(R.id.row_toggle_margins, btnToggleMargins);
        setupRowClick(R.id.row_toggle_vert_margins, btnToggleVertMargins);
        setupRowClick(R.id.row_toggle_footer_offset, btnToggleFooterOffset);
        setupRowClick(R.id.row_toggle_orientation, btnToggleOrientation);
    }

    private void setupRowClick(int rowId, final View targetButton) {
        View row = findViewById(rowId);
        if (row != null && targetButton != null) {
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    targetButton.performClick();
                }
            });
        }
    }

    private void switchFormatSubtab(String subtab) {
        activeSubtab = subtab;
        if (layoutSubtabViewContainer != null) {
            layoutSubtabViewContainer.setVisibility("view".equals(subtab) ? View.VISIBLE : View.GONE);
        }
        if (layoutSubtabFormatContainer != null) {
            layoutSubtabFormatContainer.setVisibility("format".equals(subtab) ? View.VISIBLE : View.GONE);
        }
        if (layoutSubtabSpacingContainer != null) {
            layoutSubtabSpacingContainer.setVisibility("spacing".equals(subtab) ? View.VISIBLE : View.GONE);
        }

        if (btnSubtabView != null) {
            boolean active = "view".equals(subtab);
            btnSubtabView.setBackgroundResource(active ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
            btnSubtabView.setTextColor(Color.BLACK);
            btnSubtabView.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (btnSubtabFormat != null) {
            boolean active = "format".equals(subtab);
            btnSubtabFormat.setBackgroundResource(active ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
            btnSubtabFormat.setTextColor(Color.BLACK);
            btnSubtabFormat.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        }
        if (btnSubtabSpacing != null) {
            boolean active = "spacing".equals(subtab);
            btnSubtabSpacing.setBackgroundResource(active ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
            btnSubtabSpacing.setTextColor(Color.BLACK);
            btnSubtabSpacing.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        }
        adjustFormatPanelHeight();
    }

    private void adjustFormatPanelHeight() {
        if (readerFormatScroll == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;
        int chromeReservedPx = Math.round(285 * density);
        int maxAllowedPx = dm.heightPixels - chromeReservedPx;
        int naturalContentPx = Math.round(310 * density);
        ViewGroup.LayoutParams lp = readerFormatScroll.getLayoutParams();
        if (lp != null) {
            int targetHeight = (maxAllowedPx < naturalContentPx)
                    ? Math.max(Math.round(135 * density), maxAllowedPx)
                    : ViewGroup.LayoutParams.WRAP_CONTENT;
            if (lp.height != targetHeight) {
                lp.height = targetHeight;
                readerFormatScroll.setLayoutParams(lp);
            }
        }
    }

    private void updateFontFamilyButtonText() {
        if (btnToggleFontFamily == null) return;
        String name = FontHelper.getFontDisplayName(typographyConfig.getFontFamily());
        btnToggleFontFamily.setText(name);
    }

    private void showFontSelectionDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final float density = getResources().getDisplayMetrics().density;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final int targetW = Math.min((int) (dm.widthPixels * 0.92), (int) (580 * density));
        final int targetH = Math.min((int) (dm.heightPixels * 0.88), (int) (425 * density));

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int padH = (int) (12 * density);
        int padV = (int) (8 * density);
        root.setPadding(padH, padV, padH, padV);

        TextView titleView = new TextView(this);
        titleView.setText("Выбор шрифта для чтения");
        titleView.setTextSize(14);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, (int) (2 * density));
        root.addView(titleView);

        TextView hintView = new TextView(this);
        hintView.setText("Встроенные шрифты, папка /Fonts устройства и импорт .ttf / .otf");
        hintView.setTextSize(10);
        hintView.setTextColor(Color.BLACK);
        hintView.setGravity(Gravity.CENTER);
        hintView.setPadding(0, 0, 0, (int) (4 * density));
        root.addView(hintView);

        View divider = new View(this);
        divider.setBackgroundColor(Color.BLACK);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density)));

        final LinearLayout fontListLayout = new LinearLayout(this);
        fontListLayout.setOrientation(LinearLayout.VERTICAL);
        fontListLayout.setPadding(0, (int) (4 * density), 0, (int) (4 * density));

        final String currentFontId = typographyConfig.getFontFamily();
        final List<FontHelper.FontItem> fonts = FontHelper.getAvailableFonts(this);
        final List<LinearLayout> fontItemViews = new ArrayList<LinearLayout>();

        final int[] focusedIndex = new int[] { 0 };
        for (int i = 0; i < fonts.size(); i++) {
            if (fonts.get(i).id.equalsIgnoreCase(currentFontId)) {
                focusedIndex[0] = i;
                break;
            }
        }

        for (int i = 0; i < fonts.size(); i++) {
            final int index = i;
            final FontHelper.FontItem fontItem = fonts.get(i);
            final boolean isSelected = fontItem.id.equalsIgnoreCase(currentFontId);
            Typeface tf = FontHelper.getTypeface(this, fontItem.id);

            LinearLayout itemLayout = new LinearLayout(this);
            itemLayout.setOrientation(LinearLayout.HORIZONTAL);
            itemLayout.setGravity(Gravity.CENTER_VERTICAL);
            itemLayout.setBackgroundResource(isSelected ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
            int itemPadH = (int) (8 * density);
            int itemPadV = (int) (4 * density);
            itemLayout.setPadding(itemPadH, itemPadV, itemPadH, itemPadV);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (33 * density));
            itemLp.setMargins(0, (int) (1 * density), 0, (int) (1 * density));
            itemLayout.setLayoutParams(itemLp);

            TextView bulletView = new TextView(this);
            bulletView.setText(isSelected ? "● " : "○ ");
            bulletView.setTextSize(13);
            bulletView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            bulletView.setTextColor(Color.BLACK);
            itemLayout.addView(bulletView);

            TextView fontNameView = new TextView(this);
            fontNameView.setText(fontItem.name);
            fontNameView.setTextSize(13);
            fontNameView.setTypeface(tf, isSelected ? Typeface.BOLD : Typeface.NORMAL);
            fontNameView.setTextColor(Color.BLACK);
            fontNameView.setSingleLine(true);
            fontNameView.setEllipsize(TextUtils.TruncateAt.END);
            itemLayout.addView(fontNameView);

            View spacer = new View(this);
            LinearLayout.LayoutParams spacerLp = new LinearLayout.LayoutParams(0, 1, 1.0f);
            itemLayout.addView(spacer, spacerLp);

            TextView badgeView = new TextView(this);
            badgeView.setText(fontItem.subtitle);
            badgeView.setTextSize(10);
            badgeView.setTextColor(Color.BLACK);
            badgeView.setSingleLine(true);
            badgeView.setEllipsize(TextUtils.TruncateAt.END);
            badgeView.setPadding((int) (4 * density), 0, 0, 0);
            itemLayout.addView(badgeView);

            itemLayout.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    typographyConfig.setFontFamily(fontItem.id);
                    appSettings.setFontFamily(fontItem.id);
                    updateFontFamilyButtonText();
                    repaginateCurrentChapter();
                    EpdController.requestFullRefresh(ReaderActivity.this, null);
                }
            });

            fontItemViews.add(itemLayout);
            fontListLayout.addView(itemLayout);
        }

        final com.onyx.yandexbooks.core.eink.EinkScrollView fontScroll = new com.onyx.yandexbooks.core.eink.EinkScrollView(this);
        LinearLayout.LayoutParams fontScrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        fontScroll.setLayoutParams(fontScrollLp);
        fontScroll.addView(fontListLayout);
        root.addView(fontScroll);

        View bottomDivider = new View(this);
        bottomDivider.setBackgroundColor(Color.BLACK);
        root.addView(bottomDivider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density)));

        LinearLayout bottomBtnRow = new LinearLayout(this);
        bottomBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bottomRowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (34 * density));
        bottomRowLp.setMargins(0, (int) (6 * density), 0, 0);
        bottomBtnRow.setLayoutParams(bottomRowLp);

        Button btnImportFont = new Button(this);
        btnImportFont.setText("+ Добавить шрифт");
        btnImportFont.setTextSize(11);
        btnImportFont.setTypeface(null, Typeface.BOLD);
        btnImportFont.setTextColor(Color.BLACK);
        btnImportFont.setBackgroundResource(R.drawable.btn_eink_primary);
        btnImportFont.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams btnImportLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        btnImportLp.setMargins(0, 0, (int) (6 * density), 0);
        btnImportFont.setLayoutParams(btnImportLp);
        btnImportFont.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                launchImportFontPicker();
            }
        });
        bottomBtnRow.addView(btnImportFont);

        Button btnClose = new Button(this);
        btnClose.setText("Закрыть");
        btnClose.setTextSize(12);
        btnClose.setTypeface(null, Typeface.BOLD);
        btnClose.setTextColor(Color.BLACK);
        btnClose.setBackgroundResource(R.drawable.btn_eink);
        btnClose.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams btnCloseLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        btnClose.setLayoutParams(btnCloseLp);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        bottomBtnRow.addView(btnClose);
        root.addView(bottomBtnRow);

        dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        dialog.setCanceledOnTouchOutside(true);

        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (HardwareKeyHandler.isPageKey(keyCode, event)
                        || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {

                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        int count = fonts.size();
                        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == 93 || keyCode == 167 || keyCode == 261) {
                            focusedIndex[0] = (focusedIndex[0] + 1) % count;
                            updateFontDialogVisuals(fontItemViews, focusedIndex[0], fonts, currentFontId);
                            EpdController.requestFullRefresh(ReaderActivity.this, root);
                        } else if (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == 92 || keyCode == 166 || keyCode == 260) {
                            focusedIndex[0] = (focusedIndex[0] - 1 + count) % count;
                            updateFontDialogVisuals(fontItemViews, focusedIndex[0], fonts, currentFontId);
                            EpdController.requestFullRefresh(ReaderActivity.this, root);
                        } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                            if (focusedIndex[0] >= 0 && focusedIndex[0] < count) {
                                fontItemViews.get(focusedIndex[0]).performClick();
                            }
                        }
                    }
                    return true;
                }
                return false;
            }
        });

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
                    dialog.getWindow().setLayout(targetW, targetH);
                }
                EpdController.requestFullRefresh(ReaderActivity.this, root);
            }
        });

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                EpdController.requestFullRefresh(ReaderActivity.this, null);
            }
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
            dialog.getWindow().setLayout(targetW, targetH);
        }
    }

    private void launchImportFontPicker() {
        try {
            Intent intent;
            if (android.os.Build.VERSION.SDK_INT >= 19) {
                intent = new Intent("android.intent.action.OPEN_DOCUMENT");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
            } else {
                intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
            }
            startActivityForResult(Intent.createChooser(intent, "Выберите файл шрифта (.ttf / .otf)"), REQUEST_IMPORT_FONT);
        } catch (android.content.ActivityNotFoundException e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                fallback.setType("*/*");
                startActivityForResult(fallback, REQUEST_IMPORT_FONT);
            } catch (Throwable t) {
                EinkNotice.show(this, "Шрифты",
                        "Не найден системный проводник файлов. Вы можете скопировать файлы .ttf / .otf в папку /Fonts или /Books/Fonts на устройстве.");
            }
        } catch (Throwable t) {
            EinkNotice.show(this, "Шрифты", "Не удалось открыть выбор файла шрифта: " + t.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMPORT_FONT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            String importedId = FontHelper.importFontFromUri(this, data.getData());
            if (importedId != null && !importedId.isEmpty()) {
                typographyConfig.setFontFamily(importedId);
                appSettings.setFontFamily(importedId);
                updateFontFamilyButtonText();
                repaginateCurrentChapter();
                EinkNotice.show(this, "Шрифт добавлен",
                        "Шрифт «" + FontHelper.getFontDisplayName(importedId) + "» импортирован и применён.");
            } else {
                EinkNotice.show(this, "Ошибка импорта",
                        "Не удалось прочитать файл шрифта. Убедитесь, что выбран корректный файл .ttf или .otf.");
            }
        }
    }

    private void updateFontDialogVisuals(List<LinearLayout> itemViews, int focusedIdx, List<FontHelper.FontItem> fonts, String currentFontId) {
        for (int i = 0; i < itemViews.size(); i++) {
            LinearLayout item = itemViews.get(i);
            boolean isFocused = (i == focusedIdx);
            boolean isCurrentActive = fonts.get(i).id.equalsIgnoreCase(currentFontId);
            item.setBackgroundResource((isFocused || isCurrentActive) ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
            TextView bullet = (TextView) item.getChildAt(0);
            if (bullet != null) {
                bullet.setText(isCurrentActive ? "● " : (isFocused ? "▶ " : "○ "));
            }
        }
    }

    private void updateIndentButtonText() {
        if (btnToggleIndent == null) return;
        btnToggleIndent.setText(typographyConfig.getParagraphIndentPx() + " px");
    }

    private void cycleIndent() {
        int cur = typographyConfig.getParagraphIndentPx();
        int next;
        if (cur == 0) next = 20;
        else if (cur <= 20) next = 32;
        else if (cur <= 32) next = 44;
        else next = 0;
        typographyConfig.setParagraphIndentPx(next);
        appSettings.setParagraphIndentPx(next);
        updateIndentButtonText();
        repaginateCurrentChapter();
    }

    private void updateTapZonesButtonText() {
        if (btnToggleTapZones == null) return;
        String mode = typographyConfig.getTapZoneMode();
        if (com.onyx.yandexbooks.core.storage.AppSettings.TAP_ZONE_LEFT_HANDED.equalsIgnoreCase(mode)) {
            btnToggleTapZones.setText("Для левшей");
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.TAP_ZONE_TOP_BACK_BOTTOM_FWD.equalsIgnoreCase(mode)) {
            btnToggleTapZones.setText("Верх/Низ");
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.TAP_ZONE_CUSTOM.equalsIgnoreCase(mode)) {
            btnToggleTapZones.setText("Своя 3×3");
        } else {
            btnToggleTapZones.setText("Стандарт");
        }
    }

    private void updateFullscreenButtonText() {
        if (btnToggleFullscreen == null || appSettings == null) return;
        btnToggleFullscreen.setText(appSettings.isFullscreenReader() ? "Скрыт бар" : "Выкл");
    }

    private void applyFullscreenMode() {
        if (getWindow() == null || appSettings == null) return;
        if (appSettings.isFullscreenReader()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    private void addQuickBookmarkAtCurrentPosition() {
        if (dbHelper == null || bookUuid == null || bookUuid.isEmpty()) return;
        String title = getActiveChapterTitle();
        String snippet = "";
        if (currentPages != null && currentPageIndex < currentPages.size()) {
            TextPaginator.Page page = currentPages.get(currentPageIndex);
            if (page != null && page.lines != null) {
                StringBuilder sb = new StringBuilder();
                for (TextPaginator.Line line : page.lines) {
                    if (line != null && line.text != null) sb.append(line.text).append(" ");
                    if (sb.length() > 90) break;
                }
                snippet = sb.toString().trim();
                if (snippet.length() > 90) snippet = snippet.substring(0, 90) + "...";
            }
        }
        dbHelper.addBookmark(bookUuid, currentChapterIndex, currentPageIndex, title, snippet);
        EinkNotice.show(ReaderActivity.this, "Закладка", "Закладка сохранена:\n" + title + " (стр. " + globalPageIndex + ")");
    }

    private void updateEdgeBrightnessButtonText() {
        if (btnToggleEdgeBrightness == null || appSettings == null) return;
        btnToggleEdgeBrightness.setText(appSettings.isEdgeBrightnessSwipeEnabled() ? "Свайп (Вкл)" : "Выключена");
    }

    private void updateAudioSyncButtonText() {
        if (btnToggleAudioSync == null || appSettings == null) return;
        btnToggleAudioSync.setText(appSettings.isAudioSyncEnabled() ? "Спрашивать" : "Выключена");
    }

    private void updateAudioSyncRowVisibility() {
        if (rowToggleAudioSync == null) return;
        rowToggleAudioSync.setVisibility(hasLinkedAudio ? View.VISIBLE : View.GONE);
    }

    private void showTapZoneSettingsDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setWindowAnimations(0);
        }

        float density = getResources().getDisplayMetrics().density;
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * density);
        root.setPadding(pad, pad, pad, pad);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setStroke((int) Math.max(2, 2 * density), Color.BLACK);
        root.setBackgroundDrawable(bg);

        TextView titleView = new TextView(this);
        titleView.setText("Зоны касания экрана");
        titleView.setTextSize(15);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setPadding(0, 0, 0, (int) (8 * density));
        root.addView(titleView);

        String cur = typographyConfig.getTapZoneMode();
        final String[] modes = new String[]{
                com.onyx.yandexbooks.core.storage.AppSettings.TAP_ZONE_STANDARD,
                com.onyx.yandexbooks.core.storage.AppSettings.TAP_ZONE_LEFT_HANDED,
                com.onyx.yandexbooks.core.storage.AppSettings.TAP_ZONE_TOP_BACK_BOTTOM_FWD,
                com.onyx.yandexbooks.core.storage.AppSettings.TAP_ZONE_CUSTOM
        };
        final String[] labels = new String[]{
                "Стандарт (Лево=Назад, Право=Вперед)",
                "Для левшей (Лево верх/низ=Вперед, центр=Назад)",
                "Верх / Низ (Верх=Назад, Низ=Вперед)",
                "Пользовательская сетка 3×3"
        };

        for (int i = 0; i < modes.length; i++) {
            final String m = modes[i];
            boolean selected = m.equalsIgnoreCase(cur);
            Button btn = new Button(this);
            btn.setText((selected ? "✓ " : "") + labels[i]);
            btn.setTextSize(12);
            btn.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
            btn.setTextColor(Color.BLACK);
            btn.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            btn.setBackgroundResource(selected ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (42 * density));
            lp.setMargins(0, (int) (4 * density), 0, (int) (4 * density));
            btn.setLayoutParams(lp);
            int pH = (int) (10 * density);
            btn.setPadding(pH, 0, pH, 0);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    applyTapZoneMode(m);
                    dialog.dismiss();
                    if (com.onyx.yandexbooks.core.storage.AppSettings.TAP_ZONE_CUSTOM.equalsIgnoreCase(m)) {
                        showCustomTapZoneGridDialog();
                    }
                }
            });
            root.addView(btn);
        }

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brLp.setMargins(0, (int) (8 * density), 0, 0);
        bottomRow.setLayoutParams(brLp);

        Button btnEditGrid = new Button(this);
        btnEditGrid.setText("Настроить сетку 3×3");
        btnEditGrid.setTextSize(12);
        btnEditGrid.setTypeface(null, Typeface.BOLD);
        btnEditGrid.setTextColor(Color.BLACK);
        btnEditGrid.setBackgroundResource(R.drawable.btn_eink);
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(0, (int) (40 * density), 1.0f);
        gLp.setMargins(0, 0, (int) (6 * density), 0);
        btnEditGrid.setLayoutParams(gLp);
        btnEditGrid.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showCustomTapZoneGridDialog();
            }
        });
        bottomRow.addView(btnEditGrid);

        Button btnClose = new Button(this);
        btnClose.setText("Закрыть");
        btnClose.setTextSize(12);
        btnClose.setTypeface(null, Typeface.BOLD);
        btnClose.setTextColor(Color.BLACK);
        btnClose.setBackgroundResource(R.drawable.btn_eink);
        btnClose.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (40 * density)));
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        bottomRow.addView(btnClose);
        root.addView(bottomRow);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.90f);
            dialog.getWindow().setLayout(maxW, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        EpdController.requestFullRefresh(ReaderActivity.this, root);
    }

    private void applyTapZoneMode(String mode) {
        typographyConfig.setTapZoneMode(mode);
        typographyConfig.setCustomTapZones(appSettings.getCustomTapZones());
        appSettings.setTapZoneMode(mode);
        updateTapZonesButtonText();
        if (readerCanvas != null) {
            readerCanvas.setTypographyConfig(typographyConfig);
        }
    }

    private void showCustomTapZoneGridDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setWindowAnimations(0);
        }

        final String[] zones = appSettings.getCustomTapZones();
        final Button[] cellButtons = new Button[9];

        float density = getResources().getDisplayMetrics().density;
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * density);
        root.setPadding(pad, pad, pad, pad);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setStroke((int) Math.max(2, 2 * density), Color.BLACK);
        root.setBackgroundDrawable(bg);

        TextView titleView = new TextView(this);
        titleView.setText("Кастомная сетка тапов (3×3)");
        titleView.setTextSize(15);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        root.addView(titleView);

        TextView subView = new TextView(this);
        subView.setText("Нажмите на любую из 9 зон для смены действия:");
        subView.setTextSize(11);
        subView.setTextColor(Color.DKGRAY);
        subView.setPadding(0, (int) (4 * density), 0, (int) (8 * density));
        root.addView(subView);

        for (int r = 0; r < 3; r++) {
            LinearLayout rowLayout = new LinearLayout(this);
            rowLayout.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (58 * density));
            rLp.setMargins(0, (int) (2 * density), 0, (int) (2 * density));
            rowLayout.setLayoutParams(rLp);

            for (int c = 0; c < 3; c++) {
                final int idx = r * 3 + c;
                final Button cellBtn = new Button(this);
                cellButtons[idx] = cellBtn;
                cellBtn.setText(formatTapActionShortLabel(zones[idx]));
                cellBtn.setTextSize(11);
                cellBtn.setTypeface(null, Typeface.BOLD);
                cellBtn.setTextColor(Color.BLACK);
                cellBtn.setBackgroundResource(R.drawable.btn_eink);
                cellBtn.setPadding((int) (4 * density), 0, (int) (4 * density), 0);
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
                cLp.setMargins((int) (2 * density), 0, (int) (2 * density), 0);
                cellBtn.setLayoutParams(cLp);
                cellBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        zones[idx] = cycleTapZoneAction(zones[idx]);
                        cellBtn.setText(formatTapActionShortLabel(zones[idx]));
                    }
                });
                rowLayout.addView(cellBtn);
            }
            root.addView(rowLayout);
        }

        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prLp.setMargins(0, (int) (8 * density), 0, (int) (4 * density));
        presetRow.setLayoutParams(prLp);

        Button btnPresetStd = new Button(this);
        btnPresetStd.setText("Шаблон: Правша");
        btnPresetStd.setTextSize(11);
        btnPresetStd.setTextColor(Color.BLACK);
        btnPresetStd.setBackgroundResource(R.drawable.btn_eink);
        LinearLayout.LayoutParams p1Lp = new LinearLayout.LayoutParams(0, (int) (36 * density), 1.0f);
        p1Lp.setMargins(0, 0, (int) (4 * density), 0);
        btnPresetStd.setLayoutParams(p1Lp);
        btnPresetStd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String[] std = new String[]{
                        "prev", "menu", "next",
                        "prev", "menu", "next",
                        "prev", "menu", "next"
                };
                for (int i = 0; i < 9; i++) {
                    zones[i] = std[i];
                    cellButtons[i].setText(formatTapActionShortLabel(zones[i]));
                }
            }
        });
        presetRow.addView(btnPresetStd);

        Button btnPresetLeft = new Button(this);
        btnPresetLeft.setText("Шаблон: Левша");
        btnPresetLeft.setTextSize(11);
        btnPresetLeft.setTextColor(Color.BLACK);
        btnPresetLeft.setBackgroundResource(R.drawable.btn_eink);
        btnPresetLeft.setLayoutParams(new LinearLayout.LayoutParams(0, (int) (36 * density), 1.0f));
        btnPresetLeft.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String[] left = new String[]{
                        "next", "menu", "next",
                        "prev", "menu", "next",
                        "next", "menu", "next"
                };
                for (int i = 0; i < 9; i++) {
                    zones[i] = left[i];
                    cellButtons[i].setText(formatTapActionShortLabel(zones[i]));
                }
            }
        });
        presetRow.addView(btnPresetLeft);
        root.addView(presetRow);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams arLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arLp.setMargins(0, (int) (4 * density), 0, 0);
        actionRow.setLayoutParams(arLp);

        Button btnSave = new Button(this);
        btnSave.setText("Сохранить и включить 3×3");
        btnSave.setTextSize(12);
        btnSave.setTypeface(null, Typeface.BOLD);
        btnSave.setTextColor(Color.BLACK);
        btnSave.setBackgroundResource(R.drawable.btn_eink_primary);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, (int) (40 * density), 1.0f);
        sLp.setMargins(0, 0, (int) (6 * density), 0);
        btnSave.setLayoutParams(sLp);
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                appSettings.setCustomTapZones(zones);
                applyTapZoneMode(com.onyx.yandexbooks.core.storage.AppSettings.TAP_ZONE_CUSTOM);
                dialog.dismiss();
            }
        });
        actionRow.addView(btnSave);

        Button btnCancel = new Button(this);
        btnCancel.setText("Отмена");
        btnCancel.setTextSize(12);
        btnCancel.setTextColor(Color.BLACK);
        btnCancel.setBackgroundResource(R.drawable.btn_eink);
        btnCancel.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (int) (40 * density)));
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        actionRow.addView(btnCancel);
        root.addView(actionRow);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.90f);
            dialog.getWindow().setLayout(maxW, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
        EpdController.requestFullRefresh(ReaderActivity.this, root);
    }

    private static String cycleTapZoneAction(String cur) {
        String[] order = new String[]{
                com.onyx.yandexbooks.core.storage.AppSettings.TAP_ACTION_NEXT,
                com.onyx.yandexbooks.core.storage.AppSettings.TAP_ACTION_PREV,
                com.onyx.yandexbooks.core.storage.AppSettings.TAP_ACTION_MENU,
                com.onyx.yandexbooks.core.storage.AppSettings.TAP_ACTION_TOC,
                com.onyx.yandexbooks.core.storage.AppSettings.TAP_ACTION_BOOKMARK,
                com.onyx.yandexbooks.core.storage.AppSettings.TAP_ACTION_REFRESH,
                com.onyx.yandexbooks.core.storage.AppSettings.TAP_ACTION_NONE
        };
        for (int i = 0; i < order.length; i++) {
            if (order[i].equalsIgnoreCase(cur)) {
                return order[(i + 1) % order.length];
            }
        }
        return order[0];
    }

    private static String formatTapActionShortLabel(String action) {
        if ("next".equalsIgnoreCase(action)) return "Вперед →";
        if ("prev".equalsIgnoreCase(action)) return "← Назад";
        if ("menu".equalsIgnoreCase(action)) return "Меню";
        if ("toc".equalsIgnoreCase(action)) return "Оглавл.";
        if ("bookmark".equalsIgnoreCase(action)) return "Закладка";
        if ("refresh".equalsIgnoreCase(action)) return "Обновить";
        return "Пусто";
    }

    private void updateTextAlignButtonText() {
        if (btnToggleTextAlign == null || typographyConfig == null) return;
        String mode = typographyConfig.getTextAlignMode();
        if (com.onyx.yandexbooks.core.storage.AppSettings.TEXT_ALIGN_LEFT.equalsIgnoreCase(mode)) {
            btnToggleTextAlign.setText("По левому");
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.TEXT_ALIGN_BOOK.equalsIgnoreCase(mode)) {
            btnToggleTextAlign.setText("Как в книге");
        } else {
            btnToggleTextAlign.setText("По ширине");
        }
    }

    private void cycleTextAlignMode() {
        String cur = typographyConfig.getTextAlignMode();
        String next;
        if (com.onyx.yandexbooks.core.storage.AppSettings.TEXT_ALIGN_JUSTIFY.equalsIgnoreCase(cur)) {
            next = com.onyx.yandexbooks.core.storage.AppSettings.TEXT_ALIGN_LEFT;
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.TEXT_ALIGN_LEFT.equalsIgnoreCase(cur)) {
            next = com.onyx.yandexbooks.core.storage.AppSettings.TEXT_ALIGN_BOOK;
        } else {
            next = com.onyx.yandexbooks.core.storage.AppSettings.TEXT_ALIGN_JUSTIFY;
        }
        typographyConfig.setTextAlignMode(next);
        appSettings.setTextAlignMode(next);
        updateTextAlignButtonText();
        repaginateCurrentChapter();
    }

    private void updateContrastButtonText() {
        if (btnToggleContrast == null) return;
        btnToggleContrast.setText(typographyConfig.isBoldText() ? "Жирный" : "Обычный");
    }

    private void updateHyphenationButtonText() {
        if (btnToggleHyphenation == null) return;
        btnToggleHyphenation.setText(typographyConfig.isHyphenationEnabled() ? "Включены" : "Отключены");
    }

    private void updateEinkContrastButtonText() {
        if (btnToggleEinkContrast == null) return;
        btnToggleEinkContrast.setText("high".equalsIgnoreCase(typographyConfig.getContrastMode()) ? "Высокий" : "Обычный");
    }

    private void updateNightModeButtonText() {
        if (btnToggleNightMode == null) return;
        btnToggleNightMode.setText(typographyConfig.isNightMode() ? "Тёмная" : "Светлая");
    }

    private void updateEpubFontsButtonText() {
        if (btnToggleEpubFonts == null || typographyConfig == null) return;
        btnToggleEpubFonts.setText(typographyConfig.isUseEpubFonts() ? "Из EPUB" : "Из настроек");
    }

    private void updateLineSpacingButtonText() {
        if (btnToggleLineSpacing == null) return;
        btnToggleLineSpacing.setText(String.format(Locale.US, "%.2fx", typographyConfig.getLineSpacingMultiplier()));
    }

    private void cycleLineSpacing() {
        float current = typographyConfig.getLineSpacingMultiplier();
        float next;
        if (current <= 1.05f) {
            next = 1.25f;
        } else if (current <= 1.30f) {
            next = 1.50f;
        } else if (current <= 1.55f) {
            next = 1.75f;
        } else {
            next = 1.00f;
        }
        typographyConfig.setLineSpacingMultiplier(next);
        appSettings.setLineSpacingMultiplier(next);
        updateLineSpacingButtonText();
        if (readerCanvas != null) readerCanvas.setTypographyConfig(typographyConfig);
        repaginateCurrentChapter();
    }

    private String getMarginButtonText() {
        String mode = appSettings.getMarginMode();
        if (com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_EXTRA_NARROW.equalsIgnoreCase(mode)) {
            return "Сверх-узкие (8)";
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_MEDIUM.equalsIgnoreCase(mode)) {
            return "Средние (32)";
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_WIDE.equalsIgnoreCase(mode)) {
            return "Широкие (48)";
        } else {
            return "Узкие (18)";
        }
    }

    private void cycleMarginMode() {
        String current = appSettings.getMarginMode();
        String nextMode;
        if (com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_EXTRA_NARROW.equalsIgnoreCase(current)) {
            nextMode = com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_NARROW;
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_NARROW.equalsIgnoreCase(current)) {
            nextMode = com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_MEDIUM;
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_MEDIUM.equalsIgnoreCase(current)) {
            nextMode = com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_WIDE;
        } else {
            nextMode = com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_EXTRA_NARROW;
        }
        appSettings.setMarginMode(nextMode);
        int px = appSettings.getMarginPaddingPx();
        typographyConfig.setPaddingLeftPx(px);
        typographyConfig.setPaddingRightPx(px);
        if (btnToggleMargins != null) btnToggleMargins.setText(getMarginButtonText());
        if (readerCanvas != null) readerCanvas.setTypographyConfig(typographyConfig);
        repaginateCurrentChapter();
    }

    private void updateVertMarginButtonText() {
        if (btnToggleVertMargins == null) return;
        String mode = typographyConfig.getVerticalMarginMode();
        if ("small".equalsIgnoreCase(mode)) {
            btnToggleVertMargins.setText("Малые");
        } else if ("large".equalsIgnoreCase(mode)) {
            btnToggleVertMargins.setText("Большие");
        } else {
            btnToggleVertMargins.setText("Стандарт");
        }
    }

    private void cycleVertMarginMode() {
        String cur = typographyConfig.getVerticalMarginMode();
        String next;
        if ("small".equalsIgnoreCase(cur)) {
            next = "normal";
        } else if ("normal".equalsIgnoreCase(cur)) {
            next = "large";
        } else {
            next = "small";
        }
        typographyConfig.setVerticalMarginMode(next);
        appSettings.setVerticalMarginMode(next);
        applyFooterOffsetToConfig();
        updateVertMarginButtonText();
        if (readerCanvas != null) readerCanvas.setTypographyConfig(typographyConfig);
        repaginateCurrentChapter();
    }

    private TypographyConfig cloneTypographyConfig(TypographyConfig src) {
        TypographyConfig dst = new TypographyConfig();
        if (src == null) return dst;
        dst.setFontSizeSp(src.getFontSizeSp());
        dst.setFontFamily(src.getFontFamily());
        dst.setLineSpacingMultiplier(src.getLineSpacingMultiplier());
        dst.setParagraphIndentPx(src.getParagraphIndentPx());
        dst.setContrastMode(src.getContrastMode());
        dst.setVerticalMarginMode(src.getVerticalMarginMode());
        dst.setPaddingLeftPx(src.getPaddingLeftPx());
        dst.setPaddingRightPx(src.getPaddingRightPx());
        dst.setPaddingTopPx(src.getPaddingTopPx());
        dst.setPaddingBottomPx(src.getPaddingBottomPx());
        dst.setFooterBottomOffsetDp(src.getFooterBottomOffsetDp());
        dst.setFooterReservedHeightPx(src.getFooterReservedHeightPx());
        dst.setHyphenationEnabled(src.isHyphenationEnabled());
        dst.setTextAlignMode(src.getTextAlignMode());
        dst.setJustifyEnabled(src.isJustifyEnabled());
        dst.setBoldText(src.isBoldText());
        dst.setNightMode(src.isNightMode());
        dst.setTapZoneMode(src.getTapZoneMode());
        dst.setCustomTapZones(src.getCustomTapZones());
        dst.setUseEpubFonts(src.isUseEpubFonts());
        dst.setDensityScale(src.getDensityScale());
        return dst;
    }

    private void applyFooterOffsetToConfig() {
        if (typographyConfig == null || appSettings == null) return;
        int offsetDp = appSettings.getReaderFooterOffsetDp();
        typographyConfig.setFooterBottomOffsetDp(offsetDp);
        float density = getResources().getDisplayMetrics().density;
        int offsetPx = Math.round(offsetDp * density);
        typographyConfig.setPaddingBottomPx(Math.max(typographyConfig.getPaddingBottomPx(), offsetPx));
        typographyConfig.setFooterReservedHeightPx(Math.max(26, Math.round(22 * density)));
    }

    private void updateFooterOffsetButtonText() {
        if (btnToggleFooterOffset == null || appSettings == null) return;
        int dp = appSettings.getReaderFooterOffsetDp();
        if (dp <= 10) {
            btnToggleFooterOffset.setText(dp + " dp (Малый)");
        } else if (dp <= 18) {
            btnToggleFooterOffset.setText(dp + " dp (Darwin)");
        } else if (dp <= 28) {
            btnToggleFooterOffset.setText(dp + " dp (Крупный)");
        } else {
            btnToggleFooterOffset.setText(dp + " dp (Макс.)");
        }
    }

    private void cycleFooterOffsetMode() {
        int cur = appSettings.getReaderFooterOffsetDp();
        int next;
        if (cur < 14) next = 16;
        else if (cur < 22) next = 24;
        else if (cur < 30) next = 32;
        else if (cur < 38) next = 40;
        else next = 8;

        appSettings.setReaderFooterOffsetDp(next);
        typographyConfig.setVerticalMarginMode(typographyConfig.getVerticalMarginMode());
        applyFooterOffsetToConfig();
        updateFooterOffsetButtonText();
        if (readerCanvas != null) readerCanvas.setTypographyConfig(typographyConfig);
        repaginateCurrentChapter();
    }

    private void updateOrientationButtonText() {
        if (btnToggleOrientation == null) return;
        String mode = appSettings.getReaderOrientation();
        if (com.onyx.yandexbooks.core.storage.AppSettings.ORIENTATION_REVERSE_LANDSCAPE.equalsIgnoreCase(mode)) {
            btnToggleOrientation.setText("Обр. ландшафт");
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.ORIENTATION_LANDSCAPE.equalsIgnoreCase(mode)) {
            btnToggleOrientation.setText("Ландшафт");
        } else {
            btnToggleOrientation.setText("Портрет");
        }
    }

    private void cycleOrientation() {
        String current = appSettings.getReaderOrientation();
        String next;
        if (com.onyx.yandexbooks.core.storage.AppSettings.ORIENTATION_PORTRAIT.equalsIgnoreCase(current)) {
            next = com.onyx.yandexbooks.core.storage.AppSettings.ORIENTATION_LANDSCAPE;
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.ORIENTATION_LANDSCAPE.equalsIgnoreCase(current)) {
            next = com.onyx.yandexbooks.core.storage.AppSettings.ORIENTATION_REVERSE_LANDSCAPE;
        } else {
            next = com.onyx.yandexbooks.core.storage.AppSettings.ORIENTATION_PORTRAIT;
        }
        appSettings.setReaderOrientation(next);
        updateOrientationButtonText();
        applyOrientation();
    }

    private void applyOrientation() {
        String mode = appSettings.getReaderOrientation();
        if (com.onyx.yandexbooks.core.storage.AppSettings.ORIENTATION_REVERSE_LANDSCAPE.equalsIgnoreCase(mode)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.ORIENTATION_LANDSCAPE.equalsIgnoreCase(mode)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateOrientationButtonText();
        adjustFormatPanelHeight();
        if (readerCanvas != null) {
            readerCanvas.post(new Runnable() {
                @Override
                public void run() {
                    repaginateCurrentChapter();
                    EpdController.requestFullRefresh(ReaderActivity.this, readerCanvas);
                }
            });
        }
    }

    private void updateMenuControls() {
        if (chapters != null && currentChapterIndex < chapters.size()) {
            String title = getActiveChapterTitle();
            if (title == null || title.trim().isEmpty()) {
                Chapter ch = chapters.get(currentChapterIndex);
                title = ch.getTitle();
            }
            if (title == null || title.trim().isEmpty()) {
                title = "Глава " + (currentChapterIndex + 1);
            }
            if (readerChapterTitleView != null) {
                readerChapterTitleView.setText(title);
            }
        }

        recalculateTotalBookPages();

        if (readerPageSeekbar != null) {
            readerPageSeekbar.setMax(Math.max(0, totalBookPages - 1));
            readerPageSeekbar.setProgress(Math.max(0, Math.min(globalPageIndex - 1, totalBookPages - 1)));
        }

        updatePageCounterText();
    }

    private void updatePageCounterText() {
        if (readerPageCounter != null) {
            recalculateTotalBookPages();
            double percent = calculateCurrentGlobalPercent();
            readerPageCounter.setText(String.format(Locale.getDefault(), "%d/%d (%.0f%%)", globalPageIndex, totalBookPages, percent));
        }
    }

    private void calculateTocPageNumbers(List<EpubParser.TocNode> nodes) {
        if (nodes == null || chapters == null) return;
        for (EpubParser.TocNode node : nodes) {
            int sIdx = node.spineIndex;
            if (sIdx >= 0 && sIdx < chapters.size()) {
                int preceding = 0;
                for (int i = 0; i < sIdx; i++) {
                    preceding += (chapterPageCounts != null && i < chapterPageCounts.length) ? chapterPageCounts[i] : 1;
                }
                int chPages = Math.max(1, (chapterPageCounts != null && sIdx < chapterPageCounts.length) ? chapterPageCounts[sIdx] : 1);
                long chLen = (chapterLengths != null && sIdx < chapterLengths.length) ? chapterLengths[sIdx] : 10000;
                String cachedRaw = chapterTextMemoryCache.get(sIdx);
                long approxRawLen = (cachedRaw != null && !cachedRaw.isEmpty())
                        ? cachedRaw.length()
                        : Math.max(chLen, (long) (chLen * 1.12));
                int pInCh = 0;
                if (node.charOffset > 0 && approxRawLen > 0) {
                    double frac = Math.max(0.0, Math.min(1.0, (double) node.charOffset / (double) Math.max(approxRawLen, node.charOffset)));
                    pInCh = Math.max(0, Math.min(chPages - 1, (int) Math.round(frac * Math.max(0, chPages - 1))));
                }
                node.pageNumber = preceding + pInCh + 1;
            }
            if (node.children != null && !node.children.isEmpty()) {
                calculateTocPageNumbers(node.children);
            }
        }
    }

    private void flattenVisibleNodes(List<EpubParser.TocNode> source, List<EpubParser.TocNode> outList) {
        if (source == null) return;
        for (EpubParser.TocNode node : source) {
            outList.add(node);
            if (node.isExpanded && node.children != null && !node.children.isEmpty()) {
                flattenVisibleNodes(node.children, outList);
            }
        }
    }

    private void showTableOfContentsDialog() {
        try {
            if (chapters == null || chapters.isEmpty()) {
                EinkNotice.show(this, "Оглавление", "Оглавление недоступно для этой книги.");
                return;
            }

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setWindowAnimations(0);
        }

        final float density = getResources().getDisplayMetrics().density;
        DisplayMetrics dm = getResources().getDisplayMetrics();

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_notice_bg);
        int pad = (int) (12 * density);
        root.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(this);
        titleView.setText("Содержание, закладки и цитаты");
        titleView.setTextSize(14);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, (int) (6 * density));
        root.addView(titleView);

        // Сегментированные вкладки: [ Оглавление ] | [ Закладки ] | [ Цитаты ]
        LinearLayout tabsLayout = new LinearLayout(this);
        tabsLayout.setOrientation(LinearLayout.HORIZONTAL);
        tabsLayout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (32 * density)));
        tabsLayout.setGravity(Gravity.CENTER);

        List<EpubParser.TocNode> rawTree = cacheManager.loadTocTree(bookUuid);
        if (rawTree == null || rawTree.isEmpty()) {
            rawTree = new ArrayList<>();
            for (int i = 0; i < chapters.size(); i++) {
                Chapter ch = chapters.get(i);
                EpubParser.TocNode node = new EpubParser.TocNode("ch_" + i, ch.getTitle(), "", 0);
                node.spineIndex = i;
                node.charOffset = 0;
                rawTree.add(node);
            }
        }
        if (chapterLengths == null || chapterLengths.length != chapters.size()) {
            calculateChapterLengths();
        }
        calculateTocPageNumbers(rawTree);

        final List<EpubParser.TocNode> tocTree = rawTree;
        final List<EpubParser.TocNode> visibleTocNodes = new ArrayList<>();
        flattenVisibleNodes(tocTree, visibleTocNodes);

        final Button btnTabChapters = new Button(this);
        btnTabChapters.setText("Оглавление (" + visibleTocNodes.size() + ")");
        btnTabChapters.setTextSize(11);
        btnTabChapters.setSingleLine(true);
        btnTabChapters.setEllipsize(TextUtils.TruncateAt.END);
        btnTabChapters.setTypeface(null, Typeface.BOLD);
        btnTabChapters.setBackgroundResource(R.drawable.btn_eink_primary);
        btnTabChapters.setTextColor(Color.BLACK);
        LinearLayout.LayoutParams tabLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        tabLp1.setMargins(0, 0, (int) (2 * density), 0);
        btnTabChapters.setLayoutParams(tabLp1);

        final Button btnTabBookmarks = new Button(this);
        List<DatabaseHelper.Bookmark> initialBookmarks = dbHelper.getBookmarks(bookUuid);
        btnTabBookmarks.setText("Закладки (" + initialBookmarks.size() + ")");
        btnTabBookmarks.setTextSize(11);
        btnTabBookmarks.setSingleLine(true);
        btnTabBookmarks.setEllipsize(TextUtils.TruncateAt.END);
        btnTabBookmarks.setTypeface(null, Typeface.BOLD);
        btnTabBookmarks.setBackgroundResource(R.drawable.btn_eink);
        btnTabBookmarks.setTextColor(Color.BLACK);
        LinearLayout.LayoutParams tabLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        tabLp2.setMargins((int) (2 * density), 0, (int) (2 * density), 0);
        btnTabBookmarks.setLayoutParams(tabLp2);

        final Button btnTabQuotes = new Button(this);
        List<Quote> initialQuotes = dbHelper.getQuotesForBook(bookUuid);
        btnTabQuotes.setText("Цитаты (" + initialQuotes.size() + ")");
        btnTabQuotes.setTextSize(11);
        btnTabQuotes.setSingleLine(true);
        btnTabQuotes.setEllipsize(TextUtils.TruncateAt.END);
        btnTabQuotes.setTypeface(null, Typeface.BOLD);
        btnTabQuotes.setBackgroundResource(R.drawable.btn_eink);
        btnTabQuotes.setTextColor(Color.BLACK);
        LinearLayout.LayoutParams tabLp3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        tabLp3.setMargins((int) (2 * density), 0, 0, 0);
        btnTabQuotes.setLayoutParams(tabLp3);

        tabsLayout.addView(btnTabChapters);
        tabsLayout.addView(btnTabBookmarks);
        tabsLayout.addView(btnTabQuotes);
        root.addView(tabsLayout);

        View divider = new View(this);
        divider.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density));
        divLp.setMargins(0, (int) (6 * density), 0, (int) (6 * density));
        root.addView(divider, divLp);

        // Контейнер 1: Оглавление (Древовидное оглавление NeoReader)
        final EinkListView listChaptersView = new EinkListView(this);
        listChaptersView.setDivider(new ColorDrawable(Color.LTGRAY));
        listChaptersView.setDividerHeight((int) Math.max(1, density));
        EpdController.configureEinkListView(this, listChaptersView);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        listChaptersView.setLayoutParams(listLp);

        final BaseAdapter tocAdapter = new BaseAdapter() {
            @Override
            public int getCount() { return visibleTocNodes.size(); }
            @Override
            public Object getItem(int pos) { return visibleTocNodes.get(pos); }
            @Override
            public long getItemId(int pos) { return pos; }
            @Override
            public View getView(final int pos, View convertView, ViewGroup parent) {
                LinearLayout rowLayout;
                if (convertView instanceof LinearLayout) {
                    rowLayout = (LinearLayout) convertView;
                } else {
                    rowLayout = new LinearLayout(ReaderActivity.this);
                    rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                    rowLayout.setGravity(Gravity.CENTER_VERTICAL);
                    int pV = (int) (8 * density);
                    int pH = (int) (6 * density);
                    rowLayout.setPadding(pH, pV, pH, pV);

                    TextView tvArrow = new TextView(ReaderActivity.this);
                    tvArrow.setTag("arrow");
                    tvArrow.setTextSize(12);
                    tvArrow.setGravity(Gravity.CENTER);
                    tvArrow.setTextColor(Color.BLACK);
                    LinearLayout.LayoutParams lpArr = new LinearLayout.LayoutParams((int) (22 * density), ViewGroup.LayoutParams.WRAP_CONTENT);
                    rowLayout.addView(tvArrow, lpArr);

                    TextView tvTitle = new TextView(ReaderActivity.this);
                    tvTitle.setTag("title");
                    tvTitle.setTextSize(13);
                    tvTitle.setTextColor(Color.BLACK);
                    tvTitle.setEllipsize(TextUtils.TruncateAt.END);
                    tvTitle.setMaxLines(2);
                    LinearLayout.LayoutParams lpTit = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
                    rowLayout.addView(tvTitle, lpTit);

                    TextView tvPage = new TextView(ReaderActivity.this);
                    tvPage.setTag("page");
                    tvPage.setTextSize(12);
                    tvPage.setTextColor(Color.DKGRAY);
                    tvPage.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
                    LinearLayout.LayoutParams lpPg = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    lpPg.setMargins((int) (6 * density), 0, (int) (4 * density), 0);
                    rowLayout.addView(tvPage, lpPg);
                }

                final EpubParser.TocNode node = visibleTocNodes.get(pos);
                TextView tvArrow = (TextView) rowLayout.findViewWithTag("arrow");
                TextView tvTitle = (TextView) rowLayout.findViewWithTag("title");
                TextView tvPage = (TextView) rowLayout.findViewWithTag("page");

                // Отступ по уровню вложенности (level 0, 1, 2...)
                int indentLeft = (int) ((node.level * 16 + 4) * density);
                rowLayout.setPadding(indentLeft, (int) (8 * density), (int) (6 * density), (int) (8 * density));

                if (node.hasChildren) {
                    tvArrow.setText(node.isExpanded ? "v " : "> ");
                    tvArrow.setVisibility(View.VISIBLE);
                    tvArrow.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            node.isExpanded = !node.isExpanded;
                            visibleTocNodes.clear();
                            flattenVisibleNodes(tocTree, visibleTocNodes);
                            btnTabChapters.setText("Оглавление (" + visibleTocNodes.size() + ")");
                            notifyDataSetChanged();
                            EpdController.requestFullRefresh(ReaderActivity.this, root);
                        }
                    });
                } else {
                    tvArrow.setText("   ");
                    tvArrow.setOnClickListener(null);
                }

                String title = (node.title != null && !node.title.isEmpty()) ? node.title : ("Глава " + (node.spineIndex + 1));
                if (node.spineIndex == currentChapterIndex) {
                    tvTitle.setText(title + " (читается)");
                    tvTitle.setTypeface(null, Typeface.BOLD);
                } else {
                    tvTitle.setText(title);
                    tvTitle.setTypeface(null, Typeface.NORMAL);
                }

                tvPage.setText(String.valueOf(node.pageNumber));

                return rowLayout;
            }
        };
        listChaptersView.setAdapter(tocAdapter);

        listChaptersView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < visibleTocNodes.size()) {
                    EpubParser.TocNode clicked = visibleTocNodes.get(position);
                    dialog.dismiss();
                    hideMenuOverlay();
                    userHasInteracted = true;
                    int targetSpine = clicked.spineIndex;
                    int targetOffset = Math.max(0, clicked.charOffset);
                    if ((targetSpine < 0 || targetSpine >= chapters.size()) && clicked.title != null) {
                        String wantTitle = clicked.title.trim();
                        for (int ci = 0; ci < chapters.size(); ci++) {
                            String ct = chapters.get(ci).getTitle();
                            if (ct != null && ct.trim().equalsIgnoreCase(wantTitle)) {
                                targetSpine = ci;
                                targetOffset = 0;
                                break;
                            }
                        }
                    }
                    if (targetSpine < 0 || targetSpine >= chapters.size()) {
                        targetSpine = 0;
                        targetOffset = 0;
                    }
                    loadChapterAtCharOffset(targetSpine, targetOffset);
                }
            }
        });
        root.addView(listChaptersView);

        // Контейнер 2: Закладки
        final LinearLayout bookmarksContainer = new LinearLayout(this);
        bookmarksContainer.setOrientation(LinearLayout.VERTICAL);
        bookmarksContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        bookmarksContainer.setVisibility(View.GONE);

        Button btnAddBookmark = new Button(this);
        btnAddBookmark.setText("+ Добавить текущую страницу в закладки");
        btnAddBookmark.setTextSize(12);
        btnAddBookmark.setTypeface(null, Typeface.BOLD);
        btnAddBookmark.setTextColor(Color.BLACK);
        btnAddBookmark.setBackgroundResource(R.drawable.btn_eink);
        btnAddBookmark.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (34 * density)));
        bookmarksContainer.addView(btnAddBookmark);

        final EinkListView listBookmarksView = new EinkListView(this);
        listBookmarksView.setDivider(new ColorDrawable(Color.LTGRAY));
        listBookmarksView.setDividerHeight((int) Math.max(1, density));
        EpdController.configureEinkListView(this, listBookmarksView);
        LinearLayout.LayoutParams bmListLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        bmListLp.setMargins(0, (int) (6 * density), 0, 0);
        listBookmarksView.setLayoutParams(bmListLp);
        bookmarksContainer.addView(listBookmarksView);

        final TextView emptyBookmarksView = new TextView(this);
        emptyBookmarksView.setText("Закладок пока нет.\nНажмите кнопку выше, чтобы сохранить место чтения.");
        emptyBookmarksView.setTextSize(13);
        emptyBookmarksView.setTextColor(Color.DKGRAY);
        emptyBookmarksView.setGravity(Gravity.CENTER);
        emptyBookmarksView.setPadding((int) (12 * density), (int) (32 * density), (int) (12 * density), (int) (32 * density));
        bookmarksContainer.addView(emptyBookmarksView);

        root.addView(bookmarksContainer);

        // Адаптер закладок
        final List<DatabaseHelper.Bookmark> bookmarksList = new ArrayList<>(initialBookmarks);
        final BaseAdapter bookmarksAdapter = new BaseAdapter() {
            private final SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
            @Override
            public int getCount() { return bookmarksList.size(); }
            @Override
            public Object getItem(int pos) { return bookmarksList.get(pos); }
            @Override
            public long getItemId(int pos) { return bookmarksList.get(pos).id; }
            @Override
            public View getView(final int pos, View convertView, ViewGroup parent) {
                LinearLayout itemLayout;
                if (convertView instanceof LinearLayout) {
                    itemLayout = (LinearLayout) convertView;
                } else {
                    itemLayout = new LinearLayout(ReaderActivity.this);
                    itemLayout.setOrientation(LinearLayout.HORIZONTAL);
                    itemLayout.setGravity(Gravity.CENTER_VERTICAL);
                    int pV = (int) (8 * density);
                    int pH = (int) (4 * density);
                    itemLayout.setPadding(pH, pV, pH, pV);

                    LinearLayout textCol = new LinearLayout(ReaderActivity.this);
                    textCol.setOrientation(LinearLayout.VERTICAL);
                    textCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

                    TextView tvTitle = new TextView(ReaderActivity.this);
                    tvTitle.setTag("title");
                    tvTitle.setTextSize(13);
                    tvTitle.setTypeface(null, Typeface.BOLD);
                    tvTitle.setTextColor(Color.BLACK);
                    textCol.addView(tvTitle);

                    TextView tvSnippet = new TextView(ReaderActivity.this);
                    tvSnippet.setTag("snippet");
                    tvSnippet.setTextSize(11);
                    tvSnippet.setTextColor(Color.DKGRAY);
                    tvSnippet.setMaxLines(2);
                    tvSnippet.setEllipsize(TextUtils.TruncateAt.END);
                    textCol.addView(tvSnippet);

                    TextView tvDate = new TextView(ReaderActivity.this);
                    tvDate.setTag("date");
                    tvDate.setTextSize(10);
                    tvDate.setTextColor(Color.GRAY);
                    textCol.addView(tvDate);

                    itemLayout.addView(textCol);

                    Button btnDel = new Button(ReaderActivity.this);
                    btnDel.setTag("delete");
                    btnDel.setText("X");
                    btnDel.setTextSize(13);
                    btnDel.setTypeface(null, Typeface.BOLD);
                    btnDel.setTextColor(Color.BLACK);
                    btnDel.setBackgroundResource(R.drawable.btn_eink);
                    LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams((int) (32 * density), (int) (32 * density));
                    delLp.setMargins((int) (6 * density), 0, 0, 0);
                    btnDel.setLayoutParams(delLp);
                    itemLayout.addView(btnDel);
                }

                final DatabaseHelper.Bookmark bm = bookmarksList.get(pos);
                TextView tvTitle = (TextView) itemLayout.findViewWithTag("title");
                TextView tvSnippet = (TextView) itemLayout.findViewWithTag("snippet");
                TextView tvDate = (TextView) itemLayout.findViewWithTag("date");
                Button btnDel = (Button) itemLayout.findViewWithTag("delete");

                tvTitle.setText(bm.title != null ? bm.title : ("Глава " + (bm.chapterIndex + 1) + ", стр. " + (bm.pageIndex + 1)));
                tvSnippet.setText(bm.snippet != null ? bm.snippet : "");
                tvDate.setText(sdf.format(new Date(bm.timestamp)));

                btnDel.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dbHelper.deleteBookmark(bm.id);
                        bookmarksList.remove(pos);
                        notifyDataSetChanged();
                        btnTabBookmarks.setText("Закладки (" + bookmarksList.size() + ")");
                        if (bookmarksList.isEmpty()) {
                            listBookmarksView.setVisibility(View.GONE);
                            emptyBookmarksView.setVisibility(View.VISIBLE);
                        }
                        EpdController.requestFullRefresh(ReaderActivity.this, root);
                        EinkNotice.show(ReaderActivity.this, "Закладки", "Закладка удалена.");
                    }
                });

                return itemLayout;
            }
        };
        listBookmarksView.setAdapter(bookmarksAdapter);

        final Runnable updateBookmarksVisibility = new Runnable() {
            @Override
            public void run() {
                if (bookmarksList.isEmpty()) {
                    listBookmarksView.setVisibility(View.GONE);
                    emptyBookmarksView.setVisibility(View.VISIBLE);
                } else {
                    listBookmarksView.setVisibility(View.VISIBLE);
                    emptyBookmarksView.setVisibility(View.GONE);
                }
            }
        };
        updateBookmarksVisibility.run();

        listBookmarksView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                DatabaseHelper.Bookmark bm = bookmarksList.get(position);
                dialog.dismiss();
                hideMenuOverlay();
                isInitialLoading = false;
                userHasInteracted = true;
                loadChapter(bm.chapterIndex, bm.pageIndex);
            }
        });

        // Добавление закладки
        btnAddBookmark.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String chTitle = "Глава " + (currentChapterIndex + 1);
                if (chapters != null && currentChapterIndex < chapters.size()) {
                    String t = chapters.get(currentChapterIndex).getTitle();
                    if (t != null && !t.trim().isEmpty()) {
                        chTitle = t;
                    }
                }
                String title = chTitle + ", стр. " + (currentPageIndex + 1);
                String snippet = "";
                if (currentPages != null && currentPageIndex < currentPages.size()) {
                    TextPaginator.Page page = currentPages.get(currentPageIndex);
                    if (page.lines != null && !page.lines.isEmpty()) {
                        StringBuilder sb = new StringBuilder();
                        for (TextPaginator.Line line : page.lines) {
                            if (line != null && line.text != null) sb.append(line.text).append(" ");
                            if (sb.length() > 90) break;
                        }
                        snippet = sb.toString().trim();
                        if (snippet.length() > 90) {
                            snippet = snippet.substring(0, 90) + "...";
                        }
                    }
                }
                long newId = dbHelper.addBookmark(bookUuid, currentChapterIndex, currentPageIndex, title, snippet);
                DatabaseHelper.Bookmark newBm = new DatabaseHelper.Bookmark();
                newBm.id = newId;
                newBm.bookUuid = bookUuid;
                newBm.chapterIndex = currentChapterIndex;
                newBm.pageIndex = currentPageIndex;
                newBm.title = title;
                newBm.snippet = snippet;
                newBm.timestamp = System.currentTimeMillis();

                bookmarksList.add(0, newBm);
                bookmarksAdapter.notifyDataSetChanged();
                btnTabBookmarks.setText("Закладки (" + bookmarksList.size() + ")");
                updateBookmarksVisibility.run();
                EpdController.requestFullRefresh(ReaderActivity.this, root);
                EinkNotice.show(ReaderActivity.this, "Закладки", "Закладка сохранена:\n" + title);
            }
        });

        // Контейнер 3: Цитаты и заметки
        final LinearLayout quotesContainer = new LinearLayout(this);
        quotesContainer.setOrientation(LinearLayout.VERTICAL);
        quotesContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        quotesContainer.setVisibility(View.GONE);

        final List<Quote> quotesList = new ArrayList<>(initialQuotes);

        Button btnExportQuotes = new Button(this);
        btnExportQuotes.setText("Экспорт цитат в файл (.md)");
        btnExportQuotes.setTextSize(12);
        btnExportQuotes.setTypeface(null, Typeface.BOLD);
        btnExportQuotes.setTextColor(Color.BLACK);
        btnExportQuotes.setBackgroundResource(R.drawable.btn_eink);
        btnExportQuotes.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (34 * density)));
        btnExportQuotes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportBookQuotesToFile(quotesList);
            }
        });
        quotesContainer.addView(btnExportQuotes);

        final EinkListView listQuotesView = new EinkListView(this);
        listQuotesView.setDivider(new ColorDrawable(Color.LTGRAY));
        listQuotesView.setDividerHeight((int) Math.max(1, density));
        EpdController.configureEinkListView(this, listQuotesView);
        LinearLayout.LayoutParams qListLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        qListLp.setMargins(0, (int) (6 * density), 0, 0);
        listQuotesView.setLayoutParams(qListLp);
        quotesContainer.addView(listQuotesView);

        final TextView emptyQuotesView = new TextView(this);
        emptyQuotesView.setText("Цитат пока нет.\nУдерживайте палец на слове на странице, чтобы выделить фрагмент и сохранить цитату.");
        emptyQuotesView.setTextSize(13);
        emptyQuotesView.setTextColor(Color.DKGRAY);
        emptyQuotesView.setGravity(Gravity.CENTER);
        emptyQuotesView.setPadding((int) (12 * density), (int) (32 * density), (int) (12 * density), (int) (32 * density));
        quotesContainer.addView(emptyQuotesView);

        root.addView(quotesContainer);

        final Runnable updateQuotesVisibility = new Runnable() {
            @Override
            public void run() {
                if (quotesList.isEmpty()) {
                    listQuotesView.setVisibility(View.GONE);
                    emptyQuotesView.setVisibility(View.VISIBLE);
                } else {
                    listQuotesView.setVisibility(View.VISIBLE);
                    emptyQuotesView.setVisibility(View.GONE);
                }
            }
        };
        updateQuotesVisibility.run();

        final BaseAdapter quotesAdapter = new BaseAdapter() {
            private final SimpleDateFormat qSdf = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
            @Override
            public int getCount() { return quotesList.size(); }
            @Override
            public Object getItem(int pos) { return quotesList.get(pos); }
            @Override
            public long getItemId(int pos) { return quotesList.get(pos).id; }
            @Override
            public View getView(final int pos, View convertView, ViewGroup parent) {
                LinearLayout rowLayout;
                if (convertView instanceof LinearLayout) {
                    rowLayout = (LinearLayout) convertView;
                } else {
                    rowLayout = new LinearLayout(ReaderActivity.this);
                    rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                    rowLayout.setGravity(Gravity.CENTER_VERTICAL);
                    rowLayout.setPadding((int) (4 * density), (int) (8 * density), (int) (4 * density), (int) (8 * density));

                    LinearLayout col = new LinearLayout(ReaderActivity.this);
                    col.setOrientation(LinearLayout.VERTICAL);
                    col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

                    TextView tvMeta = new TextView(ReaderActivity.this);
                    tvMeta.setTag("qmeta");
                    tvMeta.setTextSize(11);
                    tvMeta.setTypeface(null, Typeface.BOLD);
                    tvMeta.setTextColor(Color.DKGRAY);
                    col.addView(tvMeta);

                    TextView tvText = new TextView(ReaderActivity.this);
                    tvText.setTag("qtext");
                    tvText.setTextSize(13);
                    tvText.setTextColor(Color.BLACK);
                    tvText.setMaxLines(3);
                    tvText.setEllipsize(TextUtils.TruncateAt.END);
                    col.addView(tvText);

                    TextView tvNote = new TextView(ReaderActivity.this);
                    tvNote.setTag("qnote");
                    tvNote.setTextSize(11);
                    tvNote.setTypeface(null, Typeface.ITALIC);
                    tvNote.setTextColor(Color.DKGRAY);
                    tvNote.setMaxLines(2);
                    tvNote.setEllipsize(TextUtils.TruncateAt.END);
                    col.addView(tvNote);

                    rowLayout.addView(col);

                    Button btnDelQ = new Button(ReaderActivity.this);
                    btnDelQ.setTag("qdel");
                    btnDelQ.setText("X");
                    btnDelQ.setTextSize(13);
                    btnDelQ.setTypeface(null, Typeface.BOLD);
                    btnDelQ.setTextColor(Color.BLACK);
                    btnDelQ.setBackgroundResource(R.drawable.btn_eink);
                    LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams((int) (32 * density), (int) (32 * density));
                    delLp.setMargins((int) (6 * density), 0, 0, 0);
                    btnDelQ.setLayoutParams(delLp);
                    rowLayout.addView(btnDelQ);
                }

                final Quote q = quotesList.get(pos);
                TextView tvMeta = (TextView) rowLayout.findViewWithTag("qmeta");
                TextView tvText = (TextView) rowLayout.findViewWithTag("qtext");
                TextView tvNote = (TextView) rowLayout.findViewWithTag("qnote");
                Button btnDelQ = (Button) rowLayout.findViewWithTag("qdel");

                tvMeta.setText("Гл. " + (q.chapterIndex + 1) + " • " + qSdf.format(new Date(q.createdAt > 0 ? q.createdAt : System.currentTimeMillis())));
                tvText.setText("«" + (q.text != null ? q.text : "") + "»");
                if (q.note != null && !q.note.trim().isEmpty()) {
                    tvNote.setVisibility(View.VISIBLE);
                    tvNote.setText("Заметка: " + q.note.trim());
                } else {
                    tvNote.setVisibility(View.GONE);
                }

                btnDelQ.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dbHelper.deleteQuote(q.id);
                        if (q.quoteUuid != null && !q.quoteUuid.isEmpty()) {
                            apiClient.deleteBookQuote(q.quoteUuid, null);
                        }
                        quotesList.remove(pos);
                        notifyDataSetChanged();
                        btnTabQuotes.setText("Цитаты (" + quotesList.size() + ")");
                        updateQuotesVisibility.run();
                        invalidateChapterQuotesCache();
                        refreshCanvasChapterQuotes(true);
                        EpdController.requestFullRefresh(ReaderActivity.this, root);
                    }
                });
                return rowLayout;
            }
        };
        listQuotesView.setAdapter(quotesAdapter);
        listQuotesView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < quotesList.size()) {
                    Quote q = quotesList.get(position);
                    dialog.dismiss();
                    hideMenuOverlay();
                    isInitialLoading = false;
                    userHasInteracted = true;
                    loadChapterAtCharOffset(q.chapterIndex, Math.max(0, q.startOffset));
                }
            }
        });

        // Переключение вкладок Оглавление / Закладки / Цитаты
        btnTabChapters.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnTabChapters.setBackgroundResource(R.drawable.btn_eink_primary);
                btnTabChapters.setTextColor(Color.BLACK);
                btnTabBookmarks.setBackgroundResource(R.drawable.btn_eink);
                btnTabBookmarks.setTextColor(Color.BLACK);
                btnTabQuotes.setBackgroundResource(R.drawable.btn_eink);
                btnTabQuotes.setTextColor(Color.BLACK);
                listChaptersView.setVisibility(View.VISIBLE);
                bookmarksContainer.setVisibility(View.GONE);
                quotesContainer.setVisibility(View.GONE);
                EpdController.requestFullRefresh(ReaderActivity.this, root);
            }
        });

        btnTabBookmarks.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnTabBookmarks.setBackgroundResource(R.drawable.btn_eink_primary);
                btnTabBookmarks.setTextColor(Color.BLACK);
                btnTabChapters.setBackgroundResource(R.drawable.btn_eink);
                btnTabChapters.setTextColor(Color.BLACK);
                btnTabQuotes.setBackgroundResource(R.drawable.btn_eink);
                btnTabQuotes.setTextColor(Color.BLACK);
                listChaptersView.setVisibility(View.GONE);
                bookmarksContainer.setVisibility(View.VISIBLE);
                quotesContainer.setVisibility(View.GONE);
                EpdController.requestFullRefresh(ReaderActivity.this, root);
            }
        });

        btnTabQuotes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnTabQuotes.setBackgroundResource(R.drawable.btn_eink_primary);
                btnTabQuotes.setTextColor(Color.BLACK);
                btnTabChapters.setBackgroundResource(R.drawable.btn_eink);
                btnTabChapters.setTextColor(Color.BLACK);
                btnTabBookmarks.setBackgroundResource(R.drawable.btn_eink);
                btnTabBookmarks.setTextColor(Color.BLACK);
                listChaptersView.setVisibility(View.GONE);
                bookmarksContainer.setVisibility(View.GONE);
                quotesContainer.setVisibility(View.VISIBLE);
                EpdController.requestFullRefresh(ReaderActivity.this, root);
            }
        });

        Button btnClose = new Button(this);
        btnClose.setText("Закрыть");
        btnClose.setTextSize(12);
        btnClose.setTypeface(null, Typeface.BOLD);
        btnClose.setTextColor(Color.BLACK);
        btnClose.setBackgroundResource(R.drawable.btn_eink);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (36 * density));
        closeLp.setMargins(0, (int) (6 * density), 0, 0);
        btnClose.setLayoutParams(closeLp);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        root.addView(btnClose);

        dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final int targetW = Math.min((int) (dm.widthPixels * 0.92), (int) (680 * density));
        final int targetH = (int) (dm.heightPixels * 0.88);

        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (HardwareKeyHandler.isPageKey(keyCode, event)) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        EinkListView activeList = (listChaptersView.getVisibility() == View.VISIBLE) ? listChaptersView : listBookmarksView;
                        if (activeList != null && activeList.getVisibility() == View.VISIBLE && activeList.getCount() > 0) {
                            if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == 93 || keyCode == 167 || keyCode == 261) {
                                activeList.pageScrollEink(true);
                            } else {
                                activeList.pageScrollEink(false);
                            }
                            EpdController.requestFullRefresh(ReaderActivity.this, root);
                        }
                    }
                    return true;
                }
                return false;
            }
        });

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
                    dialog.getWindow().setLayout(targetW, targetH);
                }
                EpdController.requestFullRefresh(ReaderActivity.this, root);
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                forceEpdRefresh();
            }
        });
        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            dialog.getWindow().setLayout(targetW, targetH);
        }
        forceEpdRefresh();
        } catch (Throwable t) {
            Log.e(TAG, "Error displaying TOC dialog", t);
            EinkNotice.show(this, "Оглавление", "Ошибка отображения оглавления книги.");
        }
    }

    private void showProgressDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setWindowAnimations(0);
        }

        float density = getResources().getDisplayMetrics().density;
        DisplayMetrics dm = getResources().getDisplayMetrics();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_notice_bg);
        int pad = (int) (16 * density);
        root.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(this);
        titleView.setText("Прогресс чтения");
        titleView.setTextSize(14);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, (int) (8 * density));
        root.addView(titleView);

        View divider = new View(this);
        divider.setBackgroundColor(Color.BLACK);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density)));

        double percent = calculateCurrentGlobalPercent();
        int totalCh = chapters != null ? chapters.size() : 1;
        int totalP = currentPages != null ? currentPages.size() : 1;

        TextView infoView = new TextView(this);
        infoView.setTextSize(13);
        infoView.setTextColor(Color.BLACK);
        infoView.setLineSpacing(4f, 1.2f);
        infoView.setPadding(0, (int) (12 * density), 0, (int) (12 * density));
        String info = "Книга: " + (bookTitle != null ? bookTitle : "") + "\n\n"
                + String.format(Locale.getDefault(), "Прочитано: %.1f%%\n", percent)
                + "Глава: " + (currentChapterIndex + 1) + " из " + totalCh + "\n"
                + "Страница главы: " + (currentPageIndex + 1) + " из " + totalP;
        infoView.setText(info);
        root.addView(infoView);

        final Button btnSyncNow = new Button(this);
        btnSyncNow.setText("Синхронизировать");
        btnSyncNow.setTextSize(12);
        btnSyncNow.setTypeface(null, Typeface.BOLD);
        btnSyncNow.setTextColor(Color.BLACK);
        btnSyncNow.setBackgroundResource(R.drawable.btn_eink_primary);
        btnSyncNow.setPadding(0, 0, 0, 0);
        btnSyncNow.setSingleLine(true);
        btnSyncNow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (42 * density)));
        btnSyncNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnSyncNow.setEnabled(false);
                btnSyncNow.setText("Синхронизация...");
                saveProgress();
                syncManager.flushOfflineQueue();
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        btnSyncNow.setText("Синхронизировано");
                        EinkNotice.show(ReaderActivity.this, "Синхронизация", "Прогресс отправлен в облако Яндекс Книг.");
                    }
                }, 1000);
            }
        });
        root.addView(btnSyncNow);

        Button btnClose = new Button(this);
        btnClose.setText("Закрыть");
        btnClose.setTextSize(12);
        btnClose.setTypeface(null, Typeface.BOLD);
        btnClose.setTextColor(Color.BLACK);
        btnClose.setBackgroundResource(R.drawable.btn_eink);
        btnClose.setPadding(0, 0, 0, 0);
        btnClose.setSingleLine(true);
        LinearLayout.LayoutParams lpClose = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (40 * density));
        lpClose.setMargins(0, (int) (8 * density), 0, 0);
        btnClose.setLayoutParams(lpClose);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        root.addView(btnClose);

        dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final int targetW = Math.min((int) (dm.widthPixels * 0.85), (int) (520 * density));

        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (HardwareKeyHandler.isPageKey(keyCode, event)) {
                    return true;
                }
                return false;
            }
        });

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
                    dialog.getWindow().setLayout(targetW, ViewGroup.LayoutParams.WRAP_CONTENT);
                }
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                forceEpdRefresh();
            }
        });

        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            dialog.getWindow().setLayout(targetW, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        forceEpdRefresh();
    }

    private void calculateChapterLengths() {
        if (chapters == null || chapters.isEmpty()) {
            chapterLengths = new long[0];
            chapterPageCounts = new int[0];
            chapterPageExact = new boolean[0];
            totalBookLength = 0;
            totalBookPages = 1;
            globalPageIndex = 1;
            return;
        }
        chapterLengths = new long[chapters.size()];
        if (chapterPageCounts == null || chapterPageCounts.length != chapters.size()) {
            chapterPageCounts = new int[chapters.size()];
        }
        if (chapterPageExact == null || chapterPageExact.length != chapters.size()) {
            chapterPageExact = new boolean[chapters.size()];
        }
        totalBookLength = 0;
        for (int i = 0; i < chapters.size(); i++) {
            Chapter ch = chapters.get(i);
            long len = ch.getCharCount();
            if (len <= 0) {
                long fileBytes = cacheManager.getChapterLength(bookUuid, ch.getId());
                if (fileBytes > 0) {
                    len = Math.max(1L, Math.round(fileBytes * 0.52));
                }
            }
            if (len <= 0) {
                len = 10000; // Оценочная длина по умолчанию
            }
            chapterLengths[i] = len;
            totalBookLength += len;
        }

        // Проверяем точный кэш постраничной разбивки для текущих настроек экрана (v1.7.0)
        int[] dims = resolveReaderCanvasDims();
        String sig = buildPrepagSignature(dims[0], dims[1], typographyConfig);
        currentPrepagConfigSig = sig;
        int[] cachedCounts = (cacheManager != null && bookUuid != null)
                ? cacheManager.loadPageCountsCache(bookUuid, sig, chapters.size()) : null;
        if (cachedCounts != null && cachedCounts.length == chapters.size()) {
            for (int i = 0; i < chapters.size(); i++) {
                chapterPageCounts[i] = Math.max(1, cachedCounts[i]);
                chapterPageExact[i] = true;
            }
        } else {
            for (int i = 0; i < chapters.size(); i++) {
                chapterPageExact[i] = false;
            }
            // Откладываем фоновый пересчёт всей книги, чтобы он не отбирал CPU/Flash I/O у открытия первой видимой страницы
            scheduleDelayedPrepagination(dims[0], dims[1], sig, 1200L);
        }
        recalculateTotalBookPages();
    }

    private void scheduleDelayedPrepagination(final int screenW, final int screenH, final String sig, long delayMs) {
        prepaginationTaskId.incrementAndGet();
        if (pendingPrepagRunnable != null) {
            autoSaveHandler.removeCallbacks(pendingPrepagRunnable);
            pendingPrepagRunnable = null;
        }
        pendingPrepagRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && sig != null && sig.equals(currentPrepagConfigSig)) {
                    prepaginateBookInBackground(screenW, screenH, sig);
                }
            }
        };
        autoSaveHandler.postDelayed(pendingPrepagRunnable, delayMs);
    }

    private int[] resolveReaderCanvasDims() {
        int w = readerCanvas != null ? readerCanvas.getWidth() : 0;
        int h = readerCanvas != null ? readerCanvas.getHeight() : 0;
        if (w <= 0 || h <= 0) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            w = dm.widthPixels;
            h = dm.heightPixels;
        }
        return new int[]{Math.max(100, w), Math.max(100, h)};
    }

    private String buildPrepagSignature(int screenW, int screenH, TypographyConfig cfg) {
        if (cfg == null) return screenW + "x" + screenH;
        return screenW + "x" + screenH
                + "|fs=" + cfg.getFontSizeSp()
                + "|ff=" + cfg.getFontFamily()
                + "|ls=" + String.format(Locale.US, "%.2f", cfg.getLineSpacingMultiplier())
                + "|in=" + cfg.getParagraphIndentPx()
                + "|bold=" + (cfg.isBoldText() ? 1 : 0)
                + "|hyph=" + (cfg.isHyphenationEnabled() ? 1 : 0)
                + "|align=" + cfg.getTextAlignMode()
                + "|pl=" + cfg.getPaddingLeftPx()
                + "|pr=" + cfg.getPaddingRightPx()
                + "|pt=" + cfg.getPaddingTopPx()
                + "|pb=" + cfg.getPaddingBottomPx()
                + "|fr=" + cfg.getFooterReservedHeightPx()
                + "|epubf=" + (cfg.isUseEpubFonts() ? 1 : 0);
    }

    private void prepaginateBookInBackground(final int screenW, final int screenH, final String sig) {
        if (chapters == null || chapters.isEmpty() || cacheManager == null || bookUuid == null || readerCanvas == null) {
            return;
        }
        final long myTaskId = prepaginationTaskId.incrementAndGet();
        final List<Chapter> chaptersSnap = new ArrayList<>(chapters);
        final TypographyConfig cfgCopy = cloneTypographyConfig(typographyConfig);
        final Paint basePaint = readerCanvas.buildPaintForConfig(cfgCopy);
        final String bookId = bookUuid;

        prepaginationExecutor.execute(new Runnable() {
            @Override
            public void run() {
                int oldPriority = Thread.currentThread().getPriority();
                try {
                    try {
                        Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
                    } catch (Throwable ignored) {}
                    int n = chaptersSnap.size();
                    final int[] exactCounts = new int[n];
                    TextPaginator bgPaginator = new TextPaginator();
                    for (int i = 0; i < n; i++) {
                        if (myTaskId != prepaginationTaskId.get()) {
                            return;
                        }
                        if (chapterPageExact != null && i < chapterPageExact.length && chapterPageExact[i]
                                && chapterPageCounts != null && i < chapterPageCounts.length && chapterPageCounts[i] > 0) {
                            exactCounts[i] = chapterPageCounts[i];
                            continue;
                        }
                        Chapter ch = chaptersSnap.get(i);
                        String chId = ch.getId();
                        String text = chapterTextMemoryCache.get(i);
                        if (text == null) {
                            text = cacheManager.loadChapter(bookId, chId);
                        }
                        if (text == null) {
                            text = cacheManager.loadChapter(bookId, String.valueOf(i));
                        }
                        if (text == null || text.trim().isEmpty()) {
                            String t = ch.getTitle();
                            text = (t != null && !t.startsWith("ch_") && !t.startsWith("ch-")) ? t : "";
                        }
                        List<TextPaginator.Page> pList = bgPaginator.paginateCancellable(
                                text, screenW, screenH, basePaint, cfgCopy, prepaginationTaskId, myTaskId);
                        if (pList == null || myTaskId != prepaginationTaskId.get()) {
                            return;
                        }
                        exactCounts[i] = !pList.isEmpty() ? pList.size() : 1;
                        Thread.yield();
                    }
                    if (myTaskId != prepaginationTaskId.get()) {
                        return;
                    }
                    cacheManager.savePageCountsCache(bookId, sig, exactCounts);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (myTaskId != prepaginationTaskId.get() || !sig.equals(currentPrepagConfigSig)) {
                                return;
                            }
                            if (chapterPageCounts == null || chapterPageCounts.length != exactCounts.length) {
                                chapterPageCounts = new int[exactCounts.length];
                            }
                            if (chapterPageExact == null || chapterPageExact.length != exactCounts.length) {
                                chapterPageExact = new boolean[exactCounts.length];
                            }
                            for (int i = 0; i < exactCounts.length; i++) {
                                chapterPageCounts[i] = exactCounts[i];
                                chapterPageExact[i] = true;
                            }
                            recalculateTotalBookPages();
                            updateMenuControls();
                            if (!currentPages.isEmpty() && currentPageIndex < currentPages.size() && readerCanvas != null) {
                                String title = getActiveChapterTitle();
                                double percent = calculateCurrentGlobalPercent();
                                int totalCh = chapters != null ? chapters.size() : 1;
                                readerCanvas.setPage(
                                        currentPages.get(currentPageIndex),
                                        globalPageIndex,
                                        totalBookPages,
                                        title,
                                        currentChapterIndex,
                                        totalCh,
                                        percent,
                                        currentPageIndex + 1,
                                        currentPages.size()
                                );
                            }
                        }
                    });
                } catch (Throwable ignored) {
                } finally {
                    try {
                        Thread.currentThread().setPriority(oldPriority);
                    } catch (Throwable ignored) {}
                }
            }
        });
    }

    private void recalculateTotalBookPages() {
        if (chapters == null || chapters.isEmpty()) {
            totalBookPages = 1;
            globalPageIndex = 1;
            return;
        }
        double safeAvgChars = (avgCharsPerPage > 50.0) ? avgCharsPerPage : 750.0;
        int total = 0;
        int preceding = 0;

        for (int i = 0; i < chapters.size(); i++) {
            boolean isExact = (chapterPageExact != null && i < chapterPageExact.length && chapterPageExact[i]);
            int pages = (chapterPageCounts != null && i < chapterPageCounts.length) ? chapterPageCounts[i] : 0;
            if (!isExact && pages <= 0) {
                long chLen = (chapterLengths != null && i < chapterLengths.length) ? chapterLengths[i] : 10000;
                pages = Math.max(1, (int) Math.round(chLen / safeAvgChars));
                if (chapterPageCounts != null && i < chapterPageCounts.length) {
                    chapterPageCounts[i] = pages;
                }
            } else if (pages <= 0) {
                pages = 1;
            }
            if (i < currentChapterIndex) {
                preceding += pages;
            }
            total += pages;
        }

        totalBookPages = Math.max(1, total);
        globalPageIndex = Math.max(1, Math.min(totalBookPages, preceding + currentPageIndex + 1));
    }

    private void handleCanvasLinkTap(final String rawTarget, final String rawLabel) {
        if (rawTarget == null || rawTarget.trim().isEmpty()) return;
        final String target = rawTarget.trim();
        final String lower = target.toLowerCase(Locale.US);

        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) {
            new AlertDialog.Builder(this)
                    .setTitle("Внешняя ссылка")
                    .setMessage("Открыть внешнюю ссылку во внешнем браузере?\n\n" + target)
                    .setPositiveButton("Открыть в браузере", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            try {
                                Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(target));
                                startActivity(browserIntent);
                            } catch (Throwable t) {
                                EinkNotice.show(ReaderActivity.this, "Ошибка ссылки", "Не удалось открыть браузер для:\n" + target);
                            }
                        }
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }

        final boolean looksLikeFootnoteLabel = rawLabel != null
                && rawLabel.trim().matches("^\\s*[\\[\\(]?[0-9\\*†‡§]+[\\]\\)]?\\s*$");
        if (!looksLikeFootnoteLabel && cacheManager != null && bookUuid != null
                && chapters != null && !chapters.isEmpty() && !lower.contains("note") && !lower.contains("fn")) {
            EpubParser.TocNode directNode = findTocNodeByTargetHref(cacheManager.loadTocTree(bookUuid), target);
            if (directNode != null && directNode.spineIndex >= 0 && directNode.spineIndex < chapters.size()) {
                loadChapterAtCharOffset(directNode.spineIndex, Math.max(0, directNode.charOffset));
                return;
            }
        }

        final String fallbackTitle = (rawLabel != null && !rawLabel.trim().isEmpty())
                ? "Ссылка: " + rawLabel.trim()
                : "Внутренняя ссылка";
        paginationExecutor.execute(new Runnable() {
            @Override
            public void run() {
                String[] preview = null;
                try {
                    File epubFile = (cacheManager != null && bookUuid != null)
                            ? cacheManager.getEpubFile(bookUuid) : null;
                    if (epubFile != null && epubFile.exists() && epubFile.length() > 0) {
                        preview = EpubFormatting.extractInternalLinkPreview(epubFile, target);
                    }
                } catch (Throwable ignored) {}
                final String dialogTitle = (preview != null && preview.length > 0 && preview[0] != null && !preview[0].isEmpty())
                        ? preview[0] : fallbackTitle;
                final String dialogBody = (preview != null && preview.length > 1 && preview[1] != null && !preview[1].isEmpty())
                        ? preview[1]
                        : "Переход по внутренней ссылке: " + target + "\n\nПодробный текст раздела не найден в EPUB.";
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing()) {
                            EinkNotice.showReadable(ReaderActivity.this, dialogTitle, dialogBody, getReadablePopupTextSizeSp());
                        }
                    }
                });
            }
        });
    }

    private EpubParser.TocNode findTocNodeByTargetHref(List<EpubParser.TocNode> nodes, String rawTarget) {
        if (nodes == null || rawTarget == null || rawTarget.isEmpty()) return null;
        String cleanTarget = rawTarget.trim();
        String targetFile = cleanTarget.contains("#") ? cleanTarget.substring(0, cleanTarget.indexOf('#')) : cleanTarget;
        String targetAnchor = cleanTarget.contains("#") ? cleanTarget.substring(cleanTarget.indexOf('#') + 1) : "";
        String targetBase = targetFile.contains("/") ? targetFile.substring(targetFile.lastIndexOf('/') + 1) : targetFile;
        for (EpubParser.TocNode node : nodes) {
            if (node.rawHref != null && !node.rawHref.isEmpty()) {
                String nh = node.rawHref.trim();
                String nFile = nh.contains("#") ? nh.substring(0, nh.indexOf('#')) : nh;
                String nAnchor = nh.contains("#") ? nh.substring(nh.indexOf('#') + 1) : "";
                String nBase = nFile.contains("/") ? nFile.substring(nFile.lastIndexOf('/') + 1) : nFile;
                if (nh.equalsIgnoreCase(cleanTarget)
                        || (!targetAnchor.isEmpty() && targetAnchor.equalsIgnoreCase(nAnchor)
                                && (targetBase.isEmpty() || targetBase.equalsIgnoreCase(nBase)))
                        || (targetAnchor.isEmpty() && nAnchor.isEmpty() && !targetBase.isEmpty() && targetBase.equalsIgnoreCase(nBase))) {
                    return node;
                }
            }
            if (node.children != null && !node.children.isEmpty()) {
                EpubParser.TocNode found = findTocNodeByTargetHref(node.children, rawTarget);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void showLoadingOverlay(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (readerLoadingText != null && message != null) {
                    readerLoadingText.setText(message);
                }
                if (readerLoadingOverlay != null) {
                    readerLoadingOverlay.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void hideLoadingOverlay() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (readerLoadingOverlay != null) {
                    readerLoadingOverlay.setVisibility(View.GONE);
                }
            }
        });
    }

    private void loadBookData() {
        if (bookUuid != null && !bookUuid.isEmpty()) {
            dbHelper.updateBookShelf(bookUuid, "reading");
            loadBookFootnotesAsync();
            loadBookQuotesCloudSyncAsync();
        }
        // Проверяем, есть ли уже главы в локальной БД
        List<Chapter> localChapters = dbHelper.getChapters(bookUuid);
        boolean isCacheValid = false;
        if (localChapters != null && !localChapters.isEmpty()) {
            isCacheValid = cacheManager.isChapterCacheValid(bookUuid, localChapters);
            if (isCacheValid && !cacheManager.isFormattedCacheCurrent(bookUuid)) {
                File epubCheck = cacheManager.getEpubFile(bookUuid);
                if (epubCheck != null && epubCheck.exists() && epubCheck.length() > 0) {
                    isCacheValid = false;
                }
            }
        }

        if (isCacheValid) {
            // Главы уже в базе данных и валидны: открываем мгновенно без блокировки UI-потока
            chapters = localChapters;
            if (readerCanvas != null && typographyConfig.isUseEpubFonts()) {
                readerCanvas.setEpubFontPaths(cacheManager.loadBookEpubFonts(bookUuid));
            }
            resolveInitialPositionAndOpen();
            // Загружаем дерево оглавления в фоне (название текущей главы уже доступно из chapters)
            prepaginationExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        final List<EpubParser.TocNode> tree = cacheManager.loadTocTree(bookUuid);
                        cachedTocTree = tree;
                    } catch (Throwable ignored) {}
                }
            });
        } else {
            // Главы еще не извлечены или повреждены предыдущей версией: запускаем парсинг
            showLoadingOverlay("Подготовка книги к чтению...");
            cacheManager.reparseAndSaveBook(bookUuid, bookTitle, new CacheManager.BookReadyCallback() {
                @Override
                public void onReady(List<Chapter> loadedChapters) {
                    hideLoadingOverlay();
                    chapters = loadedChapters;
                    cachedTocTree = cacheManager.loadTocTree(bookUuid);
                    if (readerCanvas != null) {
                        readerCanvas.setEpubFontPaths(cacheManager.loadBookEpubFonts(bookUuid));
                        readerCanvas.setTypographyConfig(typographyConfig);
                    }
                    if (!chapters.isEmpty()) {
                        resolveInitialPositionAndOpen();
                    } else {
                        EinkNotice.show(ReaderActivity.this, "Ошибка книги", "Главы книги не найдены.");
                    }
                }

                @Override
                public void onError(String errorMessage) {
                    hideLoadingOverlay();
                    chapters = dbHelper.getChapters(bookUuid);
                    cachedTocTree = cacheManager.loadTocTree(bookUuid);
                    if (chapters != null && !chapters.isEmpty()) {
                        resolveInitialPositionAndOpen();
                    } else {
                        EinkNotice.show(ReaderActivity.this, "Ошибка книги", "Книга не готова к чтению:\n" + errorMessage);
                    }
                }
            });
        }

        // Запрашиваем актуальный прогресс из облака Яндекса
        apiClient.getReadingProgress(bookUuid, new YandexBooksApiClient.ApiCallback<ReadingProgress>() {
            @Override
            public void onSuccess(final ReadingProgress cloudProgress) {
                if (cloudProgress == null) return;
                if (cloudProgress.getLibraryCardId() != null && !cloudProgress.getLibraryCardId().isEmpty()) {
                    dbHelper.updateBookCardId(bookUuid, cloudProgress.getLibraryCardId());
                }
                final boolean audioAvailable = cloudProgress.getLinkedAudioUuid() != null
                        && !cloudProgress.getLinkedAudioUuid().isEmpty();
                if (audioAvailable) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            hasLinkedAudio = true;
                            updateAudioSyncRowVisibility();
                        }
                    });
                }
                if (cloudProgress.getPercent() <= 0 && (cloudProgress.getCfi() == null || cloudProgress.getCfi().isEmpty()) && cloudProgress.getParagraphIndex() <= 0) {
                    if (audioAvailable && cloudProgress.getAudioPercent() > 0.0) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                offerAudioSyncIfNewer(cloudProgress);
                            }
                        });
                    }
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        applyCloudProgressIfNewer(cloudProgress);
                        if (audioAvailable && cloudProgress.getAudioPercent() > 0.0) {
                            offerAudioSyncIfNewer(cloudProgress);
                        }
                    }
                });
            }

            @Override
            public void onError(String errorMessage) {
                // Офлайн или сбой сети - остаемся на локальном месте
            }
        });
    }

    private boolean hasCoverChapter() {
        return chapters != null && !chapters.isEmpty() && "ch_0_cover".equals(chapters.get(0).getId());
    }

    private int chapterIndexToSpineIndex(int chapterIndex) {
        if (chapters != null && chapterIndex >= 0 && chapterIndex < chapters.size()) {
            int orig = chapters.get(chapterIndex).getOriginalOpfSpineIndex();
            if (orig >= 0) {
                return orig;
            }
        }
        if (hasCoverChapter()) {
            return Math.max(0, chapterIndex - 1);
        }
        return Math.max(0, chapterIndex);
    }

    private int spineIndexToChapterIndex(int spineIndex) {
        if (chapters != null) {
            for (int i = 0; i < chapters.size(); i++) {
                if (chapters.get(i).getOriginalOpfSpineIndex() == spineIndex && spineIndex >= 0) {
                    return i;
                }
            }
        }
        if (hasCoverChapter()) {
            return Math.min(chapters != null ? chapters.size() - 1 : spineIndex + 1, spineIndex + 1);
        }
        return Math.min(chapters != null ? chapters.size() - 1 : spineIndex, spineIndex);
    }

    private int resolveChapterIndexFromCfi(YandexBooksApiClient.CfiPosition cfiPos) {
        if (cfiPos == null) return 0;
        if (chapters != null && cfiPos.opfItemId != null && !cfiPos.opfItemId.isEmpty()) {
            for (int i = 0; i < chapters.size(); i++) {
                if (cfiPos.opfItemId.equals(chapters.get(i).getOpfItemId())) {
                    return i;
                }
            }
        }
        return spineIndexToChapterIndex(cfiPos.chapterIndex);
    }

    private void resolveInitialPositionAndOpen() {
        calculateChapterLengths();

        ReadingProgress p = dbHelper.getProgress(bookUuid);
        double percent = 0.0;
        int targetChapter = -1;
        int targetPage = 0;
        int targetParagraph = 0;

        double intentPercent = getIntent().getDoubleExtra("book_percent", 0.0);
        int intentChapter = getIntent().getIntExtra("book_chapter", -1);

        if (p != null) {
            percent = p.getPercent();
            targetChapter = spineIndexToChapterIndex(p.getChapterIndex());
            targetPage = p.getPageIndex();
            targetParagraph = p.getParagraphIndex();
            if (p.getCfi() != null && !p.getCfi().isEmpty()) {
                YandexBooksApiClient.CfiPosition cfiPos = YandexBooksApiClient.parseCfiPosition(p.getCfi());
                if (cfiPos != null) {
                    if (cfiPos.paragraphIndex > 0) {
                        targetParagraph = cfiPos.paragraphIndex;
                    }
                    if (cfiPos.chapterIndex >= 0 || !cfiPos.opfItemId.isEmpty()) {
                        targetChapter = resolveChapterIndexFromCfi(cfiPos);
                    }
                }
            }
        } else {
            Book b = dbHelper.getBookByUuid(bookUuid);
            if (b != null) {
                percent = b.getPercent();
                targetChapter = spineIndexToChapterIndex(b.getCurrentChapterIndex());
                targetPage = 0;
                if (b.getCfi() != null && !b.getCfi().isEmpty()) {
                    YandexBooksApiClient.CfiPosition cfiPos = YandexBooksApiClient.parseCfiPosition(b.getCfi());
                    if (cfiPos != null) {
                        if (cfiPos.paragraphIndex > 0) {
                            targetParagraph = cfiPos.paragraphIndex;
                        }
                        if (cfiPos.chapterIndex >= 0 || !cfiPos.opfItemId.isEmpty()) {
                            targetChapter = resolveChapterIndexFromCfi(cfiPos);
                        }
                    }
                }
            }
        }

        if (intentPercent > percent) {
            percent = intentPercent;
            if (intentChapter >= 0) {
                targetChapter = spineIndexToChapterIndex(intentChapter);
            } else {
                // Серверный процент выше локального, но номер главы не передан:
                // сбрасываем устаревший локальный targetChapter/targetParagraph, чтобы открыть книгу строго по percent
                targetChapter = -1;
            }
            targetPage = 0;
            targetParagraph = 0;
        } else if (percent <= 0.0 && intentPercent > 0.0) {
            percent = intentPercent;
            if (intentChapter >= 0) {
                targetChapter = spineIndexToChapterIndex(intentChapter);
            } else {
                targetChapter = -1;
            }
            targetPage = 0;
            targetParagraph = 0;
        }

        // Защита от ложного 100% (когда глава 0 или книга только открыта)
        if (percent >= 99.0 && targetChapter <= 0) {
            percent = 0.0;
            targetChapter = 0;
            targetPage = 0;
            targetParagraph = 0;
        }

        // Если книга завершена (100% или >= 99.0%), при повторном открытии для чтения
        // начинаем с первой главы (Глава 0 или Глава 1 при искусственной обложке), чтобы ридер не зависал на странице выходных данных/тиража
        if (percent >= 99.0) {
            currentChapterIndex = spineIndexToChapterIndex(0);
            currentPageIndex = 0;
            loadChapter(currentChapterIndex, 0);
            return;
        }

        // Если есть конкретный абзац и глава в допустимом диапазоне (>= 0)
        if (targetChapter >= 0 && chapters != null && targetChapter < chapters.size() && targetParagraph > 0) {
            currentChapterIndex = targetChapter;
            loadChapterAtParagraph(currentChapterIndex, targetParagraph);
            return;
        }

        // Если процент известен (> 0.5% и < 99.0%) и есть totalBookLength:
        if (percent > 0.5 && percent < 99.0 && totalBookLength > 0 && chapters != null && !chapters.isEmpty()) {
            long targetGlobalOffset = (long) ((percent / 100.0) * totalBookLength);
            long acc = 0;
            int matchedCh = 0;
            long matchedOffset = 0;
            for (int i = 0; i < chapters.size(); i++) {
                long chLen = chapterLengths[i];
                if (targetGlobalOffset <= acc + chLen || i == chapters.size() - 1) {
                    matchedCh = i;
                    matchedOffset = Math.max(0, targetGlobalOffset - acc);
                    break;
                }
                acc += chLen;
            }
            long curChLen = chapterLengths[matchedCh];
            double chapterFraction = (curChLen > 0) ? ((double) matchedOffset / (double) curChLen) : 0.0;

            if (targetChapter > 0 && targetChapter < chapters.size()) {
                if (Math.abs(targetChapter - matchedCh) <= 1) {
                    currentChapterIndex = targetChapter;
                    if (targetPage > 0) {
                        currentPageIndex = targetPage;
                        loadChapter(currentChapterIndex, currentPageIndex);
                    } else {
                        long tcLen = chapterLengths[targetChapter];
                        long tcOffset = targetGlobalOffset - acc;
                        double tcFrac = (tcLen > 0) ? Math.max(0.0, Math.min(1.0, (double) tcOffset / (double) tcLen)) : 0.0;
                        loadChapterWithFraction(targetChapter, tcFrac);
                    }
                    return;
                }
            }
            currentChapterIndex = matchedCh;
            loadChapterWithFraction(currentChapterIndex, chapterFraction);
            return;
        }

        if (targetChapter > 0 && chapters != null && targetChapter < chapters.size()) {
            currentChapterIndex = targetChapter;
            currentPageIndex = Math.max(0, targetPage);
            loadChapter(currentChapterIndex, currentPageIndex);
            return;
        }

        currentChapterIndex = spineIndexToChapterIndex(0);
        currentPageIndex = (targetChapter == currentChapterIndex) ? Math.max(0, targetPage) : 0;
        loadChapter(currentChapterIndex, currentPageIndex);
    }

    private void navigateToResolvedProgress(ReadingProgress cloudProgress, int cloudChapter, int cloudParagraph, double cloudPercent) {
        if (chapters == null || chapters.isEmpty()) return;
        // Если есть конкретный абзац и допустимая глава (>= 0)
        if (cloudChapter >= 0 && cloudChapter < chapters.size() && cloudParagraph > 0) {
            currentChapterIndex = cloudChapter;
            loadChapterAtParagraph(currentChapterIndex, cloudParagraph);
            dbHelper.saveProgress(cloudProgress);
            dbHelper.ensureCloudSyncBookmark(bookUuid, currentChapterIndex, currentPageIndex, (float) cloudPercent);
            EinkNotice.show(ReaderActivity.this, "Синхронизация", String.format(Locale.getDefault(), "Синхронизировано: %.0f%% (Гл. %d, абз. %d)\n[Закладка создана]", cloudPercent, currentChapterIndex + 1, cloudParagraph + 1));
        } else if (cloudChapter >= 0 && cloudChapter < chapters.size()
                && (cloudChapter > 0 || cloudPercent <= 1.0 || cloudProgress.getPageIndex() > 0)) {
            currentChapterIndex = cloudChapter;
            currentPageIndex = Math.max(0, cloudProgress.getPageIndex());
            loadChapter(currentChapterIndex, currentPageIndex);
            dbHelper.saveProgress(cloudProgress);
            dbHelper.ensureCloudSyncBookmark(bookUuid, currentChapterIndex, currentPageIndex, (float) cloudPercent);
            EinkNotice.show(ReaderActivity.this, "Синхронизация", String.format(Locale.getDefault(), "Синхронизировано: %.0f%% (Гл. %d)\n[Закладка создана]", cloudPercent, currentChapterIndex + 1));
        } else if (cloudPercent > 0 && cloudPercent < 99.0 && totalBookLength > 0) {
            // Если номер главы не указан – маппинг по общей длине
            if (chapterLengths == null || chapterLengths.length != chapters.size()) {
                calculateChapterLengths();
            }
            long targetGlobalOffset = (long) ((cloudPercent / 100.0) * totalBookLength);
            long acc = 0;
            int matchedCh = 0;
            long matchedOffset = 0;
            for (int i = 0; i < chapters.size(); i++) {
                long chLen = chapterLengths[i];
                if (targetGlobalOffset <= acc + chLen || i == chapters.size() - 1) {
                    matchedCh = i;
                    matchedOffset = Math.max(0, targetGlobalOffset - acc);
                    break;
                }
                acc += chLen;
            }
            long curChLen = chapterLengths[matchedCh];
            double chapterFraction = (curChLen > 0) ? ((double) matchedOffset / (double) curChLen) : 0.0;

            currentChapterIndex = matchedCh;
            loadChapterWithFraction(currentChapterIndex, chapterFraction);
            dbHelper.saveProgress(cloudProgress);
            dbHelper.ensureCloudSyncBookmark(bookUuid, currentChapterIndex, currentPageIndex, (float) cloudPercent);
            EinkNotice.show(ReaderActivity.this, "Синхронизация", String.format(Locale.getDefault(), "Синхронизировано: %.0f%% (Гл. %d)\n[Закладка создана]", cloudPercent, currentChapterIndex + 1));
        }
    }

    private void applyCloudProgressIfNewer(final ReadingProgress cloudProgress) {
        if (chapters == null || chapters.isEmpty()) return;

        ReadingProgress local = dbHelper.getProgress(bookUuid);
        double curPercent = calculateCurrentGlobalPercent();
        final double cloudPercent = cloudProgress.getPercent();
        int resolvedChapter = cloudProgress.getChapterIndex();
        int resolvedParagraph = cloudProgress.getParagraphIndex();
        if (cloudProgress.getCfi() != null && !cloudProgress.getCfi().isEmpty()) {
            YandexBooksApiClient.CfiPosition cfiPos = YandexBooksApiClient.parseCfiPosition(cloudProgress.getCfi());
            if (cfiPos != null) {
                if (cfiPos.paragraphIndex > 0 || resolvedParagraph <= 0) {
                    resolvedParagraph = cfiPos.paragraphIndex;
                }
                if (cfiPos.chapterIndex >= 0 || !cfiPos.opfItemId.isEmpty()) {
                    resolvedChapter = resolveChapterIndexFromCfi(cfiPos);
                }
            }
        } else if (hasCoverChapter() && resolvedChapter >= 0) {
            resolvedChapter = spineIndexToChapterIndex(resolvedChapter);
        }

        // Защита от искаженного 100% при 0 главе или завершенной книге
        if (cloudPercent >= 99.0) {
            return;
        }

        final int targetChapter = resolvedChapter;
        final int targetParagraph = resolvedParagraph;
        long localTs = (local != null) ? local.getTimestamp() : 0L;
        long cloudTs = cloudProgress.getTimestamp();

        boolean isForward = false;
        boolean isBackward = false;

        if (targetChapter > currentChapterIndex || (targetChapter == currentChapterIndex && targetParagraph > calculateCurrentParagraphIndex()) || cloudPercent > curPercent + 0.5) {
            isForward = true;
        } else if (targetChapter < currentChapterIndex || (targetChapter == currentChapterIndex && targetParagraph < calculateCurrentParagraphIndex()) || cloudPercent < curPercent - 2.0) {
            isBackward = true;
        }

        // 1. Читатель уже начал листать страницы книги на ридере во время сетевого запроса
        if (userHasInteracted) {
            if (isForward && (cloudTs >= localTs || cloudPercent > curPercent + 1.0)) {
                String msg = String.format(Locale.getDefault(),
                        "На другом устройстве вы остановились дальше:\nГл. %d (%.0f%%)\n\nПерейти к этому месту?",
                        Math.max(1, targetChapter + 1), cloudPercent);
                EinkNotice.showConfirm(ReaderActivity.this, "Синхронизация", msg, "Перейти", "Остаться",
                        new Runnable() {
                            @Override
                            public void run() {
                                navigateToResolvedProgress(cloudProgress, targetChapter, targetParagraph, cloudPercent);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                saveProgress();
                            }
                        });
            }
            return;
        }

        // 2. Защита от случайного отката назад (LWW с валидацией вперед)
        if (isBackward) {
            if (cloudTs > localTs && localTs > 0) {
                String msg = String.format(Locale.getDefault(),
                        "На другом устройстве открыта более ранняя позиция:\nГл. %d (%.0f%%)\n\nОткатиться назад к этому месту?",
                        Math.max(1, targetChapter + 1), cloudPercent);
                EinkNotice.showConfirm(ReaderActivity.this, "Синхронизация", msg, "Откатиться", "Остаться",
                        new Runnable() {
                            @Override
                            public void run() {
                                navigateToResolvedProgress(cloudProgress, targetChapter, targetParagraph, cloudPercent);
                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                saveProgress();
                            }
                        });
            }
            return;
        }

        // 3. Бесшовное открытие сохраненной позиции при запуске ридера
        boolean isNewer = false;
        if (local == null) {
            isNewer = (cloudPercent > 0 || targetChapter > 0 || targetParagraph > 0 || (cloudProgress.getCfi() != null && !cloudProgress.getCfi().isEmpty()));
        } else if (cloudTs > localTs && cloudTs > 0) {
            if (Math.abs(cloudPercent - curPercent) > 0.05 || targetChapter != currentChapterIndex || Math.abs(targetParagraph - calculateCurrentParagraphIndex()) > 0) {
                isNewer = true;
            }
        } else if (isForward && (cloudTs >= localTs || cloudTs == 0 || local.isSyncedWithServer())) {
            isNewer = true;
        }

        if (isNewer) {
            navigateToResolvedProgress(cloudProgress, targetChapter, targetParagraph, cloudPercent);
        }
    }

    private int cachedQuotesChapterIndex = -1;

    private void invalidateChapterQuotesCache() {
        cachedQuotesChapterIndex = -1;
    }

    private void refreshCanvasChapterQuotes(boolean force) {
        if (readerCanvas == null || dbHelper == null || bookUuid == null || bookUuid.isEmpty()) return;
        if (!force && cachedQuotesChapterIndex == currentChapterIndex) return;
        cachedQuotesChapterIndex = currentChapterIndex;
        List<Quote> qList = dbHelper.getQuotesForChapter(bookUuid, currentChapterIndex);
        readerCanvas.setChapterQuotes(qList);
    }

    private void loadBookQuotesCloudSyncAsync() {
        if (bookUuid == null || bookUuid.isEmpty() || apiClient == null || dbHelper == null) return;
        dbHelper.purgeLegacyCloudQuotesIfNeeded(appSettings);
        apiClient.getBookQuotes(bookUuid, new YandexBooksApiClient.ApiCallback<List<Quote>>() {
            @Override
            public void onSuccess(List<Quote> remoteQuotes) {
                if (remoteQuotes != null) {
                    dbHelper.replaceSyncedCloudQuotes(bookUuid, remoteQuotes);
                    invalidateChapterQuotesCache();
                    refreshCanvasChapterQuotes(true);
                }
            }

            @Override
            public void onError(String errorMessage) {
                // Игнорируем ошибку сети, локальные цитаты доступны автономно
            }
        });
    }

    private float getReadablePopupTextSizeSp() {
        int bookSp = (typographyConfig != null) ? typographyConfig.getFontSizeSp() : 18;
        return Math.max(16.0f, Math.min(24.0f, bookSp * 0.85f));
    }

    private static String formatQuotedPreview(String raw) {
        if (raw == null) return "";
        String t = raw.trim();
        if (t.isEmpty()) return "";
        if ((t.startsWith("«") && t.endsWith("»")) || (t.startsWith("\"") && t.endsWith("\""))) {
            return t;
        }
        return "«" + t + "»";
    }

    private void showTextSelectionActionDialog(final String selectedText, final int startOffset, final int endOffset) {
        if (isFinishing() || selectedText == null || selectedText.trim().isEmpty()) {
            if (readerCanvas != null) readerCanvas.clearSelection();
            return;
        }
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setWindowAnimations(0);
        }

        final DisplayMetrics dm = getResources().getDisplayMetrics();
        final float density = dm.density;
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_notice_bg);
        int padH = (int) (10 * density);
        int padV = (int) (10 * density);
        root.setPadding(padH, padV, padH, padV);

        TextView titleView = new TextView(this);
        titleView.setText("Выделенный фрагмент");
        titleView.setTextSize(16.5f);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
        root.addView(titleView);

        View divider = new View(this);
        divider.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(2, 2 * density));
        divLp.setMargins(0, (int) (5 * density), 0, (int) (7 * density));
        root.addView(divider, divLp);

        // Высококонтрастный превью-блок с вертикальной полосой и крупным чисто-чёрным шрифтом
        LinearLayout previewBox = new LinearLayout(this);
        previewBox.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pbLp.setMargins(0, 0, 0, (int) (8 * density));
        previewBox.setLayoutParams(pbLp);

        View quoteBar = new View(this);
        quoteBar.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams qbLp = new LinearLayout.LayoutParams((int) Math.max(3, 3 * density), ViewGroup.LayoutParams.MATCH_PARENT);
        qbLp.setMargins(0, (int) (2 * density), (int) (8 * density), (int) (2 * density));
        previewBox.addView(quoteBar, qbLp);

        TextView previewView = new TextView(this);
        previewView.setText(formatQuotedPreview(selectedText));
        previewView.setTextSize(Math.max(15.5f, Math.min(20.0f, getReadablePopupTextSizeSp())));
        previewView.setTypeface(null, Typeface.NORMAL);
        previewView.setTextColor(Color.BLACK);
        previewView.setLineSpacing(2f, 1.22f);
        previewView.setMaxLines(6);
        previewView.setEllipsize(TextUtils.TruncateAt.END);
        previewBox.addView(previewView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));
        root.addView(previewBox);

        int btnHeight = (int) (40 * density);
        int btnGap = (int) (5 * density);

        Button btnSaveQuote = new Button(this);
        btnSaveQuote.setText("Сохранить цитату");
        btnSaveQuote.setTextSize(14.5f);
        btnSaveQuote.setTypeface(null, Typeface.BOLD);
        btnSaveQuote.setTextColor(Color.BLACK);
        btnSaveQuote.setBackgroundResource(R.drawable.btn_eink_primary);
        btnSaveQuote.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams bLp1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, btnHeight);
        bLp1.setMargins(0, 0, 0, btnGap);
        btnSaveQuote.setLayoutParams(bLp1);
        btnSaveQuote.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                saveSelectedQuote(selectedText.trim(), "", startOffset, endOffset);
            }
        });
        root.addView(btnSaveQuote);

        Button btnAddNote = new Button(this);
        btnAddNote.setText("Сохранить с заметкой");
        btnAddNote.setTextSize(14.5f);
        btnAddNote.setTypeface(null, Typeface.BOLD);
        btnAddNote.setTextColor(Color.BLACK);
        btnAddNote.setBackgroundResource(R.drawable.btn_eink);
        btnAddNote.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams bLp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, btnHeight);
        bLp2.setMargins(0, 0, 0, btnGap);
        btnAddNote.setLayoutParams(bLp2);
        btnAddNote.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showQuoteNoteDialog(selectedText.trim(), startOffset, endOffset);
            }
        });
        root.addView(btnAddNote);

        Button btnCopy = new Button(this);
        btnCopy.setText("Копировать текст");
        btnCopy.setTextSize(14.5f);
        btnCopy.setTypeface(null, Typeface.BOLD);
        btnCopy.setTextColor(Color.BLACK);
        btnCopy.setBackgroundResource(R.drawable.btn_eink);
        btnCopy.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams bLp3 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, btnHeight);
        bLp3.setMargins(0, 0, 0, btnGap);
        btnCopy.setLayoutParams(bLp3);
        btnCopy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("quote", selectedText.trim()));
                    }
                    EinkNotice.show(ReaderActivity.this, "Цитаты", "Текст скопирован в буфер обмена.");
                } catch (Throwable ignored) {}
                if (readerCanvas != null) readerCanvas.clearSelection();
            }
        });
        root.addView(btnCopy);

        Button btnCancel = new Button(this);
        btnCancel.setText("Отмена");
        btnCancel.setTextSize(14.5f);
        btnCancel.setTypeface(null, Typeface.BOLD);
        btnCancel.setTextColor(Color.BLACK);
        btnCancel.setBackgroundResource(R.drawable.btn_eink);
        btnCancel.setPadding(0, 0, 0, 0);
        btnCancel.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, btnHeight));
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (readerCanvas != null) readerCanvas.clearSelection();
            }
        });
        root.addView(btnCancel);

        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface d) {
                if (readerCanvas != null) readerCanvas.clearSelection();
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                EpdController.requestFullRefresh(ReaderActivity.this, null);
            }
        });

        final int targetW = Math.min((int) (dm.widthPixels * 0.94), (int) (540 * density));
        dialog.setContentView(root, new ViewGroup.LayoutParams(targetW, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(targetW, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        EpdController.requestFullRefresh(ReaderActivity.this, root);
    }

    private void showQuoteNoteDialog(final String selectedText, final int startOffset, final int endOffset) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setWindowAnimations(0);
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_notice_bg);
        int pad = (int) (10 * density);
        root.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(this);
        titleView.setText("Заметка к цитате");
        titleView.setTextSize(16.5f);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setPadding(0, 0, 0, (int) (6 * density));
        root.addView(titleView);

        final EditText input = new EditText(this);
        input.setHint("Введите комментарий...");
        input.setTextSize(15.5f);
        input.setTextColor(Color.BLACK);
        input.setMinLines(2);
        input.setMaxLines(5);
        root.addView(input);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.setMargins(0, (int) (10 * density), 0, 0);
        btnRow.setLayoutParams(rLp);

        Button btnSave = new Button(this);
        btnSave.setText("Сохранить");
        btnSave.setTextSize(14.5f);
        btnSave.setTypeface(null, Typeface.BOLD);
        btnSave.setTextColor(Color.BLACK);
        btnSave.setBackgroundResource(R.drawable.btn_eink_primary);
        btnSave.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0, (int) (40 * density), 1.0f);
        bLp.setMargins(0, 0, (int) (4 * density), 0);
        btnSave.setLayoutParams(bLp);
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                saveSelectedQuote(selectedText, input.getText().toString().trim(), startOffset, endOffset);
            }
        });
        btnRow.addView(btnSave);

        Button btnCancel = new Button(this);
        btnCancel.setText("Отмена");
        btnCancel.setTextSize(14.5f);
        btnCancel.setTypeface(null, Typeface.BOLD);
        btnCancel.setTextColor(Color.BLACK);
        btnCancel.setBackgroundResource(R.drawable.btn_eink);
        btnCancel.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, (int) (40 * density), 1.0f);
        cLp.setMargins((int) (4 * density), 0, 0, 0);
        btnCancel.setLayoutParams(cLp);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (readerCanvas != null) readerCanvas.clearSelection();
            }
        });
        btnRow.addView(btnCancel);
        root.addView(btnRow);

        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface d) {
                if (readerCanvas != null) readerCanvas.clearSelection();
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                EpdController.requestFullRefresh(ReaderActivity.this, null);
            }
        });

        int targetW = Math.min((int) (dm.widthPixels * 0.94), (int) (540 * density));
        dialog.setContentView(root, new ViewGroup.LayoutParams(targetW, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(targetW, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        EpdController.requestFullRefresh(ReaderActivity.this, root);
    }

    private void saveSelectedQuote(String selectedText, String note, int startOffset, int endOffset) {
        final Quote q = new Quote();
        q.bookUuid = bookUuid;
        q.chapterIndex = currentChapterIndex;
        q.startOffset = Math.max(0, startOffset);
        q.endOffset = Math.max(q.startOffset, endOffset);
        q.text = selectedText;
        q.note = note != null ? note : "";
        q.createdAt = System.currentTimeMillis();
        q.synced = false;

        int parIdx = calculateCurrentParagraphIndex();
        if (chapters != null && currentChapterIndex >= 0 && currentChapterIndex < chapters.size()) {
            Chapter curCh = chapters.get(currentChapterIndex);
            int opfIdx = curCh.getOriginalOpfSpineIndex();
            String opfId = curCh.getOpfItemId();
            if (opfIdx >= 0 && opfId != null && !opfId.isEmpty()) {
                q.cfi = YandexBooksApiClient.buildCanonicalCfi(opfIdx, opfId, parIdx, q.startOffset);
            } else {
                q.cfi = YandexBooksApiClient.generateCfi(chapterIndexToSpineIndex(currentChapterIndex), parIdx);
            }
        }

        final long localId = dbHelper.insertQuote(q);
        invalidateChapterQuotesCache();
        refreshCanvasChapterQuotes(true);
        if (readerCanvas != null) {
            readerCanvas.clearSelection();
        }
        EinkNotice.show(this, "Цитаты", "Цитата сохранена.");

        if (apiClient != null && localId > 0) {
            apiClient.createBookQuote(q, new YandexBooksApiClient.ApiCallback<String>() {
                @Override
                public void onSuccess(String remoteUuid) {
                    dbHelper.markQuoteSynced(localId, remoteUuid);
                }

                @Override
                public void onError(String errorMessage) {
                    // Сохранена локально
                }
            });
        }
    }

    private void exportBookQuotesToFile(List<Quote> quotesList) {
        if (quotesList == null || quotesList.isEmpty()) {
            EinkNotice.show(this, "Экспорт цитат", "Нет сохраненных цитат для экспорта.");
            return;
        }
        try {
            File extDir = android.os.Environment.getExternalStorageDirectory();
            File quotesDir = (extDir != null && extDir.canWrite())
                    ? new File(extDir, "Books/YandexBooks/Quotes")
                    : new File(getExternalFilesDir(null), "Quotes");
            if (!quotesDir.exists()) {
                quotesDir.mkdirs();
            }
            String safeTitle = (bookTitle != null && !bookTitle.trim().isEmpty())
                    ? bookTitle.replaceAll("[\\\\/:*?\"<>|\\s]+", "_")
                    : bookUuid;
            if (safeTitle.length() > 64) safeTitle = safeTitle.substring(0, 64);
            File outFile = new File(quotesDir, safeTitle + "_quotes.md");

            StringBuilder sb = new StringBuilder();
            sb.append("# Цитаты: ").append(bookTitle != null ? bookTitle : bookUuid).append("\n\n");
            SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault());
            for (int i = 0; i < quotesList.size(); i++) {
                Quote q = quotesList.get(i);
                sb.append("## ").append(i + 1).append(". Глава ").append(q.chapterIndex + 1)
                        .append(" (").append(sdf.format(new Date(q.createdAt > 0 ? q.createdAt : System.currentTimeMillis()))).append(")\n");
                sb.append("> ").append(q.text != null ? q.text : "").append("\n\n");
                if (q.note != null && !q.note.trim().isEmpty()) {
                    sb.append("**Заметка:** ").append(q.note.trim()).append("\n\n");
                }
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
            fos.write(sb.toString().getBytes("UTF-8"));
            fos.flush();
            fos.close();

            EinkNotice.show(this, "Экспорт цитат", "Сохранено в файл:\n" + outFile.getAbsolutePath());
        } catch (Throwable t) {
            EinkNotice.show(this, "Экспорт цитат", "Ошибка экспорта: " + t.getMessage());
        }
    }

    private void loadBookFootnotesAsync() {
        if (bookUuid == null || bookUuid.isEmpty() || cacheManager == null) return;
        paginationExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final Map<String, String> notes = cacheManager.ensureFootnotesExtractedSync(bookUuid);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (readerCanvas != null && notes != null && !notes.isEmpty()) {
                            readerCanvas.setFootnotes(notes);
                        }
                    }
                });
            }
        });
    }

    private void offerAudioSyncIfNewer(final ReadingProgress cloudProgress) {
        if (audioSyncPromptShown || cloudProgress == null || chapters == null || chapters.isEmpty()) return;
        if (cloudProgress.getLinkedAudioUuid() == null || cloudProgress.getLinkedAudioUuid().isEmpty()) return;
        if (!appSettings.isAudioSyncEnabled()) return;

        final double audioPct = cloudProgress.getAudioPercent();
        long audioTs = cloudProgress.getAudioUpdatedAt();
        if (audioPct <= 0.0 || audioPct >= 99.0) return;

        ReadingProgress local = dbHelper.getProgress(bookUuid);
        long localTs = (local != null) ? local.getTimestamp() : 0L;
        double curPercent = calculateCurrentGlobalPercent();
        if (audioTs > 0 && localTs > 0 && audioTs <= localTs) return;
        if (Math.abs(audioPct - curPercent) < 2.0) return;

        audioSyncPromptShown = true;
        String msg = String.format(Locale.getDefault(),
                "Вы слушали аудиоверсию этой книги до %.0f%%.\n\nПерейти к этому месту в тексте?",
                audioPct);
        EinkNotice.showConfirm(ReaderActivity.this, "Синхронизация с аудио", msg, "Перейти к аудио", "Остаться",
                new Runnable() {
                    @Override
                    public void run() {
                        navigateToResolvedProgress(cloudProgress, -1, 0, audioPct);
                    }
                },
                null);
    }

    private void loadChapter(final int index) {
        loadChapter(index, 0);
    }

    private void loadChapter(final int index, final int targetPage) {
        loadChapterInternal(index, -1.0, targetPage, -1);
    }

    private void loadChapterWithFraction(final int index, final double anchorFraction) {
        loadChapterInternal(index, anchorFraction, 0, -1);
    }

    private void loadChapterAtCharOffset(final int index, final int targetCharOffset) {
        userHasInteracted = true;
        loadChapterInternal(index, -1.0, 0, targetCharOffset);
    }

    private void loadChapterAtParagraph(final int index, final int targetParagraph) {
        if (chapters == null || chapters.isEmpty()) return;
        if (index < 0 || index >= chapters.size()) return;
        currentChapterIndex = index;
        loadChapterInternal(index, -1.0, 0, -1, targetParagraph);
    }

    private int findParagraphOffset(String rawText, int targetParagraph) {
        if (rawText == null || rawText.isEmpty() || targetParagraph <= 0) return 0;
        int parCount = 0;
        int len = rawText.length();
        for (int i = 0; i < len; i++) {
            if (rawText.charAt(i) == '\n') {
                parCount++;
                if (parCount >= targetParagraph) {
                    return Math.min(i + 1, len - 1);
                }
            }
        }
        return Math.max(0, len - 1);
    }

    private void loadChapterInternal(final int index, final double anchorFraction, final int targetPage, final int targetCharOffset) {
        loadChapterInternal(index, anchorFraction, targetPage, targetCharOffset, -1);
    }

    private void loadChapterInternal(final int index, final double anchorFraction, final int targetPage, final int targetCharOffset, final int targetParagraph) {
        if (chapters == null || chapters.isEmpty()) return;
        if (index < 0 || index >= chapters.size()) return;
        currentChapterIndex = index;

        // 1. ПРОВЕРКА ВНУТРИПАМЯТНОГО КЭША (МГНОВЕННЫЙ ПЕРЕХОД < 5 МС БЕЗ ЗАДЕРЖКИ)
        if (chapterPagesMemoryCache.containsKey(index)) {
            final List<TextPaginator.Page> cachedPages = chapterPagesMemoryCache.get(index);
            final String cachedText = chapterTextMemoryCache.get(index);
            if (cachedPages != null && !cachedPages.isEmpty()) {
                isPaginating = false;
                hideLoadingOverlay();
                currentPages = cachedPages;
                if (chapterPageCounts != null && index < chapterPageCounts.length) {
                    chapterPageCounts[index] = currentPages.size();
                }
                if (chapterPageExact != null && index < chapterPageExact.length) {
                    chapterPageExact[index] = true;
                }
                if (cachedText != null && cachedText.length() > 3000) {
                    avgCharsPerPage = (double) EpubFormatting.stripFormattingTokens(cachedText).length() / (double) currentPages.size();
                }
                recalculateTotalBookPages();
                int effectiveCharOffset = targetCharOffset;
                if (effectiveCharOffset < 0 && targetParagraph > 0 && cachedText != null) {
                    effectiveCharOffset = findParagraphOffset(cachedText, targetParagraph);
                }
                resolveTargetPagePosition(targetPage, effectiveCharOffset, anchorFraction, cachedText);
                if (readerCanvas != null) {
                    readerCanvas.setTypographyConfig(typographyConfig);
                }
                renderCurrentPage();
                updateMenuControls();
                isInitialLoading = false;
                preloadAdjacentChapters(index);
                return;
            }
        }

        isPaginating = true;

        final Chapter ch = chapters.get(index);
        final String chId = ch.getId();
        final String chTitle = ch.getTitle();

        final TypographyConfig configCopy = cloneTypographyConfig(typographyConfig);
        final Paint paint = readerCanvas.buildPaintForConfig(configCopy);

        int w = readerCanvas.getWidth();
        int h = readerCanvas.getHeight();
        if (w <= 0 || h <= 0) {
            w = getResources().getDisplayMetrics().widthPixels;
            h = getResources().getDisplayMetrics().heightPixels;
        }
        final int screenWidth = w;
        final int screenHeight = h;
        lastPaginatedWidth = w;
        lastPaginatedHeight = h;

        final long taskId = paginationTaskId.incrementAndGet();

        paginationExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    // 1. ЧТЕНИЕ ТЕКСТА ГЛАВЫ ИЗ RAM-КЭША ИЛИ ДИСКА В ФОНОВОМ ПОТОКЕ
                    String text = chapterTextMemoryCache.get(index);
                    if (text == null) {
                        text = cacheManager.loadChapter(bookUuid, chId);
                    }
                    if (text == null) {
                        text = cacheManager.loadChapter(bookUuid, String.valueOf(index));
                    }

                    if (text == null || text.trim().isEmpty()) {
                        String title = chTitle;
                        if (title == null || title.trim().isEmpty() || title.startsWith("ch_") || title.startsWith("ch-")) {
                            title = "";
                        }
                        text = title;
                    }

                    final String rawText = text;
                    chapterTextMemoryCache.put(index, rawText);

                    // 1.5 APPLE-ПОДХОД: для больших глав (> 12 КБ) сначала мгновенно показываем 1 видимую страницу (~10 мс),
                    // пока полная пагинация всей главы дорабатывает в фоне!
                    if (rawText.length() > 12000 && (currentPages == null || currentPages.isEmpty())) {
                        int quickOffset = -1;
                        if (targetCharOffset >= 0) {
                            quickOffset = targetCharOffset;
                        } else if (targetParagraph > 0) {
                            quickOffset = findParagraphOffset(rawText, targetParagraph);
                        } else if (anchorFraction >= 0.0 && anchorFraction <= 1.0) {
                            quickOffset = (int) Math.round(rawText.length() * anchorFraction);
                        } else if (targetPage == 0) {
                            quickOffset = 0;
                        }
                        if (quickOffset >= 0 && taskId == paginationTaskId.get()) {
                            final TextPaginator.Page firstScreenPage = paginator.paginateSinglePageFromOffset(
                                    rawText, quickOffset, screenWidth, screenHeight, paint, configCopy);
                            if (firstScreenPage != null && taskId == paginationTaskId.get()) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (taskId != paginationTaskId.get() || readerCanvas == null) return;
                                        hideLoadingOverlay();
                                        readerCanvas.setTypographyConfig(typographyConfig);
                                        String title = getActiveChapterTitle();
                                        double percent = calculateCurrentGlobalPercent();
                                        int totalCh = chapters != null ? chapters.size() : 1;
                                        readerCanvas.setPage(
                                                firstScreenPage,
                                                globalPageIndex,
                                                totalBookPages,
                                                title,
                                                currentChapterIndex,
                                                totalCh,
                                                percent,
                                                1,
                                                1
                                        );
                                    }
                                });
                            }
                        }
                    }

                    // 2. ПОЛНАЯ ОТМЕНЯЕМАЯ ПАГИНАЦИЯ ГЛАВЫ В ФОНОВОМ ПОТОКЕ
                    final List<TextPaginator.Page> pages = paginator.paginateCancellable(
                            rawText,
                            screenWidth,
                            screenHeight,
                            paint,
                            configCopy,
                            paginationTaskId,
                            taskId
                    );

                    if (pages == null || taskId != paginationTaskId.get()) {
                        return;
                    }

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (taskId != paginationTaskId.get()) {
                                return;
                            }
                            isPaginating = false;
                            hideLoadingOverlay();
                            currentPages = pages;

                            if (currentPages != null && !currentPages.isEmpty()) {
                                chapterPagesMemoryCache.put(index, pages);
                                chapterTextMemoryCache.put(index, rawText);
                                pruneMemoryCache(index);

                                if (chapterPageCounts != null && currentChapterIndex < chapterPageCounts.length) {
                                    chapterPageCounts[currentChapterIndex] = currentPages.size();
                                }
                                if (chapterPageExact != null && currentChapterIndex < chapterPageExact.length) {
                                    chapterPageExact[currentChapterIndex] = true;
                                }
                                if (rawText != null && rawText.length() > 3000) {
                                    avgCharsPerPage = (double) EpubFormatting.stripFormattingTokens(rawText).length() / (double) currentPages.size();
                                }
                                recalculateTotalBookPages();
                                int effectiveCharOffset = targetCharOffset;
                                if (effectiveCharOffset < 0 && targetParagraph > 0 && rawText != null) {
                                    effectiveCharOffset = findParagraphOffset(rawText, targetParagraph);
                                }
                                resolveTargetPagePosition(targetPage, effectiveCharOffset, anchorFraction, rawText);
                            }

                            if (readerCanvas != null) {
                                readerCanvas.setTypographyConfig(typographyConfig);
                            }
                            renderCurrentPage();
                            updateMenuControls();
                            isInitialLoading = false;
                            preloadAdjacentChapters(index);
                        }
                    });
                } catch (Throwable t) {
                    android.util.Log.e("ReaderActivity", "Pagination background error", t);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            isPaginating = false;
                            hideLoadingOverlay();
                        }
                    });
                }
            }
        });
    }

    private void resolveTargetPagePosition(int targetPage, int targetCharOffset, double anchorFraction, String rawText) {
        if (currentPages == null || currentPages.isEmpty()) {
            currentPageIndex = 0;
            return;
        }
        if (targetPage == -999) {
            currentPageIndex = Math.max(0, currentPages.size() - 1);
        } else if (targetCharOffset >= 0) {
            int resolvedP = 0;
            boolean found = false;
            for (int i = 0; i < currentPages.size(); i++) {
                TextPaginator.Page p = currentPages.get(i);
                if (targetCharOffset >= p.startCharOffset && targetCharOffset <= p.endCharOffset) {
                    resolvedP = i;
                    found = true;
                    break;
                }
            }
            if (!found) {
                if (targetCharOffset >= currentPages.get(currentPages.size() - 1).endCharOffset) {
                    resolvedP = currentPages.size() - 1;
                } else {
                    resolvedP = 0;
                }
            }
            currentPageIndex = resolvedP;
        } else if (anchorFraction >= 0.0 && rawText != null && rawText.length() > 0) {
            int charOff = (int) Math.round(anchorFraction * rawText.length());
            int resolvedP = 0;
            boolean found = false;
            for (int i = 0; i < currentPages.size(); i++) {
                TextPaginator.Page p = currentPages.get(i);
                if (charOff >= p.startCharOffset && charOff <= p.endCharOffset) {
                    resolvedP = i;
                    found = true;
                    break;
                }
            }
            if (!found) {
                if (charOff >= currentPages.get(currentPages.size() - 1).endCharOffset) {
                    resolvedP = currentPages.size() - 1;
                } else {
                    resolvedP = 0;
                }
            }
            currentPageIndex = resolvedP;
        } else if (targetPage >= 0 && targetPage < currentPages.size()) {
            currentPageIndex = targetPage;
        } else if (currentPageIndex >= currentPages.size()) {
            currentPageIndex = Math.max(0, currentPages.size() - 1);
        }
    }

    private void preloadAdjacentChapters(final int curIndex) {
        final int[] adj = new int[]{curIndex + 1, curIndex - 1};
        for (final int targetIdx : adj) {
            if (targetIdx >= 0 && chapters != null && targetIdx < chapters.size()) {
                if (!chapterPagesMemoryCache.containsKey(targetIdx)) {
                    final Chapter ch = chapters.get(targetIdx);
                    final String chId = ch.getId();
                    final String chTitle = ch.getTitle();
                    paginationExecutor.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (chapterPagesMemoryCache.containsKey(targetIdx)) return;
                                String text = cacheManager.loadChapter(bookUuid, chId);
                                if (text == null) text = cacheManager.loadChapter(bookUuid, String.valueOf(targetIdx));
                                if (text == null || text.trim().isEmpty()) {
                                    text = (chTitle != null && !chTitle.startsWith("ch_")) ? chTitle : "";
                                }
                                TypographyConfig cfg = cloneTypographyConfig(typographyConfig);
                                Paint paint = readerCanvas.buildPaintForConfig(cfg);
                                int w = lastPaginatedWidth > 0 ? lastPaginatedWidth : readerCanvas.getWidth();
                                int h = lastPaginatedHeight > 0 ? lastPaginatedHeight : readerCanvas.getHeight();
                                if (w <= 0 || h <= 0) {
                                    w = getResources().getDisplayMetrics().widthPixels;
                                    h = getResources().getDisplayMetrics().heightPixels;
                                }

                                List<TextPaginator.Page> pages = paginator.paginate(text, w, h, paint, cfg);
                                if (pages != null && !pages.isEmpty()) {
                                    chapterPagesMemoryCache.put(targetIdx, pages);
                                    chapterTextMemoryCache.put(targetIdx, text);
                                    if (chapterPageCounts != null && targetIdx < chapterPageCounts.length) {
                                        chapterPageCounts[targetIdx] = pages.size();
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                }
            }
        }
    }

    private void pruneMemoryCache(int curIndex) {
        if (chapterPagesMemoryCache.size() > 5) {
            Iterator<Integer> it = chapterPagesMemoryCache.keySet().iterator();
            while (it.hasNext()) {
                int key = it.next();
                if (Math.abs(key - curIndex) > 2) {
                    it.remove();
                    chapterTextMemoryCache.remove(key);
                }
            }
        }
    }

    private void displayChapterText(String rawText, String title) {
        displayChapterText(rawText, title, -1.0, 0, -1);
    }

    private void displayChapterText(final String rawText, final String title, final double anchorFraction, final int targetPage) {
        displayChapterText(rawText, title, anchorFraction, targetPage, -1);
    }

    private void displayChapterText(final String rawText, final String title, final double anchorFraction, final int targetPage, final int targetCharOffset) {
        isPaginating = true;
        // Синхронизируем конфигурацию с холстом
        readerCanvas.setTypographyConfig(typographyConfig);

        final Paint paint = new Paint(readerCanvas.getTextPaint());

        int w = readerCanvas.getWidth();
        int h = readerCanvas.getHeight();
        if (w <= 0 || h <= 0) {
            w = getResources().getDisplayMetrics().widthPixels;
            h = getResources().getDisplayMetrics().heightPixels;
        }
        final int screenWidth = w;
        final int screenHeight = h;

        final TypographyConfig configCopy = cloneTypographyConfig(typographyConfig);

        final long taskId = paginationTaskId.incrementAndGet();

        paginationExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final List<TextPaginator.Page> pages = paginator.paginate(
                        rawText,
                        screenWidth,
                        screenHeight,
                        paint,
                        configCopy
                );

                if (taskId != paginationTaskId.get()) {
                    return;
                }

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (taskId != paginationTaskId.get()) {
                            return;
                        }
                        isPaginating = false;
                        hideLoadingOverlay();
                        currentPages = pages;

                        if (currentPages != null && !currentPages.isEmpty()) {
                            if (chapterPageCounts != null && currentChapterIndex < chapterPageCounts.length) {
                                chapterPageCounts[currentChapterIndex] = currentPages.size();
                            }
                            if (chapterPageExact != null && currentChapterIndex < chapterPageExact.length) {
                                chapterPageExact[currentChapterIndex] = true;
                            }
                            if (rawText != null && rawText.length() > 3000) {
                                avgCharsPerPage = (double) EpubFormatting.stripFormattingTokens(rawText).length() / (double) currentPages.size();
                            }
                            recalculateTotalBookPages();

                            if (targetPage == -999) {
                                currentPageIndex = Math.max(0, currentPages.size() - 1);
                            } else if (targetCharOffset >= 0) {
                                int resolvedP = 0;
                                boolean found = false;
                                for (int i = 0; i < currentPages.size(); i++) {
                                    TextPaginator.Page p = currentPages.get(i);
                                    if (targetCharOffset >= p.startCharOffset && targetCharOffset <= p.endCharOffset) {
                                        resolvedP = i;
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found) {
                                    if (!currentPages.isEmpty() && targetCharOffset >= currentPages.get(currentPages.size() - 1).endCharOffset) {
                                        resolvedP = currentPages.size() - 1;
                                    } else {
                                        resolvedP = 0;
                                    }
                                }
                                currentPageIndex = resolvedP;
                            } else if (anchorFraction >= 0.0) {
                                int charOff = (int) Math.round(anchorFraction * rawText.length());
                                int resolvedP = 0;
                                boolean found = false;
                                for (int i = 0; i < currentPages.size(); i++) {
                                    TextPaginator.Page p = currentPages.get(i);
                                    if (charOff >= p.startCharOffset && charOff <= p.endCharOffset) {
                                        resolvedP = i;
                                        found = true;
                                        break;
                                    }
                                }
                                if (!found) {
                                    if (!currentPages.isEmpty() && charOff >= currentPages.get(currentPages.size() - 1).endCharOffset) {
                                        resolvedP = currentPages.size() - 1;
                                    } else {
                                        resolvedP = 0;
                                    }
                                }
                                currentPageIndex = resolvedP;
                            } else if (targetPage >= 0 && targetPage < currentPages.size()) {
                                currentPageIndex = targetPage;
                            } else if (currentPageIndex >= currentPages.size()) {
                                currentPageIndex = Math.max(0, currentPages.size() - 1);
                            }
                        }

                        renderCurrentPage();
                        updateMenuControls();
                        isInitialLoading = false; // Первичная страница представлена пользователю
                    }
                });
            }
        });
    }

    /**
     * Концепция Apple Books: мгновенная визуализация текущей видимой страницы (~5–15 мс)
     * сразу в момент изменения шрифта/настроек, с последующим фоновым пересчётом всей главы и счётчика страниц книги.
     */
    private void renderInstantViewportPreview(int anchorCharOffset) {
        if (readerCanvas == null || paginator == null || chapters == null || chapters.isEmpty()) return;
        String cachedText = chapterTextMemoryCache.get(currentChapterIndex);
        if (cachedText == null || cachedText.isEmpty()) return;
        try {
            int[] dims = resolveReaderCanvasDims();
            TypographyConfig cfgSnap = cloneTypographyConfig(typographyConfig);
            Paint previewPaint = readerCanvas.buildPaintForConfig(cfgSnap);
            TextPaginator.Page previewPage = paginator.paginateSinglePageFromOffset(
                    cachedText, Math.max(0, anchorCharOffset), dims[0], dims[1], previewPaint, cfgSnap);
            if (previewPage != null) {
                readerCanvas.setTypographyConfig(typographyConfig);
                String title = getActiveChapterTitle();
                double percent = calculateCurrentGlobalPercent();
                int totalCh = chapters.size();
                int chTotal = (currentPages != null && !currentPages.isEmpty()) ? currentPages.size() : 1;
                readerCanvas.setPage(
                        previewPage,
                        globalPageIndex,
                        totalBookPages,
                        title,
                        currentChapterIndex,
                        totalCh,
                        percent,
                        Math.min(currentPageIndex + 1, chTotal),
                        chTotal
                );
            }
        } catch (Throwable ignored) {}
    }

    private void scheduleDebouncedRepaginate() {
        // 1. Немедленно останавливаем фоновый пересчёт всей книги, чтобы не мешал мгновенной отрисовке
        prepaginationTaskId.incrementAndGet();
        if (pendingPrepagRunnable != null) {
            autoSaveHandler.removeCallbacks(pendingPrepagRunnable);
            pendingPrepagRunnable = null;
        }

        // 2. Фиксируем точную позицию чтения (символьное смещение) до серии нажатий +/-
        if (pendingRepagAnchorCharOffset < 0 && currentPages != null && !currentPages.isEmpty() && currentPageIndex < currentPages.size()) {
            pendingRepagAnchorCharOffset = Math.max(0, currentPages.get(currentPageIndex).startCharOffset);
            pendingRepagAnchorFraction = (double) currentPageIndex / (double) Math.max(1, currentPages.size());
        }

        // 3. МГНОВЕННО визуализируем видимую страницу с новым размером шрифта (0 мс задержки!)
        renderInstantViewportPreview(Math.max(0, pendingRepagAnchorCharOffset));

        // 4. Откладываем полный пересчёт текущей главы и всей книги до паузы после нажатий пользователя
        if (pendingRepagRunnable != null) {
            autoSaveHandler.removeCallbacks(pendingRepagRunnable);
        }
        pendingRepagRunnable = new Runnable() {
            @Override
            public void run() {
                double anchorFrac = pendingRepagAnchorFraction;
                int anchorChar = pendingRepagAnchorCharOffset;
                pendingRepagAnchorFraction = -1.0;
                pendingRepagAnchorCharOffset = -1;
                repaginateCurrentChapterInternal(anchorFrac, anchorChar, true);
            }
        };
        autoSaveHandler.postDelayed(pendingRepagRunnable, 260L);
    }

    private void repaginateCurrentChapter() {
        int anchorChar = -1;
        if (currentPages != null && !currentPages.isEmpty() && currentPageIndex < currentPages.size()) {
            anchorChar = Math.max(0, currentPages.get(currentPageIndex).startCharOffset);
        }
        // Мгновенный отклик видимой страницы и для других настроек (выравнивание, отступ, межстрочный, шрифт)
        if (anchorChar >= 0) {
            renderInstantViewportPreview(anchorChar);
        }
        repaginateCurrentChapterInternal(-1.0, anchorChar, true);
    }

    private void repaginateCurrentChapterInternal(double anchorOverride, int anchorCharOverride, boolean debouncePrepag) {
        // Очищаем только свёрстанные страницы, сохраняя сырой текст главы в RAM (0 мс чтения диска!)
        chapterPagesMemoryCache.clear();
        double fraction = anchorOverride;
        if (fraction < 0 && currentPages != null && !currentPages.isEmpty() && currentPageIndex < currentPages.size()) {
            fraction = (double) currentPageIndex / (double) Math.max(1, currentPages.size());
        }
        if (chapters != null && !chapters.isEmpty()) {
            final int[] dims = resolveReaderCanvasDims();
            final String sig = buildPrepagSignature(dims[0], dims[1], typographyConfig);
            if (!sig.equals(currentPrepagConfigSig)) {
                currentPrepagConfigSig = sig;
                int[] cachedCounts = (cacheManager != null && bookUuid != null)
                        ? cacheManager.loadPageCountsCache(bookUuid, sig, chapters.size()) : null;
                if (cachedCounts != null && cachedCounts.length == chapters.size()) {
                    if (chapterPageCounts == null || chapterPageCounts.length != chapters.size()) {
                        chapterPageCounts = new int[chapters.size()];
                    }
                    if (chapterPageExact == null || chapterPageExact.length != chapters.size()) {
                        chapterPageExact = new boolean[chapters.size()];
                    }
                    for (int i = 0; i < chapters.size(); i++) {
                        chapterPageCounts[i] = Math.max(1, cachedCounts[i]);
                        chapterPageExact[i] = true;
                    }
                } else {
                    if (chapterPageExact != null) {
                        for (int i = 0; i < chapterPageExact.length; i++) {
                            chapterPageExact[i] = false;
                        }
                    }
                    scheduleDelayedPrepagination(dims[0], dims[1], sig, debouncePrepag ? 1000L : 500L);
                }
            }
        }
        if (chapters != null && currentChapterIndex >= 0 && currentChapterIndex < chapters.size()) {
            if (anchorCharOverride >= 0) {
                loadChapterInternal(currentChapterIndex, fraction, -1, anchorCharOverride, -1);
            } else {
                loadChapterWithFraction(currentChapterIndex, fraction);
            }
        }
    }

    private double calculateCurrentGlobalPercent() {
        if (totalBookPages <= 0) return 0.0;
        double p = ((double) globalPageIndex / (double) totalBookPages) * 100.0;
        return Math.min(100.0, Math.max(0.0, p));
    }

    private String getActiveChapterTitle() {
        if (cachedTocTree != null && !cachedTocTree.isEmpty() && currentChapterIndex >= 0) {
            int currentOffset = (currentPages != null && currentPageIndex < currentPages.size())
                    ? currentPages.get(currentPageIndex).startCharOffset : 0;
            String tocTitle = findActiveTocTitle(cachedTocTree, currentChapterIndex, currentOffset);
            if (tocTitle != null && !tocTitle.trim().isEmpty()) {
                return tocTitle;
            }
        }
        if (chapters != null && currentChapterIndex >= 0 && currentChapterIndex < chapters.size()) {
            String chTitle = chapters.get(currentChapterIndex).getTitle();
            if (chTitle != null && !chTitle.trim().isEmpty() && !chTitle.startsWith("ch_") && !chTitle.startsWith("ch-")) {
                return chTitle;
            }
        }
        return bookTitle != null ? bookTitle : "";
    }

    private String findActiveTocTitle(List<EpubParser.TocNode> nodes, int curSpineIndex, int curCharOffset) {
        List<EpubParser.TocNode> flat = new ArrayList<>();
        flattenAllTocNodes(nodes, flat);
        EpubParser.TocNode best = null;
        for (EpubParser.TocNode node : flat) {
            if (node.spineIndex < 0) continue;
            boolean validSameSpine = (node.spineIndex == curSpineIndex && node.charOffset <= curCharOffset);
            boolean earlierSpine = (node.spineIndex < curSpineIndex);
            if (!validSameSpine && !earlierSpine) continue;
            if (best == null
                    || best.spineIndex < node.spineIndex
                    || (best.spineIndex == node.spineIndex && best.charOffset <= node.charOffset)) {
                best = node;
            }
        }
        return best != null ? best.title : null;
    }

    private void flattenAllTocNodes(List<EpubParser.TocNode> nodes, List<EpubParser.TocNode> out) {
        if (nodes == null) return;
        for (EpubParser.TocNode n : nodes) {
            out.add(n);
            if (n.children != null && !n.children.isEmpty()) {
                flattenAllTocNodes(n.children, out);
            }
        }
    }

    private void renderCurrentPage() {
        try {
            recalculateTotalBookPages();
            if (!currentPages.isEmpty() && currentPageIndex < currentPages.size()) {
                refreshCanvasChapterQuotes(false);
                String title = getActiveChapterTitle();
                double percent = calculateCurrentGlobalPercent();
                int totalChapters = chapters != null ? chapters.size() : 1;
                readerCanvas.setPage(
                        currentPages.get(currentPageIndex),
                        globalPageIndex,
                        totalBookPages,
                        title,
                        currentChapterIndex,
                        totalChapters,
                        percent,
                        currentPageIndex + 1,
                        currentPages.size()
                );
            }

            if (!hasPerformedInitialRefresh) {
                hasPerformedInitialRefresh = true;
                forceEpdRefresh();
            }

            pageTurnCounter++;
            if (pageTurnCounter >= typographyConfig.getEpdFullRefreshInterval()) {
                pageTurnCounter = 0;
                forceEpdRefresh();
            }

            if (userHasInteracted) {
                saveProgress();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in renderCurrentPage", t);
        }
    }

    private void flipPageForward() {
        try {
            if (isPaginating || isInitialLoading
                    || chapters == null || chapters.isEmpty()
                    || currentPages == null || currentPages.isEmpty()
                    || (readerLoadingOverlay != null && readerLoadingOverlay.getVisibility() == View.VISIBLE)) {
                return; // Защита от наложения пагинаций и ложного завершения до загрузки книги
            }
            userHasInteracted = true;
            isInitialLoading = false;
            if (currentPageIndex + 1 < currentPages.size()) {
                currentPageIndex++;
                renderCurrentPage();
                updateMenuControls();
                scheduleAutoSaveProgress();
            } else if (currentChapterIndex + 1 < chapters.size()) {
                currentChapterIndex++;
                currentPageIndex = 0;
                loadChapter(currentChapterIndex, 0);
                scheduleAutoSaveProgress();
            } else {
                markBookCompletedAtEnd();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in flipPageForward", t);
        }
    }

    private void markBookCompletedAtEnd() {
        try {
            if (isPaginating || isInitialLoading
                    || chapters == null || chapters.isEmpty()
                    || currentPages == null || currentPages.isEmpty()
                    || currentChapterIndex < chapters.size() - 1
                    || currentPageIndex < currentPages.size() - 1) {
                return;
            }
            boolean alreadyDone = false;
            String cardId = null;
            String docUuid = null;
            if (dbHelper != null && bookUuid != null) {
                Book b = dbHelper.getBookByUuid(bookUuid);
                if (b != null) {
                    alreadyDone = "done".equalsIgnoreCase(b.getShelfType()) && b.getPercent() >= 99.5;
                    cardId = b.getLibraryCardId();
                    docUuid = b.getDocumentUuid();
                }
            }
            if (!alreadyDone) {
                long now = System.currentTimeMillis();
                int parIndex = calculateCurrentParagraphIndex();
                int spineIndex = chapterIndexToSpineIndex(currentChapterIndex);
                String opfItemId = (chapters != null && currentChapterIndex >= 0 && currentChapterIndex < chapters.size())
                        ? chapters.get(currentChapterIndex).getOpfItemId() : "";
                int pageCharOffset = (currentPages != null && currentPageIndex >= 0 && currentPageIndex < currentPages.size())
                        ? Math.max(0, currentPages.get(currentPageIndex).startCharOffset) : 0;
                String cfi = (opfItemId != null && !opfItemId.isEmpty())
                        ? YandexBooksApiClient.buildCanonicalCfi(spineIndex, opfItemId, parIndex, pageCharOffset)
                        : YandexBooksApiClient.generateCfi(spineIndex, parIndex);

                ReadingProgress progress = new ReadingProgress(
                        bookUuid,
                        100.0,
                        spineIndex,
                        parIndex,
                        currentPageIndex,
                        now
                );
                progress.setCfi(cfi);
                if (cardId != null && !cardId.isEmpty()) progress.setLibraryCardId(cardId);
                if (docUuid != null && !docUuid.isEmpty()) progress.setDocumentUuid(docUuid);

                if (dbHelper != null) {
                    dbHelper.saveProgress(progress);
                    dbHelper.updateBookShelf(bookUuid, "done");
                }
                if (syncManager != null) {
                    syncManager.saveAndSyncProgress(progress, true, null);
                }
                if (apiClient != null) {
                    apiClient.updateBookShelfState(bookUuid, cardId, "done", new YandexBooksApiClient.ApiCallback<Boolean>() {
                        @Override
                        public void onSuccess(Boolean result) {}
                        @Override
                        public void onError(String errorMessage) {}
                    });
                }
                updateMenuControls();
                EinkNotice.show(this, "Книга завершена", "Достигнут конец книги: отмечено 100% и перенесено на полку «Прочитано».");
            } else {
                EinkNotice.show(this, "Чтение", "Конец книги.");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in markBookCompletedAtEnd", t);
            EinkNotice.show(this, "Чтение", "Конец книги.");
        }
    }

    private void flipPageBackward() {
        try {
            if (isPaginating || isInitialLoading
                    || chapters == null || chapters.isEmpty()
                    || currentPages == null || currentPages.isEmpty()
                    || (readerLoadingOverlay != null && readerLoadingOverlay.getVisibility() == View.VISIBLE)) {
                return; // Защита от наложения пагинаций и случайных нажатий во время загрузки
            }
            userHasInteracted = true;
            isInitialLoading = false;
            if (currentPageIndex > 0) {
                currentPageIndex--;
                renderCurrentPage();
                updateMenuControls();
                scheduleAutoSaveProgress();
            } else if (chapters != null && currentChapterIndex > 0) {
                currentChapterIndex--;
                loadChapter(currentChapterIndex, -999);
                scheduleAutoSaveProgress();
            } else {
                EinkNotice.show(this, "Чтение", "Начало книги.");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in flipPageBackward", t);
        }
    }

    private void forceEpdRefresh() {
        try {
            EpdController.applyGCOnce(readerCanvas);
            EpdController.requestFullRefresh(this, readerCanvas);
        } catch (Throwable t) {
            Log.e(TAG, "Error in forceEpdRefresh", t);
        }
    }

    private int calculateCurrentParagraphIndex() {
        try {
            if (currentPages == null || currentPageIndex < 0 || currentPageIndex >= currentPages.size()) return 0;
            String rawText = chapterTextMemoryCache.get(currentChapterIndex);
            if (rawText == null || rawText.isEmpty()) return 0;
            int offset = currentPages.get(currentPageIndex).startCharOffset;
            if (offset <= 0) return 0;
            int parCount = 0;
            int len = Math.min(offset, rawText.length());
            for (int i = 0; i < len; i++) {
                if (rawText.charAt(i) == '\n') parCount++;
            }
            return parCount;
        } catch (Throwable t) {
            return 0;
        }
    }

    private void saveProgress() {
        try {
            double percent = calculateCurrentGlobalPercent();
            long now = System.currentTimeMillis();
            int parIndex = calculateCurrentParagraphIndex();
            int spineIndex = chapterIndexToSpineIndex(currentChapterIndex);
            String opfItemId = (chapters != null && currentChapterIndex >= 0 && currentChapterIndex < chapters.size())
                    ? chapters.get(currentChapterIndex).getOpfItemId() : "";
            int pageCharOffset = (currentPages != null && currentPageIndex >= 0 && currentPageIndex < currentPages.size())
                    ? Math.max(0, currentPages.get(currentPageIndex).startCharOffset) : 0;
            String cfi = (opfItemId != null && !opfItemId.isEmpty())
                    ? YandexBooksApiClient.buildCanonicalCfi(spineIndex, opfItemId, parIndex, pageCharOffset)
                    : YandexBooksApiClient.generateCfi(spineIndex, parIndex);

            ReadingProgress progress = new ReadingProgress(
                    bookUuid,
                    percent,
                    spineIndex,
                    parIndex,
                    currentPageIndex,
                    now
            );
            progress.setCfi(cfi);

            try {
                Book b = dbHelper.getBookByUuid(bookUuid);
                if (b != null) {
                    if (b.getLibraryCardId() != null && !b.getLibraryCardId().isEmpty()) {
                        progress.setLibraryCardId(b.getLibraryCardId());
                    }
                    if (b.getDocumentUuid() != null && !b.getDocumentUuid().isEmpty()) {
                        progress.setDocumentUuid(b.getDocumentUuid());
                    }
                }
            } catch (Exception ignored) {}

            // Мгновенно сохраняем в локальную SQLite базу ридера
            if (dbHelper != null) {
                dbHelper.saveProgress(progress);
            }

            // Отправка в облако Яндекса
            if (syncManager != null) {
                syncManager.saveAndSyncProgress(progress, true, null);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in saveProgress", t);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        EpdController.enableRegalMode(readerCanvas);
        forceEpdRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        if (userHasInteracted) {
            saveProgress();
            syncManager.flushOfflineQueue();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        if (pendingRepagRunnable != null) autoSaveHandler.removeCallbacks(pendingRepagRunnable);
        if (pendingPrepagRunnable != null) autoSaveHandler.removeCallbacks(pendingPrepagRunnable);
        paginationExecutor.shutdownNow();
        prepaginationExecutor.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (isMenuOverlayVisible()) {
            hideMenuOverlay();
            return;
        }
        autoSaveHandler.removeCallbacks(autoSaveRunnable);
        if (userHasInteracted) {
            saveProgress();
            syncManager.flushOfflineQueue();
        }
        super.onBackPressed();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) return false;
        try {
            int keyCode = event.getKeyCode();
            if (keyHandler != null && HardwareKeyHandler.isPageKey(keyCode, event)) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        try {
                            event.startTracking();
                        } catch (Throwable ignored) {}
                    }
                    return keyHandler.handleKeyDown(keyCode, event);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    return keyHandler.handleKeyUp(keyCode, event);
                }
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in dispatchKeyEvent", t);
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyHandler != null && keyHandler.handleKeyDown(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyHandler != null && keyHandler.handleKeyUp(keyCode, event)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }
}
