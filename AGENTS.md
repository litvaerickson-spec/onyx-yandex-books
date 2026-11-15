# Правила разработки и верификации проекта `01_onyx_yandex_books`

Эти правила действуют **строго для проекта `01_onyx_yandex_books`** (`Onyx Boox · Яндекс Книги Lite`).

---

## 1. Обязательное тестирование КАЖДОЙ версии APK на ВСЕХ 5 Docker-эмуляторах

Перед завершением любой задачи, затрагивающей Java-код, ресурсы UI, `AndroidManifest.xml`, `build.gradle`, `build_apk.sh`, OTA-обновление/откат, хранилище или сборку нового релизного/бета APK:

1. **Собери подписанный APK:** `bash build_apk.sh` (с инкрементом SemVer и `versionCode`).
2. **Прогони полный сквозной E2E-тест на ВСЕХ 5 целевых профилях Android в изолированном Docker-контейнере** (`onyx-yandex-books-emulator:latest`):
   - `onyx_darwin1_api17` — **Android 4.2.2 Jelly Bean (API 17)**, `758x1024`, `212 dpi`, Dalvik (Onyx Boox Darwin 1 / RK3026)
   - `onyx_darwin5_api19` — **Android 4.4.4 KitKat (API 19)**, `758x1024`, `212 dpi`, Dalvik (Onyx Boox Darwin 3, 5)
   - `onyx_darwin6_api19` — **Android 4.4.4 KitKat (API 19)**, `1072x1448`, `300 dpi`, Dalvik (Onyx Boox Darwin 6)
   - `onyx_darwin5_api23` — **Android 6.0 Marshmallow (API 23)**, `758x1024`, `212 dpi`, `x86_64`, ART
   - `onyx_darwin7_api28` — **Android 9.0 Pie (API 28)**, `1072x1448`, `300 dpi`, `x86_64`, ART
3. **Команда прогона всей матрицы (строго последовательно из-за KVM RAM):**
   ```bash
   bash tools/emulator/run_all_emulators.sh
   ```
   *(Одиночный профиль при отладке конкретного бага: `AVD_NAME=<имя_avd> bash tools/emulator/run_emulator_e2e.sh`)*
4. **Визуальный контроль:** после прохождения проверь итоговые скриншоты в `docs/emulator_verify/<avd>/` (через `read_file`) и убедись, что `logcat AndroidRuntime` не содержит `FATAL EXCEPTION`.
5. **Расширение E2E при новых функциях:** если в новой версии добавлена новая ключевая функциональность (новый экран, настройка, парсер, миграция БД, новый шаг OTA), добавь её проверку в [tools/emulator/e2e_verify.py](tools/emulator/e2e_verify.py), чтобы она тестировалась автоматически на всех 5 ОС.

Подробный пошаговый runbook для агента вынесен в локальный навык проекта: [.agents/skills/onyx-emulator-testing/SKILL.md](.agents/skills/onyx-emulator-testing/SKILL.md) и [tools/emulator/README.md](tools/emulator/README.md).

---

## 2. Изоляция хост-системы (Zero Host Clutter)

- **Категорически запрещено** скачивать Android emulator, system-images или создавать AVD в хостовую систему (`~/.android`, `/opt/android-sdk`).
- Весь эмуляторный стек живет **только** внутри Docker-образа `onyx-yandex-books-emulator:latest` (определен в [tools/emulator/Dockerfile](tools/emulator/Dockerfile)).
- Любой контейнер запускается строго с флагом `--rm`, `--network=host` и пробросом `/dev/kvm`.

---

## 3. Прочие обязательные ограничения проекта

1. **Запрет авто-публикации на GitHub:** никогда не выполнять `git push` и не создавать релизы/теги на удаленном GitHub без прямого явного указания пользователя (`git commit` и локальный `git tag` — только локально).
2. **Терминология:** никогда не использовать слово «читалка» и любые его производные в коде, ресурсах, тестах и документации (кроме формулировки самого запрета).
3. **Весь тестовый комплект:** помимо реальных эмуляторов, перед локальным коммитом запускать `python3 tools/verify_core_logic.py` (и профильные тесты в `tools/`), `sha256sum -c SHA256SUMS` и `apksigner verify --verbose <apk>`.
