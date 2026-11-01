package com.onyx.yandexbooks.core.eink;

import android.view.KeyEvent;

/**
 * Обработчик физических кнопок корпуса Onyx Boox (Darwin 1, 3, 5, 6, Livingstone, Poke, Page, Leaf, Palma и др.).
 * Перехватывает боковые клавиши листания, регулировки громкости и навигации.
 */
public class HardwareKeyHandler {

    public interface OnKeyActionListener {
        void onPageForward();
        void onPageBackward();
        void onLongPressRefresh();
    }

    private final OnKeyActionListener listener;

    public HardwareKeyHandler(OnKeyActionListener listener) {
        this.listener = listener;
    }

    public boolean handleKeyDown(int keyCode, KeyEvent event) {
        if (listener == null) return false;

        // Долгое нажатие клавиши листания -> принудительное полное обновление E-Ink
        if (event != null && event.isLongPress()) {
            if (isPageKey(keyCode, event)) {
                listener.onLongPressRefresh();
                return true;
            }
        }

        // Подавление аппаратного автоповтора при удерживании кнопки (защита от пролистывания пачками на E-Ink)
        if (event != null && event.getRepeatCount() > 0) {
            return true;
        }

        // Безопасная проверка скан-кода (если метод доступен в среде рантайма)
        int scanCode = 0;
        if (event != null) {
            try {
                scanCode = event.getScanCode();
            } catch (Throwable ignored) {}
        }
        if (scanCode == 93 || scanCode == 109) { // 93 / KEY_PAGEDOWN (109)
            listener.onPageForward();
            return true;
        } else if (scanCode == 92 || scanCode == 104) { // 92 / KEY_PAGEUP (104)
            listener.onPageBackward();
            return true;
        }

        switch (keyCode) {
            // Листание вперед (правая / нижняя боковая клавиша)
            case KeyEvent.KEYCODE_PAGE_DOWN: // 93
            case KeyEvent.KEYCODE_VOLUME_DOWN: // 25
            case KeyEvent.KEYCODE_DPAD_RIGHT: // 22
            case KeyEvent.KEYCODE_DPAD_DOWN: // 20
            case 167: // KEYCODE_CHANNEL_DOWN
            case 261: // KEYCODE_NAVIGATE_NEXT
                listener.onPageForward();
                return true;

            // Листание назад (левая / верхняя боковая клавиша)
            case KeyEvent.KEYCODE_PAGE_UP: // 92
            case KeyEvent.KEYCODE_VOLUME_UP: // 24
            case KeyEvent.KEYCODE_DPAD_LEFT: // 21
            case KeyEvent.KEYCODE_DPAD_UP: // 19
            case 166: // KEYCODE_CHANNEL_UP
            case 260: // KEYCODE_NAVIGATE_PREVIOUS
                listener.onPageBackward();
                return true;
        }

        return false;
    }

    public boolean handleKeyUp(int keyCode, KeyEvent event) {
        return isPageKey(keyCode, event);
    }

    public static boolean isPageKey(int keyCode) {
        return isPageKey(keyCode, null);
    }

    public static boolean isPageKey(int keyCode, KeyEvent event) {
        int scanCode = 0;
        if (event != null) {
            try {
                scanCode = event.getScanCode();
            } catch (Throwable ignored) {}
        }
        if (scanCode == 92 || scanCode == 93 || scanCode == 104 || scanCode == 109) {
            return true;
        }
        return keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
               keyCode == KeyEvent.KEYCODE_PAGE_UP ||
               keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
               keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
               keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
               keyCode == KeyEvent.KEYCODE_DPAD_UP ||
               keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
               keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
               keyCode == 92 ||
               keyCode == 93 ||
               keyCode == 166 ||
               keyCode == 167 ||
               keyCode == 260 ||
               keyCode == 261;
    }
}
