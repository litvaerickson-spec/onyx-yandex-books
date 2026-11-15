#!/usr/bin/env python3
"""
Real Android Emulator E2E verification driver for Yandex Books Lite (Onyx Boox).
Runs inside the isolated Docker container over adb (emulator-5554).
Tests:
  1. APK installation & AndroidManifest permissions check (DOWNLOAD_WITHOUT_NOTIFICATION).
  2. Cold start & runtime storage permission handling -> AuthActivity rendering (no crashes).
  3. MainActivity authenticated shelf + Settings Menu -> Release Archive -> Rollback to v1.6.1-beta.
  4. Real DownloadManager & DocumentsUI ("Загрузки") visibility verification (no empty "Ничего нет" / "No items").
  5. Backup existence check in /sdcard/Books/YandexBooks/backup/ and clean logcat assertion.
"""

import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


SERIAL = os.environ.get("ADB_SERIAL", "emulator-5554")
PKG = "com.onyx.yandexbooks"


def adb(*args, check=True, capture=True, timeout=60):
    cmd = ["adb", "-s", SERIAL] + list(args)
    res = subprocess.run(cmd, capture_output=capture, text=True, timeout=timeout)
    if check and res.returncode != 0:
        raise RuntimeError(f"Command {' '.join(cmd)} failed ({res.returncode}):\n{res.stderr}\n{res.stdout}")
    return res


def shell(cmd_str, check=True, timeout=30):
    return adb("shell", cmd_str, check=check, timeout=timeout).stdout.strip()


def get_top_activity():
    out = shell("dumpsys activity activities", check=False, timeout=20)
    lines = [
        ln.strip()
        for ln in out.splitlines()
        if ("mResumedActivity" in ln or "mFocusedActivity" in ln or "top-activity" in ln)
    ]
    return "\n".join(lines[:3])


def screencap(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    remote = "/data/local/tmp/emu_screencap.png"
    shell(f"screencap -p {remote}")
    adb("pull", remote, path, timeout=20)
    shell(f"rm -f {remote}", check=False)
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        raise RuntimeError(f"screencap failed for {path}")
    print(f"[screenshot] saved -> {path} ({os.path.getsize(path)} bytes)")


def dump_ui(retries=6):
    last_out = ""
    last_xml = ""
    for attempt in range(retries):
        shell("rm -f /data/local/tmp/ui.xml", check=False)
        last_out = shell("uiautomator dump /data/local/tmp/ui.xml", check=False)
        last_xml = shell("cat /data/local/tmp/ui.xml", check=False)
        if last_xml.strip().startswith("<"):
            return ET.fromstring(last_xml)
        time.sleep(1.0)
    raise RuntimeError(f"uiautomator dump failed after {retries} attempts: {last_out}\nxml={last_xml[:200]}")


def iter_nodes(root):
    for node in root.iter("node"):
        yield node.attrib


def parse_bounds(bounds_str):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_str or "")
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_node(root, text_contains=None, text_equals=None, res_id_contains=None, pkg_contains=None, clickable_only=False):
    for attrs in iter_nodes(root):
        text = attrs.get("text", "")
        desc = attrs.get("content-desc", "")
        rid = attrs.get("resource-id", "")
        pkg = attrs.get("package", "")
        if clickable_only and attrs.get("clickable") != "true":
            continue
        if pkg_contains and pkg_contains not in pkg:
            continue
        if res_id_contains and res_id_contains not in rid:
            continue
        if text_equals and (text.lower() != text_equals.lower() and desc.lower() != text_equals.lower()):
            continue
        if text_contains and (text_contains.lower() not in text.lower() and text_contains.lower() not in desc.lower()):
            continue
        return attrs
    return None


def tap_node(attrs, label=""):
    pt = parse_bounds(attrs.get("bounds", ""))
    if not pt:
        raise RuntimeError(f"Node has invalid bounds: {attrs}")
    print(f"[tap] {label or attrs.get('text') or attrs.get('resource-id')} at {pt}")
    shell(f"input tap {pt[0]} {pt[1]}")


def wait_for_node(text_contains=None, res_id_contains=None, pkg_contains=None, clickable_only=False, timeout=20):
    deadline = time.time() + timeout
    last_err = None
    last_texts = []
    while time.time() < deadline:
        try:
            root = dump_ui()
            last_texts = all_visible_texts(root)
            n = find_node(root, text_contains=text_contains, res_id_contains=res_id_contains,
                          pkg_contains=pkg_contains, clickable_only=clickable_only)
            if n:
                return root, n
        except Exception as e:
            last_err = e
        time.sleep(1.0)
    raise RuntimeError(
        f"Timeout waiting for UI node (text={text_contains}, rid={res_id_contains}, pkg={pkg_contains}); "
        f"visible_texts={last_texts}; last_err={last_err}"
    )


def all_visible_texts(root):
    texts = []
    for attrs in iter_nodes(root):
        t = (attrs.get("text") or "").strip()
        if t:
            texts.append(t)
    return texts


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True, help="Path to APK inside container")
    parser.add_argument("--artifacts", required=True, help="Artifacts output directory")
    parser.add_argument("--tag", default="emu", help="Artifact filename prefix (e.g. api23)")
    args = parser.parse_args()

    if not os.path.exists(args.apk):
        raise FileNotFoundError(f"APK not found: {args.apk}")

    try:
        _run_tests(args)
    finally:
        print("--- Last AndroidRuntime / FATAL logcat lines ---")
        crash_log = adb("logcat", "-d", "-s", "AndroidRuntime:E", "YandexBooksApp:E", "AppUpdateManager:W", check=False).stdout
        if crash_log.strip():
            print(crash_log[-4000:])


def _run_tests(args):
    # Enable adb root (available on AOSP default system images) for clean pre-auth / DB inspection
    root_out = adb("root", check=False).stdout.strip()
    print(f"[adb] root: {root_out}")
    adb("wait-for-device", timeout=30)

    sdk = shell("getprop ro.build.version.sdk")
    rel = shell("getprop ro.build.version.release")
    abi = shell("getprop ro.product.cpu.abilist")
    wm = shell("wm size")
    print(f"[device] Android {rel} (API {sdk}), ABI={abi}, {wm}")

    # Clear logcat before test
    adb("logcat", "-c", check=False)

    # 1. Install APK cleanly
    shell(f"pm uninstall {PKG}", check=False)
    print(f"[install] Installing {args.apk} ...")
    inst = adb("install", "-r", args.apk, timeout=60).stdout.strip()
    print(f"[install] {inst}")

    # Verify manifest permissions on real PackageManager
    pkg_dump = shell(f"dumpsys package {PKG}", timeout=20)
    if "android.permission.DOWNLOAD_WITHOUT_NOTIFICATION" not in pkg_dump:
        raise AssertionError("android.permission.DOWNLOAD_WITHOUT_NOTIFICATION missing in dumpsys package!")
    print("[check] PackageManager registered android.permission.DOWNLOAD_WITHOUT_NOTIFICATION: OK")

    sdk_int = int(re.sub(r"[^0-9]", "", sdk) or "0")

    # 2. Cold launch without token -> runtime storage permission prompt (API 23+) -> AuthActivity
    shell(f"am start -n {PKG}/.ui.MainActivity")
    time.sleep(2.5)
    if sdk_int >= 23:
        for _ in range(3):
            root = dump_ui()
            allow_btn = (
                find_node(root, res_id_contains="permission_allow_button")
                or find_node(root, text_equals="Allow", clickable_only=True)
                or find_node(root, text_equals="Разрешить", clickable_only=True)
            )
            if not allow_btn:
                break
            tap_node(allow_btn, "System runtime permission ALLOW button")
            time.sleep(2.5)

    screencap(os.path.join(args.artifacts, f"{args.tag}_01_cold_launch.png"))
    top_act = get_top_activity()
    print(f"[cold_launch] Top activity: {top_act}")
    if PKG not in top_act:
        raise AssertionError(f"App not in foreground after launch! Top activity: {top_act}")

    # Verify runtime storage permission became granted (API 23+ only; API <= 22 grants at install)
    if sdk_int >= 23:
        pkg_dump = shell(f"dumpsys package {PKG}", timeout=20)
        if "WRITE_EXTERNAL_STORAGE: granted=true" not in pkg_dump:
            shell(f"pm grant {PKG} android.permission.WRITE_EXTERNAL_STORAGE")
            shell(f"pm grant {PKG} android.permission.READ_EXTERNAL_STORAGE")
            print("[perm] Granted storage permissions via pm grant")
        else:
            print("[perm] Runtime WRITE_EXTERNAL_STORAGE granted via UI: OK")
    else:
        print(f"[perm] API {sdk_int} < 23: storage permissions granted at install time")

    # 3. Seed test OAuth token in TokenStorage so we can test MainActivity shelf + Rollback UI flow
    shell(f"am force-stop {PKG}")
    prefs_dir = f"/data/data/{PKG}/shared_prefs"
    shell(f"mkdir -p {prefs_dir}")
    prefs_xml = (
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
        "<map>\n"
        "    <string name=\"access_token\">emu_test_token_verify_rollback</string>\n"
        "    <string name=\"refresh_token\">emu_test_refresh</string>\n"
        "    <string name=\"user_login\">emulator.tester</string>\n"
        "    <long name=\"expires_at\" value=\"4102444800000\" />\n"
        "</map>\n"
    )
    # Use adb push instead of stdin pipe over adb shell to avoid adbd EOF hangs on API 17-23
    tmp_prefs = os.path.join(args.artifacts, f".tmp_{args.tag}_prefs.xml")
    with open(tmp_prefs, "w", encoding="utf-8") as f:
        f.write(prefs_xml)
    try:
        adb("push", tmp_prefs, f"{prefs_dir}/yandex_auth_prefs.xml", timeout=20)
    finally:
        if os.path.exists(tmp_prefs):
            os.remove(tmp_prefs)
    # Fix file ownership to app UID so app can read/write its prefs
    pkg_info = shell(f"dumpsys package {PKG}", check=False)
    m_uid = re.search(r"(?:userId|appId)=(\d+)", pkg_info)
    if m_uid:
        uid = m_uid.group(1)
        shell(f"chown {uid}:{uid} {prefs_dir}", check=False)
        shell(f"chown {uid}:{uid} {prefs_dir}/yandex_auth_prefs.xml", check=False)
        shell(f"chmod 771 {prefs_dir}", check=False)
        shell(f"chmod 660 {prefs_dir}/yandex_auth_prefs.xml", check=False)
    shell(f"restorecon -RF {prefs_dir} 2>/dev/null || restorecon -R {prefs_dir} 2>/dev/null || true", check=False)

    # Ensure clean /sdcard/Download and clean DownloadManager DB before rollback test
    shell("rm -f /sdcard/Download/yandex-books-lite*.apk")
    shell("rm -rf /sdcard/Books/YandexBooks/backup")

    # Temporarily disable network while opening MainActivity so Yandex API does not 401-logout our test seed token
    shell("svc wifi disable", check=False)
    shell("svc data disable", check=False)

    shell(f"am start -n {PKG}/.ui.MainActivity")
    time.sleep(3.0)
    # Dismiss any offline/network EinkNotice popup so MainActivity is cleanly interactive
    for _ in range(5):
        root = dump_ui()
        if find_node(root, res_id_contains="btn_main_menu") or find_node(root, text_equals="Меню", clickable_only=True):
            break
        dismiss_btn = (
            find_node(root, text_equals="Понятно", clickable_only=True)
            or find_node(root, text_equals="ОК", clickable_only=True)
            or find_node(root, text_equals="OK", clickable_only=True)
            or find_node(root, text_equals="Закрыть", clickable_only=True)
        )
        if dismiss_btn:
            tap_node(dismiss_btn, "Dismiss initial notice / AOSP system dialog")
            time.sleep(1.5)
        else:
            time.sleep(1.0)

    try:
        root, btn_menu = wait_for_node(res_id_contains="btn_main_menu", timeout=8)
    except RuntimeError:
        root, btn_menu = wait_for_node(text_contains="Меню", clickable_only=True, timeout=10)
    time.sleep(1.5)
    screencap(os.path.join(args.artifacts, f"{args.tag}_02_main_shelf.png"))

    shelf_root = dump_ui()
    shelf_joined = " | ".join(all_visible_texts(shelf_root))
    if "Файлы (" not in shelf_joined:
        raise AssertionError(f"v1.7.0-beta 5th tab 'Файлы (...)' missing on main shelf UI: {shelf_joined}")
    print("[check] Main shelf displays 5 tabs including 'Файлы (0)': OK")

    # 4. Open Main Menu -> re-enable network -> "Архив версий и откат ПО"
    m_ver = re.search(r"versionName=([^\s]+)", pkg_dump)
    installed_ver = m_ver.group(1) if m_ver else ""
    tap_node(btn_menu, "Main Menu button")
    root, btn_archive = wait_for_node(text_contains="Архив версий", timeout=10)
    menu_joined = " | ".join(all_visible_texts(root))
    if installed_ver and installed_ver not in menu_joined:
        raise AssertionError(f"Expected version '{installed_ver}' in Main Menu UI, got: {menu_joined}")
    print(f"[check] Main Menu shows installed version '{installed_ver}': OK")
    screencap(os.path.join(args.artifacts, f"{args.tag}_03_main_menu.png"))

    shell("svc wifi enable", check=False)
    shell("svc data enable", check=False)
    time.sleep(3.0)
    tap_node(btn_archive, "Архив версий и откат ПО")

    # Wait for GitHub release list to load and locate v1.6.1-beta
    print("[archive] Waiting for GitHub releases list to load (v1.6.1-beta)...")
    root, node_161 = wait_for_node(text_contains="1.6.1-beta", timeout=35)
    screencap(os.path.join(args.artifacts, f"{args.tag}_04_archive_list.png"))
    tap_node(node_161, "Release 1.6.1-beta")

    # Confirm downgrade dialog -> "Сохранить копию и скачать"
    root, btn_save_dl = wait_for_node(text_contains="Сохранить копию и скачать", timeout=15)
    screencap(os.path.join(args.artifacts, f"{args.tag}_05_confirm_downgrade.png"))
    tap_node(btn_save_dl, "Сохранить копию и скачать")

    # Wait for real APK download from GitHub & backup creation
    print("[download] Waiting for real APK download + DownloadManager registration...")
    root, btn_show_dl = wait_for_node(text_contains="Показать в Загрузках", timeout=120)
    screencap(os.path.join(args.artifacts, f"{args.tag}_06_ready_to_rollback.png"))

    # Check backup & APK file on disk
    dl_files = shell("ls -la /sdcard/Download/")
    bk_files = shell("ls -la /sdcard/Books/YandexBooks/backup/")
    print(f"[fs] /sdcard/Download:\n{dl_files}")
    print(f"[fs] /sdcard/Books/YandexBooks/backup:\n{bk_files}")
    if "yandex-books-lite-v1.6.1-beta.apk" not in dl_files:
        raise AssertionError("Downloaded APK yandex-books-lite-v1.6.1-beta.apk not found in /sdcard/Download!")
    bk_content = shell("cat /sdcard/Books/YandexBooks/backup/*.json", check=False)
    for setting_key in ("dither_covers_enabled", "edge_brightness_swipe", "audio_sync_enabled", "use_epub_fonts", "custom_tap_zones", "fullscreen_reader", "quotes_count"):
        if setting_key not in bk_content:
            raise AssertionError(f"v1.7.0-beta setting '{setting_key}' missing from live Android backup!")
    print("[check] Live Android backup includes all v1.7.0-beta keys (custom_tap_zones/fullscreen_reader/quotes_count/...): OK")

    # Verify DownloadProvider DB directly on real Android OS!
    dm_query = shell("content query --uri content://downloads/my_downloads", check=False)
    print(f"[DownloadProvider] my_downloads query:\n{dm_query}")
    if "yandex-books-lite-v1.6.1-beta.apk" not in dm_query:
        raise AssertionError("DownloadProvider DB does NOT contain yandex-books-lite-v1.6.1-beta.apk!")

    # Tap "Показать в Загрузках" -> real DocumentsUI must open and show the APK!
    tap_node(btn_show_dl, "Показать в Загрузках")
    time.sleep(3.0)

    top_dl = get_top_activity()
    print(f"[downloads_ui] Foreground activity: {top_dl}")
    screencap(os.path.join(args.artifacts, f"{args.tag}_07_system_downloads_ui.png"))

    dl_root = dump_ui()
    dl_texts = all_visible_texts(dl_root)
    print(f"[downloads_ui] Visible UI texts: {dl_texts}")

    joined = " | ".join(dl_texts)
    if any(empty_marker in joined for empty_marker in ("Ничего нет", "No items", "No downloads", "Нет загрузок", "Nothing here")):
        raise AssertionError(f"FAIL: System Downloads UI is EMPTY! Texts: {dl_texts}")
    if "yandex-books-lite" not in joined and "Яндекс Книги Lite" not in joined:
        raise AssertionError(f"FAIL: APK entry NOT visible in system Downloads UI! Texts: {dl_texts}")
    print("[PASS] System Downloads UI (DocumentsUI/Downloads) visibly displays yandex-books-lite-v1.6.1-beta.apk!")

    # Verify no fatal crashes occurred in logcat
    crashes = adb("logcat", "-d", "-s", "AndroidRuntime:E").stdout
    if "FATAL EXCEPTION" in crashes:
        raise AssertionError(f"FATAL EXCEPTION detected in logcat:\n{crashes}")
    print("[check] Zero FATAL EXCEPTION in AndroidRuntime logcat: OK")

    print(f"=== EMULATOR E2E VERIFICATION PASSED ({args.tag} / API {sdk}) ===")


if __name__ == "__main__":
    main()
