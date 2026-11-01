package com.onyx.yandexbooks.core.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.onyx.yandexbooks.R;
import com.onyx.yandexbooks.core.eink.EinkScrollView;
import com.onyx.yandexbooks.core.eink.EpdController;

/**
 * Высококонтрастные E-Ink уведомления с обязательной кнопкой подтверждения «ОК».
 * Никогда не исчезают автоматически, гарантируют максимальную контрастность (черный текст
 * на чисто белом фоне) и полное отсутствие серых подложек и дизеринга (отключение FLAG_DIM_BEHIND).
 */
public final class EinkNotice {

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static Dialog sCurrentDialog = null;

    private static class MaxHeightScrollView extends EinkScrollView {
        private final int maxHeight;

        MaxHeightScrollView(Context context, int maxHeight) {
            super(context);
            this.maxHeight = maxHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int hMode = MeasureSpec.getMode(heightMeasureSpec);
            int hSize = MeasureSpec.getSize(heightMeasureSpec);
            if (hMode == MeasureSpec.UNSPECIFIED || hSize > maxHeight) {
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private EinkNotice() {}

    /**
     * Показать стандартное уведомление с заголовком «Уведомление» и кнопкой «ОК».
     */
    public static void show(Context context, String message) {
        show(context, "Уведомление", message, "ОК", null);
    }

    /**
     * Показать уведомление с заданным заголовком и кнопкой «ОК».
     */
    public static void show(Context context, String title, String message) {
        show(context, title, message, "ОК", null);
    }

    /**
     * Показать высококонтрастное диалоговое окно подтверждения с двумя кнопками (Подтвердить / Отмена)
     * без серого затемнения фона (без FLAG_DIM_BEHIND).
     */
    public static void showConfirm(Context context, String title, String message, String confirmText, Runnable onConfirm) {
        showConfirm(context, title, message, confirmText, "Отмена", onConfirm, null);
    }

    /**
     * Показать высококонтрастное диалоговое окно с двумя действиями.
     */
    public static void showConfirm(final Context context, final String title, final String message,
                                   final String confirmText, final String cancelText,
                                   final Runnable onConfirm, final Runnable onCancel) {
        if (context == null || message == null || message.trim().isEmpty()) {
            return;
        }

        if (Looper.myLooper() != Looper.getMainLooper()) {
            sMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    showConfirm(context, title, message, confirmText, cancelText, onConfirm, onCancel);
                }
            });
            return;
        }

        final Activity activity = findActivity(context);
        if (activity == null || activity.isFinishing()) {
            return;
        }

        if (sCurrentDialog != null && sCurrentDialog.isShowing()) {
            try {
                sCurrentDialog.dismiss();
            } catch (Exception ignored) {}
            sCurrentDialog = null;
        }

        try {
            final Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            Window window = dialog.getWindow();
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setWindowAnimations(0);
            }

            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            float density = dm.density;

            final LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.dialog_notice_bg);
            int padH = (int) (16 * density);
            int padV = (int) (14 * density);
            root.setPadding(padH, padV, padH, padV);

            String displayTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : "Подтверждение";
            TextView titleView = new TextView(activity);
            titleView.setText(displayTitle);
            titleView.setTextSize(15);
            titleView.setTypeface(null, Typeface.BOLD);
            titleView.setTextColor(Color.BLACK);
            titleView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            root.addView(titleView);

            View divider = new View(activity);
            divider.setBackgroundColor(Color.BLACK);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(2, 2 * density));
            divLp.setMargins(0, (int) (6 * density), 0, (int) (8 * density));
            root.addView(divider, divLp);

            final int maxScrollH = (int) (dm.heightPixels * 0.55);
            EinkScrollView scrollView = new MaxHeightScrollView(activity, maxScrollH);
            scrollView.setFadingEdgeLength(0);
            scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
            root.addView(scrollView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView messageView = new TextView(activity);
            messageView.setText(message.trim());
            messageView.setTextSize(13.5f);
            messageView.setTypeface(null, Typeface.NORMAL);
            messageView.setTextColor(Color.BLACK);
            messageView.setLineSpacing(3f, 1.25f);
            scrollView.addView(messageView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout btnRow = new LinearLayout(activity);
            btnRow.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (40 * density));
            rowLp.setMargins(0, (int) (12 * density), 0, 0);
            btnRow.setLayoutParams(rowLp);

            Button btnCancel = new Button(activity);
            btnCancel.setText((cancelText != null && !cancelText.trim().isEmpty()) ? cancelText.trim() : "Отмена");
            btnCancel.setTextSize(12.5f);
            btnCancel.setTypeface(null, Typeface.BOLD);
            btnCancel.setTextColor(Color.BLACK);
            btnCancel.setBackgroundResource(R.drawable.btn_eink);
            btnCancel.setSingleLine(true);
            btnCancel.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams lpCancel = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
            lpCancel.setMargins(0, 0, (int) (4 * density), 0);
            btnCancel.setLayoutParams(lpCancel);
            btnCancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    if (onCancel != null) {
                        try { onCancel.run(); } catch (Exception ignored) {}
                    }
                }
            });

            Button btnConfirm = new Button(activity);
            btnConfirm.setText((confirmText != null && !confirmText.trim().isEmpty()) ? confirmText.trim() : "ОК");
            btnConfirm.setTextSize(12.5f);
            btnConfirm.setTypeface(null, Typeface.BOLD);
            btnConfirm.setTextColor(Color.BLACK);
            btnConfirm.setBackgroundResource(R.drawable.btn_eink_primary);
            btnConfirm.setSingleLine(true);
            btnConfirm.setPadding(0, 0, 0, 0);
            LinearLayout.LayoutParams lpConfirm = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.0f);
            lpConfirm.setMargins((int) (4 * density), 0, 0, 0);
            btnConfirm.setLayoutParams(lpConfirm);
            btnConfirm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                    if (onConfirm != null) {
                        try { onConfirm.run(); } catch (Exception ignored) {}
                    }
                }
            });

            btnRow.addView(btnCancel);
            btnRow.addView(btnConfirm);
            root.addView(btnRow);

            final int targetW = Math.min((int) (dm.widthPixels * 0.90), (int) (440 * density));
            dialog.setContentView(root, new ViewGroup.LayoutParams(targetW, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Аппаратные клавиши (Back = Cancel, Enter/Dpad Center = Confirm)
            dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
                @Override
                public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (keyCode == KeyEvent.KEYCODE_BACK) {
                            dialog.dismiss();
                            if (onCancel != null) {
                                try { onCancel.run(); } catch (Exception ignored) {}
                            }
                            return true;
                        } else if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                            dialog.dismiss();
                            if (onConfirm != null) {
                                try { onConfirm.run(); } catch (Exception ignored) {}
                            }
                            return true;
                        }
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
                    EpdController.requestFullRefresh(activity, root);
                }
            });

            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    if (sCurrentDialog == dialog) {
                        sCurrentDialog = null;
                    }
                    EpdController.requestFullRefresh(activity, null);
                }
            });

            sCurrentDialog = dialog;
            dialog.show();

            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(targetW, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        } catch (Exception e) {
            android.util.Log.e("EinkNotice", "Failed to show EinkNotice confirm dialog", e);
        }
    }

    /**
     * Показать уведомление с настраиваемым заголовком, текстом, надписью на кнопке и колбэком.
     */
    public static void show(final Context context, final String title, final String message, final String buttonText, final Runnable onDismiss) {
        if (context == null || message == null || message.trim().isEmpty()) {
            return;
        }

        if (Looper.myLooper() != Looper.getMainLooper()) {
            sMainHandler.post(new Runnable() {
                @Override
                public void run() {
                    show(context, title, message, buttonText, onDismiss);
                }
            });
            return;
        }

        final Activity activity = findActivity(context);
        if (activity == null || activity.isFinishing()) {
            return;
        }

        // Если уже есть открытый диалог уведомления, аккуратно закрываем его перед показом нового
        if (sCurrentDialog != null && sCurrentDialog.isShowing()) {
            try {
                sCurrentDialog.dismiss();
            } catch (Exception ignored) {}
            sCurrentDialog = null;
        }

        try {
            final Dialog dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

            Window window = dialog.getWindow();
            if (window != null) {
                // Отключаем системное затемнение фона (FLAG_DIM_BEHIND), чтобы на E-Ink не появлялся грязно-серый дизеринг
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setWindowAnimations(0);
            }

            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            float density = dm.density;

            // Корневой контейнер с чисто белым фоном и четкой 2.5dp черной рамкой
            final LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundResource(R.drawable.dialog_notice_bg);
            int padH = (int) (16 * density);
            int padV = (int) (14 * density);
            root.setPadding(padH, padV, padH, padV);

            // Заголовок
            String displayTitle = (title != null && !title.trim().isEmpty()) ? title.trim() : "Уведомление";
            TextView titleView = new TextView(activity);
            titleView.setText(displayTitle);
            titleView.setTextSize(15);
            titleView.setTypeface(null, Typeface.BOLD);
            titleView.setTextColor(Color.BLACK);
            titleView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            root.addView(titleView);

            // Разделительная черная линия 2dp
            View divider = new View(activity);
            divider.setBackgroundColor(Color.BLACK);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) Math.max(2, 2 * density));
            divLp.setMargins(0, (int) (6 * density), 0, (int) (8 * density));
            root.addView(divider, divLp);

            // Текст сообщения (в EinkScrollView с ограничением по высоте до 55% экрана)
            final int maxScrollH = (int) (dm.heightPixels * 0.55);
            EinkScrollView scrollView = new MaxHeightScrollView(activity, maxScrollH);
            scrollView.setFadingEdgeLength(0);
            scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);
            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            scrollView.setLayoutParams(scrollLp);

            TextView messageView = new TextView(activity);
            messageView.setText(message.trim());
            messageView.setTextSize(13.5f);
            messageView.setTypeface(null, Typeface.NORMAL);
            messageView.setTextColor(Color.BLACK);
            messageView.setLineSpacing(3f, 1.25f);
            scrollView.addView(messageView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(scrollView);

            // Кнопка подтверждения «ОК»
            String btnText = (buttonText != null && !buttonText.trim().isEmpty()) ? buttonText.trim() : "ОК";
            Button btnOk = new Button(activity);
            btnOk.setText(btnText);
            btnOk.setTextSize(13);
            btnOk.setTypeface(null, Typeface.BOLD);
            btnOk.setTextColor(Color.BLACK);
            btnOk.setBackgroundResource(R.drawable.btn_eink_primary);
            btnOk.setSingleLine(true);
            btnOk.setPadding(0, 0, 0, 0);

            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (40 * density));
            btnLp.setMargins(0, (int) (12 * density), 0, 0);
            btnOk.setLayoutParams(btnLp);

            btnOk.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    dialog.dismiss();
                }
            });
            root.addView(btnOk);

            final int targetW = Math.min((int) (dm.widthPixels * 0.90), (int) (440 * density));
            dialog.setContentView(root, new ViewGroup.LayoutParams(targetW, ViewGroup.LayoutParams.WRAP_CONTENT));

            // Аппаратные клавиши (Back, Enter, Dpad Center)
            dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
                @Override
                public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ENTER
                                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                            dialog.dismiss();
                            return true;
                        }
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
                    EpdController.requestFullRefresh(activity, root);
                }
            });

            dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface d) {
                    if (sCurrentDialog == dialog) {
                        sCurrentDialog = null;
                    }
                    EpdController.requestFullRefresh(activity, null);
                    if (onDismiss != null) {
                        try {
                            onDismiss.run();
                        } catch (Exception ignored) {}
                    }
                }
            });

            sCurrentDialog = dialog;
            dialog.show();

            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(targetW, ViewGroup.LayoutParams.WRAP_CONTENT);
            }

        } catch (Exception e) {
            android.util.Log.e("EinkNotice", "Failed to show EinkNotice dialog", e);
        }
    }

    private static Activity findActivity(Context context) {
        if (context instanceof Activity) {
            return (Activity) context;
        } else if (context instanceof ContextWrapper) {
            return findActivity(((ContextWrapper) context).getBaseContext());
        }
        return null;
    }
}
