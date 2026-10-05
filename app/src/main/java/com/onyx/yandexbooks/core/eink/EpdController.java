package com.onyx.yandexbooks.core.eink;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Контроллер экрана E-Ink для устройств Onyx Boox Darwin (3, 5, 6) и всех поколений Onyx.
 * Обеспечивает аппаратный сброс артефактов (ghosting) через специализированные SDK,
 * Rockchip/Freescale системные интенты и принудительную перерисовку.
 */
public class EpdController {

    private static final String TAG = "EpdController";

    private static final String[] ONYX_REFRESH_ACTIONS = new String[] {
            "action.screen.refresh",                         // Rockchip RK3026/RK3128 (Darwin 3, Darwin 5)
            "android.intent.action.FULL_SCREEN_REFRESH",     // Onyx Boox Android 4.4 standard
            "onyx.intent.action.FULL_SCREEN_REFRESH",        // Onyx Boox alternate intent
            "com.onyx.android.action.SCREEN_REFRESH",        // Onyx Boox SDK broadcast
            "android.intent.action.SCREEN_REFRESH",          // Generic E-Ink framework broadcast
            "com.onyx.intent.action.REFRESH_SCREEN"          // Freescale i.MX6 intent
    };

    private static final String[] EPD_CONTROLLER_CLASS_NAMES = new String[] {
            "com.onyx.android.sdk.device.EpdController",          // Android 4.2 / 4.4 Onyx Darwin legacy SDK
            "com.onyx.android.sdk.api.device.epd.EpdController",  // Android 9+ modern Onyx SDK
            "com.onyx.android.sdk.device.EpdDevice"               // Intermediate Onyx SDK
    };

    /**
     * Запрос аппаратного полного обновления дисплея E-Ink (GC16 Full Refresh).
     */
    public static void requestFullRefresh(Context context, View view) {
        // 1. Попытка вызвать методы Onyx SDK через рефлексию
        boolean sdkInvoked = tryOnyxSdkRefresh(view);

        // 2. Системные широковещательные интенты Onyx Boox (гарантируют вспышку контроллера экрана)
        if (context != null) {
            sendOnyxBroadcasts(context);
        }

        // 3. Fallback: принудительная инвалидация View и корневого элемента
        if (view != null) {
            try {
                view.postInvalidate();
                View root = view.getRootView();
                if (root != null && root != view) {
                    root.postInvalidate();
                }
            } catch (Throwable ignored) {}
        }
    }

    private static boolean tryOnyxSdkRefresh(View view) {
        for (String className : EPD_CONTROLLER_CLASS_NAMES) {
            try {
                Class<?> clazz = Class.forName(className);

                // А) Поиск метода refreshScreen(View, ...)
                for (Method m : clazz.getMethods()) {
                    if ("refreshScreen".equals(m.getName())) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 1 && params[0].isAssignableFrom(View.class)) {
                            m.invoke(null, view);
                            Log.d(TAG, "Triggered " + className + ".refreshScreen(View)");
                            return true;
                        } else if (params.length == 2 && params[0].isAssignableFrom(View.class)) {
                            // Второй параметр может быть String, int или UpdateMode enum
                            if (params[1] == String.class) {
                                m.invoke(null, view, "GC");
                                Log.d(TAG, "Triggered " + className + ".refreshScreen(View, 'GC')");
                                return true;
                            } else if (params[1].isEnum()) {
                                Object enumConst = findGcEnumConstant(params[1]);
                                if (enumConst != null) {
                                    m.invoke(null, view, enumConst);
                                    Log.d(TAG, "Triggered " + className + ".refreshScreen(View, " + enumConst + ")");
                                    return true;
                                }
                            } else if (params[1] == int.class) {
                                m.invoke(null, view, 1); // 1 = GC mode
                                Log.d(TAG, "Triggered " + className + ".refreshScreen(View, 1)");
                                return true;
                            }
                        }
                    } else if ("invalidate".equals(m.getName())) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 2 && params[0].isAssignableFrom(View.class) && params[1].isEnum()) {
                            Object enumConst = findGcEnumConstant(params[1]);
                            if (enumConst != null) {
                                m.invoke(null, view, enumConst);
                                Log.d(TAG, "Triggered " + className + ".invalidate(View, " + enumConst + ")");
                                return true;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Класс не найден на данном устройстве, пробуем следующий
            }
        }
        return false;
    }

    private static Object findGcEnumConstant(Class<?> enumClass) {
        try {
            Object[] constants = enumClass.getEnumConstants();
            if (constants != null) {
                for (Object c : constants) {
                    String name = c.toString();
                    if ("GC".equalsIgnoreCase(name) || "GC16".equalsIgnoreCase(name) || "CLEAR".equalsIgnoreCase(name)) {
                        return c;
                    }
                }
                if (constants.length > 0) {
                    return constants[0];
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void sendOnyxBroadcasts(Context context) {
        for (String action : ONYX_REFRESH_ACTIONS) {
            try {
                Intent intent = new Intent(action);
                intent.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                context.sendBroadcast(intent);
            } catch (Throwable ignored) {}
        }
    }
}
