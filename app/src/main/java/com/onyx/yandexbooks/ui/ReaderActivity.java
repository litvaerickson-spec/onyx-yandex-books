package com.onyx.yandexbooks.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
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

    // Элементы форматирования
    private TextView fontSizeLabel;
    private Button btnFontDecrease;
    private Button btnFontIncrease;
    private Button btnToggleLineSpacing;
    private Button btnToggleMargins;
    private Button btnToggleHyphenation;
    private Button btnToggleContrast;

    private String bookUuid;
    private String bookTitle;
    private List<Chapter> chapters = new ArrayList<>();
    private int currentChapterIndex = 0;
    private List<TextPaginator.Page> currentPages = new ArrayList<>();
    private int currentPageIndex = 0;
    private int pageTurnCounter = 0;
    private boolean hasPerformedInitialRefresh = false;

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

        fontSizeLabel = (TextView) findViewById(R.id.font_size_label);
        btnFontDecrease = (Button) findViewById(R.id.btn_font_decrease);
        btnFontIncrease = (Button) findViewById(R.id.btn_font_increase);
        btnToggleLineSpacing = (Button) findViewById(R.id.btn_toggle_line_spacing);
        btnToggleMargins = (Button) findViewById(R.id.btn_toggle_margins);
        btnToggleHyphenation = (Button) findViewById(R.id.btn_toggle_hyphenation);
        btnToggleContrast = (Button) findViewById(R.id.btn_toggle_contrast);

        if (readerBookTitleTop != null) {
            readerBookTitleTop.setText(bookTitle != null ? bookTitle : "Яндекс Книги");
        }

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
                    if (fromUser && currentPages != null && !currentPages.isEmpty()) {
                        currentPageIndex = Math.max(0, Math.min(progress, currentPages.size() - 1));
                        updatePageCounterText();
                    }
                }

                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {}

                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                    if (currentPages != null && !currentPages.isEmpty()) {
                        currentPageIndex = Math.max(0, Math.min(seekBar.getProgress(), currentPages.size() - 1));
                        renderCurrentPage();
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

        // Управление форматированием
        if (fontSizeLabel != null) {
            fontSizeLabel.setText(String.valueOf(typographyConfig.getFontSizeSp()));
        }

        if (btnFontIncrease != null) {
            btnFontIncrease.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    typographyConfig.setFontSizeSp(typographyConfig.getFontSizeSp() + 1);
                    if (fontSizeLabel != null) fontSizeLabel.setText(String.valueOf(typographyConfig.getFontSizeSp()));
                    repaginateCurrentChapter();
                }
            });
        }

        if (btnFontDecrease != null) {
            btnFontDecrease.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    typographyConfig.setFontSizeSp(typographyConfig.getFontSizeSp() - 1);
                    if (fontSizeLabel != null) fontSizeLabel.setText(String.valueOf(typographyConfig.getFontSizeSp()));
                    repaginateCurrentChapter();
                }
            });
        }

        if (btnToggleLineSpacing != null) {
            btnToggleLineSpacing.setText(String.format(Locale.US, "Интервал: %.2fx", typographyConfig.getLineSpacingMultiplier()));
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

        if (btnToggleHyphenation != null) {
            btnToggleHyphenation.setText(typographyConfig.isHyphenationEnabled() ? "Переносы: Вкл" : "Переносы: Выкл");
            btnToggleHyphenation.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean enabled = !typographyConfig.isHyphenationEnabled();
                    typographyConfig.setHyphenationEnabled(enabled);
                    btnToggleHyphenation.setText(enabled ? "Переносы: Вкл" : "Переносы: Выкл");
                    repaginateCurrentChapter();
                }
            });
        }

        if (btnToggleContrast != null) {
            btnToggleContrast.setText(typographyConfig.isBoldText() ? "Жирный: Вкл" : "Жирный: Выкл");
            btnToggleContrast.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean bold = !typographyConfig.isBoldText();
                    typographyConfig.setBoldText(bold);
                    btnToggleContrast.setText(bold ? "Жирный: Вкл" : "Жирный: Выкл");
                    repaginateCurrentChapter();
                }
            });
        }
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

        int totalP = (currentPages != null) ? Math.max(1, currentPages.size()) : 1;
        if (readerPageSeekbar != null) {
            readerPageSeekbar.setMax(Math.max(0, totalP - 1));
            readerPageSeekbar.setProgress(Math.max(0, Math.min(currentPageIndex, totalP - 1)));
        }

        updatePageCounterText();
    }

    private void updatePageCounterText() {
        if (readerPageCounter != null) {
            int totalP = (currentPages != null) ? Math.max(1, currentPages.size()) : 1;
            int curP = Math.min(currentPageIndex + 1, totalP);
            double percent = calculateCurrentGlobalPercent();
            readerPageCounter.setText(String.format(Locale.getDefault(), "%d/%d (%.0f%%)", curP, totalP, percent));
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
        if (btnToggleMargins != null) btnToggleMargins.setText(getMarginButtonText());
        readerCanvas.setTypographyConfig(typographyConfig);
        repaginateCurrentChapter();
    }

    private void cycleLineSpacing() {
        float current = typographyConfig.getLineSpacingMultiplier();
        float next;
        if (current <= 1.15f) {
            next = 1.25f;
        } else if (current <= 1.30f) {
            next = 1.40f;
        } else {
            next = 1.10f;
        }
        typographyConfig.setLineSpacingMultiplier(next);
        if (btnToggleLineSpacing != null) {
            btnToggleLineSpacing.setText(String.format(Locale.US, "Интервал: %.2fx", next));
        }
        readerCanvas.setTypographyConfig(typographyConfig);
        repaginateCurrentChapter();
    }

    private void showTableOfContentsDialog() {
        if (chapters == null || chapters.isEmpty()) {
            Toast.makeText(this, "Оглавление недоступно", Toast.LENGTH_SHORT).show();
            return;
        }

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        float density = getResources().getDisplayMetrics().density;
        DisplayMetrics dm = getResources().getDisplayMetrics();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int pad = (int) (14 * density);
        root.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(this);
        titleView.setText("Содержание (" + chapters.size() + " глав)");
        titleView.setTextSize(14);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, (int) (8 * density));
        root.addView(titleView);

        View divider = new View(this);
        divider.setBackgroundColor(Color.BLACK);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density)));

        ListView listView = new ListView(this);
        listView.setDivider(new ColorDrawable(Color.LTGRAY));
        listView.setDividerHeight((int) Math.max(1, density));
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        listLp.setMargins(0, (int) (6 * density), 0, (int) (6 * density));
        listView.setLayoutParams(listLp);

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
        listView.setAdapter(tocAdapter);
        listView.setSelection(Math.max(0, currentChapterIndex - 2));

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                dialog.dismiss();
                hideMenuOverlay();
                loadChapter(position);
            }
        });
        root.addView(listView);

        Button btnClose = new Button(this);
        btnClose.setText("Закрыть");
        btnClose.setTextSize(12);
        btnClose.setTypeface(null, Typeface.BOLD);
        btnClose.setTextColor(Color.BLACK);
        btnClose.setBackgroundResource(R.drawable.btn_eink);
        btnClose.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (36 * density)));
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
            int w = (int) (dm.widthPixels * 0.90);
            int h = (int) (dm.heightPixels * 0.85);
            dialog.getWindow().setLayout(w, h);
        }
        dialog.show();
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
        dialog.show();
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

        // Проверяем, есть ли уже главы в локальной БД
        List<Chapter> localChapters = dbHelper.getChapters(bookUuid);
        if (localChapters != null && !localChapters.isEmpty()) {
            chapters = localChapters;
            resolveInitialPositionAndOpen();
        } else {
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
                (cloudProgress.getPercent() > local.getPercent() + 0.5);

        if (isNewer) {
            int targetCh = cloudProgress.getChapterIndex();
            if (targetCh <= 0 && cloudProgress.getPercent() > 0) {
                targetCh = (int) Math.floor((cloudProgress.getPercent() / 100.0) * chapters.size());
            }
            if (targetCh >= chapters.size()) targetCh = chapters.size() - 1;
            targetCh = Math.max(0, targetCh);

            if (targetCh != currentChapterIndex || cloudProgress.getPageIndex() != currentPageIndex) {
                currentChapterIndex = targetCh;
                currentPageIndex = Math.max(0, cloudProgress.getPageIndex());
                loadChapter(currentChapterIndex);
                Toast.makeText(ReaderActivity.this, String.format(Locale.getDefault(), "Синхронизировано: %.0f%% (Гл. %d)", cloudProgress.getPercent(), currentChapterIndex + 1), Toast.LENGTH_SHORT).show();
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
        displayChapterText(rawText, title, -1);
    }

    private void displayChapterText(final String rawText, final String title, final int anchorCharOffset) {
        // Синхронизируем типографику с View
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

                        if (anchorCharOffset >= 0 && !currentPages.isEmpty()) {
                            int targetPage = 0;
                            for (int i = 0; i < currentPages.size(); i++) {
                                TextPaginator.Page p = currentPages.get(i);
                                if (anchorCharOffset >= p.startCharOffset && anchorCharOffset <= p.endCharOffset) {
                                    targetPage = i;
                                    break;
                                }
                            }
                            currentPageIndex = targetPage;
                        } else if (currentPageIndex >= currentPages.size()) {
                            currentPageIndex = Math.max(0, currentPages.size() - 1);
                        }

                        renderCurrentPage();
                        updateMenuControls();
                    }
                });
            }
        });
    }

    private void repaginateCurrentChapter() {
        if (chapters != null && currentChapterIndex < chapters.size()) {
            int anchorOffset = -1;
            if (currentPages != null && currentPageIndex < currentPages.size()) {
                anchorOffset = currentPages.get(currentPageIndex).startCharOffset;
            }
            String id = chapters.get(currentChapterIndex).getId();
            String text = cacheManager.loadChapter(bookUuid, id);
            if (text != null) {
                displayChapterText(text, chapters.get(currentChapterIndex).getTitle(), anchorOffset);
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

        if (!hasPerformedInitialRefresh) {
            hasPerformedInitialRefresh = true;
            forceEpdRefresh();
        }

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
            updateMenuControls();
        } else if (currentChapterIndex + 1 < chapters.size()) {
            currentChapterIndex++;
            currentPageIndex = 0;
            loadChapter(currentChapterIndex);
        }
    }

    private void flipPageBackward() {
        if (currentPageIndex > 0) {
            currentPageIndex--;
            renderCurrentPage();
            updateMenuControls();
        } else if (currentChapterIndex > 0) {
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
