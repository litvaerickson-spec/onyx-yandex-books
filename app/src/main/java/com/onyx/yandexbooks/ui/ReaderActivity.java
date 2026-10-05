package com.onyx.yandexbooks.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import java.io.File;
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
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.text.TextUtils;
import java.text.SimpleDateFormat;
import java.util.Date;

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
import java.util.Locale;
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

    // Элементы подвкладки «Формат»
    private Button btnToggleContrast; // Утолщение текста
    private Button btnToggleHyphenation; // Переносы слов TeX
    private Button btnToggleEinkContrast; // Контраст E-Ink

    // Элементы подвкладки «Между строк»
    private Button btnToggleLineSpacing;
    private Button btnToggleMargins;
    private Button btnToggleVertMargins;

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

    // Сквозной подсчет страниц книги от общего объема
    private int[] chapterPageCounts;
    private int totalBookPages = 1;
    private int globalPageIndex = 1;
    private double avgCharsPerPage = 750.0;

    // Фоновый исполнитель пагинации: полностью исключает блокировку UI-потока и ANR
    private final ExecutorService paginationExecutor = Executors.newSingleThreadExecutor();
    private final AtomicLong paginationTaskId = new AtomicLong(0);

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

        readerTopBar = findViewById(R.id.reader_top_bar);
        readerTopDivider = findViewById(R.id.reader_top_divider);
        readerBottomBar = findViewById(R.id.reader_bottom_bar);
        readerFormatPanel = findViewById(R.id.reader_format_panel);

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

        // Контролы подвкладки «Формат»
        btnToggleContrast = (Button) findViewById(R.id.btn_toggle_contrast);
        btnToggleHyphenation = (Button) findViewById(R.id.btn_toggle_hyphenation);
        btnToggleEinkContrast = (Button) findViewById(R.id.btn_toggle_eink_contrast);

        // Контролы подвкладки «Между строк»
        btnToggleLineSpacing = (Button) findViewById(R.id.btn_toggle_line_spacing);
        btnToggleMargins = (Button) findViewById(R.id.btn_toggle_margins);
        btnToggleVertMargins = (Button) findViewById(R.id.btn_toggle_vert_margins);

        if (readerBookTitleTop != null) {
            readerBookTitleTop.setText(bookTitle != null ? bookTitle : "Яндекс Книги");
        }

        appSettings = new com.onyx.yandexbooks.core.storage.AppSettings(this);
        typographyConfig = new TypographyConfig();
        typographyConfig.setFontSizeSp(appSettings.getFontSizeSp());
        typographyConfig.setFontFamily(appSettings.getFontFamily());
        typographyConfig.setLineSpacingMultiplier(appSettings.getLineSpacingMultiplier());
        typographyConfig.setParagraphIndentPx(appSettings.getParagraphIndentPx());
        typographyConfig.setHyphenationEnabled(appSettings.isHyphenationEnabled());
        typographyConfig.setBoldText(appSettings.isBoldText());
        typographyConfig.setContrastMode(appSettings.getContrastMode());
        typographyConfig.setVerticalMarginMode(appSettings.getVerticalMarginMode());

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
        syncManager = new SyncManager(this, apiClient);

        setupHardwareKeys();
        setupCanvasListeners();
        setupNeoReaderControls();

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
                    if (currentChapterIndex > 0) {
                        currentChapterIndex--;
                        currentPageIndex = 0;
                        isInitialLoading = false;
                        loadChapter(currentChapterIndex);
                    } else {
                        Toast.makeText(ReaderActivity.this, "Это первая глава книги", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        if (btnNextChapter != null) {
            btnNextChapter.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (chapters != null && currentChapterIndex + 1 < chapters.size()) {
                        currentChapterIndex++;
                        currentPageIndex = 0;
                        isInitialLoading = false;
                        loadChapter(currentChapterIndex);
                    } else {
                        Toast.makeText(ReaderActivity.this, "Это последняя глава книги", Toast.LENGTH_SHORT).show();
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
            seekbarFontSize.setMax(24); // 12sp .. 36sp
            seekbarFontSize.setProgress(Math.max(0, Math.min(24, typographyConfig.getFontSizeSp() - 12)));
            seekbarFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (fromUser) {
                        int size = 12 + progress;
                        typographyConfig.setFontSizeSp(size);
                        if (fontSizeLabel != null) fontSizeLabel.setText(String.valueOf(size));
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    int size = 12 + seekBar.getProgress();
                    typographyConfig.setFontSizeSp(size);
                    appSettings.setFontSizeSp(size);
                    repaginateCurrentChapter();
                }
            });
        }

        if (btnFontIncrease != null) {
            btnFontIncrease.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int cur = typographyConfig.getFontSizeSp();
                    if (cur < 36) {
                        int next = cur + 1;
                        typographyConfig.setFontSizeSp(next);
                        appSettings.setFontSizeSp(next);
                        if (fontSizeLabel != null) fontSizeLabel.setText(String.valueOf(next));
                        if (seekbarFontSize != null) seekbarFontSize.setProgress(next - 12);
                        repaginateCurrentChapter();
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
                        repaginateCurrentChapter();
                    }
                }
            });
        }

        updateFontFamilyButtonText();
        if (btnToggleFontFamily != null) {
            btnToggleFontFamily.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cycleFontFamily();
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

        // Подвкладка 2: Формат (Жирный/Обычный, Переносы слов TeX, Контраст E-Ink)
        updateContrastButtonText();
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
            btnSubtabView.setTextColor(active ? Color.WHITE : Color.BLACK);
        }
        if (btnSubtabFormat != null) {
            boolean active = "format".equals(subtab);
            btnSubtabFormat.setBackgroundResource(active ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
            btnSubtabFormat.setTextColor(active ? Color.WHITE : Color.BLACK);
        }
        if (btnSubtabSpacing != null) {
            boolean active = "spacing".equals(subtab);
            btnSubtabSpacing.setBackgroundResource(active ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
            btnSubtabSpacing.setTextColor(active ? Color.WHITE : Color.BLACK);
        }
    }

    private void updateFontFamilyButtonText() {
        if (btnToggleFontFamily == null) return;
        String f = typographyConfig.getFontFamily();
        if ("sans-serif".equalsIgnoreCase(f)) {
            btnToggleFontFamily.setText("Sans-Serif ∨");
        } else if ("monospace".equalsIgnoreCase(f)) {
            btnToggleFontFamily.setText("Monospace ∨");
        } else {
            btnToggleFontFamily.setText("Serif ∨");
        }
    }

    private void cycleFontFamily() {
        String f = typographyConfig.getFontFamily();
        String next;
        if ("serif".equalsIgnoreCase(f)) {
            next = "sans-serif";
        } else if ("sans-serif".equalsIgnoreCase(f)) {
            next = "monospace";
        } else {
            next = "serif";
        }
        typographyConfig.setFontFamily(next);
        appSettings.setFontFamily(next);
        updateFontFamilyButtonText();
        if (readerCanvas != null) readerCanvas.setTypographyConfig(typographyConfig);
        repaginateCurrentChapter();
    }

    private void updateIndentButtonText() {
        if (btnToggleIndent == null) return;
        btnToggleIndent.setText(typographyConfig.getParagraphIndentPx() + " px ∨");
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

    private void updateLineSpacingButtonText() {
        if (btnToggleLineSpacing == null) return;
        btnToggleLineSpacing.setText(String.format(Locale.US, "%.2fx ∨", typographyConfig.getLineSpacingMultiplier()));
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
        if (com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_MEDIUM.equalsIgnoreCase(mode)) {
            return "Средние (32) ∨";
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.MARGIN_WIDE.equalsIgnoreCase(mode)) {
            return "Широкие (48) ∨";
        } else {
            return "Узкие (18) ∨";
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
        if (btnToggleMargins != null) btnToggleMargins.setText(getMarginButtonText());
        if (readerCanvas != null) readerCanvas.setTypographyConfig(typographyConfig);
        repaginateCurrentChapter();
    }

    private void updateVertMarginButtonText() {
        if (btnToggleVertMargins == null) return;
        String mode = typographyConfig.getVerticalMarginMode();
        if ("small".equalsIgnoreCase(mode)) {
            btnToggleVertMargins.setText("Малые ∨");
        } else if ("large".equalsIgnoreCase(mode)) {
            btnToggleVertMargins.setText("Большие ∨");
        } else {
            btnToggleVertMargins.setText("Стандарт ∨");
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
        updateVertMarginButtonText();
        if (readerCanvas != null) readerCanvas.setTypographyConfig(typographyConfig);
        repaginateCurrentChapter();
    }

    private void updateMenuControls() {
        if (chapters != null && currentChapterIndex < chapters.size()) {
            Chapter ch = chapters.get(currentChapterIndex);
            String title = ch.getTitle();
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

    private void showTableOfContentsDialog() {
        try {
            if (chapters == null || chapters.isEmpty()) {
                Toast.makeText(this, "Оглавление недоступно", Toast.LENGTH_SHORT).show();
                return;
            }

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final float density = getResources().getDisplayMetrics().density;
        DisplayMetrics dm = getResources().getDisplayMetrics();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = (int) (12 * density);
        root.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(this);
        titleView.setText("Содержание и закладки");
        titleView.setTextSize(14);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, (int) (6 * density));
        root.addView(titleView);

        // Сегментированные вкладки: [ Оглавление ] | [ Закладки ]
        LinearLayout tabsLayout = new LinearLayout(this);
        tabsLayout.setOrientation(LinearLayout.HORIZONTAL);
        tabsLayout.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (32 * density)));
        tabsLayout.setGravity(Gravity.CENTER);

        final Button btnTabChapters = new Button(this);
        btnTabChapters.setText("Оглавление (" + chapters.size() + ")");
        btnTabChapters.setTextSize(12);
        btnTabChapters.setTypeface(null, Typeface.BOLD);
        btnTabChapters.setBackgroundResource(R.drawable.btn_eink_primary);
        btnTabChapters.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams tabLp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        tabLp1.setMargins(0, 0, (int) (4 * density), 0);
        btnTabChapters.setLayoutParams(tabLp1);

        final Button btnTabBookmarks = new Button(this);
        List<DatabaseHelper.Bookmark> initialBookmarks = dbHelper.getBookmarks(bookUuid);
        btnTabBookmarks.setText("Закладки (" + initialBookmarks.size() + ")");
        btnTabBookmarks.setTextSize(12);
        btnTabBookmarks.setTypeface(null, Typeface.BOLD);
        btnTabBookmarks.setBackgroundResource(R.drawable.btn_eink);
        btnTabBookmarks.setTextColor(Color.BLACK);
        LinearLayout.LayoutParams tabLp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        tabLp2.setMargins((int) (4 * density), 0, 0, 0);
        btnTabBookmarks.setLayoutParams(tabLp2);

        tabsLayout.addView(btnTabChapters);
        tabsLayout.addView(btnTabBookmarks);
        root.addView(tabsLayout);

        View divider = new View(this);
        divider.setBackgroundColor(Color.BLACK);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density));
        divLp.setMargins(0, (int) (6 * density), 0, (int) (6 * density));
        root.addView(divider, divLp);

        // Контейнер 1: Оглавление
        final ListView listChaptersView = new ListView(this);
        listChaptersView.setDivider(new ColorDrawable(Color.LTGRAY));
        listChaptersView.setDividerHeight((int) Math.max(1, density));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        listChaptersView.setLayoutParams(listLp);

        BaseAdapter tocAdapter = new BaseAdapter() {
            @Override
            public int getCount() { return chapters.size(); }
            @Override
            public Object getItem(int pos) { return chapters.get(pos); }
            @Override
            public long getItemId(int pos) { return pos; }
            @Override
            public View getView(final int pos, View convertView, ViewGroup parent) {
                TextView tv = (TextView) convertView;
                if (tv == null) {
                    tv = new TextView(ReaderActivity.this);
                    tv.setTextSize(13);
                    int pV = (int) (10 * density);
                    int pH = (int) (6 * density);
                    tv.setPadding(pH, pV, pH, pV);
                }
                Chapter ch = chapters.get(pos);
                String title = ch.getTitle();
                if (title == null || title.trim().isEmpty()) {
                    title = "Глава " + (pos + 1);
                }
                if (pos == currentChapterIndex) {
                    tv.setText("▶ " + title + " (читается)");
                    tv.setTypeface(null, Typeface.BOLD);
                    tv.setTextColor(Color.BLACK);
                } else {
                    tv.setText(title);
                    tv.setTypeface(null, Typeface.NORMAL);
                    tv.setTextColor(Color.BLACK);
                }
                return tv;
            }
        };
        listChaptersView.setAdapter(tocAdapter);
        listChaptersView.setSelection(Math.max(0, currentChapterIndex - 2));

        listChaptersView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                dialog.dismiss();
                hideMenuOverlay();
                currentPageIndex = 0;
                isInitialLoading = false;
                loadChapter(position);
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

        final ListView listBookmarksView = new ListView(this);
        listBookmarksView.setDivider(new ColorDrawable(Color.LTGRAY));
        listBookmarksView.setDividerHeight((int) Math.max(1, density));
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
                    btnDel.setText("✕");
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
                        Toast.makeText(ReaderActivity.this, "Закладка удалена", Toast.LENGTH_SHORT).show();
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
                Toast.makeText(ReaderActivity.this, "Закладка сохранена: " + title, Toast.LENGTH_SHORT).show();
            }
        });

        // Переключение вкладок Оглавление / Закладки
        btnTabChapters.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnTabChapters.setBackgroundResource(R.drawable.btn_eink_primary);
                btnTabChapters.setTextColor(Color.WHITE);
                btnTabBookmarks.setBackgroundResource(R.drawable.btn_eink);
                btnTabBookmarks.setTextColor(Color.BLACK);
                listChaptersView.setVisibility(View.VISIBLE);
                bookmarksContainer.setVisibility(View.GONE);
            }
        });

        btnTabBookmarks.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnTabBookmarks.setBackgroundResource(R.drawable.btn_eink_primary);
                btnTabBookmarks.setTextColor(Color.WHITE);
                btnTabChapters.setBackgroundResource(R.drawable.btn_eink);
                btnTabChapters.setTextColor(Color.BLACK);
                listChaptersView.setVisibility(View.GONE);
                bookmarksContainer.setVisibility(View.VISIBLE);
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
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            int w = (int) (dm.widthPixels * 0.92);
            int h = (int) (dm.heightPixels * 0.88);
            dialog.getWindow().setLayout(w, h);
        }
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                forceEpdRefresh();
            }
        });
        dialog.show();
        forceEpdRefresh();
        } catch (Throwable t) {
            Log.e(TAG, "Error displaying TOC dialog", t);
            Toast.makeText(this, "Ошибка отображения оглавления", Toast.LENGTH_SHORT).show();
        }
    }

    private void showProgressDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        float density = getResources().getDisplayMetrics().density;
        DisplayMetrics dm = getResources().getDisplayMetrics();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
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
        btnSyncNow.setText("Синхронизировать сейчас");
        btnSyncNow.setTextSize(12);
        btnSyncNow.setTypeface(null, Typeface.BOLD);
        btnSyncNow.setTextColor(Color.WHITE);
        btnSyncNow.setBackgroundResource(R.drawable.btn_eink_primary);
        btnSyncNow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (38 * density)));
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
                        btnSyncNow.setText("Синхронизировано!");
                        Toast.makeText(ReaderActivity.this, "Прогресс отправлен в облако Яндекс Книг", Toast.LENGTH_SHORT).show();
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
        LinearLayout.LayoutParams lpClose = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (36 * density));
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
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.WHITE));
            int w = (int) (dm.widthPixels * 0.85);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                forceEpdRefresh();
            }
        });
        dialog.show();
        forceEpdRefresh();
    }

    private void calculateChapterLengths() {
        if (chapters == null || chapters.isEmpty()) {
            chapterLengths = new long[0];
            chapterPageCounts = new int[0];
            totalBookLength = 0;
            totalBookPages = 1;
            globalPageIndex = 1;
            return;
        }
        chapterLengths = new long[chapters.size()];
        if (chapterPageCounts == null || chapterPageCounts.length != chapters.size()) {
            chapterPageCounts = new int[chapters.size()];
        }
        totalBookLength = 0;
        for (int i = 0; i < chapters.size(); i++) {
            Chapter ch = chapters.get(i);
            long len = cacheManager.getChapterLength(bookUuid, ch.getId());
            if (len <= 0) {
                len = 10000; // Оценочная длина по умолчанию
            }
            chapterLengths[i] = len;
            totalBookLength += len;
        }
        recalculateTotalBookPages();
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
            int pages = (chapterPageCounts != null && i < chapterPageCounts.length) ? chapterPageCounts[i] : 0;
            if (pages <= 0) {
                long chLen = (chapterLengths != null && i < chapterLengths.length) ? chapterLengths[i] : 10000;
                pages = Math.max(1, (int) Math.round(chLen / safeAvgChars));
                if (chapterPageCounts != null && i < chapterPageCounts.length) {
                    chapterPageCounts[i] = pages;
                }
            }
            if (i < currentChapterIndex) {
                preceding += pages;
            }
            total += pages;
        }

        totalBookPages = Math.max(1, total);
        globalPageIndex = Math.max(1, Math.min(totalBookPages, preceding + currentPageIndex + 1));
    }

    private void loadBookData() {
        // Проверяем, есть ли уже главы в локальной БД
        List<Chapter> localChapters = dbHelper.getChapters(bookUuid);
        File epubFile = cacheManager.getEpubFile(bookUuid);
        int lastTocVersion = appSettings.getInt("toc_ver_" + bookUuid, 0);
        boolean hasLocalEpub = epubFile != null && epubFile.exists() && epubFile.length() > 0;
        boolean needsMigration = hasLocalEpub && (lastTocVersion < 6 || localChapters == null || localChapters.size() <= 1);

        if (localChapters != null && !localChapters.isEmpty() && !needsMigration) {
            chapters = localChapters;
            resolveInitialPositionAndOpen();
        } else {
            cacheManager.reparseAndSaveBook(bookUuid, bookTitle, new CacheManager.BookReadyCallback() {
                @Override
                public void onReady(List<Chapter> loadedChapters) {
                    appSettings.putInt("toc_ver_" + bookUuid, 6);
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
        }

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
                // Офлайн или сбой сети - остаемся на локальном месте
            }
        });
    }

    private void resolveInitialPositionAndOpen() {
        calculateChapterLengths();

        ReadingProgress p = dbHelper.getProgress(bookUuid);
        double percent = 0.0;
        int targetChapter = -1;
        int targetPage = 0;

        double intentPercent = getIntent().getDoubleExtra("book_percent", 0.0);
        int intentChapter = getIntent().getIntExtra("book_chapter", -1);

        if (p != null) {
            percent = p.getPercent();
            targetChapter = p.getChapterIndex();
            targetPage = p.getPageIndex();
        } else {
            Book b = dbHelper.getBookByUuid(bookUuid);
            if (b != null) {
                percent = b.getPercent();
                targetChapter = b.getCurrentChapterIndex();
                targetPage = 0;
            }
        }

        if (intentPercent > percent && intentChapter >= 0) {
            percent = intentPercent;
            targetChapter = intentChapter;
        }

        // Защита от ложного 100% (когда глава 0 или книга только открыта)
        if (percent >= 99.0 && targetChapter <= 0) {
            percent = 0.0;
            targetChapter = 0;
            targetPage = 0;
        }

        // Если книга завершена (100% и последняя глава), при повторном открытии для чтения
        // начинаем с первой главы (Глава 0), чтобы ридер не зависал на странице выходных данных/тиража
        if (chapters != null && !chapters.isEmpty() && targetChapter >= chapters.size() - 1 && percent >= 99.0) {
            currentChapterIndex = 0;
            currentPageIndex = 0;
            loadChapter(0, 0);
            return;
        }

        // Если есть конкретный индекс главы > 0:
        if (chapters != null && !chapters.isEmpty() && targetChapter > 0 && targetChapter < chapters.size()) {
            currentChapterIndex = targetChapter;
            currentPageIndex = Math.max(0, targetPage);
            loadChapter(currentChapterIndex, currentPageIndex);
            return;
        }

        // Иначе (если только процент, либо targetChapter <= 0, но percent > 2.0):
        if (percent > 2.0 && percent < 99.0 && totalBookLength > 0 && chapters != null && !chapters.isEmpty()) {
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
            currentChapterIndex = matchedCh;
            loadChapterWithFraction(currentChapterIndex, chapterFraction);
        } else {
            currentChapterIndex = 0;
            currentPageIndex = (targetChapter == 0) ? Math.max(0, targetPage) : 0;
            loadChapter(currentChapterIndex, currentPageIndex);
        }
    }

    private void applyCloudProgressIfNewer(ReadingProgress cloudProgress) {
        if (chapters == null || chapters.isEmpty()) return;
        // Если читатель уже листает страницы книги, не перебиваем его фоновой облачной синхронизацией
        if (!isInitialLoading) return;

        ReadingProgress local = dbHelper.getProgress(bookUuid);
        double curPercent = calculateCurrentGlobalPercent();
        double cloudPercent = cloudProgress.getPercent();
        int cloudChapter = cloudProgress.getChapterIndex();

        // Защита от искаженного 100% при 0 главе
        if (cloudPercent >= 99.0 && cloudChapter <= 0) {
            return;
        }

        long localTs = (local != null) ? local.getTimestamp() : 0L;
        long cloudTs = cloudProgress.getTimestamp();

        boolean isNewer = false;
        if (local == null) {
            isNewer = (cloudPercent > 0);
        } else if (cloudTs > localTs && cloudTs > 0 && Math.abs(cloudPercent - curPercent) > 1.5) {
            isNewer = true;
        } else if (cloudPercent > curPercent + 2.0 && cloudTs >= localTs) {
            isNewer = true;
        }

        if (isNewer) {
            // Если сервер возвращает конкретную главу в допустимом диапазоне (> 0)
            if (cloudChapter > 0 && cloudChapter < chapters.size()) {
                if (cloudChapter != currentChapterIndex || Math.abs(cloudPercent - curPercent) > 2.0) {
                    currentChapterIndex = cloudChapter;
                    currentPageIndex = Math.max(0, cloudProgress.getPageIndex());
                    loadChapter(currentChapterIndex, currentPageIndex);
                    dbHelper.saveProgress(cloudProgress);
                    Toast.makeText(ReaderActivity.this, String.format(Locale.getDefault(), "Синхронизировано: %.0f%% (Гл. %d)", cloudPercent, currentChapterIndex + 1), Toast.LENGTH_SHORT).show();
                }
            } else if (cloudPercent > 0 && cloudPercent < 99.0 && totalBookLength > 0) {
                // Если номер главы не указан (или 0 при высоком проценте) – маппинг по общей длине
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

                if (matchedCh != currentChapterIndex || Math.abs(cloudPercent - curPercent) > 2.0) {
                    currentChapterIndex = matchedCh;
                    loadChapterWithFraction(currentChapterIndex, chapterFraction);
                    dbHelper.saveProgress(cloudProgress);
                    Toast.makeText(ReaderActivity.this, String.format(Locale.getDefault(), "Синхронизировано: %.0f%% (Гл. %d)", cloudPercent, currentChapterIndex + 1), Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void loadChapter(final int index) {
        loadChapter(index, 0);
    }

    private void loadChapter(final int index, final int targetPage) {
        loadChapterInternal(index, -1.0, targetPage);
    }

    private void loadChapterWithFraction(final int index, final double anchorFraction) {
        loadChapterInternal(index, anchorFraction, 0);
    }

    private void loadChapterInternal(final int index, final double anchorFraction, final int targetPage) {
        if (chapters == null || chapters.isEmpty()) return;
        if (index < 0 || index >= chapters.size()) return;
        currentChapterIndex = index;
        currentPageIndex = (targetPage == -999) ? 0 : Math.max(0, targetPage);
        final Chapter ch = chapters.get(index);

        String text = cacheManager.loadChapter(bookUuid, ch.getId());
        if (text != null && !text.isEmpty()) {
            displayChapterText(text, ch.getTitle(), anchorFraction, targetPage);
        } else {
            Toast.makeText(ReaderActivity.this, "Текст главы не найден", Toast.LENGTH_SHORT).show();
        }
    }

    private void displayChapterText(String rawText, String title) {
        displayChapterText(rawText, title, -1.0, 0);
    }

    private void displayChapterText(final String rawText, final String title, final double anchorFraction, final int targetPage) {
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

        final TypographyConfig configCopy = new TypographyConfig();
        configCopy.setFontSizeSp(typographyConfig.getFontSizeSp());
        configCopy.setFontFamily(typographyConfig.getFontFamily());
        configCopy.setLineSpacingMultiplier(typographyConfig.getLineSpacingMultiplier());
        configCopy.setParagraphIndentPx(typographyConfig.getParagraphIndentPx());
        configCopy.setPaddingLeftPx(typographyConfig.getPaddingLeftPx());
        configCopy.setPaddingRightPx(typographyConfig.getPaddingRightPx());
        configCopy.setPaddingTopPx(typographyConfig.getPaddingTopPx());
        configCopy.setPaddingBottomPx(typographyConfig.getPaddingBottomPx());
        configCopy.setFooterReservedHeightPx(typographyConfig.getFooterReservedHeightPx());
        configCopy.setHyphenationEnabled(typographyConfig.isHyphenationEnabled());
        configCopy.setJustifyEnabled(typographyConfig.isJustifyEnabled());
        configCopy.setBoldText(typographyConfig.isBoldText());
        configCopy.setContrastMode(typographyConfig.getContrastMode());
        configCopy.setVerticalMarginMode(typographyConfig.getVerticalMarginMode());

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
                        currentPages = pages;

                        if (currentPages != null && !currentPages.isEmpty()) {
                            if (chapterPageCounts != null && currentChapterIndex < chapterPageCounts.length) {
                                chapterPageCounts[currentChapterIndex] = currentPages.size();
                            }
                            if (rawText != null && rawText.length() > 0) {
                                avgCharsPerPage = (double) rawText.length() / (double) currentPages.size();
                            }
                            recalculateTotalBookPages();

                            if (targetPage == -999) {
                                currentPageIndex = currentPages.size() - 1;
                            } else if (anchorFraction >= 0.0) {
                                int targetCharOffset = (int) Math.round(anchorFraction * rawText.length());
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

    private void repaginateCurrentChapter() {
        if (chapters != null && currentChapterIndex < chapters.size()) {
            double fraction = -1.0;
            if (currentPages != null && !currentPages.isEmpty() && currentPageIndex < currentPages.size()) {
                fraction = (double) currentPageIndex / (double) Math.max(1, currentPages.size());
            }
            String id = chapters.get(currentChapterIndex).getId();
            String text = cacheManager.loadChapter(bookUuid, id);
            if (text != null) {
                displayChapterText(text, chapters.get(currentChapterIndex).getTitle(), fraction, currentPageIndex);
            }
        }
    }

    private double calculateCurrentGlobalPercent() {
        if (chapters == null || chapters.isEmpty()) return 0.0;
        if (totalBookLength <= 0 || chapterLengths == null || chapterLengths.length != chapters.size()) {
            double chapterWeight = 100.0 / chapters.size();
            double inChapterPercent = (currentPages == null || currentPages.isEmpty()) ? 0.0 : ((double) (currentPageIndex + 1) / currentPages.size());
            return Math.min(100.0, Math.max(0.0, (currentChapterIndex * chapterWeight) + (inChapterPercent * chapterWeight)));
        }

        long precedingLength = 0;
        for (int i = 0; i < currentChapterIndex; i++) {
            precedingLength += chapterLengths[i];
        }
        long curChapterLen = chapterLengths[currentChapterIndex];
        double inChapterFraction = 0.0;
        if (currentPages != null && !currentPages.isEmpty()) {
            inChapterFraction = (double) (currentPageIndex + 1) / (double) currentPages.size();
        }
        double globalBytes = precedingLength + (inChapterFraction * curChapterLen);
        double percent = (globalBytes / (double) totalBookLength) * 100.0;
        return Math.min(100.0, Math.max(0.0, percent));
    }

    private void renderCurrentPage() {
        recalculateTotalBookPages();
        if (!currentPages.isEmpty() && currentPageIndex < currentPages.size()) {
            String title = (chapters != null && currentChapterIndex < chapters.size()) ? chapters.get(currentChapterIndex).getTitle() : bookTitle;
            double percent = calculateCurrentGlobalPercent();
            int totalChapters = chapters != null ? chapters.size() : 1;
            readerCanvas.setPage(currentPages.get(currentPageIndex), globalPageIndex, totalBookPages, title, currentChapterIndex, totalChapters, percent);
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

        if (!isInitialLoading) {
            saveProgress();
        }
    }

    private void flipPageForward() {
        isInitialLoading = false;
        if (currentPageIndex + 1 < currentPages.size()) {
            currentPageIndex++;
            renderCurrentPage();
            updateMenuControls();
        } else if (currentChapterIndex + 1 < chapters.size()) {
            currentChapterIndex++;
            currentPageIndex = 0;
            loadChapter(currentChapterIndex);
        }
    }

    private void flipPageBackward() {
        isInitialLoading = false;
        if (currentPageIndex > 0) {
            currentPageIndex--;
            renderCurrentPage();
            updateMenuControls();
        } else if (currentChapterIndex > 0) {
            currentChapterIndex--;
            loadChapter(currentChapterIndex, -999);
        }
    }

    private void forceEpdRefresh() {
        EpdController.requestFullRefresh(this, readerCanvas);
    }

    private void saveProgress() {
        double percent = calculateCurrentGlobalPercent();
        long now = System.currentTimeMillis();

        ReadingProgress progress = new ReadingProgress(
                bookUuid,
                percent,
                currentChapterIndex,
                0,
                currentPageIndex,
                now
        );

        // Мгновенно сохраняем в локальную SQLite базу читалки
        dbHelper.saveProgress(progress);

        // Отправка в облако Яндекса
        syncManager.saveAndSyncProgress(progress, true, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        forceEpdRefresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProgress();
        syncManager.flushOfflineQueue();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        paginationExecutor.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (isMenuOverlayVisible()) {
            hideMenuOverlay();
            return;
        }
        saveProgress();
        syncManager.flushOfflineQueue();
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyHandler.handleKeyDown(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
