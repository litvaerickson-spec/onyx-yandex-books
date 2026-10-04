package com.onyx.yandexbooks.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.onyx.yandexbooks.core.storage.CacheManager;
import com.onyx.yandexbooks.core.storage.DatabaseHelper;
import com.onyx.yandexbooks.core.sync.SyncManager;
import com.onyx.yandexbooks.core.ui.CoverLoader;

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
    private Button btnRefreshTop, btnLogout, btnToggleReaderMode;
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
        btnRefreshTop = (Button) findViewById(R.id.btn_refresh_top);
        btnLogout = (Button) findViewById(R.id.btn_logout);
        loadingTextView = (TextView) findViewById(R.id.loading_text);
        emptyStateContainer = findViewById(R.id.empty_state_container);
        btnRefreshShelf = (Button) findViewById(R.id.btn_refresh_shelf);

        adapter = new BooksAdapter();
        booksListView.setAdapter(adapter);

        setupTabs();
        setupHeaderActions();

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

    private void updateEmptyState() {
        if (currentBooks.isEmpty()) {
            emptyStateContainer.setVisibility(View.VISIBLE);
            booksListView.setVisibility(View.GONE);
        } else {
            emptyStateContainer.setVisibility(View.GONE);
            booksListView.setVisibility(View.VISIBLE);
        }
    }

    private void loadBooks(final String shelf) {
        // 1. Мгновенно отображаем книги из локальной БД SQLite
        currentBooks = dbHelper.getBooksByShelf(shelf);
        adapter.notifyDataSetChanged();
        updateTabBadges();
        updateTabStyles();
        updateEmptyState();

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
            }

            @Override
            public void onError(String errorMessage) {
                loadingTextView.setVisibility(View.GONE);
                updateEmptyState();
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
        }
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
                statusTag.setText(downloaded ? " • ✔ В памяти" : " • ☁ В сети");
            }

            double pct = book.getPercent();
            progressBar.setProgress((int) Math.round(pct));
            if (pct > 0.0) {
                progress.setText(String.format("%.0f%%", pct));
            } else {
                progress.setText("Не начата");
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

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(book.getTitle());

        ScrollView scrollView = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 16, 24, 16);

        TextView authorView = new TextView(this);
        authorView.setText("Автор: " + book.getAuthor());
        authorView.setTextSize(14);
        authorView.setTypeface(null, android.graphics.Typeface.BOLD);
        authorView.setTextColor(Color.BLACK);
        layout.addView(authorView);

        TextView progressView = new TextView(this);
        progressView.setText(String.format("Прогресс: %.0f%% • %s", book.getPercent(), (isDownloaded ? "✔ Сохранено в памяти" : "☁ В сети")));
        progressView.setTextSize(12);
        progressView.setTextColor(Color.BLACK);
        progressView.setPadding(0, 4, 0, 10);
        layout.addView(progressView);

        View divider = new View(this);
        divider.setBackgroundColor(Color.BLACK);
        layout.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 2));

        TextView annHeader = new TextView(this);
        annHeader.setText("Описание книги:");
        annHeader.setTextSize(13);
        annHeader.setTypeface(null, android.graphics.Typeface.BOLD);
        annHeader.setTextColor(Color.BLACK);
        annHeader.setPadding(0, 10, 0, 4);
        layout.addView(annHeader);

        TextView annView = new TextView(this);
        String ann = book.getAnnotation();
        if (ann == null || ann.trim().isEmpty()) {
            ann = "Описание книги не предоставлено сервисом.";
        }
        annView.setText(ann);
        annView.setTextSize(13);
        annView.setTextColor(Color.BLACK);
        annView.setLineSpacing(4f, 1.15f);
        layout.addView(annView);

        scrollView.addView(layout);
        builder.setView(scrollView);

        builder.setPositiveButton("📖 В Onyx (NeoReader)", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                openInOnyxReader(book);
            }
        });

        builder.setNeutralButton("⚡ В читалке Lite", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                openInLiteReader(book);
            }
        });

        if (!isDownloaded) {
            Button downloadBtnInDialog = new Button(this);
            downloadBtnInDialog.setText("📥 Скачать EPUB в память устройства");
            downloadBtnInDialog.setTextSize(12);
            downloadBtnInDialog.setTextColor(Color.BLACK);
            downloadBtnInDialog.setBackgroundResource(R.drawable.btn_eink);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44 * (int) getResources().getDisplayMetrics().density);
            lp.setMargins(0, 16, 0, 8);
            downloadBtnInDialog.setLayoutParams(lp);
            downloadBtnInDialog.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Toast.makeText(MainActivity.this, "Загрузка книги «" + book.getTitle() + "»...", Toast.LENGTH_SHORT).show();
                    cacheManager.downloadBookAsync(book.getUuid(), book.getTitle(), new CacheManager.DownloadProgressCallback() {
                        @Override
                        public void onProgress(int downloadedCount, int totalCount) {}

                        @Override
                        public void onComplete() {
                            Toast.makeText(MainActivity.this, "«" + book.getTitle() + "» сохранена в /sdcard/Books/!", Toast.LENGTH_SHORT).show();
                            adapter.notifyDataSetChanged();
                        }

                        @Override
                        public void onError(String message) {
                            Toast.makeText(MainActivity.this, "Ошибка скачивания: " + message, Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
            layout.addView(downloadBtnInDialog);
        } else {
            TextView pathView = new TextView(this);
            pathView.setText("✔ Книга сохранена в памяти (/sdcard/Books/YandexBooks/) и доступна для любой системной читалки.");
            pathView.setTextSize(11);
            pathView.setTextColor(Color.BLACK);
            pathView.setPadding(0, 12, 0, 4);
            layout.addView(pathView);
        }

        builder.setNegativeButton("Закрыть", null);
        AlertDialog dialog = builder.create();
        dialog.show();
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
