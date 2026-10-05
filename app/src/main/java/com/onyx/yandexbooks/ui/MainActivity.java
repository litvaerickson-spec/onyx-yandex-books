package com.onyx.yandexbooks.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
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
    private Button tabReadingBtn, tabToReadBtn, tabCatalogBtn, tabSearchBtn;
    private Button btnToggleReaderMode, btnCheckUpdate, btnMainMenu;
    private LinearLayout searchBarContainer;
    private View searchBarDivider;
    private EditText searchQueryInput;
    private Button btnSearchSubmit;
    private TextView loadingTextView;
    private View emptyStateContainer;
    private TextView emptyShelfTitleView;
    private TextView emptyShelfHintView;
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
        tabCatalogBtn = (Button) findViewById(R.id.tab_catalog_btn);
        tabSearchBtn = (Button) findViewById(R.id.tab_search_btn);

        btnToggleReaderMode = (Button) findViewById(R.id.btn_toggle_reader_mode);
        btnCheckUpdate = (Button) findViewById(R.id.btn_check_update);
        btnMainMenu = (Button) findViewById(R.id.btn_main_menu);

        searchBarContainer = (LinearLayout) findViewById(R.id.search_bar_container);
        searchBarDivider = findViewById(R.id.search_bar_divider);
        searchQueryInput = (EditText) findViewById(R.id.search_query_input);
        btnSearchSubmit = (Button) findViewById(R.id.btn_search_submit);

        loadingTextView = (TextView) findViewById(R.id.loading_text);
        emptyStateContainer = findViewById(R.id.empty_state_container);
        emptyShelfTitleView = (TextView) findViewById(R.id.empty_shelf_title_view);
        emptyShelfHintView = (TextView) findViewById(R.id.empty_shelf_hint_view);
        btnRefreshShelf = (Button) findViewById(R.id.btn_refresh_shelf);

        shelfFooterStatus = (TextView) findViewById(R.id.shelf_footer_status);
        btnShelfPrevPage = (Button) findViewById(R.id.btn_shelf_prev_page);
        btnShelfNextPage = (Button) findViewById(R.id.btn_shelf_next_page);
        shelfPageIndicator = (TextView) findViewById(R.id.shelf_page_indicator);

        adapter = new BooksAdapter();
        booksListView.setAdapter(adapter);

        setupTabs();
        setupHeaderActions();
        setupSearchActions();
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
                        ? "Читалка: Системная Onyx (NeoReader/AlReader)" 
                        : "Читалка: Встроенная Онлайн (с синхронизацией)";
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

        if (btnMainMenu != null) {
            btnMainMenu.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showMainMenuDialog();
                }
            });
        }

        if (btnRefreshShelf != null) {
            btnRefreshShelf.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if ("catalog".equals(currentShelf)) {
                        loadCatalog();
                    } else if ("search".equals(currentShelf)) {
                        String q = searchQueryInput != null ? searchQueryInput.getText().toString().trim() : "";
                        if (!q.isEmpty()) executeSearch(q);
                    } else {
                        loadBooks(currentShelf);
                    }
                }
            });
        }
    }

    private void setupSearchActions() {
        if (btnSearchSubmit != null && searchQueryInput != null) {
            btnSearchSubmit.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    executeSearch(searchQueryInput.getText().toString().trim());
                }
            });

            searchQueryInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
                @Override
                public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                    if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                            || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                        executeSearch(searchQueryInput.getText().toString().trim());
                        return true;
                    }
                    return false;
                }
            });
        }
    }

    private void showMainMenuDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        float density = getResources().getDisplayMetrics().density;
        DisplayMetrics dm = getResources().getDisplayMetrics();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        int padH = (int) (16 * density);
        int padV = (int) (14 * density);
        root.setPadding(padH, padV, padH, padV);

        TextView titleView = new TextView(this);
        titleView.setText("Меню приложения");
        titleView.setTextSize(14);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, (int) (8 * density));
        root.addView(titleView);

        View divider = new View(this);
        divider.setBackgroundColor(Color.BLACK);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density)));

        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.setPadding(0, (int) (8 * density), 0, (int) (8 * density));

        int doneCount = dbHelper.getBooksCountByShelf("done");
        Button btnShelfDone = createMenuButton("Полка «Прочитано» (" + doneCount + ")", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                switchTab("done");
            }
        }, density);
        listLayout.addView(btnShelfDone);

        Button btnSync = createMenuButton("Синхронизировать полки", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                loadBooks(currentShelf);
                syncManager.flushOfflineQueue();
            }
        }, density);
        listLayout.addView(btnSync);

        Button btnUpdate = createMenuButton("Проверить обновление ПО", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                checkAppUpdate(true);
            }
        }, density);
        listLayout.addView(btnUpdate);

        Button btnRefresh = createMenuButton("Очистить экран (E-Ink)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                EpdController.requestFullRefresh(MainActivity.this, null);
            }
        }, density);
        listLayout.addView(btnRefresh);

        Button btnLogoutItem = createMenuButton("Выйти из аккаунта", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showLogoutConfirmDialog();
            }
        }, density);
        listLayout.addView(btnLogoutItem);

        root.addView(listLayout);

        Button btnClose = new Button(this);
        btnClose.setText("Закрыть");
        btnClose.setTextSize(11);
        btnClose.setTypeface(null, Typeface.BOLD);
        btnClose.setTextColor(Color.BLACK);
        btnClose.setBackgroundResource(R.drawable.btn_eink);
        LinearLayout.LayoutParams lpClose = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (34 * density));
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
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
            int w = (int) (dm.widthPixels * 0.85);
            dialog.getWindow().setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private Button createMenuButton(String text, View.OnClickListener listener, float density) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(11);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setTextColor(Color.BLACK);
        btn.setBackgroundResource(R.drawable.btn_eink);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (36 * density));
        lp.setMargins(0, 0, 0, (int) (6 * density));
        btn.setLayoutParams(lp);
        btn.setOnClickListener(listener);
        return btn;
    }

    private void showLogoutConfirmDialog() {
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

    private Dialog showEinkLoadingDialog(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setCancelable(true);
        builder.setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                EpdController.requestFullRefresh(MainActivity.this, null);
            }
        });
        final AlertDialog dialog = builder.create();
        dialog.show();

        // 25-секундный предохранитель для защиты от зависаний
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (dialog != null && dialog.isShowing()) {
                    try {
                        dialog.dismiss();
                    } catch (Exception ignored) {}
                    Toast.makeText(MainActivity.this, "Превышено время ожидания загрузки книги. Попробуйте еще раз или режим Onyx.", Toast.LENGTH_LONG).show();
                    EpdController.requestFullRefresh(MainActivity.this, null);
                }
            }
        }, 25000);

        return dialog;
    }

    private void dismissEinkLoadingDialog(Dialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {}
            EpdController.requestFullRefresh(MainActivity.this, null);
        }
    }

    private void checkAppUpdate(final boolean userTriggered) {
        final Dialog pd;
        if (userTriggered) {
            pd = showEinkLoadingDialog("Обновление ПО", "Проверка обновлений на GitHub...");
        } else {
            pd = null;
        }

        AppUpdateManager.getInstance().fetchLatestRelease(this, new AppUpdateManager.UpdateCheckCallback() {
            @Override
            public void onResult(boolean updateAvailable, final AppUpdateManager.ReleaseInfo release, String message) {
                dismissEinkLoadingDialog(pd);

                if (updateAvailable && release != null) {
                    if (btnCheckUpdate != null) {
                        btnCheckUpdate.setVisibility(View.VISIBLE);
                        btnCheckUpdate.setText(release.tagName);
                        btnCheckUpdate.setBackgroundResource(R.drawable.btn_eink_primary);
                        btnCheckUpdate.setTextColor(Color.WHITE);
                    }
                    if (userTriggered) {
                        AppUpdateManager.getInstance().showUpdateDialog(MainActivity.this, release);
                    } else {
                        Toast.makeText(MainActivity.this, "Доступно обновление " + release.tagName, Toast.LENGTH_LONG).show();
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

        tabCatalogBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab("catalog");
            }
        });

        tabSearchBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchTab("search");
            }
        });
    }

    private void switchTab(String shelf) {
        currentShelf = shelf;
        updateTabStyles();

        if ("search".equals(shelf)) {
            if (searchBarContainer != null) {
                searchBarContainer.setVisibility(View.VISIBLE);
                if (searchBarDivider != null) searchBarDivider.setVisibility(View.VISIBLE);
                if (searchQueryInput != null) searchQueryInput.requestFocus();
            }
            updateEmptyState();
            updateShelfFooter();
        } else {
            if (searchBarContainer != null) {
                searchBarContainer.setVisibility(View.GONE);
                if (searchBarDivider != null) searchBarDivider.setVisibility(View.GONE);
            }
            if ("catalog".equals(shelf)) {
                loadCatalog();
            } else {
                loadBooks(shelf);
            }
        }
    }

    private boolean isNetworkAvailable() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.NetworkInfo netInfo = cm.getActiveNetworkInfo();
                return netInfo != null && netInfo.isConnected();
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void showNoInternetDialog(final Runnable onRetry) {
        if (isFinishing()) return;
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Нет подключения к интернету");
        builder.setMessage("Для работы каталога и поиска книг требуется подключение к сети Wi-Fi.\n\nПожалуйста, включите Wi-Fi на устройстве и попробуйте снова.");
        builder.setPositiveButton("Повторить попытку", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                if (onRetry != null) {
                    onRetry.run();
                }
            }
        });
        builder.setNegativeButton("Закрыть", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        });
        builder.setCancelable(true);
        AlertDialog dialog = builder.create();
        dialog.show();
        EpdController.requestFullRefresh(this, null);
    }

    private void loadCatalog() {
        if (!isNetworkAvailable()) {
            currentBooks.clear();
            adapter.notifyDataSetChanged();
            loadingTextView.setVisibility(View.GONE);
            updateEmptyState();
            updateShelfFooter();
            showNoInternetDialog(new Runnable() {
                @Override
                public void run() {
                    loadCatalog();
                }
            });
            return;
        }

        currentBooks.clear();
        adapter.notifyDataSetChanged();
        updateEmptyState();
        updateShelfFooter();

        loadingTextView.setText("Загрузка каталога Яндекс Книг...");
        loadingTextView.setVisibility(View.VISIBLE);

        apiClient.getRecommendations(new YandexBooksApiClient.ApiCallback<List<Book>>() {
            @Override
            public void onSuccess(List<Book> books) {
                loadingTextView.setVisibility(View.GONE);
                if (books != null) {
                    currentBooks = books;
                } else {
                    currentBooks = new ArrayList<>();
                }
                adapter.notifyDataSetChanged();
                updateEmptyState();
                updateShelfFooter();
                EpdController.requestFullRefresh(MainActivity.this, booksListView);
            }

            @Override
            public void onError(String errorMessage) {
                loadingTextView.setVisibility(View.GONE);
                updateEmptyState();
                updateShelfFooter();
                showNoInternetDialog(new Runnable() {
                    @Override
                    public void run() {
                        loadCatalog();
                    }
                });
            }
        });
    }

    private void executeSearch(final String query) {
        if (query == null || query.trim().isEmpty()) {
            Toast.makeText(this, "Введите поисковый запрос", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && searchQueryInput != null) {
                imm.hideSoftInputFromWindow(searchQueryInput.getWindowToken(), 0);
            }
        } catch (Exception ignored) {}

        if (!isNetworkAvailable()) {
            currentBooks.clear();
            adapter.notifyDataSetChanged();
            loadingTextView.setVisibility(View.GONE);
            updateEmptyState();
            updateShelfFooter();
            showNoInternetDialog(new Runnable() {
                @Override
                public void run() {
                    executeSearch(query);
                }
            });
            return;
        }

        loadingTextView.setText("Поиск «" + query.trim() + "»...");
        loadingTextView.setVisibility(View.VISIBLE);

        apiClient.searchBooks(query.trim(), new YandexBooksApiClient.ApiCallback<List<Book>>() {
            @Override
            public void onSuccess(List<Book> books) {
                loadingTextView.setVisibility(View.GONE);
                if (books != null) {
                    currentBooks = books;
                } else {
                    currentBooks = new ArrayList<>();
                }
                adapter.notifyDataSetChanged();
                updateEmptyState();
                updateShelfFooter();
                if (currentBooks.isEmpty()) {
                    Toast.makeText(MainActivity.this, "По запросу ничего не найдено", Toast.LENGTH_SHORT).show();
                }
                EpdController.requestFullRefresh(MainActivity.this, booksListView);
            }

            @Override
            public void onError(String errorMessage) {
                loadingTextView.setVisibility(View.GONE);
                updateEmptyState();
                updateShelfFooter();
                showNoInternetDialog(new Runnable() {
                    @Override
                    public void run() {
                        executeSearch(query);
                    }
                });
            }
        });
    }

    private void updateTabStyles() {
        boolean isReading = "reading".equals(currentShelf);
        boolean isToRead = "to_read".equals(currentShelf);
        boolean isCatalog = "catalog".equals(currentShelf);
        boolean isSearch = "search".equals(currentShelf);

        tabReadingBtn.setBackgroundResource(isReading ? R.drawable.tab_eink_active : R.drawable.tab_eink_inactive);
        tabReadingBtn.setTextColor(getResources().getColor(isReading ? R.color.eink_white : R.color.eink_black));

        tabToReadBtn.setBackgroundResource(isToRead ? R.drawable.tab_eink_active : R.drawable.tab_eink_inactive);
        tabToReadBtn.setTextColor(getResources().getColor(isToRead ? R.color.eink_white : R.color.eink_black));

        tabCatalogBtn.setBackgroundResource(isCatalog ? R.drawable.tab_eink_active : R.drawable.tab_eink_inactive);
        tabCatalogBtn.setTextColor(getResources().getColor(isCatalog ? R.color.eink_white : R.color.eink_black));

        tabSearchBtn.setBackgroundResource(isSearch ? R.drawable.tab_eink_active : R.drawable.tab_eink_inactive);
        tabSearchBtn.setTextColor(getResources().getColor(isSearch ? R.color.eink_white : R.color.eink_black));
    }

    private void updateTabBadges() {
        int readingCount = dbHelper.getBooksCountByShelf("reading");
        int toReadCount = dbHelper.getBooksCountByShelf("to_read");

        tabReadingBtn.setText("Читаю (" + readingCount + ")");
        tabToReadBtn.setText("В планах (" + toReadCount + ")");
        tabCatalogBtn.setText("Каталог");
        tabSearchBtn.setText("Поиск");
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
        else if ("catalog".equals(currentShelf)) title = "Каталог";
        else if ("search".equals(currentShelf)) title = "Поиск";

        String curVer = AppUpdateManager.getInstance().getCurrentVersionName(this);
        shelfFooterStatus.setText(title + ": " + currentBooks.size() + " • v" + curVer);
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

            if (emptyShelfTitleView != null && emptyShelfHintView != null) {
                if ("catalog".equals(currentShelf)) {
                    if (!isNetworkAvailable()) {
                        emptyShelfTitleView.setText("Нет подключения к интернету");
                        emptyShelfHintView.setText("Каталог и рекомендации доступны онлайн.\nПодключитесь к Wi-Fi и нажмите кнопку ниже.");
                        if (btnRefreshShelf != null) btnRefreshShelf.setText("Повторить попытку");
                    } else {
                        emptyShelfTitleView.setText("Каталог пуст");
                        emptyShelfHintView.setText("Не удалось загрузить рекомендации книг.\nНажмите кнопку ниже для повторной загрузки.");
                        if (btnRefreshShelf != null) btnRefreshShelf.setText("Обновить каталог");
                    }
                } else if ("search".equals(currentShelf)) {
                    if (!isNetworkAvailable()) {
                        emptyShelfTitleView.setText("Нет подключения к интернету");
                        emptyShelfHintView.setText("Для поиска книг требуется подключение к сети Wi-Fi.");
                        if (btnRefreshShelf != null) btnRefreshShelf.setText("Повторить поиск");
                    } else {
                        emptyShelfTitleView.setText("Ничего не найдено");
                        emptyShelfHintView.setText("Попробуйте изменить поисковый запрос или имя автора.");
                        if (btnRefreshShelf != null) btnRefreshShelf.setText("Очистить поиск");
                    }
                } else {
                    emptyShelfTitleView.setText(R.string.empty_shelf_title);
                    emptyShelfHintView.setText(R.string.empty_shelf_hint);
                    if (btnRefreshShelf != null) btnRefreshShelf.setText("Обновить список книг");
                }
            }
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
            // Перезагружаем прогресс после возврата из читалки (не затирая результаты поиска и каталога)
            if (!"catalog".equals(currentShelf) && !"search".equals(currentShelf)) {
                currentBooks = dbHelper.getBooksByShelf(currentShelf);
                adapter.notifyDataSetChanged();
            }
            updateTabBadges();
            updateShelfFooter();
        }
        // Очищаем экран при возвращении в библиотеку от возможных остаточных артефактов
        EpdController.requestFullRefresh(this, booksListView);
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
            btnToggleReaderMode.setText(isOnyx ? "Onyx" : "Онлайн");
        }
    }

    private void openBook(final Book book) {
        ensureBookOnReadingShelf(book, new Runnable() {
            @Override
            public void run() {
                if (appSettings.isOnyxReaderPreferred()) {
                    openInOnyxReader(book);
                } else {
                    openInLiteReader(book);
                }
            }
        });
    }

    private void ensureBookSavedAndLinked(final Book book, final Runnable onReady) {
        ensureBookOnReadingShelf(book, onReady);
    }

    private void ensureBookOnReadingShelf(final Book book, final Runnable onReady) {
        Book existing = dbHelper.getBookByUuid(book.getUuid());
        boolean needsShelfUpdate = (existing == null || !"reading".equals(existing.getShelfType()));

        book.setShelfType("reading");
        if (existing == null) {
            dbHelper.saveBooks(java.util.Collections.singletonList(book), "reading");
        } else {
            dbHelper.updateBookShelf(book.getUuid(), "reading");
        }
        updateTabBadges();

        if (needsShelfUpdate) {
            apiClient.updateBookShelfState(book.getUuid(), "reading", new YandexBooksApiClient.ApiCallback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {
                    // Полка на сервере успешно синхронизирована в фоне
                }

                @Override
                public void onError(String errorMessage) {
                    // Локальное состояние уже сохранено в SQLite, сбой сети не блокирует чтение
                }
            });
        }
        if (onReady != null) {
            onReady.run();
        }
    }

    private void moveBookToShelf(final Book book, final String targetShelf, final Runnable onDone) {
        book.setShelfType(targetShelf);
        Book existing = dbHelper.getBookByUuid(book.getUuid());
        if (existing == null) {
            dbHelper.saveBooks(java.util.Collections.singletonList(book), targetShelf);
        } else {
            dbHelper.updateBookShelf(book.getUuid(), targetShelf);
        }
        updateTabBadges();

        apiClient.updateBookShelfState(book.getUuid(), targetShelf, new YandexBooksApiClient.ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (onDone != null) onDone.run();
                    }
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (onDone != null) onDone.run();
                    }
                });
            }
        });
    }

    private void removeBookFromLibraryAndDevice(final Book book, final Runnable onDone) {
        dbHelper.removeBook(book.getUuid());
        cacheManager.deleteBookCache(book.getUuid(), book.getTitle());
        updateTabBadges();

        if (!"catalog".equals(currentShelf) && !"search".equals(currentShelf)) {
            for (int i = 0; i < currentBooks.size(); i++) {
                if (book.getUuid().equals(currentBooks.get(i).getUuid())) {
                    currentBooks.remove(i);
                    break;
                }
            }
            adapter.notifyDataSetChanged();
            updateEmptyState();
            updateShelfFooter();
        }

        apiClient.removeBookFromLibrary(book.getUuid(), new YandexBooksApiClient.ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "«" + book.getTitle() + "» убрана с полки", Toast.LENGTH_SHORT).show();
                        if (onDone != null) onDone.run();
                    }
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this, "Удалено с устройства (сервер: " + errorMessage + ")", Toast.LENGTH_SHORT).show();
                        if (onDone != null) onDone.run();
                    }
                });
            }
        });
    }

    private void updateShelfButtonStyles(Button btnReading, Button btnToRead, Button btnDone, String activeShelf) {
        boolean isReading = "reading".equals(activeShelf);
        boolean isToRead = "to_read".equals(activeShelf);
        boolean isDone = "done".equals(activeShelf);

        btnReading.setBackgroundResource(isReading ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
        btnReading.setTextColor(isReading ? Color.WHITE : Color.BLACK);
        btnReading.setText(isReading ? "✓ Читаю" : "Читаю");

        btnToRead.setBackgroundResource(isToRead ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
        btnToRead.setTextColor(isToRead ? Color.WHITE : Color.BLACK);
        btnToRead.setText(isToRead ? "✓ В планы" : "В планы");

        btnDone.setBackgroundResource(isDone ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
        btnDone.setTextColor(isDone ? Color.WHITE : Color.BLACK);
        btnDone.setText(isDone ? "✓ Прочитано" : "Прочитано");
    }

    private void openInOnyxReader(final Book book) {
        File epub = cacheManager.ensurePublicEpubFile(book.getUuid(), book.getTitle());
        if (epub != null && epub.exists() && epub.length() > 0) {
            boolean ok = CacheManager.openInSystemReader(this, epub);
            if (!ok) {
                Toast.makeText(this, "Читалка Onyx не найдена. Открываем в читалке Онлайн...", Toast.LENGTH_SHORT).show();
                openInLiteReader(book);
            }
            return;
        }

        final Dialog dialog = showEinkLoadingDialog("Яндекс Книги", "Загрузка EPUB в /sdcard/Books/ для Onyx...");

        cacheManager.downloadBookAsync(book.getUuid(), book.getTitle(), new CacheManager.DownloadProgressCallback() {
            @Override
            public void onProgress(int downloadedCount, int totalCount) {}

            @Override
            public void onComplete() {
                dismissEinkLoadingDialog(dialog);
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
                dismissEinkLoadingDialog(dialog);
                Toast.makeText(MainActivity.this, "Ошибка скачивания: " + message + ". Открываем в читалке Онлайн.", Toast.LENGTH_LONG).show();
                openInLiteReader(book);
            }
        });
    }

    private void openInLiteReader(final Book book) {
        if (cacheManager.isBookDownloaded(book.getUuid())) {
            launchReader(book);
            return;
        }

        final Dialog dialog = showEinkLoadingDialog("Яндекс Книги", "Загрузка и подготовка книги к чтению...");

        cacheManager.ensureBookReady(book.getUuid(), book.getTitle(), new CacheManager.BookReadyCallback() {
            @Override
            public void onReady(List<Chapter> chapters) {
                dismissEinkLoadingDialog(dialog);
                adapter.notifyDataSetChanged();
                launchReader(book);
            }

            @Override
            public void onError(String message) {
                dismissEinkLoadingDialog(dialog);
                Toast.makeText(MainActivity.this, "Не удалось открыть книгу: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void launchReader(Book book) {
        Intent intent = new Intent(MainActivity.this, ReaderActivity.class);
        intent.putExtra("book_uuid", book.getUuid());
        intent.putExtra("book_title", book.getTitle());
        intent.putExtra("book_percent", book.getPercent());
        intent.putExtra("book_chapter", book.getCurrentChapterIndex());
        intent.putExtra("book_paragraph", book.getCurrentParagraphIndex());
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
                annotation.setVisibility(View.GONE);
            }

            final boolean downloaded = cacheManager.isBookDownloaded(book.getUuid());
            if (statusTag != null) {
                statusTag.setText(downloaded ? " • В памяти" : " • Онлайн");
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

        // 1. Полный заголовок книги без обрезки
        TextView titleView = new TextView(this);
        titleView.setText(book.getTitle());
        titleView.setTextSize(14);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        root.addView(titleView);

        // 2. Метаданные (Автор и Прогресс/Статус) - две ультракомпактные строки
        LinearLayout metaLayout = new LinearLayout(this);
        metaLayout.setOrientation(LinearLayout.VERTICAL);
        metaLayout.setPadding(0, (int) (2 * density), 0, (int) (4 * density));

        TextView authorView = new TextView(this);
        authorView.setText("Автор: " + (book.getAuthor() != null ? book.getAuthor() : "Не указан"));
        authorView.setTextSize(12);
        authorView.setTypeface(null, Typeface.NORMAL);
        authorView.setTextColor(Color.BLACK);
        metaLayout.addView(authorView);

        double pct = book.getPercent();
        TextView progressView = new TextView(this);
        String progStr = pct > 0 ? String.format("%.0f%%", pct) : "0%";
        progressView.setText("Прогресс: " + progStr + "  •  Статус: " + (isDownloaded ? "В памяти" : "В сети"));
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

        // 6. Компактный блок кнопок действий
        LinearLayout buttonContainer = new LinearLayout(this);
        buttonContainer.setOrientation(LinearLayout.VERTICAL);
        buttonContainer.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        buttonContainer.setPadding(0, (int) (6 * density), 0, 0);

        int row1Height = (int) (36 * density);
        int row2Height = (int) (32 * density);

        // Строка 1: Кнопки чтения [ Onyx Reader ] и [ Читалка Онлайн ] бок о бок (50% / 50%)
        LinearLayout readRow = new LinearLayout(this);
        readRow.setOrientation(LinearLayout.HORIZONTAL);
        readRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row1Height));

        Button btnOnyx = new Button(this);
        btnOnyx.setText("Onyx Reader");
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
                ensureBookOnReadingShelf(book, new Runnable() {
                    @Override
                    public void run() {
                        openInOnyxReader(book);
                    }
                });
            }
        });
        readRow.addView(btnOnyx);

        Button btnLite = new Button(this);
        btnLite.setText("Читалка Онлайн");
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
                ensureBookOnReadingShelf(book, new Runnable() {
                    @Override
                    public void run() {
                        openInLiteReader(book);
                    }
                });
            }
        });
        readRow.addView(btnLite);
        buttonContainer.addView(readRow);

        // Определение актуальной полки книги
        Book dbBook = dbHelper.getBookByUuid(book.getUuid());
        final String[] curShelfRef = new String[]{
                (dbBook != null && dbBook.getShelfType() != null) ? dbBook.getShelfType() : book.getShelfType()
        };

        // Строка 2: Переключение разделов [ Читаю ] [ В планы ] [ Прочитано ]
        LinearLayout shelfRow = new LinearLayout(this);
        shelfRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lpShelfRow = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row2Height);
        lpShelfRow.setMargins(0, (int) (4 * density), 0, 0);
        shelfRow.setLayoutParams(lpShelfRow);

        final Button btnShelfReading = new Button(this);
        btnShelfReading.setTextSize(11);
        btnShelfReading.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lpSR = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpSR.setMargins(0, 0, (int) (2 * density), 0);
        btnShelfReading.setLayoutParams(lpSR);

        final Button btnShelfToRead = new Button(this);
        btnShelfToRead.setTextSize(11);
        btnShelfToRead.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lpST = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpST.setMargins((int) (2 * density), 0, (int) (2 * density), 0);
        btnShelfToRead.setLayoutParams(lpST);

        final Button btnShelfDone = new Button(this);
        btnShelfDone.setTextSize(11);
        btnShelfDone.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams lpSD = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpSD.setMargins((int) (2 * density), 0, 0, 0);
        btnShelfDone.setLayoutParams(lpSD);

        updateShelfButtonStyles(btnShelfReading, btnShelfToRead, btnShelfDone, curShelfRef[0]);

        btnShelfReading.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("reading".equals(curShelfRef[0])) {
                    Toast.makeText(MainActivity.this, "Книга уже в разделе «Читаю»", Toast.LENGTH_SHORT).show();
                    return;
                }
                btnShelfReading.setEnabled(false);
                moveBookToShelf(book, "reading", new Runnable() {
                    @Override
                    public void run() {
                        btnShelfReading.setEnabled(true);
                        curShelfRef[0] = "reading";
                        updateShelfButtonStyles(btnShelfReading, btnShelfToRead, btnShelfDone, "reading");
                        Toast.makeText(MainActivity.this, "«" + book.getTitle() + "» перенесена в «Читаю»", Toast.LENGTH_SHORT).show();
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        });

        btnShelfToRead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("to_read".equals(curShelfRef[0])) {
                    Toast.makeText(MainActivity.this, "Книга уже в разделе «В планах»", Toast.LENGTH_SHORT).show();
                    return;
                }
                btnShelfToRead.setEnabled(false);
                moveBookToShelf(book, "to_read", new Runnable() {
                    @Override
                    public void run() {
                        btnShelfToRead.setEnabled(true);
                        curShelfRef[0] = "to_read";
                        updateShelfButtonStyles(btnShelfReading, btnShelfToRead, btnShelfDone, "to_read");
                        Toast.makeText(MainActivity.this, "«" + book.getTitle() + "» перенесена в «В планы»", Toast.LENGTH_SHORT).show();
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        });

        btnShelfDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("done".equals(curShelfRef[0])) {
                    Toast.makeText(MainActivity.this, "Книга уже в разделе «Прочитано»", Toast.LENGTH_SHORT).show();
                    return;
                }
                btnShelfDone.setEnabled(false);
                moveBookToShelf(book, "done", new Runnable() {
                    @Override
                    public void run() {
                        btnShelfDone.setEnabled(true);
                        curShelfRef[0] = "done";
                        updateShelfButtonStyles(btnShelfReading, btnShelfToRead, btnShelfDone, "done");
                        Toast.makeText(MainActivity.this, "«" + book.getTitle() + "» перенесена в «Прочитано»", Toast.LENGTH_SHORT).show();
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        });

        shelfRow.addView(btnShelfReading);
        shelfRow.addView(btnShelfToRead);
        shelfRow.addView(btnShelfDone);
        buttonContainer.addView(shelfRow);

        // Строка 3: Скачивание/Удаление файла, Убрать с полки, Закрыть
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lpActionRow = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row2Height);
        lpActionRow.setMargins(0, (int) (4 * density), 0, 0);
        actionRow.setLayoutParams(lpActionRow);

        final Button btnDownloadOrDelete = new Button(this);
        btnDownloadOrDelete.setTextSize(11);
        btnDownloadOrDelete.setTypeface(null, Typeface.BOLD);
        btnDownloadOrDelete.setTextColor(Color.BLACK);
        btnDownloadOrDelete.setBackgroundResource(R.drawable.btn_eink);
        LinearLayout.LayoutParams lpDown = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f);
        lpDown.setMargins(0, 0, (int) (2 * density), 0);
        btnDownloadOrDelete.setLayoutParams(lpDown);

        if (!isDownloaded) {
            btnDownloadOrDelete.setText("Скачать EPUB");
            btnDownloadOrDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    btnDownloadOrDelete.setEnabled(false);
                    btnDownloadOrDelete.setText("Загрузка...");
                    Toast.makeText(MainActivity.this, "Загрузка книги «" + book.getTitle() + "»...", Toast.LENGTH_SHORT).show();
                    ensureBookOnReadingShelf(book, new Runnable() {
                        @Override
                        public void run() {
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
                                            btnDownloadOrDelete.setEnabled(true);
                                            btnDownloadOrDelete.setText("Скачать EPUB");
                                        }
                                    });
                                }
                            });
                        }
                    });
                }
            });
        } else {
            btnDownloadOrDelete.setText("Удалить файл");
            btnDownloadOrDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("Удалить файл")
                            .setMessage("Удалить загруженный файл «" + book.getTitle() + "» из памяти устройства?\n(Книга на полке и прогресс сохранятся)")
                            .setPositiveButton("Удалить", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    cacheManager.deleteBookCache(book.getUuid(), book.getTitle());
                                    adapter.notifyDataSetChanged();
                                    Toast.makeText(MainActivity.this, "Файл книги удален из памяти", Toast.LENGTH_SHORT).show();
                                    dialog.dismiss();
                                    EpdController.requestFullRefresh(MainActivity.this, booksListView);
                                }
                            })
                            .setNegativeButton("Отмена", null)
                            .show();
                }
            });
        }
        actionRow.addView(btnDownloadOrDelete);

        Button btnRemoveShelf = new Button(this);
        btnRemoveShelf.setText("Убрать с полки");
        btnRemoveShelf.setTextSize(11);
        btnRemoveShelf.setTypeface(null, Typeface.BOLD);
        btnRemoveShelf.setTextColor(Color.BLACK);
        btnRemoveShelf.setBackgroundResource(R.drawable.btn_eink);
        LinearLayout.LayoutParams lpRemove = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.1f);
        lpRemove.setMargins((int) (2 * density), 0, (int) (2 * density), 0);
        btnRemoveShelf.setLayoutParams(lpRemove);
        btnRemoveShelf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Убрать с полки")
                        .setMessage("Убрать книгу «" + book.getTitle() + "» из библиотеки и удалить с устройства?")
                        .setPositiveButton("Убрать", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                removeBookFromLibraryAndDevice(book, new Runnable() {
                                    @Override
                                    public void run() {
                                        dialog.dismiss();
                                        EpdController.requestFullRefresh(MainActivity.this, booksListView);
                                    }
                                });
                            }
                        })
                        .setNegativeButton("Отмена", null)
                        .show();
            }
        });
        actionRow.addView(btnRemoveShelf);

        Button btnClose = new Button(this);
        btnClose.setText("Закрыть");
        btnClose.setTextSize(11);
        btnClose.setTypeface(null, Typeface.BOLD);
        btnClose.setTextColor(Color.BLACK);
        btnClose.setBackgroundResource(R.drawable.btn_eink);
        LinearLayout.LayoutParams lpClose = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.8f);
        btnClose.setLayoutParams(lpClose);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        actionRow.addView(btnClose);
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
