package com.onyx.yandexbooks.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
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
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import com.onyx.yandexbooks.core.eink.EinkListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.onyx.yandexbooks.R;
import com.onyx.yandexbooks.core.api.YandexBooksApiClient;
import com.onyx.yandexbooks.core.api.models.Book;
import com.onyx.yandexbooks.core.api.models.Chapter;
import com.onyx.yandexbooks.core.api.models.ReadingProgress;
import com.onyx.yandexbooks.core.auth.TokenStorage;
import com.onyx.yandexbooks.core.eink.EinkScrollView;
import com.onyx.yandexbooks.core.eink.EpdController;
import com.onyx.yandexbooks.core.eink.HardwareKeyHandler;
import com.onyx.yandexbooks.core.storage.CacheManager;
import com.onyx.yandexbooks.core.storage.DatabaseHelper;
import com.onyx.yandexbooks.core.sync.SyncManager;
import com.onyx.yandexbooks.core.ui.CoverLoader;
import com.onyx.yandexbooks.core.ui.EinkNotice;
import com.onyx.yandexbooks.core.update.AppUpdateManager;

import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Главный экран полок и библиотеки с высокой контрастностью для E-Ink Carta.
 */
public class MainActivity extends Activity {

    private static final int REQUEST_AUTH = 1001;

    private EinkListView booksListView;
    private LinearLayout mainHeaderBar, mainTabsBar, shelfFooterBar;
    private TextView mainHeaderTitle;
    private Button tabReadingBtn, tabToReadBtn, tabDoneBtn, tabUserBooksBtn, tabCatalogBtn, tabSearchBtn;
    private Button btnToggleReaderMode, btnCheckUpdate, btnMainMenu;
    private LinearLayout catalogBarContainer;
    private View catalogBarDivider;
    private Button btnCatalogCategory, btnCatalogReload;
    private String selectedCatalogShelfUuid = YandexBooksApiClient.AUTO_POPULAR_SHELF_UUID;
    private String selectedCatalogShelfTitle = "Популярное";
    private List<YandexBooksApiClient.CatalogCategory> cachedCatalogCategories = new ArrayList<>();
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

    private static boolean sUpdateNotifiedThisSession = false;
    private static boolean sUpdateCheckedSuccessfully = false;
    private boolean waitingStoragePermissionForRestore = false;
    private long lastAutoBackupMs = 0L;
    private BroadcastReceiver networkReceiver;

    @Override
    protected void onStart() {
        super.onStart();
        if (networkReceiver == null) {
            networkReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (isNetworkAvailable()) {
                        if (!sUpdateCheckedSuccessfully) {
                            checkAppUpdate(false);
                        }
                        if (syncManager != null && tokenStorage != null && tokenStorage.isAuthorized()) {
                            syncManager.flushOfflineQueue();
                        }
                    }
                }
            };
            try {
                registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
            } catch (Exception ignored) {}
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (networkReceiver != null) {
            try {
                unregisterReceiver(networkReceiver);
            } catch (Exception ignored) {}
            networkReceiver = null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        appSettings = new com.onyx.yandexbooks.core.storage.AppSettings(this);
        tokenStorage = new TokenStorage(this);
        dbHelper = DatabaseHelper.getInstance(this);
        // Автоматическое восстановление резервной копии до масштабирования и разметки экрана
        dbHelper.restoreBackupFromPublicStorageIfNeeded(this);
        com.onyx.yandexbooks.core.ui.UiScaleHelper.applyScale(this, appSettings.getUiScale());
        setContentView(R.layout.activity_main);
        com.onyx.yandexbooks.core.ui.UiScaleHelper.applyViewTreeScale(findViewById(android.R.id.content), appSettings.getUiScale());

        waitingStoragePermissionForRestore = !tokenStorage.isAuthorized() && requestRuntimeStoragePermissionsIfNeeded();
        apiClient = new YandexBooksApiClient(tokenStorage);
        cacheManager = new CacheManager(this, apiClient);
        syncManager = new SyncManager(this, apiClient);
        coverLoader = CoverLoader.getInstance(this);

        booksListView = (EinkListView) findViewById(R.id.books_list_view);
        EpdController.configureEinkListView(this, booksListView);
        mainHeaderBar = (LinearLayout) findViewById(R.id.main_header_bar);
        mainHeaderTitle = (TextView) findViewById(R.id.main_header_title);
        mainTabsBar = (LinearLayout) findViewById(R.id.main_tabs_bar);
        shelfFooterBar = (LinearLayout) findViewById(R.id.shelf_footer_bar);
        tabReadingBtn = (Button) findViewById(R.id.tab_reading_btn);
        tabToReadBtn = (Button) findViewById(R.id.tab_to_read_btn);
        tabDoneBtn = (Button) findViewById(R.id.tab_done_btn);
        tabUserBooksBtn = (Button) findViewById(R.id.tab_user_books_btn);
        tabCatalogBtn = (Button) findViewById(R.id.tab_catalog_btn);
        tabSearchBtn = (Button) findViewById(R.id.tab_search_btn);

        catalogBarContainer = (LinearLayout) findViewById(R.id.catalog_bar_container);
        catalogBarDivider = findViewById(R.id.catalog_bar_divider);
        btnCatalogCategory = (Button) findViewById(R.id.btn_catalog_category);
        btnCatalogReload = (Button) findViewById(R.id.btn_catalog_reload);
        if (btnCatalogCategory != null) {
            btnCatalogCategory.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showCatalogCategoryDialog();
                }
            });
        }
        if (btnCatalogReload != null) {
            btnCatalogReload.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadCatalog();
                }
            });
        }

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
        applyMainVisualPreferences();

        if (!waitingStoragePermissionForRestore) {
            checkAuthAndLoad();
        }
    }

    private boolean requestRuntimeStoragePermissionsIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            try {
                java.lang.reflect.Method checkSelf = android.content.Context.class.getMethod("checkSelfPermission", String.class);
                Object res = checkSelf.invoke(this, "android.permission.WRITE_EXTERNAL_STORAGE");
                if (res instanceof Integer && ((Integer) res) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    java.lang.reflect.Method reqPerms = android.app.Activity.class.getMethod("requestPermissions", String[].class, int.class);
                    reqPerms.invoke(this, new String[]{
                            "android.permission.WRITE_EXTERNAL_STORAGE",
                            "android.permission.READ_EXTERNAL_STORAGE"
                    }, 1002);
                    return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 1002) {
            boolean wasWaiting = waitingStoragePermissionForRestore;
            waitingStoragePermissionForRestore = false;
            boolean granted = grantResults != null && grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (granted && dbHelper != null) {
                if (dbHelper.restoreBackupFromPublicStorageIfNeeded(this)) {
                    com.onyx.yandexbooks.core.ui.UiScaleHelper.applyScale(this, appSettings.getUiScale());
                    applyMainVisualPreferences();
                    updateReaderModeButton();
                }
            }
            if (wasWaiting) {
                checkAuthAndLoad();
            }
        }
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
                cycleReaderMode();
            }
        });
        btnToggleReaderMode.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showReaderModeSelectionDialog();
                return true;
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
                    } else if ("user_books".equals(currentShelf)) {
                        loadUserUploadedBooks();
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

        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setWindowAnimations(0);
        }

        float density = getResources().getDisplayMetrics().density;
        final DisplayMetrics dm = getResources().getDisplayMetrics();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_notice_bg);
        int padH = (int) (14 * density);
        int padV = (int) (12 * density);
        root.setPadding(padH, padV, padH, padV);

        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        headerRow.setPadding(0, 0, 0, (int) (6 * density));

        TextView titleView = new TextView(this);
        titleView.setText("Меню приложения");
        titleView.setTextSize(14);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding((int) (28 * density), 0, 0, 0);
        headerRow.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        Button btnTopClose = new Button(this);
        btnTopClose.setText("X");
        btnTopClose.setTextSize(12);
        btnTopClose.setTypeface(null, Typeface.BOLD);
        btnTopClose.setTextColor(Color.BLACK);
        btnTopClose.setBackgroundResource(R.drawable.btn_eink);
        btnTopClose.setPadding(0, 0, 0, 0);
        btnTopClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        headerRow.addView(btnTopClose, new LinearLayout.LayoutParams((int) (30 * density), (int) (28 * density)));
        root.addView(headerRow);

        View divider = new View(this);
        divider.setBackgroundColor(Color.BLACK);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density)));

        final TextView accountView = new TextView(this);
        String savedLogin = (tokenStorage != null) ? tokenStorage.getUserLogin() : "";
        accountView.setText("Аккаунт: " + ((savedLogin != null && !savedLogin.trim().isEmpty()) ? savedLogin : "определяется..."));
        accountView.setTextSize(11);
        accountView.setTypeface(null, Typeface.BOLD);
        accountView.setTextColor(Color.BLACK);
        accountView.setGravity(Gravity.CENTER);
        accountView.setPadding(0, (int) (4 * density), 0, (int) (2 * density));
        root.addView(accountView);
        if ((savedLogin == null || savedLogin.trim().isEmpty()) && apiClient != null && tokenStorage != null && tokenStorage.isAuthorized()) {
            apiClient.fetchCurrentUserLogin(new YandexBooksApiClient.ApiCallback<String>() {
                @Override
                public void onSuccess(String login) {
                    if (login != null && !login.trim().isEmpty()) {
                        accountView.setText("Аккаунт: " + login);
                    } else {
                        accountView.setText("Аккаунт: авторизован");
                    }
                }
                @Override
                public void onError(String errorMessage) {
                    accountView.setText("Аккаунт: авторизован");
                }
            });
        }

        final com.onyx.yandexbooks.core.eink.EinkScrollView menuScroll = new com.onyx.yandexbooks.core.eink.EinkScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollLp.setMargins(0, (int) (4 * density), 0, (int) (4 * density));
        menuScroll.setLayoutParams(scrollLp);

        LinearLayout listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        listLayout.setPadding(0, (int) (2 * density), 0, (int) (2 * density));

        int doneCount = dbHelper.getBooksCountByShelf("done");
        Button btnShelfDone = createMenuButton("Прочитано (" + doneCount + ")", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                switchTab("done");
            }
        }, density);
        listLayout.addView(btnShelfDone);

        int userBooksCount = dbHelper.getBooksCountByShelf("user_books");
        Button btnShelfUserBooks = createMenuButton("Мои файлы (" + userBooksCount + ")", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                switchTab("user_books");
            }
        }, density);
        listLayout.addView(btnShelfUserBooks);

        Button btnBatchDownload = createMenuButton("Скачать полку", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                startBatchDownloadCurrentShelf();
            }
        }, density);
        listLayout.addView(btnBatchDownload);

        Button btnSync = createMenuButton("Синхронизация", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (!isNetworkAvailable()) {
                    EinkNotice.show(MainActivity.this, "Синхронизация",
                            "Нет подключения к интернету.\nВесь прогресс чтения сохранён локально и автоматически отправится в облако при подключении к Wi-Fi.");
                    return;
                }
                syncManager.flushOfflineQueue(new SyncManager.SyncCompleteCallback() {
                    @Override
                    public void onSyncFinished(final int count) {
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing()) return;
                                if (!"catalog".equals(currentShelf) && !"search".equals(currentShelf)) {
                                    loadBooks(currentShelf);
                                }
                                if (count > 0) {
                                    EinkNotice.show(MainActivity.this, "Синхронизация",
                                            "Двусторонняя синхронизация выполнена.\nОтправлено обновлений в облако: " + count + ". Полки и прогресс обновлены с сервера.");
                                } else {
                                    EinkNotice.show(MainActivity.this, "Синхронизация",
                                            "Двусторонняя синхронизация выполнена.\nПолки и прогресс чтения обновлены с сервера Яндекс Книг.");
                                }
                            }
                        }, count > 0 ? 450L : 100L);
                    }
                });
            }
        }, density);
        listLayout.addView(btnSync);

        Button btnUiScale = createMenuButton("Внешний вид и интерфейс ПО", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showAppAppearanceDialog();
            }
        }, density);
        listLayout.addView(btnUiScale);

        Button btnUpdate = createMenuButton("Обновление ПО", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                checkAppUpdate(true);
            }
        }, density);
        listLayout.addView(btnUpdate);

        Button btnArchive = createMenuButton("Архив версий и откат ПО", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                AppUpdateManager.getInstance().showReleaseArchiveDialog(MainActivity.this);
            }
        }, density);
        listLayout.addView(btnArchive);

        Button btnRefresh = createMenuButton("Очистить экран", new View.OnClickListener() {
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

        menuScroll.addView(listLayout);
        root.addView(menuScroll);

        TextView tvBetaNotice = new TextView(this);
        tvBetaNotice.setText("Яндекс Книги Lite v" + AppUpdateManager.getInstance().getCurrentVersionName(this));
        tvBetaNotice.setTextSize(11);
        tvBetaNotice.setTypeface(null, Typeface.BOLD);
        tvBetaNotice.setTextColor(Color.BLACK);
        tvBetaNotice.setGravity(Gravity.CENTER);
        tvBetaNotice.setPadding(0, (int) (3 * density), 0, (int) (6 * density));
        root.addView(tvBetaNotice);

        LinearLayout bottomBtnRow = new LinearLayout(this);
        bottomBtnRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomBtnRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (36 * density)));

        Button btnClose = new Button(this);
        btnClose.setText("Свернуть меню");
        btnClose.setTextSize(11.0f);
        btnClose.setTypeface(null, Typeface.BOLD);
        btnClose.setTextColor(Color.BLACK);
        btnClose.setBackgroundResource(R.drawable.btn_eink);
        btnClose.setPadding((int) (4 * density), 0, (int) (4 * density), 0);
        btnClose.setSingleLine(true);
        btnClose.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lpClose = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpClose.setMargins(0, 0, (int) (3 * density), 0);
        btnClose.setLayoutParams(lpClose);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        bottomBtnRow.addView(btnClose);

        Button btnExitApp = new Button(this);
        btnExitApp.setText("Выйти из ПО");
        btnExitApp.setTextSize(11.0f);
        btnExitApp.setTypeface(null, Typeface.BOLD);
        btnExitApp.setTextColor(Color.BLACK);
        btnExitApp.setBackgroundResource(R.drawable.btn_eink);
        btnExitApp.setPadding((int) (4 * density), 0, (int) (4 * density), 0);
        btnExitApp.setSingleLine(true);
        btnExitApp.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lpExit = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpExit.setMargins((int) (3 * density), 0, 0, 0);
        btnExitApp.setLayoutParams(lpExit);
        btnExitApp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                try {
                    dbHelper.exportBackupToPublicStorage(MainActivity.this);
                } catch (Throwable ignored) {}
                finish();
            }
        });
        bottomBtnRow.addView(btnExitApp);
        root.addView(bottomBtnRow);

        dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final int targetW = (int) (dm.widthPixels * 0.90);
        final int targetH = Math.min((int) (dm.heightPixels * 0.90), (int) (515 * density));
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                        || keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                        || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    return true;
                }
                return false;
            }
        });

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setLayout(targetW, targetH);
                }
                EpdController.requestFullRefresh(MainActivity.this, root);
            }
        });

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                EpdController.requestFullRefresh(MainActivity.this, null);
            }
        });

        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(targetW, targetH);
        }
    }

    private void applyMainVisualPreferences() {
        float density = getResources().getDisplayMetrics().density;
        String mode = appSettings.getPanelSizeMode();

        int headerBarDp = 44;
        int headerBtnDp = 34;
        float headerTitleSp = 14f;
        float headerBtnSp = 12.5f;
        int menuMinWDp = 76;
        int tabsBarDp = 38;
        int tabBtnDp = 32;
        float tabSp = 10.5f;
        int footerBarDp = 46;
        int footerBtnDp = 34;
        float footerTextSp = 12.5f;
        float footerBtnSp = 12f;

        if (com.onyx.yandexbooks.core.storage.AppSettings.PANEL_SIZE_COMPACT.equalsIgnoreCase(mode)) {
            headerBarDp = 36; headerBtnDp = 28; headerTitleSp = 13f; headerBtnSp = 11f; menuMinWDp = 62;
            tabsBarDp = 32; tabBtnDp = 28; tabSp = 9.5f;
            footerBarDp = 38; footerBtnDp = 28; footerTextSp = 11.5f; footerBtnSp = 11f;
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.PANEL_SIZE_LARGE.equalsIgnoreCase(mode)) {
            headerBarDp = 52; headerBtnDp = 42; headerTitleSp = 15.5f; headerBtnSp = 14f; menuMinWDp = 88;
            tabsBarDp = 44; tabBtnDp = 38; tabSp = 11.5f;
            footerBarDp = 54; footerBtnDp = 40; footerTextSp = 14f; footerBtnSp = 13.5f;
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.PANEL_SIZE_XLARGE.equalsIgnoreCase(mode)) {
            headerBarDp = 60; headerBtnDp = 48; headerTitleSp = 17f; headerBtnSp = 15.5f; menuMinWDp = 100;
            tabsBarDp = 50; tabBtnDp = 44; tabSp = 12.5f;
            footerBarDp = 62; footerBtnDp = 46; footerTextSp = 15.5f; footerBtnSp = 15f;
        }

        setViewHeightPx(mainHeaderBar, Math.round(headerBarDp * density));
        if (mainHeaderTitle != null) {
            mainHeaderTitle.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, headerTitleSp);
        }
        stylePanelButton(btnToggleReaderMode, headerBtnDp, 60, headerBtnSp, 10, density);
        stylePanelButton(btnCheckUpdate, headerBtnDp, 60, headerBtnSp, 10, density);
        stylePanelButton(btnMainMenu, headerBtnDp, menuMinWDp, headerBtnSp, 12, density);

        setViewHeightPx(mainTabsBar, Math.round(tabsBarDp * density));
        float effectiveTabSp = Math.max(8.5f, tabSp - 1.0f);
        Button[] tabs = new Button[]{tabReadingBtn, tabToReadBtn, tabDoneBtn, tabUserBooksBtn, tabCatalogBtn, tabSearchBtn};
        for (Button t : tabs) {
            stylePanelButton(t, tabBtnDp, 0, effectiveTabSp, 1, density);
        }

        setViewHeightPx(shelfFooterBar, Math.round(footerBarDp * density));
        if (shelfFooterStatus != null) {
            shelfFooterStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, footerTextSp);
        }
        if (shelfPageIndicator != null) {
            shelfPageIndicator.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, footerTextSp);
        }
        stylePanelButton(btnShelfPrevPage, footerBtnDp, 56, footerBtnSp, 10, density);
        stylePanelButton(btnShelfNextPage, footerBtnDp, 56, footerBtnSp, 10, density);
    }

    private void setViewHeightPx(View v, int heightPx) {
        if (v == null) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null && lp.height != heightPx) {
            lp.height = heightPx;
            v.setLayoutParams(lp);
        }
    }

    private void stylePanelButton(Button btn, int heightDp, int minWidthDp, float textSp, int padHDp, float density) {
        if (btn == null) return;
        int hPx = Math.round(heightDp * density);
        ViewGroup.LayoutParams lp = btn.getLayoutParams();
        if (lp != null) {
            lp.height = hPx;
            btn.setLayoutParams(lp);
        }
        btn.setMinHeight(hPx);
        btn.setMinimumHeight(hPx);
        if (minWidthDp > 0) {
            int minW = Math.round(minWidthDp * density);
            btn.setMinWidth(minW);
            btn.setMinimumWidth(minW);
        }
        btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, textSp);
        int padH = Math.round(padHDp * density);
        btn.setPadding(padH, 0, padH, 0);
    }

    private void showAppAppearanceDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setWindowAnimations(0);
        }

        final float density = getResources().getDisplayMetrics().density;
        final DisplayMetrics dm = getResources().getDisplayMetrics();

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_notice_bg);
        int padH = (int) (14 * density);
        int padV = (int) (12 * density);
        root.setPadding(padH, padV, padH, padV);

        TextView titleView = new TextView(this);
        titleView.setText("Внешний вид и интерфейс ПО");
        titleView.setTextSize(15);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, (int) (6 * density));
        root.addView(titleView);

        View divTop = new View(this);
        divTop.setBackgroundColor(Color.BLACK);
        root.addView(divTop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(2, 2 * density)));

        final com.onyx.yandexbooks.core.eink.EinkScrollView scroll = new com.onyx.yandexbooks.core.eink.EinkScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scrollLp.setMargins(0, (int) (6 * density), 0, (int) (6 * density));
        scroll.setLayoutParams(scrollLp);

        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // 1. Размер верхней/нижней панелей и кнопки «Меню»
        addAppearanceSectionLabel(content, "1. Панели и кнопки («Меню», вкладки, статус версии):", density);
        final String[] panelValues = new String[]{
                com.onyx.yandexbooks.core.storage.AppSettings.PANEL_SIZE_COMPACT,
                com.onyx.yandexbooks.core.storage.AppSettings.PANEL_SIZE_COMFORTABLE,
                com.onyx.yandexbooks.core.storage.AppSettings.PANEL_SIZE_LARGE,
                com.onyx.yandexbooks.core.storage.AppSettings.PANEL_SIZE_XLARGE
        };
        final String[] panelNames = new String[]{"Компактный", "Оптим. (Darwin)", "Крупный", "Максимум"};
        final Button[] panelBtns = new Button[4];
        for (int rowIdx = 0; rowIdx < 2; rowIdx++) {
            LinearLayout row = createHorizontalOptionRow(density);
            for (int col = 0; col < 2; col++) {
                final int idx = rowIdx * 2 + col;
                Button b = createOptionChipButton(panelNames[idx], density, col == 0);
                panelBtns[idx] = b;
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        appSettings.setPanelSizeMode(panelValues[idx]);
                        applyMainVisualPreferences();
                        refreshChipGroup(panelBtns, panelValues, panelValues[idx]);
                    }
                });
                row.addView(b);
            }
            content.addView(row);
        }
        refreshChipGroup(panelBtns, panelValues, appSettings.getPanelSizeMode());

        // 2. Плотность карточек книг на полке
        addAppearanceSectionLabel(content, "2. Карточки книг и кнопки на полке:", density);
        final String[] cardValues = new String[]{
                com.onyx.yandexbooks.core.storage.AppSettings.CARD_DENSITY_COMPACT,
                com.onyx.yandexbooks.core.storage.AppSettings.CARD_DENSITY_NORMAL,
                com.onyx.yandexbooks.core.storage.AppSettings.CARD_DENSITY_LARGE
        };
        final String[] cardNames = new String[]{"Комп.", "Стандарт", "Крупные"};
        final Button[] cardBtns = new Button[3];
        LinearLayout cardRow = createHorizontalOptionRow(density);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Button b = createOptionChipButton(cardNames[idx], density, i < 2);
            cardBtns[idx] = b;
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    appSettings.setCardDensityMode(cardValues[idx]);
                    if (adapter != null) adapter.notifyDataSetChanged();
                    refreshChipGroup(cardBtns, cardValues, cardValues[idx]);
                }
            });
            cardRow.addView(b);
        }
        content.addView(cardRow);
        refreshChipGroup(cardBtns, cardValues, appSettings.getCardDensityMode());

        // 3. Нижний отступ колонтитула в ридере Lite (от бортика)
        addAppearanceSectionLabel(content, "3. Отступ часов/заряда в ридере Lite (от бортика):", density);
        LinearLayout offsetRow = createHorizontalOptionRow(density);
        final TextView offsetValView = new TextView(this);
        offsetValView.setText(appSettings.getReaderFooterOffsetDp() + " dp");
        offsetValView.setTextSize(13.5f);
        offsetValView.setTypeface(null, Typeface.BOLD);
        offsetValView.setTextColor(Color.BLACK);
        offsetValView.setGravity(Gravity.CENTER);

        Button btnMinusOffset = createOptionChipButton("- 4 dp", density, true);
        btnMinusOffset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = appSettings.getReaderFooterOffsetDp() - 4;
                appSettings.setReaderFooterOffsetDp(next);
                offsetValView.setText(appSettings.getReaderFooterOffsetDp() + " dp");
            }
        });
        Button btnPlusOffset = createOptionChipButton("+ 4 dp", density, false);
        btnPlusOffset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = appSettings.getReaderFooterOffsetDp() + 4;
                appSettings.setReaderFooterOffsetDp(next);
                offsetValView.setText(appSettings.getReaderFooterOffsetDp() + " dp");
            }
        });
        offsetRow.addView(btnMinusOffset);
        offsetRow.addView(offsetValView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f));
        offsetRow.addView(btnPlusOffset);
        content.addView(offsetRow);

        // 4. Глобальный масштаб диалогов и текстов ОС
        addAppearanceSectionLabel(content, "4. Общий масштаб текста и диалогов:", density);
        Button btnGlobalScale = createMenuButton("Масштаб ОС: " + getUiScaleLabel() + "  (выбрать...)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showUiScaleDialog();
            }
        }, density);
        content.addView(btnGlobalScale);

        Button btnResetVisual = createMenuButton("Сбросить визуал по умолчанию (Darwin)", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                appSettings.setPanelSizeMode(com.onyx.yandexbooks.core.storage.AppSettings.PANEL_SIZE_COMFORTABLE);
                appSettings.setCardDensityMode(com.onyx.yandexbooks.core.storage.AppSettings.CARD_DENSITY_NORMAL);
                appSettings.setReaderFooterOffsetDp(com.onyx.yandexbooks.core.storage.AppSettings.DEFAULT_FOOTER_OFFSET_DP);
                applyMainVisualPreferences();
                if (adapter != null) adapter.notifyDataSetChanged();
                refreshChipGroup(panelBtns, panelValues, appSettings.getPanelSizeMode());
                refreshChipGroup(cardBtns, cardValues, appSettings.getCardDensityMode());
                offsetValView.setText(appSettings.getReaderFooterOffsetDp() + " dp");
            }
        }, density);
        content.addView(btnResetVisual);

        scroll.addView(content);
        root.addView(scroll);

        View divBot = new View(this);
        divBot.setBackgroundColor(Color.BLACK);
        root.addView(divBot, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density)));

        Button btnDone = new Button(this);
        btnDone.setText("Готово");
        btnDone.setTextSize(13);
        btnDone.setTypeface(null, Typeface.BOLD);
        btnDone.setTextColor(Color.BLACK);
        btnDone.setBackgroundResource(R.drawable.btn_eink_primary);
        LinearLayout.LayoutParams lpDone = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (38 * density));
        lpDone.setMargins(0, (int) (6 * density), 0, 0);
        btnDone.setLayoutParams(lpDone);
        btnDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        root.addView(btnDone);

        dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final int targetW = (int) (dm.widthPixels * 0.92);
        final int targetH = Math.min((int) (dm.heightPixels * 0.86), (int) (490 * density));
        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setLayout(targetW, targetH);
                }
                EpdController.requestFullRefresh(MainActivity.this, root);
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                EpdController.requestFullRefresh(MainActivity.this, null);
            }
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(targetW, targetH);
        }
    }

    private void addAppearanceSectionLabel(LinearLayout parent, String text, float density) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(Color.BLACK);
        tv.setPadding(0, (int) (6 * density), 0, (int) (4 * density));
        parent.addView(tv);
    }

    private LinearLayout createHorizontalOptionRow(float density) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (36 * density));
        lp.setMargins(0, 0, 0, (int) (4 * density));
        row.setLayoutParams(lp);
        return row;
    }

    private Button createOptionChipButton(String text, float density, boolean addRightMargin) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(11.5f);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(Color.BLACK);
        b.setBackgroundResource(R.drawable.btn_eink);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setPadding((int) (4 * density), 0, (int) (4 * density), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        if (addRightMargin) {
            lp.setMargins(0, 0, (int) (4 * density), 0);
        }
        b.setLayoutParams(lp);
        return b;
    }

    private void refreshChipGroup(Button[] buttons, String[] values, String activeValue) {
        if (buttons == null || values == null) return;
        for (int i = 0; i < buttons.length && i < values.length; i++) {
            boolean sel = values[i].equalsIgnoreCase(activeValue);
            buttons[i].setBackgroundResource(sel ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
        }
    }

    private String getUiScaleLabel() {
        float scale = appSettings.getUiScale();
        int pct = Math.round(scale * 100);
        if (pct >= 195) return pct + "% (Макс 10.3\")";
        if (pct >= 175) return pct + "% (Планшет 10.3\")";
        if (pct >= 155) return pct + "% (Огромный)";
        if (pct >= 140) return pct + "% (Очень крупный)";
        if (pct >= 125) return pct + "% (Крупный)";
        if (pct >= 110) return pct + "% (Увеличенный)";
        return pct + "% (Стандарт)";
    }

    private void showUiScaleDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setWindowAnimations(0);
        }

        float density = getResources().getDisplayMetrics().density;
        final DisplayMetrics dm = getResources().getDisplayMetrics();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_notice_bg);
        int padH = (int) (14 * density);
        int padV = (int) (12 * density);
        root.setPadding(padH, padV, padH, padV);

        TextView titleView = new TextView(this);
        titleView.setText("Масштаб интерфейса и шрифта");
        titleView.setTextSize(14);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, (int) (6 * density));
        root.addView(titleView);

        View divider = new View(this);
        divider.setBackgroundColor(Color.BLACK);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density)));

        final com.onyx.yandexbooks.core.eink.EinkScrollView scaleScroll = new com.onyx.yandexbooks.core.eink.EinkScrollView(this);
        LinearLayout.LayoutParams scaleScrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f);
        scaleScrollLp.setMargins(0, (int) (4 * density), 0, (int) (4 * density));
        scaleScroll.setLayoutParams(scaleScrollLp);

        LinearLayout optionsLayout = new LinearLayout(this);
        optionsLayout.setOrientation(LinearLayout.VERTICAL);
        optionsLayout.setPadding(0, (int) (4 * density), 0, (int) (4 * density));

        final float[] scales = new float[]{
                com.onyx.yandexbooks.core.storage.AppSettings.UI_SCALE_DEFAULT,
                com.onyx.yandexbooks.core.storage.AppSettings.UI_SCALE_MEDIUM,
                com.onyx.yandexbooks.core.storage.AppSettings.UI_SCALE_LARGE,
                com.onyx.yandexbooks.core.storage.AppSettings.UI_SCALE_EXTRA_LARGE,
                com.onyx.yandexbooks.core.storage.AppSettings.UI_SCALE_MAX,
                com.onyx.yandexbooks.core.storage.AppSettings.UI_SCALE_180,
                com.onyx.yandexbooks.core.storage.AppSettings.UI_SCALE_200
        };
        final String[] labels = new String[]{
                "100% — Стандартный",
                "115% — Увеличенный (комфорт для 6\")",
                "130% — Крупный (рекомендуется)",
                "145% — Очень крупный (макс. четкость)",
                "160% — Огромный",
                "180% — Для планшетов 10.3\" (Go 10.3)",
                "200% — Максимальный (10.3\" 300 PPI)"
        };

        float currentScale = appSettings.getUiScale();
        for (int i = 0; i < scales.length; i++) {
            final float s = scales[i];
            boolean selected = Math.abs(currentScale - s) < 0.05f;
            String text = (selected ? "• " : "  ") + labels[i];
            Button btn = createMenuButton(text, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    appSettings.setUiScale(s);
                    com.onyx.yandexbooks.core.ui.UiScaleHelper.applyScale(MainActivity.this, s);
                    finish();
                    startActivity(getIntent());
                    overridePendingTransition(0, 0);
                }
            }, density);
            if (selected) {
                btn.setBackgroundResource(R.drawable.btn_eink_primary);
            }
            optionsLayout.addView(btn);
        }

        final boolean ditherOn = appSettings.isDitherCoversEnabled();
        final Button btnDither = createMenuButton(
                "Дизеринг обложек (E-Ink 16 градаций): " + (ditherOn ? "Вкл" : "Выкл"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean next = !appSettings.isDitherCoversEnabled();
                        appSettings.setDitherCoversEnabled(next);
                        CoverLoader.getInstance(MainActivity.this).clearMemoryCache();
                        dialog.dismiss();
                        loadBooks(currentShelf);
                    }
                }, density);
        btnDither.setBackgroundResource(ditherOn ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
        optionsLayout.addView(btnDither);

        scaleScroll.addView(optionsLayout);
        root.addView(scaleScroll);

        Button btnClose = new Button(this);
        btnClose.setText("Отмена");
        btnClose.setTextSize(12);
        btnClose.setTypeface(null, Typeface.BOLD);
        btnClose.setTextColor(Color.BLACK);
        btnClose.setBackgroundResource(R.drawable.btn_eink);
        btnClose.setPadding(0, 0, 0, 0);
        btnClose.setSingleLine(true);
        LinearLayout.LayoutParams lpClose = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (34 * density));
        btnClose.setLayoutParams(lpClose);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        root.addView(btnClose);

        dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final int targetW = (int) (dm.widthPixels * 0.88);
        final int targetH = Math.min((int) (dm.heightPixels * 0.88), (int) (465 * density));
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                return (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                        || keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_VOLUME_UP);
            }
        });

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setLayout(targetW, targetH);
                }
                EpdController.requestFullRefresh(MainActivity.this, root);
            }
        });

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                EpdController.requestFullRefresh(MainActivity.this, null);
            }
        });

        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(targetW, targetH);
        }
    }

    private Button createMenuButton(String text, View.OnClickListener listener, float density) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setTextColor(Color.BLACK);
        btn.setBackgroundResource(R.drawable.btn_eink);
        btn.setPadding(0, 0, 0, 0);
        btn.setSingleLine(true);
        btn.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (34 * density));
        lp.setMargins(0, 0, 0, (int) (5 * density));
        btn.setLayoutParams(lp);
        btn.setOnClickListener(listener);
        return btn;
    }

    private void startBatchDownloadCurrentShelf() {
        if ("catalog".equals(currentShelf) || "search".equals(currentShelf)) {
            EinkNotice.show(MainActivity.this, "Пакетное скачивание", "Пакетное скачивание доступно на полках: «Читаю», «В планах» или «Прочитано».");
            return;
        }

        // Получаем полный актуальный список всех книг текущей полки из базы данных
        List<Book> shelfBooks = dbHelper.getBooksByShelf(currentShelf);

        if (shelfBooks == null || shelfBooks.isEmpty()) {
            EinkNotice.show(MainActivity.this, "Пакетное скачивание", "На этой полке нет книг для скачивания.");
            return;
        }

        showBatchDownloadDialog(shelfBooks);
    }

    private void showBatchDownloadDialog(final List<Book> allShelfBooks) {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setWindowAnimations(0);
        }

        float density = getResources().getDisplayMetrics().density;
        final DisplayMetrics dm = getResources().getDisplayMetrics();
        final int targetW = (int) (dm.widthPixels * 0.90);

        String shelfName = "«Читаю»";
        if ("to_read".equals(currentShelf)) shelfName = "«В планах»";
        else if ("done".equals(currentShelf)) shelfName = "«Прочитано»";
        else if ("user_books".equals(currentShelf)) shelfName = "«Мои файлы»";

        final int totalOnShelf = allShelfBooks.size();
        final List<Book> neededToDownload = new ArrayList<>();
        for (Book b : allShelfBooks) {
            if (b != null && b.getUuid() != null && !cacheManager.isBookDownloaded(b.getUuid())) {
                neededToDownload.add(b);
            }
        }
        final int inMemoryCount = Math.max(0, totalOnShelf - neededToDownload.size());

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_notice_bg);
        int padH = (int) (14 * density);
        int padV = (int) (12 * density);
        root.setPadding(padH, padV, padH, padV);

        TextView titleView = new TextView(this);
        titleView.setText("Скачивание полки " + shelfName);
        titleView.setTextSize(14);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, (int) (4 * density));
        root.addView(titleView);

        TextView subtitleView = new TextView(this);
        subtitleView.setText("Всего на полке: " + totalOnShelf + " • В памяти: " + inMemoryCount + " • К загрузке: " + neededToDownload.size());
        subtitleView.setTextSize(11);
        subtitleView.setTextColor(Color.BLACK);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setPadding(0, 0, 0, (int) (6 * density));
        root.addView(subtitleView);

        View divider = new View(this);
        divider.setBackgroundColor(Color.BLACK);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density)));

        final TextView currentBookText = new TextView(this);
        currentBookText.setTextSize(11);
        currentBookText.setTextColor(Color.BLACK);
        currentBookText.setMinLines(3);
        currentBookText.setMaxLines(5);
        currentBookText.setEllipsize(TextUtils.TruncateAt.END);
        currentBookText.setPadding(0, (int) (8 * density), 0, (int) (6 * density));

        final ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        try {
            progressBar.setProgressDrawable(getResources().getDrawable(R.drawable.progress_eink_bar));
        } catch (Throwable ignored) {}
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (18 * density));
        pbLp.setMargins(0, (int) (4 * density), 0, (int) (8 * density));
        progressBar.setLayoutParams(pbLp);

        final LinearLayout actionContainer = new LinearLayout(this);
        actionContainer.setOrientation(LinearLayout.VERTICAL);

        final Button btnCancel = new Button(this);
        btnCancel.setText("Отмена");
        btnCancel.setTextSize(12);
        btnCancel.setTypeface(null, Typeface.BOLD);
        btnCancel.setTextColor(Color.BLACK);
        btnCancel.setBackgroundResource(R.drawable.btn_eink);
        btnCancel.setPadding(0, 0, 0, 0);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (34 * density));
        btnLp.setMargins(0, (int) (4 * density), 0, 0);
        btnCancel.setLayoutParams(btnLp);

        final AtomicBoolean isCancelled = new AtomicBoolean(false);
        final AtomicInteger completedCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);
        final AtomicBoolean isRunning = new AtomicBoolean(false);

        if (neededToDownload.isEmpty()) {
            currentBookText.setText("Все книги с этой полки (" + totalOnShelf + ") уже сохранены в памяти устройства.");
            root.addView(currentBookText);

            Button btnRedownloadAll = createMenuButton("Перекачать все книги (" + totalOnShelf + ")", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startBatchExecution(dialog, root, currentBookText, progressBar, actionContainer, btnCancel,
                            allShelfBooks, isCancelled, completedCount, errorCount, isRunning, density);
                }
            }, density);
            actionContainer.addView(btnRedownloadAll);

            btnCancel.setText("Закрыть");
            btnCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
            actionContainer.addView(btnCancel);
            root.addView(actionContainer);
        } else {
            currentBookText.setText("К загрузке доступно " + neededToDownload.size() + " новых книг (из " + totalOnShelf + " на полке).");
            root.addView(currentBookText);

            Button btnDownloadMissing = createMenuButton("Скачать недостающие (" + neededToDownload.size() + ")", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startBatchExecution(dialog, root, currentBookText, progressBar, actionContainer, btnCancel,
                            neededToDownload, isCancelled, completedCount, errorCount, isRunning, density);
                }
            }, density);
            actionContainer.addView(btnDownloadMissing);

            if (inMemoryCount > 0) {
                Button btnDownloadAll = createMenuButton("Скачать всю полку заново (" + totalOnShelf + ")", new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startBatchExecution(dialog, root, currentBookText, progressBar, actionContainer, btnCancel,
                                allShelfBooks, isCancelled, completedCount, errorCount, isRunning, density);
                    }
                }, density);
                actionContainer.addView(btnDownloadAll);
            }

            btnCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
            actionContainer.addView(btnCancel);
            root.addView(actionContainer);
        }

        dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        dialog.setCanceledOnTouchOutside(false);

        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                        || keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                        || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    return true;
                }
                return false;
            }
        });

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface d) {
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setLayout(targetW, ViewGroup.LayoutParams.WRAP_CONTENT);
                }
                EpdController.requestFullRefresh(MainActivity.this, root);
            }
        });

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                EpdController.requestFullRefresh(MainActivity.this, null);
            }
        });

        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(targetW, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void startBatchExecution(final Dialog dialog, final LinearLayout root,
                                     final TextView currentBookText, final ProgressBar progressBar,
                                     final LinearLayout actionContainer, final Button btnCancel,
                                     final List<Book> queue, final AtomicBoolean isCancelled,
                                     final AtomicInteger completedCount, final AtomicInteger errorCount,
                                     final AtomicBoolean isRunning, float density) {
        isRunning.set(true);
        actionContainer.removeAllViews();

        progressBar.setMax(queue.size());
        progressBar.setProgress(0);
        if (progressBar.getParent() == null) {
            root.addView(progressBar, root.indexOfChild(currentBookText) + 1);
        }

        currentBookText.setText("Подготовка к скачиванию: 0 из " + queue.size() + "...");
        progressBar.postInvalidate();
        currentBookText.postInvalidate();
        EpdController.requestFullRefresh(MainActivity.this, null);

        btnCancel.setText("Остановить");
        btnCancel.setEnabled(true);
        btnCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isRunning.get()) {
                    isCancelled.set(true);
                    btnCancel.setEnabled(false);
                    currentBookText.setText("Остановка загрузки...");
                    currentBookText.postInvalidate();
                    EpdController.requestFullRefresh(MainActivity.this, null);
                } else {
                    dialog.dismiss();
                    adapter.notifyDataSetChanged();
                    updateShelfFooter();
                    EpdController.requestFullRefresh(MainActivity.this, null);
                }
            }
        });
        actionContainer.addView(btnCancel);

        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface d) {
                isCancelled.set(true);
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                final int total = queue.size();
                for (int i = 0; i < total; i++) {
                    if (isCancelled.get() || isFinishing()) {
                        break;
                    }
                    final Book book = queue.get(i);
                    final int idx = i + 1;

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (dialog.isShowing()) {
                                progressBar.setProgress(idx - 1);
                                currentBookText.setText("Загрузка (" + idx + " из " + total + "):\n«" + book.getTitle() + "»");
                                progressBar.postInvalidate();
                                currentBookText.postInvalidate();
                                EpdController.requestFullRefresh(MainActivity.this, null);
                            }
                        }
                    });

                    YandexBooksApiClient.DownloadResult res = cacheManager.downloadBookSync(book.getUuid(), book.getTitle());
                    if (res != null && res.success) {
                        completedCount.incrementAndGet();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (dialog.isShowing()) {
                                    progressBar.setProgress(idx);
                                    currentBookText.setText("Сохранено (" + idx + " из " + total + "):\n«" + book.getTitle() + "»");
                                    progressBar.postInvalidate();
                                    currentBookText.postInvalidate();
                                    EpdController.requestFullRefresh(MainActivity.this, null);
                                }
                            }
                        });
                    } else {
                        errorCount.incrementAndGet();
                        final String errMsg = (res != null && res.errorMessage != null) ? res.errorMessage : "ошибка сети";
                        Log.e("MainActivity", "Batch download error for " + book.getTitle() + ": " + errMsg);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (dialog.isShowing()) {
                                    progressBar.setProgress(idx);
                                    currentBookText.setText("Ошибка («" + book.getTitle() + "»): " + errMsg);
                                    progressBar.postInvalidate();
                                    currentBookText.postInvalidate();
                                    EpdController.requestFullRefresh(MainActivity.this, null);
                                }
                            }
                        });
                        try {
                            Thread.sleep(600);
                        } catch (InterruptedException ignored) {}
                    }
                }

                isRunning.set(false);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!dialog.isShowing()) return;
                        progressBar.setProgress(total);
                        btnCancel.setEnabled(true);
                        btnCancel.setText("Готово");
                        int ok = completedCount.get();
                        int err = errorCount.get();
                        if (isCancelled.get()) {
                            currentBookText.setText("Загрузка остановлена.\nСкачано: " + ok + " из " + total + " (ошибок: " + err + ")");
                        } else if (err > 0) {
                            currentBookText.setText("Завершено! Успешно скачано: " + ok + " из " + total + " (ошибок: " + err + ")");
                        } else {
                            currentBookText.setText("Успешно! Все выбранные книги (" + ok + ") сохранены в память устройства.");
                        }
                        progressBar.postInvalidate();
                        currentBookText.postInvalidate();
                        adapter.notifyDataSetChanged();
                        updateShelfFooter();
                        EpdController.requestFullRefresh(MainActivity.this, null);
                    }
                });
            }
        }).start();
    }

    private void showLogoutConfirmDialog() {
        EinkNotice.showConfirm(
                MainActivity.this,
                getString(R.string.logout_confirm_title),
                getString(R.string.logout_confirm_message),
                getString(R.string.btn_logout),
                getString(R.string.btn_cancel),
                new Runnable() {
                    @Override
                    public void run() {
                        if (dbHelper != null) {
                            dbHelper.clearUserAccountDataOnLogout(MainActivity.this);
                        }
                        if (tokenStorage != null) {
                            tokenStorage.clear();
                        }
                        clearWebViewSessionCookiesOnLogout();
                        currentBooks.clear();
                        adapter.notifyDataSetChanged();
                        updateTabBadges();
                        updateEmptyState();
                        Intent authIntent = new Intent(MainActivity.this, AuthActivity.class);
                        authIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
                        startActivityForResult(authIntent, REQUEST_AUTH);
                    }
                },
                null
        );
    }

    @SuppressWarnings("deprecation")
    private void clearWebViewSessionCookiesOnLogout() {
        try {
            android.webkit.CookieSyncManager.createInstance(this);
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            if (cm != null) {
                cm.removeSessionCookie();
                cm.removeAllCookie();
                try {
                    java.lang.reflect.Method flush = cm.getClass().getMethod("flush");
                    flush.invoke(cm);
                } catch (Throwable ignored) {}
            }
            android.webkit.CookieSyncManager.getInstance().sync();
        } catch (Throwable ignored) {}
        try {
            android.webkit.WebStorage.getInstance().deleteAllData();
        } catch (Throwable ignored) {}
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
        dialog.setCanceledOnTouchOutside(false);
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();

        // 60-секундный предохранитель для защиты от зависаний (увеличен для крупных книг)
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (dialog != null && dialog.isShowing()) {
                    try {
                        dialog.dismiss();
                    } catch (Exception ignored) {}
                    EinkNotice.show(MainActivity.this, "Загрузка книги", "Превышено время ожидания загрузки книги. Попробуйте еще раз или режим Onyx.");
                    EpdController.requestFullRefresh(MainActivity.this, null);
                }
            }
        }, 60000);

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
                    sUpdateCheckedSuccessfully = true;
                    if (btnCheckUpdate != null) {
                        btnCheckUpdate.setVisibility(View.VISIBLE);
                        btnCheckUpdate.setText(release.tagName);
                        btnCheckUpdate.setBackgroundResource(R.drawable.btn_eink_primary);
                        btnCheckUpdate.setTextColor(Color.BLACK);
                    }
                    if (userTriggered || !sUpdateNotifiedThisSession) {
                        sUpdateNotifiedThisSession = true;
                        AppUpdateManager.getInstance().showUpdateDialog(MainActivity.this, release);
                    }
                } else {
                    if (message != null && !message.startsWith("Сбой") && !message.startsWith("Не удалось") && !message.startsWith("Ошибка")) {
                        sUpdateCheckedSuccessfully = true;
                    }
                    if (userTriggered) {
                        if (message != null && (message.startsWith("Сбой") || message.startsWith("Не удалось") || message.startsWith("Ошибка"))) {
                            EinkNotice.show(MainActivity.this, "Обновление ПО", "Не удалось проверить обновления.\n" + message);
                        } else {
                            String cur = AppUpdateManager.getInstance().getCurrentVersionName(MainActivity.this);
                            EinkNotice.show(MainActivity.this, "Обновление ПО", "У вас установлена последняя версия (v" + cur + ").\nОбновлений на GitHub не требуется.");
                        }
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

        if (tabDoneBtn != null) {
            tabDoneBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTab("done");
                }
            });
        }

        if (tabUserBooksBtn != null) {
            tabUserBooksBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchTab("user_books");
                }
            });
        }

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

        boolean isSearch = "search".equals(shelf);
        boolean isCatalog = "catalog".equals(shelf);
        if (searchBarContainer != null) {
            searchBarContainer.setVisibility(isSearch ? View.VISIBLE : View.GONE);
        }
        if (searchBarDivider != null) {
            searchBarDivider.setVisibility(isSearch ? View.VISIBLE : View.GONE);
        }
        if (catalogBarContainer != null) {
            catalogBarContainer.setVisibility(isCatalog ? View.VISIBLE : View.GONE);
        }
        if (catalogBarDivider != null) {
            catalogBarDivider.setVisibility(isCatalog ? View.VISIBLE : View.GONE);
        }

        if (isSearch) {
            if (searchQueryInput != null) searchQueryInput.requestFocus();
            updateEmptyState();
            updateShelfFooter();
        } else if (isCatalog) {
            loadCatalog();
        } else if ("user_books".equals(shelf)) {
            loadUserUploadedBooks();
        } else {
            loadBooks(shelf);
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
        EinkNotice.showConfirm(
                this,
                "Нет подключения к интернету",
                "Для работы каталога и поиска книг требуется подключение к сети Wi-Fi.\n\nПожалуйста, включите Wi-Fi на устройстве и попробуйте снова.",
                "Повторить попытку",
                "Закрыть",
                onRetry,
                null
        );
    }

    private void updateCatalogCategoryButtonText() {
        if (btnCatalogCategory != null) {
            btnCatalogCategory.setText("Категория: " + selectedCatalogShelfTitle + " ▼");
        }
    }

    private void showCatalogCategoryDialog() {
        if (cachedCatalogCategories == null || cachedCatalogCategories.isEmpty()) {
            apiClient.getCatalogCategories(new YandexBooksApiClient.ApiCallback<List<YandexBooksApiClient.CatalogCategory>>() {
                @Override
                public void onSuccess(List<YandexBooksApiClient.CatalogCategory> cats) {
                    if (cats != null && !cats.isEmpty()) {
                        cachedCatalogCategories = cats;
                    }
                    showCatalogCategoryPickerDialog();
                }

                @Override
                public void onError(String errorMessage) {
                    showCatalogCategoryPickerDialog();
                }
            });
            return;
        }
        showCatalogCategoryPickerDialog();
    }

    private void showCatalogCategoryPickerDialog() {
        if (isFinishing()) return;
        final List<YandexBooksApiClient.CatalogCategory> list = (cachedCatalogCategories != null && !cachedCatalogCategories.isEmpty())
                ? cachedCatalogCategories
                : java.util.Collections.singletonList(new YandexBooksApiClient.CatalogCategory(YandexBooksApiClient.AUTO_POPULAR_SHELF_UUID, "Популярное"));

        String[] titles = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            String t = list.get(i).title;
            titles[i] = list.get(i).uuid.equals(selectedCatalogShelfUuid) ? ("● " + t) : ("○ " + t);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Категории каталога");
        builder.setItems(titles, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                YandexBooksApiClient.CatalogCategory chosen = list.get(which);
                selectedCatalogShelfUuid = chosen.uuid;
                selectedCatalogShelfTitle = chosen.title;
                updateCatalogCategoryButtonText();
                loadCatalog();
            }
        });
        builder.setNegativeButton("Закрыть", null);
        AlertDialog d = builder.create();
        if (d.getWindow() != null) {
            d.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        d.show();
    }

    private void loadCatalog() {
        updateCatalogCategoryButtonText();
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

        loadingTextView.setText("Загрузка: " + selectedCatalogShelfTitle + "...");
        loadingTextView.setVisibility(View.VISIBLE);

        if (cachedCatalogCategories == null || cachedCatalogCategories.isEmpty()) {
            apiClient.getCatalogCategories(new YandexBooksApiClient.ApiCallback<List<YandexBooksApiClient.CatalogCategory>>() {
                @Override
                public void onSuccess(List<YandexBooksApiClient.CatalogCategory> cats) {
                    if (cats != null && !cats.isEmpty()) {
                        cachedCatalogCategories = cats;
                    }
                }
                @Override
                public void onError(String errorMessage) {}
            });
        }

        apiClient.getCatalogBooksByShelf(selectedCatalogShelfUuid, new YandexBooksApiClient.ApiCallback<List<Book>>() {
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
                if (!isNetworkAvailable()) {
                    showNoInternetDialog(new Runnable() {
                        @Override
                        public void run() {
                            loadCatalog();
                        }
                    });
                } else {
                    String details = (errorMessage != null && !errorMessage.trim().isEmpty())
                            ? ("\n\n" + errorMessage.trim()) : "";
                    EinkNotice.showConfirm(
                            MainActivity.this,
                            "Каталог временно недоступен",
                            "Не удалось загрузить подборку «" + selectedCatalogShelfTitle + "»." + details,
                            "Повторить",
                            "Закрыть",
                            new Runnable() {
                                @Override
                                public void run() {
                                    loadCatalog();
                                }
                            },
                            null
                    );
                }
            }
        });
    }

    private void loadUserUploadedBooks() {
        currentBooks = dbHelper.getBooksByShelf("user_books");
        adapter.notifyDataSetChanged();
        updateTabBadges();
        updateTabStyles();
        updateEmptyState();
        updateShelfFooter();

        if (currentBooks.isEmpty()) {
            loadingTextView.setText("Загрузка личных файлов...");
            loadingTextView.setVisibility(View.VISIBLE);
        }

        apiClient.fetchUserUploadedBooks(new YandexBooksApiClient.ApiCallback<List<Book>>() {
            @Override
            public void onSuccess(List<Book> books) {
                loadingTextView.setVisibility(View.GONE);
                if (books != null && !books.isEmpty()) {
                    dbHelper.saveAllBooks(books);
                }
                if ("user_books".equals(currentShelf)) {
                    currentBooks = dbHelper.getBooksByShelf("user_books");
                    adapter.notifyDataSetChanged();
                    updateTabBadges();
                    updateEmptyState();
                    updateShelfFooter();
                    EpdController.requestFullRefresh(MainActivity.this, booksListView);
                }
            }

            @Override
            public void onError(String errorMessage) {
                loadingTextView.setVisibility(View.GONE);
                if ("user_books".equals(currentShelf)) {
                    updateEmptyState();
                    updateShelfFooter();
                    if (currentBooks.isEmpty()) {
                        EinkNotice.show(MainActivity.this, "Мои файлы", "Не удалось загрузить личные файлы.\n" + (errorMessage != null ? errorMessage : ""));
                    }
                }
            }
        });
    }

    private void executeSearch(final String query) {
        if (query == null || query.trim().isEmpty()) {
            EinkNotice.show(this, "Поиск", "Введите поисковый запрос.");
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
                    EinkNotice.show(MainActivity.this, "Поиск", "По запросу «" + query.trim() + "» ничего не найдено.");
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
        boolean isDone = "done".equals(currentShelf);
        boolean isUserBooks = "user_books".equals(currentShelf);
        boolean isCatalog = "catalog".equals(currentShelf);
        boolean isSearch = "search".equals(currentShelf);

        tabReadingBtn.setBackgroundResource(isReading ? R.drawable.tab_eink_active : R.drawable.tab_eink_inactive);
        tabReadingBtn.setTextColor(Color.BLACK);
        tabReadingBtn.setTypeface(null, isReading ? Typeface.BOLD : Typeface.NORMAL);

        tabToReadBtn.setBackgroundResource(isToRead ? R.drawable.tab_eink_active : R.drawable.tab_eink_inactive);
        tabToReadBtn.setTextColor(Color.BLACK);
        tabToReadBtn.setTypeface(null, isToRead ? Typeface.BOLD : Typeface.NORMAL);

        if (tabDoneBtn != null) {
            tabDoneBtn.setBackgroundResource(isDone ? R.drawable.tab_eink_active : R.drawable.tab_eink_inactive);
            tabDoneBtn.setTextColor(Color.BLACK);
            tabDoneBtn.setTypeface(null, isDone ? Typeface.BOLD : Typeface.NORMAL);
        }

        if (tabUserBooksBtn != null) {
            tabUserBooksBtn.setBackgroundResource(isUserBooks ? R.drawable.tab_eink_active : R.drawable.tab_eink_inactive);
            tabUserBooksBtn.setTextColor(Color.BLACK);
            tabUserBooksBtn.setTypeface(null, isUserBooks ? Typeface.BOLD : Typeface.NORMAL);
        }

        tabCatalogBtn.setBackgroundResource(isCatalog ? R.drawable.tab_eink_active : R.drawable.tab_eink_inactive);
        tabCatalogBtn.setTextColor(Color.BLACK);
        tabCatalogBtn.setTypeface(null, isCatalog ? Typeface.BOLD : Typeface.NORMAL);

        tabSearchBtn.setBackgroundResource(isSearch ? R.drawable.tab_eink_active : R.drawable.tab_eink_inactive);
        tabSearchBtn.setTextColor(Color.BLACK);
        tabSearchBtn.setTypeface(null, isSearch ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void updateTabBadges() {
        int readingCount = dbHelper.getBooksCountByShelf("reading");
        int toReadCount = dbHelper.getBooksCountByShelf("to_read");
        int doneCount = dbHelper.getBooksCountByShelf("done");
        int userBooksCount = dbHelper.getBooksCountByShelf("user_books");

        tabReadingBtn.setText("Читаю (" + readingCount + ")");
        tabToReadBtn.setText("В планах (" + toReadCount + ")");
        if (tabDoneBtn != null) {
            tabDoneBtn.setText("Прочит. (" + doneCount + ")");
        }
        if (tabUserBooksBtn != null) {
            tabUserBooksBtn.setText("Файлы (" + userBooksCount + ")");
        }
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
        else if ("user_books".equals(currentShelf)) title = "Мои файлы";
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
                } else if ("done".equals(currentShelf)) {
                    emptyShelfTitleView.setText("Нет прочитанных книг");
                    emptyShelfHintView.setText("Завершённые книги появятся здесь автоматически после 100% чтения или отметки «Прочитано».");
                    if (btnRefreshShelf != null) btnRefreshShelf.setText("Обновить список");
                } else if ("user_books".equals(currentShelf)) {
                    emptyShelfTitleView.setText("Личные файлы отсутствуют");
                    emptyShelfHintView.setText("Здесь отображаются книги (EPUB/FB2), загруженные вами в профиль Яндекс Книг / Букмейта.");
                    if (btnRefreshShelf != null) btnRefreshShelf.setText("Обновить «Мои файлы»");
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

        if (currentBooks.isEmpty()) {
            loadingTextView.setVisibility(View.VISIBLE);
        }

        // 2. Фоновая загрузка актуальной библиотеки из облака Яндекса
        apiClient.getAllUserBooks(new YandexBooksApiClient.ApiCallback<List<Book>>() {
            @Override
            public void onSuccess(List<Book> allBooks) {
                loadingTextView.setVisibility(View.GONE);
                if (allBooks != null && !allBooks.isEmpty()) {
                    dbHelper.saveAllBooks(allBooks);
                    syncManager.flushOfflineQueue();
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
                    tokenStorage.clear();
                    EinkNotice.show(MainActivity.this, "Сессия истекла", "Сессия авторизации истекла (401).\nПожалуйста, войдите снова.", "ОК", new Runnable() {
                        @Override
                        public void run() {
                            startActivityForResult(new Intent(MainActivity.this, AuthActivity.class), REQUEST_AUTH);
                        }
                    });
                } else if (currentBooks.isEmpty()) {
                    EinkNotice.show(MainActivity.this, "Ошибка сети", "Не удалось загрузить книги: " + errorMessage);
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (tokenStorage.isAuthorized()) {
            // Перезагружаем прогресс после возврата из ридера (не затирая результаты поиска и каталога)
            if (!"catalog".equals(currentShelf) && !"search".equals(currentShelf)) {
                currentBooks = dbHelper.getBooksByShelf(currentShelf);
                adapter.notifyDataSetChanged();
            }
            updateTabBadges();
            updateShelfFooter();
            // Сначала дожидаемся отправки локального/офлайн-прогресса в облако,
            // и только затем подтягиваем полки с сервера, чтобы исключить гонку старого процента
            syncManager.flushOfflineQueue(new SyncManager.SyncCompleteCallback() {
                @Override
                public void onSyncFinished(final int syncedCount) {
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing() && !"catalog".equals(currentShelf) && !"search".equals(currentShelf)) {
                                loadBooks(currentShelf);
                            }
                        }
                    }, syncedCount > 0 ? 500L : 350L);
                }
            });

            // Автоматическая фоновая проверка обновлений при появлении интернета
            if (!sUpdateCheckedSuccessfully && isNetworkAvailable()) {
                checkAppUpdate(false);
            }
        }
        // Очищаем экран при возвращении в библиотеку от возможных остаточных артефактов
        EpdController.requestFullRefresh(this, booksListView);
    }

    @Override
    protected void onPause() {
        super.onPause();
        long now = System.currentTimeMillis();
        if (tokenStorage != null && tokenStorage.isAuthorized() && dbHelper != null && (now - lastAutoBackupMs > 300000L)) {
            lastAutoBackupMs = now;
            try {
                dbHelper.exportBackupToPublicStorage(this);
            } catch (Throwable ignored) {}
        }
    }

    private void scrollShelfBy(int delta) {
        if (booksListView != null && currentBooks != null && !currentBooks.isEmpty()) {
            int current = booksListView.getFirstVisiblePosition();
            int target;
            if (delta > 0) {
                target = Math.min(currentBooks.size() - 1, current + delta);
            } else {
                target = Math.max(0, current + delta);
            }
            booksListView.setSelection(target);
            updateShelfFooter();
            EpdController.requestFullRefresh(MainActivity.this, booksListView);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null) return false;
        int keyCode = event.getKeyCode();
        if (HardwareKeyHandler.isPageKey(keyCode, event)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0) {
                    if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                            || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                            || keyCode == 93 || keyCode == 167 || keyCode == 261) {
                        scrollShelfBy(3);
                    } else {
                        scrollShelfBy(-3);
                    }
                }
                return true;
            } else if (event.getAction() == KeyEvent.ACTION_UP) {
                return true;
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (HardwareKeyHandler.isPageKey(keyCode, event)) {
            if (event != null && event.getRepeatCount() > 0) {
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                    || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    || keyCode == 93 || keyCode == 167 || keyCode == 261) {
                scrollShelfBy(3);
            } else {
                scrollShelfBy(-3);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (HardwareKeyHandler.isPageKey(keyCode, event)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void updateReaderModeButton() {
        if (btnToggleReaderMode != null) {
            String mode = appSettings.getReaderMode();
            if (com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_LITE.equalsIgnoreCase(mode)) {
                btnToggleReaderMode.setText("Lite");
            } else if (com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_ALREADER.equalsIgnoreCase(mode)) {
                btnToggleReaderMode.setText("AlReader");
            } else if (com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_CHOOSER.equalsIgnoreCase(mode)) {
                btnToggleReaderMode.setText("Выбор");
            } else {
                btnToggleReaderMode.setText("NeoReader");
            }
        }
    }

    private void cycleReaderMode() {
        String cur = appSettings.getReaderMode();
        String next;
        String desc;
        if (com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_LITE.equalsIgnoreCase(cur)) {
            next = com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_NEOREADER;
            desc = "Основной ридер: Onyx NeoReader\n(Книги открываются в системном NeoReader; зажмите кнопку для быстрого выбора)";
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_NEOREADER.equalsIgnoreCase(cur)
                || com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_ONYX.equalsIgnoreCase(cur)) {
            next = com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_ALREADER;
            desc = "Основной ридер: AlReader / AlReaderX\n(Прямой запуск AlReader без перенастройки системы)";
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_ALREADER.equalsIgnoreCase(cur)) {
            next = com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_CHOOSER;
            desc = "Основной ридер: Диалог выбора\n(При каждом открытии книги система предложит выбрать приложение)";
        } else {
            next = com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_LITE;
            desc = "Основной ридер: Встроенный Lite\n(Двусторонняя облачная синхронизация прогресса чтения с Яндекс Книгами)";
        }
        appSettings.setReaderMode(next);
        updateReaderModeButton();
        adapter.notifyDataSetChanged();
        EinkNotice.show(MainActivity.this, "Режим чтения", desc);
    }

    private void showReaderModeSelectionDialog() {
        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setWindowAnimations(0);
        }
        float density = getResources().getDisplayMetrics().density;
        final DisplayMetrics dm = getResources().getDisplayMetrics();

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_notice_bg);
        int pad = (int) (14 * density);
        root.setPadding(pad, pad, pad, pad);

        TextView titleView = new TextView(this);
        titleView.setText("Выбор основного приложения для чтения");
        titleView.setTextSize(14);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setTextColor(Color.BLACK);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(0, 0, 0, (int) (6 * density));
        root.addView(titleView);

        View div = new View(this);
        div.setBackgroundColor(Color.BLACK);
        root.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density)));

        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.VERTICAL);
        opts.setPadding(0, (int) (6 * density), 0, (int) (6 * density));

        final String[] modes = new String[]{
                com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_LITE,
                com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_NEOREADER,
                com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_ALREADER,
                com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_CHOOSER
        };
        final String[] titles = new String[]{
                "Встроенный Lite (синхронизация с Яндекс)",
                "Onyx NeoReader (системный ридер)",
                "AlReader / AlReaderX (прямой запуск)",
                "Всегда спрашивать (системный выбор)"
        };

        String cur = appSettings.getReaderMode();
        for (int i = 0; i < modes.length; i++) {
            final String m = modes[i];
            boolean sel = m.equalsIgnoreCase(cur) || (i == 1 && com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_ONYX.equalsIgnoreCase(cur));
            Button b = createMenuButton((sel ? "• " : "  ") + titles[i], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    appSettings.setReaderMode(m);
                    updateReaderModeButton();
                    adapter.notifyDataSetChanged();
                }
            }, density);
            if (sel) b.setBackgroundResource(R.drawable.btn_eink_primary);
            opts.addView(b);
        }
        root.addView(opts);

        Button btnClose = createMenuButton("Закрыть", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        }, density);
        root.addView(btnClose);

        dialog.setContentView(root);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout((int) (dm.widthPixels * 0.88), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private void openBook(final Book book) {
        ensureBookOnReadingShelf(book, new Runnable() {
            @Override
            public void run() {
                // При открытии книги автоматически переводим активный раздел на "Читаю",
                // чтобы по возвращении из ридера пользователь видел список читаемых книг с актуальным прогрессом
                currentShelf = "reading";
                if (appSettings.isOnyxReaderPreferred()) {
                    openInOnyxReader(book);
                } else {
                    openInLiteReader(book);
                }
            }
        });
    }

    private void ensureBookSavedForDownload(final Book book, final Runnable onReady) {
        dbHelper.unhideBook(book.getUuid());
        Book existing = dbHelper.getBookByUuid(book.getUuid());
        String targetShelf = (existing != null && existing.getShelfType() != null)
                ? existing.getShelfType()
                : book.getShelfType();
        if (targetShelf == null || "catalog".equals(targetShelf) || "search".equals(targetShelf)) {
            targetShelf = "to_read";
        }
        book.setShelfType(targetShelf);
        if (existing != null && (book.getLibraryCardId() == null || book.getLibraryCardId().isEmpty())) {
            book.setLibraryCardId(existing.getLibraryCardId());
        }
        if (existing == null) {
            dbHelper.saveBooks(java.util.Collections.singletonList(book), targetShelf);
            apiClient.updateBookShelfState(book.getUuid(), book.getLibraryCardId(), targetShelf, new YandexBooksApiClient.ApiCallback<Boolean>() {
                @Override
                public void onSuccess(Boolean result) {}
                @Override
                public void onError(String errorMessage) {}
            });
        }
        updateTabBadges();
        if (onReady != null) {
            onReady.run();
        }
    }

    private void ensureBookOnReadingShelf(final Book book, final Runnable onReady) {
        dbHelper.unhideBook(book.getUuid());
        Book existing = dbHelper.getBookByUuid(book.getUuid());
        long nowTs = System.currentTimeMillis();

        book.setShelfType("reading");
        book.setLastReadTimestamp(nowTs);
        if (existing != null && (book.getLibraryCardId() == null || book.getLibraryCardId().isEmpty())) {
            book.setLibraryCardId(existing.getLibraryCardId());
        }
        if (existing == null) {
            dbHelper.saveBooks(java.util.Collections.singletonList(book), "reading");
        } else {
            dbHelper.updateBookShelf(book.getUuid(), "reading");
        }

        // Немедленно переключаем активную вкладку и список книг на раздел «Читаю»,
        // чтобы книга отображалась в «Читаю» при любом способе открытия (из списка или из карточки «О книге»)
        currentShelf = "reading";
        updateTabStyles();
        updateTabBadges();
        currentBooks = dbHelper.getBooksByShelf(currentShelf);
        adapter.notifyDataSetChanged();
        updateEmptyState();
        updateShelfFooter();

        // Автоматическая полусинхронизация: если есть облачный прогресс, гарантируем создание закладки
        if (book.getPercent() > 0.0 && book.getPercent() < 99.0) {
            dbHelper.ensureCloudSyncBookmark(book.getUuid(), book.getCurrentChapterIndex(), 0, book.getPercent());
        }

        apiClient.updateBookShelfState(book.getUuid(), book.getLibraryCardId(), "reading", new YandexBooksApiClient.ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                // Полка на сервере успешно синхронизирована в фоне
            }

            @Override
            public void onError(String errorMessage) {
                // Локальное состояние уже сохранено в SQLite, сбой сети не блокирует чтение
            }
        });
        if (onReady != null) {
            onReady.run();
        }
    }

    private void moveBookToShelf(final Book book, final String targetShelf, final Runnable onDone) {
        dbHelper.unhideBook(book.getUuid());
        book.setShelfType(targetShelf);
        Book existing = dbHelper.getBookByUuid(book.getUuid());
        if (existing == null) {
            dbHelper.saveBooks(java.util.Collections.singletonList(book), targetShelf);
        } else {
            dbHelper.updateBookShelf(book.getUuid(), targetShelf);
        }
        updateTabBadges();

        // Если мы находимся на полке, с которой книга перемещена, убираем ее из текущего списка
        if (!"catalog".equals(currentShelf) && !"search".equals(currentShelf)) {
            if (!currentShelf.equals(targetShelf)) {
                synchronized (currentBooks) {
                    for (int i = 0; i < currentBooks.size(); i++) {
                        if (book.getUuid().equals(currentBooks.get(i).getUuid())) {
                            currentBooks.remove(i);
                            break;
                        }
                    }
                }
                adapter.notifyDataSetChanged();
                updateEmptyState();
                updateShelfFooter();
            }
        }

        apiClient.updateBookShelfState(book.getUuid(), book.getLibraryCardId(), targetShelf, new YandexBooksApiClient.ApiCallback<Boolean>() {
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
            synchronized (currentBooks) {
                for (int i = 0; i < currentBooks.size(); i++) {
                    if (book.getUuid().equals(currentBooks.get(i).getUuid())) {
                        currentBooks.remove(i);
                        break;
                    }
                }
            }
            adapter.notifyDataSetChanged();
            updateEmptyState();
            updateShelfFooter();
        }

        apiClient.removeBookFromLibrary(book.getUuid(), book.getLibraryCardId(), new YandexBooksApiClient.ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        EinkNotice.show(MainActivity.this, "Полка", "Книга «" + book.getTitle() + "» убрана с полки.");
                        if (onDone != null) onDone.run();
                    }
                });
            }

            @Override
            public void onError(String errorMessage) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        EinkNotice.show(MainActivity.this, "Полка", "Удалено с устройства (сервер: " + errorMessage + ")");
                        if (onDone != null) onDone.run();
                    }
                });
            }
        });
    }

    private void resetBookProgressAndMoveToPlans(final Book book, final Runnable onDone) {
        Book existing = dbHelper.getBookByUuid(book.getUuid());
        if (existing == null) {
            dbHelper.saveBooks(java.util.Collections.singletonList(book), "to_read");
        }
        dbHelper.resetReadingProgress(book.getUuid());
        book.setPercent(0.0);
        book.setCurrentChapterIndex(0);
        book.setCurrentParagraphIndex(0);
        book.setLastReadTimestamp(0L);
        book.setShelfType("to_read");
        updateTabBadges();

        if (!"catalog".equals(currentShelf) && !"search".equals(currentShelf)) {
            if (!"to_read".equals(currentShelf)) {
                synchronized (currentBooks) {
                    for (int i = 0; i < currentBooks.size(); i++) {
                        if (book.getUuid().equals(currentBooks.get(i).getUuid())) {
                            currentBooks.remove(i);
                            break;
                        }
                    }
                }
                adapter.notifyDataSetChanged();
                updateEmptyState();
                updateShelfFooter();
            } else {
                adapter.notifyDataSetChanged();
                updateShelfFooter();
            }
        }

        apiClient.updateBookShelfState(book.getUuid(), book.getLibraryCardId(), "to_read", new YandexBooksApiClient.ApiCallback<Boolean>() {
            @Override
            public void onSuccess(Boolean result) {}
            @Override
            public void onError(String errorMessage) {}
        });

        EinkNotice.show(MainActivity.this, "Прогресс", "Прогресс книги «" + book.getTitle() + "» сброшен.");
        if (onDone != null) {
            onDone.run();
        }
    }

    private void updateShelfButtonStyles(Button btnReading, Button btnToRead, Button btnDone, String activeShelf) {
        boolean isReading = "reading".equals(activeShelf);
        boolean isToRead = "to_read".equals(activeShelf);
        boolean isDone = "done".equals(activeShelf);

        btnReading.setBackgroundResource(isReading ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
        btnReading.setTextColor(Color.BLACK);
        btnReading.setTypeface(null, isReading ? Typeface.BOLD : Typeface.NORMAL);
        btnReading.setText(isReading ? "• Читаю" : "Читаю");
        btnReading.setPadding(0, 0, 0, 0);
        btnReading.setSingleLine(true);

        btnToRead.setBackgroundResource(isToRead ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
        btnToRead.setTextColor(Color.BLACK);
        btnToRead.setTypeface(null, isToRead ? Typeface.BOLD : Typeface.NORMAL);
        btnToRead.setText(isToRead ? "• В планы" : "В планы");
        btnToRead.setPadding(0, 0, 0, 0);
        btnToRead.setSingleLine(true);

        btnDone.setBackgroundResource(isDone ? R.drawable.btn_eink_primary : R.drawable.btn_eink);
        btnDone.setTextColor(Color.BLACK);
        btnDone.setTypeface(null, isDone ? Typeface.BOLD : Typeface.NORMAL);
        btnDone.setText(isDone ? "• Прочитано" : "Прочитано");
        btnDone.setPadding(0, 0, 0, 0);
        btnDone.setSingleLine(true);
    }

    private void openInOnyxReader(final Book book) {
        openInExternalReaderWithMode(book, appSettings.getReaderMode());
    }

    private void openInExternalReaderWithMode(final Book book, final String readerMode) {
        File epub = cacheManager.ensurePublicEpubFile(book.getUuid(), book.getTitle());
        if (epub != null && epub.exists() && epub.length() > 0) {
            if (book.getPercent() > 0.0 && book.getPercent() < 99.0) {
                dbHelper.ensureCloudSyncBookmark(book.getUuid(), book.getCurrentChapterIndex(), 0, book.getPercent());
            }
            boolean ok = CacheManager.openInSystemReader(this, epub, book.getPercent(), book.getCurrentChapterIndex(), readerMode);
            if (!ok) {
                EinkNotice.show(this, "Ридер", "Внешний ридер не найден. Открываем во встроенном ридере...");
                openInLiteReader(book);
            }
            return;
        }

        // Если книга уже скачана во внутренний кэш, открываем её напрямую через LocalFileProvider (content://)
        if (cacheManager.isBookDownloaded(book.getUuid())) {
            File privateEpub = cacheManager.getEpubFile(book.getUuid());
            if (privateEpub != null && privateEpub.exists() && privateEpub.length() > 0) {
                if (book.getPercent() > 0.0 && book.getPercent() < 99.0) {
                    dbHelper.ensureCloudSyncBookmark(book.getUuid(), book.getCurrentChapterIndex(), 0, book.getPercent());
                }
                if (CacheManager.openInSystemReader(this, privateEpub, book.getPercent(), book.getCurrentChapterIndex(), readerMode)) {
                    return;
                }
            }
            requestRuntimeStoragePermissionsIfNeeded();
            EinkNotice.show(this, "Ридер", "Внешний ридер не найден. Открываем во встроенном ридере...");
            openInLiteReader(book);
            return;
        }

        final Dialog dialog = showEinkLoadingDialog("Яндекс Книги", "Загрузка EPUB в /sdcard/Books/ для внешнего ридера...");

        cacheManager.downloadBookAsync(book.getUuid(), book.getTitle(), new CacheManager.DownloadProgressCallback() {
            @Override
            public void onProgress(int downloadedCount, int totalCount) {}

            @Override
            public void onComplete() {
                dismissEinkLoadingDialog(dialog);
                adapter.notifyDataSetChanged();
                if (book.getPercent() > 0.0 && book.getPercent() < 99.0) {
                    dbHelper.ensureCloudSyncBookmark(book.getUuid(), book.getCurrentChapterIndex(), 0, book.getPercent());
                }
                File readyEpub = cacheManager.ensurePublicEpubFile(book.getUuid(), book.getTitle());
                if (readyEpub == null || !readyEpub.exists()) {
                    readyEpub = cacheManager.getEpubFile(book.getUuid());
                }
                if (readyEpub != null && readyEpub.exists()) {
                    boolean ok = CacheManager.openInSystemReader(MainActivity.this, readyEpub, book.getPercent(), book.getCurrentChapterIndex(), readerMode);
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
                EinkNotice.show(MainActivity.this, "Ошибка скачивания", "Ошибка скачивания: " + message + ".\nОткрываем во встроенном ридере.");
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
                EinkNotice.show(MainActivity.this, "Ошибка книги", "Не удалось открыть книгу: " + message);
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
        if (book.getLinkedAudioUuid() != null && !book.getLinkedAudioUuid().isEmpty()) {
            intent.putExtra("linked_audio_uuid", book.getLinkedAudioUuid());
            intent.putExtra("audio_percent", book.getAudioPercent());
            intent.putExtra("audio_updated_at", book.getAudioUpdatedAt());
        }
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

            applyCardDensityToRow(convertView, cover, title, author, progress, statusTag, readBtn, downloadBtn);

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

    private void applyCardDensityToRow(View row, ImageView cover, TextView title, TextView author,
                                       TextView progress, TextView statusTag, Button readBtn, Button downloadBtn) {
        float density = getResources().getDisplayMetrics().density;
        String mode = appSettings != null ? appSettings.getCardDensityMode()
                : com.onyx.yandexbooks.core.storage.AppSettings.CARD_DENSITY_NORMAL;

        int padV = 9;
        int coverW = 64;
        int coverH = 92;
        float titleSp = 13.5f;
        float authorSp = 11.5f;
        float metaSp = 10.5f;
        int readH = 34;
        int detailsH = 28;
        int btnMinW = 78;
        float btnReadSp = 12f;
        float btnDetSp = 11f;

        if (com.onyx.yandexbooks.core.storage.AppSettings.CARD_DENSITY_COMPACT.equalsIgnoreCase(mode)) {
            padV = 6; coverW = 50; coverH = 72;
            titleSp = 12f; authorSp = 10.5f; metaSp = 9.5f;
            readH = 28; detailsH = 24; btnMinW = 70; btnReadSp = 11f; btnDetSp = 10f;
        } else if (com.onyx.yandexbooks.core.storage.AppSettings.CARD_DENSITY_LARGE.equalsIgnoreCase(mode)) {
            padV = 12; coverW = 76; coverH = 110;
            titleSp = 15f; authorSp = 12.5f; metaSp = 11.5f;
            readH = 40; detailsH = 34; btnMinW = 88; btnReadSp = 13.5f; btnDetSp = 12.5f;
        }

        int pH = Math.round(10 * density);
        int pV = Math.round(padV * density);
        row.setPadding(pH, pV, pH, pV);

        if (cover != null && cover.getLayoutParams() != null) {
            ViewGroup.LayoutParams clp = cover.getLayoutParams();
            int cw = Math.round(coverW * density);
            int ch = Math.round(coverH * density);
            if (clp.width != cw || clp.height != ch) {
                clp.width = cw;
                clp.height = ch;
                cover.setLayoutParams(clp);
            }
        }
        if (title != null) title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, titleSp);
        if (author != null) author.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, authorSp);
        if (progress != null) progress.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, metaSp);
        if (statusTag != null) statusTag.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, metaSp);

        stylePanelButton(readBtn, readH, btnMinW, btnReadSp, 10, density);
        stylePanelButton(downloadBtn, detailsH, btnMinW, btnDetSp, 10, density);
    }

    private void showBookDetailsDialog(final Book book) {
        final boolean isDownloaded = cacheManager.isBookDownloaded(book.getUuid());

        final Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        if (dialog.getWindow() != null) {
            dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setWindowAnimations(0);
        }

        float density = getResources().getDisplayMetrics().density;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.dialog_notice_bg);
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
        final ReadingProgress dbProg = dbHelper.getProgress(book.getUuid());
        final boolean isSynced = (dbProg == null || dbProg.isSyncedWithServer());
        String syncNotice = isSynced ? "" : " • Не синхр.";
        progressView.setText("Прогресс: " + progStr + syncNotice + "  •  Статус: " + (isDownloaded ? "В памяти" : "В сети"));
        progressView.setTextSize(11);
        progressView.setTextColor(Color.BLACK);
        metaLayout.addView(progressView);

        root.addView(metaLayout);

        // 3. Тонкий 1px разделитель
        View divider = new View(this);
        divider.setBackgroundColor(Color.BLACK);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(1, density)));

        // 4. Аннотация книги (ГЛАВНАЯ ЧАСТЬ - занимает более 60% высоты окна!)
        final EinkScrollView scrollView = new EinkScrollView(this);
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

        // Строка 1: Кнопки чтения [ NeoReader ] [ AlReader ] [ Ридер Lite ] бок о бок
        LinearLayout readRow = new LinearLayout(this);
        readRow.setOrientation(LinearLayout.HORIZONTAL);
        readRow.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row1Height));

        Button btnOnyx = new Button(this);
        btnOnyx.setText("NeoReader");
        btnOnyx.setTextSize(10.5f);
        btnOnyx.setTypeface(null, Typeface.BOLD);
        btnOnyx.setTextColor(Color.BLACK);
        btnOnyx.setBackgroundResource(R.drawable.btn_eink_primary);
        btnOnyx.setPadding(0, 0, 0, 0);
        btnOnyx.setSingleLine(true);
        LinearLayout.LayoutParams lpOnyx = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpOnyx.setMargins(0, 0, (int) (3 * density), 0);
        btnOnyx.setLayoutParams(lpOnyx);
        btnOnyx.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                ensureBookOnReadingShelf(book, new Runnable() {
                    @Override
                    public void run() {
                        openInExternalReaderWithMode(book, com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_NEOREADER);
                    }
                });
            }
        });
        readRow.addView(btnOnyx);

        Button btnAlReader = new Button(this);
        btnAlReader.setText("AlReader");
        btnAlReader.setTextSize(10.5f);
        btnAlReader.setTypeface(null, Typeface.BOLD);
        btnAlReader.setTextColor(Color.BLACK);
        btnAlReader.setBackgroundResource(R.drawable.btn_eink);
        btnAlReader.setPadding(0, 0, 0, 0);
        btnAlReader.setSingleLine(true);
        LinearLayout.LayoutParams lpAl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpAl.setMargins((int) (2 * density), 0, (int) (2 * density), 0);
        btnAlReader.setLayoutParams(lpAl);
        btnAlReader.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                ensureBookOnReadingShelf(book, new Runnable() {
                    @Override
                    public void run() {
                        openInExternalReaderWithMode(book, com.onyx.yandexbooks.core.storage.AppSettings.READER_MODE_ALREADER);
                    }
                });
            }
        });
        readRow.addView(btnAlReader);

        Button btnLite = new Button(this);
        btnLite.setText("Ридер Lite");
        btnLite.setTextSize(10.5f);
        btnLite.setTypeface(null, Typeface.BOLD);
        btnLite.setTextColor(Color.BLACK);
        btnLite.setBackgroundResource(R.drawable.btn_eink);
        btnLite.setPadding(0, 0, 0, 0);
        btnLite.setSingleLine(true);
        LinearLayout.LayoutParams lpLite = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpLite.setMargins((int) (3 * density), 0, 0, 0);
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
        btnShelfReading.setPadding(0, 0, 0, 0);
        btnShelfReading.setSingleLine(true);
        LinearLayout.LayoutParams lpSR = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpSR.setMargins(0, 0, (int) (2 * density), 0);
        btnShelfReading.setLayoutParams(lpSR);

        final Button btnShelfToRead = new Button(this);
        btnShelfToRead.setTextSize(11);
        btnShelfToRead.setTypeface(null, Typeface.BOLD);
        btnShelfToRead.setPadding(0, 0, 0, 0);
        btnShelfToRead.setSingleLine(true);
        LinearLayout.LayoutParams lpST = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpST.setMargins((int) (2 * density), 0, (int) (2 * density), 0);
        btnShelfToRead.setLayoutParams(lpST);

        final Button btnShelfDone = new Button(this);
        btnShelfDone.setTextSize(11);
        btnShelfDone.setTypeface(null, Typeface.BOLD);
        btnShelfDone.setPadding(0, 0, 0, 0);
        btnShelfDone.setSingleLine(true);
        LinearLayout.LayoutParams lpSD = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpSD.setMargins((int) (2 * density), 0, 0, 0);
        btnShelfDone.setLayoutParams(lpSD);

        updateShelfButtonStyles(btnShelfReading, btnShelfToRead, btnShelfDone, curShelfRef[0]);

        btnShelfReading.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("reading".equals(curShelfRef[0])) {
                    EinkNotice.show(MainActivity.this, "Полка", "Книга уже в разделе «Читаю».");
                    return;
                }
                btnShelfReading.setEnabled(false);
                moveBookToShelf(book, "reading", new Runnable() {
                    @Override
                    public void run() {
                        btnShelfReading.setEnabled(true);
                        curShelfRef[0] = "reading";
                        updateShelfButtonStyles(btnShelfReading, btnShelfToRead, btnShelfDone, "reading");
                        EinkNotice.show(MainActivity.this, "Полка", "«" + book.getTitle() + "» перенесена в раздел «Читаю».");
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        });

        btnShelfToRead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("to_read".equals(curShelfRef[0])) {
                    EinkNotice.show(MainActivity.this, "Полка", "Книга уже в разделе «В планах».");
                    return;
                }
                btnShelfToRead.setEnabled(false);
                moveBookToShelf(book, "to_read", new Runnable() {
                    @Override
                    public void run() {
                        btnShelfToRead.setEnabled(true);
                        curShelfRef[0] = "to_read";
                        updateShelfButtonStyles(btnShelfReading, btnShelfToRead, btnShelfDone, "to_read");
                        EinkNotice.show(MainActivity.this, "Полка", "«" + book.getTitle() + "» перенесена в раздел «В планах».");
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        });

        btnShelfDone.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if ("done".equals(curShelfRef[0]) && book.getPercent() >= 99.5) {
                    EinkNotice.show(MainActivity.this, "Полка", "Книга уже в разделе «Прочитано».");
                    return;
                }
                btnShelfDone.setEnabled(false);
                book.setPercent(100.0);
                ReadingProgress doneProg = new ReadingProgress(
                        book.getUuid(),
                        100.0,
                        Math.max(0, book.getCurrentChapterIndex()),
                        0,
                        0,
                        System.currentTimeMillis()
                );
                if (book.getLibraryCardId() != null) doneProg.setLibraryCardId(book.getLibraryCardId());
                if (book.getDocumentUuid() != null) doneProg.setDocumentUuid(book.getDocumentUuid());
                dbHelper.saveProgress(doneProg);
                if (syncManager != null) {
                    syncManager.saveAndSyncProgress(doneProg, true, null);
                }
                moveBookToShelf(book, "done", new Runnable() {
                    @Override
                    public void run() {
                        btnShelfDone.setEnabled(true);
                        curShelfRef[0] = "done";
                        updateShelfButtonStyles(btnShelfReading, btnShelfToRead, btnShelfDone, "done");
                        EinkNotice.show(MainActivity.this, "Полка", "«" + book.getTitle() + "» отмечена на 100% и перенесена в «Прочитано».");
                        adapter.notifyDataSetChanged();
                    }
                });
            }
        });

        shelfRow.addView(btnShelfReading);
        shelfRow.addView(btnShelfToRead);
        shelfRow.addView(btnShelfDone);
        buttonContainer.addView(shelfRow);

        // Строка 3: [ Скачать / Удалить ] и [ Сбросить ]
        LinearLayout actionRow1 = new LinearLayout(this);
        actionRow1.setOrientation(LinearLayout.HORIZONTAL);
        actionRow1.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lpActionRow1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row2Height);
        lpActionRow1.setMargins(0, (int) (4 * density), 0, 0);
        actionRow1.setLayoutParams(lpActionRow1);

        final Button btnDownloadOrDelete = new Button(this);
        btnDownloadOrDelete.setTextSize(11);
        btnDownloadOrDelete.setTypeface(null, Typeface.BOLD);
        btnDownloadOrDelete.setTextColor(Color.BLACK);
        btnDownloadOrDelete.setBackgroundResource(R.drawable.btn_eink);
        btnDownloadOrDelete.setPadding(0, 0, 0, 0);
        btnDownloadOrDelete.setSingleLine(true);
        LinearLayout.LayoutParams lpDown = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpDown.setMargins(0, 0, (int) (2 * density), 0);
        btnDownloadOrDelete.setLayoutParams(lpDown);

        if (!isDownloaded) {
            btnDownloadOrDelete.setText("Скачать");
            btnDownloadOrDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    btnDownloadOrDelete.setEnabled(false);
                    btnDownloadOrDelete.setText("Загрузка...");
                    ensureBookSavedForDownload(book, new Runnable() {
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
                                            EinkNotice.show(MainActivity.this, "Память", "Книга «" + book.getTitle() + "» успешно сохранена в памяти устройства!");
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
                                            EinkNotice.show(MainActivity.this, "Ошибка скачивания", "Ошибка скачивания: " + message);
                                            btnDownloadOrDelete.setEnabled(true);
                                            btnDownloadOrDelete.setText("Скачать");
                                        }
                                    });
                                }
                            });
                        }
                    });
                }
            });
        } else {
            btnDownloadOrDelete.setText("Удалить");
            btnDownloadOrDelete.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    EinkNotice.showConfirm(
                            MainActivity.this,
                            "Удалить файл",
                            "Удалить загруженный файл «" + book.getTitle() + "» из памяти устройства?\n(Книга на полке и прогресс сохранятся)",
                            "Удалить",
                            new Runnable() {
                                @Override
                                public void run() {
                                    cacheManager.deleteBookCache(book.getUuid(), book.getTitle());
                                    adapter.notifyDataSetChanged();
                                    EinkNotice.show(MainActivity.this, "Память", "Файл книги «" + book.getTitle() + "» удален из памяти устройства.");
                                    dialog.dismiss();
                                    EpdController.requestFullRefresh(MainActivity.this, booksListView);
                                }
                            }
                    );
                }
            });
        }
        actionRow1.addView(btnDownloadOrDelete);

        Button btnResetProgress = new Button(this);
        btnResetProgress.setText("Сбросить");
        btnResetProgress.setTextSize(11);
        btnResetProgress.setTypeface(null, Typeface.BOLD);
        btnResetProgress.setTextColor(Color.BLACK);
        btnResetProgress.setBackgroundResource(R.drawable.btn_eink);
        btnResetProgress.setPadding(0, 0, 0, 0);
        btnResetProgress.setSingleLine(true);
        LinearLayout.LayoutParams lpReset = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpReset.setMargins((int) (2 * density), 0, 0, 0);
        btnResetProgress.setLayoutParams(lpReset);
        btnResetProgress.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EinkNotice.showConfirm(
                        MainActivity.this,
                        "Сбросить чтение",
                        "Сбросить прогресс чтения книги «" + book.getTitle() + "» до 0% и перенести её в раздел «В планах»?",
                        "Сбросить",
                        new Runnable() {
                            @Override
                            public void run() {
                                resetBookProgressAndMoveToPlans(book, new Runnable() {
                                    @Override
                                    public void run() {
                                        dialog.dismiss();
                                        EpdController.requestFullRefresh(MainActivity.this, booksListView);
                                    }
                                });
                            }
                        }
                );
            }
        });
        actionRow1.addView(btnResetProgress);
        buttonContainer.addView(actionRow1);

        if (!isSynced && dbProg != null) {
            Button btnSyncDirect = new Button(this);
            btnSyncDirect.setText("Синхронизировать прогресс с облаком");
            btnSyncDirect.setTextSize(11);
            btnSyncDirect.setTypeface(null, Typeface.BOLD);
            btnSyncDirect.setTextColor(Color.BLACK);
            btnSyncDirect.setBackgroundResource(R.drawable.btn_eink_primary);
            btnSyncDirect.setPadding(0, 0, 0, 0);
            btnSyncDirect.setSingleLine(true);
            LinearLayout.LayoutParams lpSync = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row2Height);
            lpSync.setMargins(0, (int) (4 * density), 0, 0);
            btnSyncDirect.setLayoutParams(lpSync);
            final ReadingProgress finalProg = dbProg;
            btnSyncDirect.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    syncManager.saveAndSyncProgress(finalProg, true, new SyncManager.ConflictResolutionCallback() {
                        @Override
                        public void onProgressUpdated(ReadingProgress progress) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    EinkNotice.show(MainActivity.this, "Синхронизация", "Прогресс книги «" + book.getTitle() + "» синхронизирован с облаком!");
                                    dialog.dismiss();
                                    loadBooks(currentShelf);
                                }
                            });
                        }
                        @Override
                        public void onConflictDetected(ReadingProgress local, ReadingProgress remote) {}
                    });
                }
            });
            buttonContainer.addView(btnSyncDirect);
        }

        // Строка 4: [ Убрать ] и [ Закрыть ]
        LinearLayout actionRow2 = new LinearLayout(this);
        actionRow2.setOrientation(LinearLayout.HORIZONTAL);
        actionRow2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lpActionRow2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row2Height);
        lpActionRow2.setMargins(0, (int) (4 * density), 0, 0);
        actionRow2.setLayoutParams(lpActionRow2);

        Button btnRemoveShelf = new Button(this);
        btnRemoveShelf.setText("Убрать");
        btnRemoveShelf.setTextSize(11);
        btnRemoveShelf.setTypeface(null, Typeface.BOLD);
        btnRemoveShelf.setTextColor(Color.BLACK);
        btnRemoveShelf.setBackgroundResource(R.drawable.btn_eink);
        btnRemoveShelf.setPadding(0, 0, 0, 0);
        btnRemoveShelf.setSingleLine(true);
        LinearLayout.LayoutParams lpRemove = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpRemove.setMargins(0, 0, (int) (2 * density), 0);
        btnRemoveShelf.setLayoutParams(lpRemove);
        btnRemoveShelf.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                EinkNotice.showConfirm(
                        MainActivity.this,
                        "Убрать с полки",
                        "Убрать книгу «" + book.getTitle() + "» из библиотеки и удалить с устройства?",
                        "Убрать",
                        new Runnable() {
                            @Override
                            public void run() {
                                removeBookFromLibraryAndDevice(book, new Runnable() {
                                    @Override
                                    public void run() {
                                        dialog.dismiss();
                                        EpdController.requestFullRefresh(MainActivity.this, booksListView);
                                    }
                                });
                            }
                        }
                );
            }
        });
        actionRow2.addView(btnRemoveShelf);

        Button btnClose = new Button(this);
        btnClose.setText("Закрыть");
        btnClose.setTextSize(11);
        btnClose.setTypeface(null, Typeface.BOLD);
        btnClose.setTextColor(Color.BLACK);
        btnClose.setBackgroundResource(R.drawable.btn_eink);
        btnClose.setPadding(0, 0, 0, 0);
        btnClose.setSingleLine(true);
        LinearLayout.LayoutParams lpClose = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
        lpClose.setMargins((int) (2 * density), 0, 0, 0);
        btnClose.setLayoutParams(lpClose);
        btnClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        actionRow2.addView(btnClose);
        buttonContainer.addView(actionRow2);

        root.addView(buttonContainer);

        dialog.setContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final int targetW = (int) (screenWidth * 0.92);
        final int targetH = (int) (screenHeight * 0.85);

        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                        || keyCode == KeyEvent.KEYCODE_PAGE_UP || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                        || keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                        boolean down = (keyCode == KeyEvent.KEYCODE_PAGE_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_DOWN);
                        scrollView.pageScrollEink(down);
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
                    dialog.getWindow().setLayout(targetW, targetH);
                }
                EpdController.requestFullRefresh(MainActivity.this, root);
            }
        });

        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                EpdController.requestFullRefresh(MainActivity.this, null);
            }
        });

        dialog.show();

        // Гарантируем размер окна диалога 92% ширины и 85% высоты дисплея
        if (dialog.getWindow() != null) {
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
