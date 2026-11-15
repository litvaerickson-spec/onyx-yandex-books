# Правила разработки и верификации проекта `01_onyx_yandex_books`

1. **Обязательная проверка на реальных Android-эмуляторах в Docker:**
   После любых изменений в приложении, сборке APK или OTA-механизме перед завершением задачи обязательно собрать APK (`bash build_apk.sh`) и прогнать E2E-проверку в изолированном Docker-контейнере, включая целевые legacy-версии Android:
   ```bash
   # Основные старые целевые ОС Onyx Boox (Dalvik + Conscrypt TLS)
   AVD_NAME=onyx_darwin1_api17 bash tools/emulator/run_emulator_e2e.sh
   AVD_NAME=onyx_darwin5_api19 bash tools/emulator/run_emulator_e2e.sh

   # Android 6.0 / 9.0 (ART + DownloadProvider / DocumentsUI)
   AVD_NAME=onyx_darwin5_api23 bash tools/emulator/run_emulator_e2e.sh
   AVD_NAME=onyx_darwin7_api28 bash tools/emulator/run_emulator_e2e.sh
   ```
2. **Изоляция хоста:** не устанавливать образы эмуляторов и AVD в хостовую ОС; все запуски эмулятора выполняются через `--rm` контейнер `onyx-yandex-books-emulator:latest`.
3. **Запрет авто-публикации на GitHub:** не выполнять `git push` и не создавать релизы на GitHub без прямого явного указания пользователя.
4. **Терминология:** никогда не использовать слово «читалка» и его производные.
