# Изолированный Android-эмулятор в Docker (Onyx Boox Darwin 1 / 3 / 5 / 6 / 7)

Все SDK, системные образы Android и AVD находятся **строго внутри Docker-образа** (`onyx-yandex-books-emulator:latest`). На хостовую систему ничего не устанавливается, а тестовые контейнеры запускаются с `--rm`.

## Профили устройств (все 5 обязательны к прогону для каждой версии)

1. **`onyx_darwin1_api17`** — Android 4.2.2 Jelly Bean (API 17), `758x1024`, `212 dpi`, `x86`, Dalvik (Darwin 1 / RK3026).
2. **`onyx_darwin5_api19`** — Android 4.4.4 KitKat (API 19), `758x1024`, `212 dpi`, `x86`, Dalvik (Darwin 3 / 5).
3. **`onyx_darwin6_api19`** — Android 4.4.4 KitKat (API 19), `1072x1448`, `300 dpi`, `x86`, Dalvik (Darwin 6).
4. **`onyx_darwin5_api23`** — Android 6.0 Marshmallow (API 23), `758x1024`, `212 dpi`, `x86_64`, ART.
5. **`onyx_darwin7_api28`** — Android 9.0 Pie (API 28), `1072x1448`, `300 dpi`, `x86_64`, ART (Darwin 7+).

## Сборка образа (выполняется один раз или при изменении Dockerfile)

```bash
docker build --network=host -t onyx-yandex-books-emulator:latest tools/emulator
```

## Запуск E2E-проверки APK

### Полный прогон по всем 5 эмуляторам (рекомендуется перед релизом / коммитом)

```bash
bash tools/emulator/run_all_emulators.sh
# Или с явным путем к APK / без остановки при первой ошибке:
bash tools/emulator/run_all_emulators.sh --no-fail-fast /path/to/yandex-books-lite-vX.Y.Z-beta.apk
```

### Одиночный прогон выбранного профиля

```bash
AVD_NAME=onyx_darwin1_api17 bash tools/emulator/run_emulator_e2e.sh
AVD_NAME=onyx_darwin5_api19 bash tools/emulator/run_emulator_e2e.sh
AVD_NAME=onyx_darwin6_api19 bash tools/emulator/run_emulator_e2e.sh
AVD_NAME=onyx_darwin5_api23 bash tools/emulator/run_emulator_e2e.sh
AVD_NAME=onyx_darwin7_api28 bash tools/emulator/run_emulator_e2e.sh
```

Скрипт:
1. Поднимает headless KVM-эмулятор в `--rm` контейнере с `--network=host` (таймаут 240с).
2. Устанавливает актуальный `yandex-books-lite-v*.apk`.
3. Проверяет права манифеста и runtime-разрешения (`DOWNLOAD_WITHOUT_NOTIFICATION`, `WRITE_EXTERNAL_STORAGE`).
4. Проверяет холодный запуск без авторизации (`AuthActivity`).
5. Сидирует настройки (`restorecon -RF` для SELinux на API 23+) и запускает сценарий отката в UI: `MainActivity` -> Меню -> «Архив версий и откат ПО» -> `1.6.1-beta` -> «Сохранить копию и скачать» -> «Показать в Загрузках».
6. Проверяет запись в `DownloadProvider`, бэкап в `/sdcard/Books/YandexBooks/backup/` и визуально через `uiautomator dump` подтверждает, что в системном окне «Загрузки» (`DocumentsUI` / `DownloadList`) отображается APK (а не «Ничего нет»), сохраняя скриншоты в `docs/emulator_verify/<avd>/`.

Подробное руководство для ИИ-агента: [`.agents/skills/onyx-emulator-testing/SKILL.md`](../../.agents/skills/onyx-emulator-testing/SKILL.md).
