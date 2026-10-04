package com.onyx.yandexbooks.core.eink;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Method;

/**
 * Контроллер экрана E-Ink для устройств Onyx Boox Darwin (3, 5, 6).
 * Обеспечивает сброс артефактов (ghosting) и управление режимами обновления экрана.
 */
public class EpdController {

    private static final String TAG = "EpdController";
    private static final String ONYX_FULL_REFRESH_ACTION = "android.intent.action.FULL_SCREEN_REFRESH";

    /**
     * Запрос аппаратного полного обновления дисплея E-Ink (GC16 Full Refresh).
     */
    public static void requestFullRefresh(Context context, View view) {
        // 1. Попытка вызвать специализированный API Onyx SDK через рефлексию
        boolean sdkSuccess = false;
        try {
            Class<?> epdControllerClass = Class.forName("com.onyx.android.sdk.api.device.epd.EpdController");
            Method resetMethod = epdControllerClass.getMethod("refreshScreen", View.class, String.class);
            resetMethod.invoke(null, view, "GC");
            sdkSuccess = true;
            Log.d(TAG, "Full refresh triggered via Onyx SDK EpdController");
        } catch (Throwable ignored) {
            // Onyx SDK не доступен, пробуем системный Broadcast
        }

        // 2. Системный Broadcast Onyx Boox
        if (!sdkSuccess && context != null) {
            try {
                Intent refreshIntent = new Intent(ONYX_FULL_REFRESH_ACTION);
                context.sendBroadcast(refreshIntent);
                Log.d(TAG, "Full refresh triggered via Onyx broadcast intent");
            } catch (Throwable t) {
                Log.w(TAG, "Failed to broadcast full refresh intent", t);
            }
        }

        // 3. Fallback: программная перерисовка View
        if (view != null) {
            view.invalidate();
        }
    }
}
