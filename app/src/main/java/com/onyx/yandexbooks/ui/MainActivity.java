package com.onyx.yandexbooks.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.onyx.yandexbooks.R;
import com.onyx.yandexbooks.core.api.YandexBooksApiClient;
import com.onyx.yandexbooks.core.api.models.Book;
import com.onyx.yandexbooks.core.api.models.Chapter;
import com.onyx.yandexbooks.core.auth.TokenStorage;
import com.onyx.yandexbooks.core.eink.EpdController;
import com.onyx.yandexbooks.core.storage.CacheManager;
import com.onyx.yandexbooks.core.storage.DatabaseHelper;
import com.onyx.yandexbooks.core.sync.SyncManager;
import com.onyx.yandexbooks.core.ui.CoverLoader;
import com.onyx.yandexbooks.core.update.AppUpdateManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Главный экран полок и библиотеки с высокой контрастностью для E-Ink Carta.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_AUTH = 1001;

    private ListView booksListView;
    private Button tabReadingBtn, tabToReadBtn, tabDoneBtn;
    private Button btnRefreshTop, btnLogout, btnToggleReaderMode, btnCheckUpdate;
    private TextView loadingTextView;
    private View emptyStateContainer;
    private Button btnRefreshShelf;

    private TokenStorage tokenStorage;
    private com.onyx.yandexbooks.core.storage.AppSettings appSettings;
    private YandexBooksApiClient apiClient;
    private DatabaseHelper dbHelper;
    private CacheManager cacheManager;
    private SyncManager syncManager;
    private CoverLoader coverLoader;

    private String currentShelf = "reading";
    private List<Book> currentBooks = new ArrayList<>();
    private BooksAdapter adapter;
    private boolean isAuthLaunching = false;

    private TextView shelfFooterStatus;
    private Button btnShelfPrevPage;
    private Button btnShelfNextPage;
    private TextView shelfPageIndicator;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tokenStorage = new TokenStorage(this);
        appSettings = new com.onyx.yandexbooks.core.storage.AppSettings(this);
        apiClient = new YandexBooksApiClient(tokenStorage);
        dbHelper = DatabaseHelper.getInstance(this);
        cacheManager = new CacheManager(this, apiClient);
        syncManager = new SyncManager(this, apiClient);
        coverLoader = CoverLoader.getInstance(this);

        booksListView = (ListView) findViewById(R.id.books_list_view);
        tabReadingBtn = (Button) findViewById(R.id.tab_reading_btn);
        tabToReadBtn = (Button) findViewById(R.id.tab_to_read_btn);
        tabDoneBtn = (Button) findViewById(R.id.tab_done_btn);
        btnToggleReaderMode = (Button) findViewById(R.id.btn_toggle_reader_mode);
        btnCheckUpdate = (Button) findViewById(R.id.btn_check_update);
        btnRefreshTop = (Button) findViewById(R.id.btn_refresh_top);
        btnLogout = (Button) findViewById(R.id.btn_logout);
        loadingTextView = (TextView) findViewById(R.id.loading_text);
        emptyStateContainer = findViewById(R.id.empty_state_container);
        btnRefreshShelf = (Button) findViewById(R.id.btn_refresh_shelf);

        shelfFooterStatus = (TextView) findViewById(R.id.shelf_footer_status);
        btnShelfPrevPage = (Button) findViewById(R.id.btn_shelf_prev_page);
        btnShelfNextPage = (Button) findViewById(R.id.btn_shelf_next_page);
        shelfPageIndicator = (TextView) findViewById(R.id.shelf_page_indicator);

        adapter = new BooksAdapter();
        booksListView.setAdapter(adapter);

        setupTabs();
        setupHeaderActions();
        setupFooterActions();

        checkAuthAndLoad();
    }

    private void checkAuthAndLoad() {
        if (!tokenStorage.isAuthorized()) {
            if (!isAuthLaunching) {
                isAuthLaunching = true;
                startActivityForResult(new Intent(this, AuthActivity.class), REQUEST_AUTH);
            }
        } else {
            isAuthLaunching = false;
            loadBooks(currentShelf);
            syncManager.flushOfflineQueue();
            checkAppUpdate(false);
        }
    }

    private void setupHeaderActions() {
        updateReaderModeButton();

        btnToggleReaderMode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean wasOnyx = appSettings.isOnyxReaderPreferred();
                String newMode = wasOnyx ? com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_LITE : com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_ONYX;
                appSettings.setReaderMode(newMode);
                updateReaderModeButton();
                adapter.notifyDataSetChanged();
                String msg = appSettings.isOnyxReaderPreferred() 
                        ? "Читалка по умолчанию: Системная Onyx (NeoReader/AlReader)" 
                        : "Читалка по умолчанию: Встроенная Lite (с синхронизацией)";
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show();
            }
        });

        if (btnCheckUpdate != null) {
            btnCheckUpdate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    checkAppUpdate(true);
                }
            });
        }

        btnRefreshTop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadBooks(currentShelf);
            }
        });

        btnRefreshShelf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadBooks(currentShelf);
            }
        });

        btnLogout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(R.string.logout_confirm_title)
                        .setMessage(R.string.logout_confirm_message)
                        .setPositiveButton(R.string.btn_logout, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                tokenStorage.clear();
                                currentBooks.clear();
                                adapter.notifyDataSetChanged();
                                updateTabBadges();
                                updateEmptyState();
                                startActivityForResult(new Intent(MainActivity.this, AuthActivity.class), REQUEST_AUTH);
                            }
                        })
                        .setNegativeButton(R.string.btn_cancel, null)
                        .show();
            }
        });
    }

    private void checkAppUpdate(final boolean userTriggered) {
        final ProgressDialog pd;
        if (userTriggered) {
            pd = new ProgressDialog(this);
            pd.setTitle("Обновление ПО");
            pd.setMessage("Проверка обновлений на GitHub...");
            pd.setCancelable(false);
            pd.show();
        } else {
            pd = null;
        }

        AppUpdateManager.getInstance().fetchLatestRelease(this, new AppUpdateManager.UpdateCheckCallback() {
            @Override
            public void onResult(boolean updateAvailable, final AppUpdateManager.ReleaseInfo release, String message) {
                if (pd != null && pd.isShowing()) {
                    pd.dismiss();
                }

                if (updateAvailable && release != null) {
                    if (btnCheckUpdate != null) {
                        btnCheckUpdate.setText("⬆ " + release.tagName);
                        btnCheckUpdate.setBackgroundResource(R.drawable.btn_eink_primary);
                        btnCheckUpdate.setTextColor(Color.WHITE);
                    }
                    if (userTriggered) {
                        AppUpdateManager.getInstance().showUpdateDialog(MainActivity.this, release);
                    } else {
                        Toast.makeText(MainActivity.this, "Доступно обновление " + release.tagName + " (нажмите ⬆)", Toast.LENGTH_LONG).show();
                    }
                } else {
                    if (userTriggered) {
                        String cur = AppUpdateManager.getInstance().getCurrentVersionName(MainActivity.this);
                        Toast.makeText(MainActivity.this, "У вас последняя версия (v" + cur + ")", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        });
    }

    private void setupTabs() {
        tabReadingBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab("reading");
            }
        });

        tabToReadBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab("to_read");
            }
        });

        tabDoneBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab("done");
            }
        });
    }

    private void switchTab(String shelf) {
        currentShelf = shelf;
        updateTabStyles();
        loadBooks(shelf);
    }

    private void updateTabStyles() {
        boolean isReading = "reading".equals(currentShelf);
        boolean isToRead = "to_read".equals(currentShelf);
        boolean isDone = "done".equals(currentShelf);

        tabReadingBtn.setBackgroundResource(isReading ? R.drawable.tab_eink_active : R.drawable.tab_eink_inactive);
        tabReadingBtn.setTextColor(getResources().getColor(isReading ? R.color.eink_white : R.color.eink_black));

        tabToReadBtn.setBackgroundResource(isToRead ? R.drawable.tab_eink_active : R.drawable.tab_eink_inactive);
        tabToReadBtn.setTextColor(getResources().getColor(isToRead ? R.color.eink_white : R.color.eink_black));

        tabDoneBtn.setBackgroundResource(isDone ? R.drawable.tab_eink_active : R.drawable.tab_eink_inactive);
        tabDoneBtn.setTextColor(getResources().getColor(isDone ? R.color.eink_white : R.color.eink_black));
    }

    private void updateTabBadges() {
        int readingCount = dbHelper.getBooksCountByShelf("reading");
        int toReadCount = dbHelper.getBooksCountByShelf("to_read");
        int doneCount = dbHelper.getBooksCountByShelf("done");

        tabReadingBtn.setText("Читаю (" + readingCount + ")");
        tabToReadBtn.setText("В планах (" + toReadCount + ")");
        tabDoneBtn.setText("Прочитано (" + doneCount + ")");
    }

    private void setupFooterActions() {
        if (btnShelfPrevPage != null) {
            btnShelfPrevPage.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (booksListView == null || currentBooks.isEmpty()) return;
                    int current = booksListView.getFirstVisiblePosition();
                    int target = Math.max(0, current - 3);
                    booksListView.setSelection(target);
                    updateShelfFooter();
                    EpdController.requestFullRefresh(MainActivity.this, booksListView);
                }
            });
        }

        if (btnShelfNextPage != null) {
            btnShelfNextPage.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (booksListView == null || currentBooks.isEmpty()) return;
                    int current = booksListView.getFirstVisiblePosition();
                    int target = Math.min(currentBooks.size() - 1, current + 3);
                    booksListView.setSelection(target);
                    updateShelfFooter();
                    EpdController.requestFullRefresh(MainActivity.this, booksListView);
                }
            });
        }

        if (booksListView != null) {
            booksListView.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {
                    if (scrollState == SCROLL_STATE_IDLE) {
                        updateShelfFooter();
                        EpdController.requestFullRefresh(MainActivity.this, booksListView);
                    }
                }
                @Override
                public void onScroll(android.widget.AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                }
            });
        }
    }

    private void updateShelfFooter() {
        if (shelfFooterStatus == null || shelfPageIndicator == null) return;
        String title = "Читаю";
        if ("to_read".equals(currentShelf)) title = "В планах";
        else if ("done".equals(currentShelf)) title = "Прочитано";

        String curVer = AppUpdateManager.getInstance().getCurrentVersionName(this);
        shelfFooterStatus.setText("📚 " + title + ": " + currentBooks.size() + " • v" + curVer);
        shelfFooterStatus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkAppUpdate(true);
            }
        });

        if (currentBooks.isEmpty()) {
            shelfPageIndicator.setText(" 0/0 ");
        } else {
            int totalPages = Math.max(1, (int) Math.ceil((double) currentBooks.size() / 3.0));
            int firstPos = booksListView.getFirstVisiblePosition();
            int currentPage = Math.min(totalPages, (firstPos / 3) + 1);
            shelfPageIndicator.setText(" " + currentPage + "/" + totalPages + " ");
        }
    }

    private void updateEmptyState() {
        if (currentBooks.isEmpty()) {
            emptyStateContainer.setVisibility(View.VISIBLE);
            booksListView.setVisibility(View.GONE);
        } else {
            emptyStateContainer.setVisibility(View.GONE);
            booksListView.setVisibility(View.VISIBLE);
        }
        updateShelfFooter();
    }

    private void loadBooks(final String shelf) {
        // 1. Мгновенно отображаем книги из локальной БД SQLite
        currentBooks = dbHelper.getBooksByShelf(shelf);
        adapter.notifyDataSetChanged();
        updateTabBadges();
        updateTabStyles();
        updateEmptyState();
        updateShelfFooter();

        loadingTextView.setVisibility(View.VISIBLE);

        // 2. Фоновая загрузка актуальной библиотеки из облака Яндекса
        apiClient.getAllUserBooks(new YandexBooksApiClient.ApiCallback<List<Book>>() {
            @Override
            public void onSuccess(List<Book> allBooks) {
                loadingTextView.setVisibility(View.GONE);
                if (allBooks != null && !allBooks.isEmpty()) {
                    dbHelper.saveAllBooks(allBooks);
                }
                currentBooks = dbHelper.getBooksByShelf(currentShelf);
                adapter.notifyDataSetChanged();
                updateTabBadges();
                updateEmptyState();
                updateShelfFooter();
            }

            @Override
            public void onError(String errorMessage) {
                loadingTextView.setVisibility(View.GONE);
                updateEmptyState();
                updateShelfFooter();
                if (errorMessage != null && (errorMessage.contains("401") || errorMessage.contains("not_authenticated"))) {
                    Toast.makeText(MainActivity.this, "Сессия истекла (401). Пожалуйста, войдите снова.", Toast.LENGTH_SHORT).show();
                    tokenStorage.clear();
                    startActivityForResult(new Intent(MainActivity.this, AuthActivity.class), REQUEST_AUTH);
                } else if (currentBooks.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Ошибка сети: " + errorMessage, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tokenStorage.isAuthorized()) {
            // Перезагружаем прогресс после возврата из читалки
            currentBooks = dbHelper.getBooksByShelf(currentShelf);
            adapter.notifyDataSetChanged();
            updateTabBadges();
            updateShelfFooter();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (booksListView != null && !currentBooks.isEmpty()) {
                int current = booksListView.getFirstVisiblePosition();
                int target = Math.min(currentBooks.size() - 1, current + 3);
                booksListView.setSelection(target);
                updateShelfFooter();
                EpdController.requestFullRefresh(MainActivity.this, booksListView);
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (booksListView != null && !currentBooks.isEmpty()) {
                int current = booksListView.getFirstVisiblePosition();
                int target = Math.max(0, current - 3);
                booksListView.setSelection(target);
                updateShelfFooter();
                EpdController.requestFullRefresh(MainActivity.this, booksListView);
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    private void updateReaderModeButton() {
        if (btnToggleReaderMode != null) {
            boolean isOnyx = appSettings.isOnyxReaderPreferred();
            btnToggleReaderMode.setText(isOnyx ? "📖 Onyx" : "⚡ Lite");
        }
    }

    private void openBook(final Book book) {
        if (appSettings.isOnyxReaderPreferred()) {
            openInOnyxReader(book);
        } else {
            openInLiteReader(book);
        }
    }

    private void openInOnyxReader(final Book book) {
        File epub = cacheManager.ensurePublicEpubFile(book.getUuid(), book.getTitle());
        if (epub != null && epub.exists() && epub.length() > 0) {
            boolean ok = CacheManager.openInSystemReader(this, epub);
            if (!ok) {
                Toast.makeText(this, "Читалка Onyx не найдена. Открываем в читалке Lite...", Toast.LENGTH_SHORT).show();
                openInLiteReader(book);
            }
            return;
        }

        final ProgressDialog dialog = new ProgressDialog(this);
        dialog.setTitle("Яндекс Книги");
        dialog.setMessage("Загрузка EPUB в /sdcard/Books/ для Onyx...");
        dialog.setIndeterminate(true);
        dialog.setCancelable(false);
        dialog.show();

        cacheManager.downloadBookAsync(book.getUuid(), book.getTitle(), new CacheManager.DownloadProgressCallback() {
            @Override
            public void onProgress(int downloadedCount, int totalCount) {}

            @Override
            public void onComplete() {
                if (dialog.isShowing()) dialog.dismiss();
                adapter.notifyDataSetChanged();
                File readyEpub = cacheManager.ensurePublicEpubFile(book.getUuid(), book.getTitle());
                if (readyEpub != null && readyEpub.exists()) {
                    boolean ok = CacheManager.openInSystemReader(MainActivity.this, readyEpub);
                    if (!ok) {
                        openInLiteReader(book);
                    }
                } else {
                    openInLiteReader(book);
                }
            }

            @Override
            public void onError(String message) {
                if (dialog.isShowing()) dialog.dismiss();
                Toast.makeText(MainActivity.this, "Ошибка скачивания: " + message + ". Открываем в Lite.", Toast.LENGTH_LONG).show();
                openInLiteReader(book);
            }
        });
    }

    private void openInLiteReader(final Book book) {
        if (cacheManager.isBookDownloaded(book.getUuid())) {
            launchReader(book);
            return;
        }

        final ProgressDialog dialog = new ProgressDialog(this);
        dialog.setTitle("Яндекс Книги");
        dialog.setMessage("Загрузка и подготовка книги к чтению...");
        dialog.setIndeterminate(true);
        dialog.setCancelable(false);
        dialog.show();

        cacheManager.ensureBookReady(book.getUuid(), book.getTitle(), new CacheManager.BookReadyCallback() {
            @Override
            public void onReady(List<Chapter> chapters) {
                if (dialog.isShowing()) dialog.dismiss();
                adapter.notifyDataSetChanged();
                launchReader(book);
            }

            @Override
            public void onError(String message) {
                if (dialog.isShowing()) dialog.dismiss();
                Toast.makeText(MainActivity.this, "Не удалось открыть книгу: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void launchReader(Book book) {
        Intent intent = new Intent(MainActivity.this, ReaderActivity.class);
        intent.putExtra("book_uuid", book.getUuid());
        intent.putExtra("book_title", book.getTitle());
        startActivity(intent);
    }

    private class BooksAdapter extends BaseAdapter {
        @Override
        public int getCount() { return currentBooks.size(); }
        @Override
        public Object getItem(int position) { return currentBooks.get(position); }
        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(MainActivity.this).inflate(R.layout.item_book, parent, false);
            }

            final Book book = currentBooks.get(position);
            ImageView cover = (ImageView) convertView.findViewById(R.id.book_cover);
            TextView title = (TextView) convertView.findViewById(R.id.book_title);
            TextView author = (TextView) convertView.findViewById(R.id.book_author);
            TextView annotation = (TextView) convertView.findViewById(R.id.book_annotation);
            TextView progress = (TextView) convertView.findViewById(R.id.book_progress);
            TextView statusTag = (TextView) convertView.findViewById(R.id.book_status_tag);
            ProgressBar progressBar = (ProgressBar) convertView.findViewById(R.id.book_progress_bar);
            Button readBtn = (Button) convertView.findViewById(R.id.book_read_btn);
            Button downloadBtn = (Button) convertView.findViewById(R.id.book_download_btn);

            title.setText(book.getTitle());
            author.setText(book.getAuthor());

            if (annotation != null) {
                String ann = book.getAnnotation();
                if (ann != null && !ann.trim().isEmpty()) {
                    annotation.setText(ann);
                    annotation.setVisibility(View.VISIBLE);
                } else {
                    annotation.setText("Нажмите «О книге» для описания");
                    annotation.setVisibility(View.VISIBLE);
                }
            }

            final boolean downloaded = cacheManager.isBookDownloaded(book.getUuid());
            if (statusTag != null) {
                statusTag.setText(downloaded ? " • ✔ Память" : " • ☁ Сеть");
            }

            double pct = book.getPercent();
            progressBar.setProgress((int) Math.round(pct));
            if (pct > 0.0) {
                progress.setText(String.format("%.0f%%", pct));
            } else {
                progress.setText("0%");
            }

            coverLoader.loadCover(cover, book.getUuid(), book.getCoverUrl(), book.getTitle());

            downloadBtn.setText("О книге");
            downloadBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showBookDetailsDialog(book);
                }
            });

            readBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openBook(book);
                }
            });

            convertView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openBook(book);
                }
            });

            convertView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    showBookDetailsDialog(book);
                    return true;
                }
            });

            return convertView;
        }
    }

    private void showBookDetailsDialog(final Book book) {
        final boolean isDownloaded = cacheManager.isBookDownloaded(book.getUuid());

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        float density = getResources().getDisplayMetrics().density;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int padH = (int) (14 * density);
        int padV = (int) (12 * density);
        root.setPadding(padH, padV, padH, padV);

        // 1. Компактный заголовок книги (14sp bold, max 2 lines)
        TextView titleView = new TextView(this);
        titleView.setText(book.getTitle());
        titleView.setTextSize(14);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(titleView);

        // 2. Метаданные (Автор и Прогресс/Статус) - две ультракомпактные строки
        LinearLayout metaLayout = new LinearLayout(this);
        metaLayout.setOrientation(LinearLayout.VERTICAL);
        metaLayout.setPadding(0, (int) (2 * density), 0, (int) (4 * density));

        TextView authorView = new TextView(this);
        authorView.setText("Автор: " + (book.getAuthor() != null ? book.getAuthor() : "Не указан"));
        authorView.setTextSize(12);
        authorView.setTypeface(null, Typeface.BOLD);
        authorView.setTextColor(Color.BLACK);
        metaLayout.addView(authorView);

        double pct = book.getPercent();
        TextView progressView = new TextView(this);
        String progStr = pct > 0 ? String.format("%.0f%%", pct) : "0%";
        progressView.setText("Прогресс: " + progStr + "  •  Статус: " + (isDownloaded ? "✔ В памяти" : "☁ В сети"));
        progressView.setTextSize(11);
        progressView.setTextColor(Color.BLACK);
        metaLayout.addView(progressView);

        root.addView(metaLayout);

        // 3. Тонкий 1px разделитель
        View divider = new View(this);
        divider.setBackgroundColor(Color.BLACK);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density)));

        // 4. Аннотация книги (ГЛАВНАЯ ЧАСТЬ - занимает более 60% высоты окна!)
        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollLp.setMargins(0, (int) (6 * density), 0, (int) (6 * density));
        scrollView.setLayoutParams(scrollLp);

        TextView annView = new TextView(this);
        String ann = book.getAnnotation();
        if (ann == null || ann.trim().isEmpty()) {
            ann = "Описание книги не предоставлено сервисом.";
        }
        annView.setText(ann);
        annView.setTextSize(13);
        annView.setTextColor(Color.BLACK);
        annView.setLineSpacing(3f, 1.2f);
        scrollView.addView(annView);
        root.addView(scrollView);

        // 5. Тонкий разделитель над кнопками
        View divider2 = new View(this);
        divider2.setBackgroundColor(Color.BLACK);
        root.addView(divider2, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density)));

        // 6. Компактный блок кнопок действий (2 компактные горизонтальные строки вместо 4 вертикальных)
        LinearLayout buttonContainer = new LinearLayout(this);
        buttonContainer.setOrientation(LinearLayout.VERTICAL);
        buttonContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buttonContainer.setPadding(0, (int) (6 * density), 0, 0);

        int row1Height = (int) (36 * density);
        int row2Height = (int) (32 * density);

        // Строка 1: Кнопки чтения [ 📖 Onyx Reader ] и [ ⚡ Читалка Lite ] бок о бок (50% / 50%)
        LinearLayout readRow = new LinearLayout(this);
        readRow.setOrientation(LinearLayout.HORIZONTAL);
        readRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row1Height));

        Button btnOnyx = new Button(this);
        btnOnyx.setText("📖 Onyx Reader");
        btnOnyx.setTextSize(11);
        btnOnyx.setTypeface(null, Typeface.BOLD);
        btnOnyx.setTextColor(Color.WHITE);
        btnOnyx.setBackgroundResource(R.drawable.btn_eink_primary);
        LinearLayout.LayoutParams lpOnyx = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpOnyx.setMargins(0, 0, (int) (4 * density), 0);
        btnOnyx.setLayoutParams(lpOnyx);
        btnOnyx.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                openInOnyxReader(book);
            }
        });
        readRow.addView(btnOnyx);

        Button btnLite = new Button(this);
        btnLite.setText("⚡ Читалка Lite");
        btnLite.setTextSize(11);
        btnLite.setTypeface(null, Typeface.BOLD);
        btnLite.setTextColor(Color.BLACK);
        btnLite.setBackgroundResource(R.drawable.btn_eink);
        LinearLayout.LayoutParams lpLite = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpLite.setMargins((int) (4 * density), 0, 0, 0);
        btnLite.setLayoutParams(lpLite);
        btnLite.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                openInLiteReader(book);
            }
        });
        readRow.addView(btnLite);
        buttonContainer.addView(readRow);

        // Строка 2: Скачивание / Статус и кнопка Закрыть
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lpActionRow = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row2Height);
        lpActionRow.setMargins(0, (int) (5 * density), 0, 0);
        actionRow.setLayoutParams(lpActionRow);

        if (!isDownloaded) {
            final Button btnDownload = new Button(this);
            btnDownload.setText("📥 Скачать EPUB");
            btnDownload.setTextSize(11);
            btnDownload.setTypeface(null, Typeface.BOLD);
            btnDownload.setTextColor(Color.BLACK);
            btnDownload.setBackgroundResource(R.drawable.btn_eink);
            LinearLayout.LayoutParams lpDown = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
            lpDown.setMargins(0, 0, (int) (4 * density), 0);
            btnDownload.setLayoutParams(lpDown);
            btnDownload.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    btnDownload.setEnabled(false);
                    btnDownload.setText("⏳ Загрузка...");
                    Toast.makeText(MainActivity.this, "Загрузка книги «" + book.getTitle() + "»...", Toast.LENGTH_SHORT).show();
                    cacheManager.downloadBookAsync(book.getUuid(), book.getTitle(), new CacheManager.DownloadProgressCallback() {
                        @Override
                        public void onProgress(int downloadedCount, int totalCount) {}

                        @Override
                        public void onComplete() {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "«" + book.getTitle() + "» сохранена в памяти!", Toast.LENGTH_SHORT).show();
                                    adapter.notifyDataSetChanged();
                                    dialog.dismiss();
                                }
                            });
                        }

                        @Override
                        public void onError(final String message) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    Toast.makeText(MainActivity.this, "Ошибка скачивания: " + message, Toast.LENGTH_LONG).show();
                                    btnDownload.setEnabled(true);
                                    btnDownload.setText("📥 Скачать EPUB");
                                }
                            });
                        }
                    });
                }
            });
            actionRow.addView(btnDownload);

            Button btnClose = new Button(this);
            btnClose.setText("✕ Закрыть");
            btnClose.setTextSize(11);
            btnClose.setTypeface(null, Typeface.BOLD);
            btnClose.setTextColor(Color.BLACK);
            btnClose.setBackgroundResource(R.drawable.btn_eink);
            LinearLayout.LayoutParams lpClose = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
            lpClose.setMargins((int) (4 * density), 0, 0, 0);
            btnClose.setLayoutParams(lpClose);
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
            actionRow.addView(btnClose);
        } else {
            TextView pathView = new TextView(this);
            pathView.setText("✔ В памяти (/sdcard/Books/...)");
            pathView.setTextSize(10);
            pathView.setTextColor(Color.BLACK);
            pathView.setSingleLine(true);
            pathView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lpPath = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
            pathView.setLayoutParams(lpPath);
            actionRow.addView(pathView);

            Button btnClose = new Button(this);
            btnClose.setText("✕ Закрыть");
            btnClose.setTextSize(11);
            btnClose.setTypeface(null, Typeface.BOLD);
            btnClose.setTextColor(Color.BLACK);
            btnClose.setBackgroundResource(R.drawable.btn_eink);
            LinearLayout.LayoutParams lpClose = new LinearLayout.LayoutParams((int) (110 * density), ViewGroup.LayoutParams.MATCH_PARENT);
            btnClose.setLayoutParams(lpClose);
            btnClose.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
            actionRow.addView(btnClose);
        }
        buttonContainer.addView(actionRow);

        root.addView(buttonContainer);

        dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dialog.show();

        // Гарантируем размер окна диалога 92% ширины и 85% высоты дисплея
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
            int targetW = (int) (screenWidth * 0.92);
            int targetH = (int) (screenHeight * 0.85);
            dialog.getWindow().setLayout(targetW, targetH);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_AUTH) {
            isAuthLaunching = false;
            if (resultCode == RESULT_OK) {
                loadBooks(currentShelf);
            } else if (!tokenStorage.isAuthorized()) {
                finish();
            }
        }
    }
}
