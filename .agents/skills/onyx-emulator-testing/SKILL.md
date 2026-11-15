---
name: onyx-emulator-testing
description: >-
  Project-specific runbook for building, running, inspecting, and extending the isolated Docker Android Emulator E2E test suite across all 5 Onyx Boox OS profiles (API 17, 19x2, 23, 28) in 01_onyx_yandex_books. Activate whenever testing a new APK version, debugging Android-version-specific UI/DownloadManager/ABI bugs, or updating E2E scenarios.
---

# Инструкция для агента: E2E-тестирование APK на всех Docker-эмуляторах Android

## 1. Где все лежит (Single Source of Truth)

Все файлы изолированного эмуляторного стенда находятся строго внутри `tools/emulator/`:

| Компонент | Путь | Назначение |
| :--- | :--- | :--- |
| Docker-образ и 5 AVD | `tools/emulator/Dockerfile` | Debian Bookworm + cmdline-tools `11076708` + `emulator` `37.2.13.0` + 5 системных образов и AVD |
| Точка входа контейнера | `tools/emulator/entrypoint.sh` | Запуск KVM-эмулятора (`-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot`), ожидание `sys.boot_completed=1`, отключение анимаций, авто-очистка по `EXIT` |
| Одиночный прогон | `tools/emulator/run_emulator_e2e.sh` | Запуск `--rm --network=host --device /dev/kvm --shm-size=1g` для выбранного `AVD_NAME` и конкретного APK |
| Матричный прогон (все 5 ОС) | `tools/emulator/run_all_emulators.sh` | Последовательный прогон одного APK через все 5 AVD с итоговой таблицей PASS/FAIL |
| Сценарий E2E-проверки | `tools/emulator/e2e_verify.py` | Python-драйвер ADB + UIAutomator (`uiautomator dump`) без сторонних pip-зависимостей |
| Compose-конфигурация | `tools/emulator/docker-compose.yml` | Референс параметров `--device /dev/kvm`, `network_mode: host`, `shm_size: 1g` |
| Артефакты и скриншоты | `docs/emulator_verify/<avd>/` | Выходные PNG и XML (игнорируются через `.gitignore`, не попадают в Git) |

---

## 2. Матрица 5 целевых эмуляторов (почему нужен каждый)

| `AVD_NAME` | Версия Android / API | Экран / DPI | VM / ABI | Целевые устройства и что ловит |
| :--- | :--- | :--- | :--- | :--- |
| `onyx_darwin1_api17` | **4.2.2 Jelly Bean (API 17)** | `758x1024` / `212` | Dalvik / `x86` | Onyx Boox Darwin 1 (RK3026): проверка отсутствия вызовов API 19+, старого `DownloadList`, `uiautomator` без `resource-id` |
| `onyx_darwin5_api19` | **4.4.4 KitKat (API 19)** | `758x1024` / `212` | Dalvik / `x86` | Onyx Boox Darwin 3, 5: основная legacy-платформа, проверка Conscrypt TLS 1.2 + `DocumentsActivity` |
| `onyx_darwin6_api19` | **4.4.4 KitKat (API 19)** | `1072x1448` / `300` | Dalvik / `x86` | Onyx Boox Darwin 6: масштабирование верстки под 300 dpi + `KitKat DocumentsUI` |
| `onyx_darwin5_api23` | **6.0 Marshmallow (API 23)** | `758x1024` / `212` | ART / `x86_64` | Runtime-разрешения (`WRITE_EXTERNAL_STORAGE`), SELinux `enforcing`, 64-битные `.so` ABI (`x86_64`) |
| `onyx_darwin7_api28` | **9.0 Pie (API 28)** | `1072x1448` / `300` | ART / `x86_64` | Onyx Boox Darwin 7 / более новые ОС: modern `DocumentsUI` (`ViewDownloadsActivity`), отсутствие краша `FilesActivity` |

---

## 3. Пошаговый алгоритм работы агента для каждой версии

### Шаг 1. Проверь наличие Docker-образа (пересобирай только при правке `tools/emulator/Dockerfile`)
```bash
docker image inspect onyx-yandex-books-emulator:latest >/dev/null 2>&1 || \
  docker build --network=host -t onyx-yandex-books-emulator:latest tools/emulator
```
> **Важно:** флаг `--network=host` обязателен на данном хосте, иначе при сборке `apt` и сеть внутри Docker bridge могут быть недоступны.

### Шаг 2. Собери актуальный подписанный APK
```bash
bash build_apk.sh
```

### Шаг 3. Запусти прогон по ВСЕМ 5 эмуляторам строго последовательно
**Никогда не запускай 2+ KVM-эмулятора параллельно** (каждый AVD резервирует `1536 MiB` RAM + `1 GiB /dev/shm`). Используй готовый оркестратор:
```bash
bash tools/emulator/run_all_emulators.sh
```
Для точечной отладки конкретного профиля или повторного прогона без fail-fast:
```bash
AVD_NAME=onyx_darwin7_api28 bash tools/emulator/run_emulator_e2e.sh /path/to/yandex-books-lite-vX.Y.Z-beta.apk
# или прогнать все без остановки на первой ошибке:
bash tools/emulator/run_all_emulators.sh --no-fail-fast
```

### Шаг 4. Проконтролируй логи и скриншоты глазами
1. Убедись, что скрипт завершился с кодом `0` и для всех 5 AVD вывел `PASS` и `Zero FATAL EXCEPTION in AndroidRuntime logcat: OK`.
2. Просмотри через `read_file` ключевые PNG в `docs/emulator_verify/<avd>/`:
   - `*_01_cold_launch.png` — стартовый экран (`AuthActivity` или запрос разрешений);
   - `*_05_confirm_downgrade.png` — форматирование диалога (заголовки, буллиты, отсутствие сырого Markdown);
   - `*_07_system_downloads_ui.png` — открытое системное окно «Загрузки» с видимым скачанным APK (никакого пустого экрана «Ничего нет»).

### Шаг 5. Интерактивная ручная отладка (если тест упал и нужно заглянуть внутрь)
Можно запустить одноразовый shell внутри того же `--rm` контейнера без запуска тестового скрипта:
```bash
docker run --rm -it --network=host --device /dev/kvm:/dev/kvm --shm-size=1g \
  -e AVD_NAME=onyx_darwin5_api19 \
  -v "$(pwd):/workspace:ro" \
  onyx-yandex-books-emulator:latest bash
# Внутри контейнера: adb devices, adb logcat, adb shell dumpsys ...
```

---

## 4. Критические технические нюансы этого окружения (грабли, которые уже решены)

При доработке `tools/emulator/e2e_verify.py` или отладке новых сценариев учитывай особенности реальных старых Android:
1. **API 17–23 (`adbd` stdin bug):** `adb shell "cat > file"` с передачей через `stdin` может зависнуть на EOF. Всегда используй `adb push <local> <remote>`.
2. **API 23+ (SELinux `enforcing`):** после `adb push` в `/data/data/<pkg>/shared_prefs/` обязательно выполняй `chown` + `chmod` + **`restorecon -RF`** (иначе приложение не сможет прочитать сидированный `yandex_auth_prefs.xml` и вернется на `AuthActivity`).
3. **API 17 (`uiautomator dump`):** на Android 4.2.2 системный дамп не содержит атрибута `resource-id`. Всегда держи fallback-поиск узлов UI по `text` / `content-desc` и `clickable="true"`.
4. **API 17–19 (нет `exec-out` и `head`):** не используй `adb exec-out screencap -p` (повредится PNG переводом `\n` → `\r\n`) и не вызывай `head` в `adb shell`. Делай `adb shell screencap -p /data/local/tmp/... && adb pull ...`, а фильтрацию вывода выполняй в Python.
5. **QEMU сеть (`eth0` 10.0.2.15):** команда `adb shell svc wifi/data disable` на эмуляторе не отключает виртуальный NAT `10.0.2.15` — это учтено в тестах токена.
6. **Чистота репозитория и хоста:** никогда не убирай `docs/emulator_verify/` и `tools/emulator/artifacts/` из `.gitignore`, а новые тестовые артефакты не коммить в Git и не складывай в `/tmp` или на рабочий стол.
