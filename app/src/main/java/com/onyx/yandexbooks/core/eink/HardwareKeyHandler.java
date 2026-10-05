package com.onyx.yandexbooks.core.eink;

import android.view.KeyEvent;

/**
 * Обработчик физических кнопок корпуса Onyx Boox Darwin 3, 5, 6.
 * Перехватывает боковые клавиши листания и регулировки громкости.
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
        if (event.isLongPress()) {
            if (isPageKey(keyCode)) {
                listener.onLongPressRefresh();
                return true;
            }
        }

        // Подавление аппаратного автоповтора при удерживании кнопки (защита от пролистывания пачками на E-Ink)
        if (event.getRepeatCount() > 0) {
            return true;
        }

        switch (keyCode) {
            // Листание вперед (правая боковая клавиша)
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                listener.onPageForward();
                return true;

            // Листание назад (левая боковая клавиша)
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
                listener.onPageBackward();
                return true;
        }

        return false;
    }

    private boolean isPageKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
               keyCode == KeyEvent.KEYCODE_PAGE_UP ||
               keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
               keyCode == KeyEvent.KEYCODE_VOLUME_UP;
    }
}
