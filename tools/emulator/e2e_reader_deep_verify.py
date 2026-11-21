#!/usr/bin/env python3
"""
Deep Independent E2E Verification Driver for Yandex Books Lite on Real Android Emulators.
Tests real EPUB book rendering, mathematical page counting, font/style/UI-scale changes,
night mode pixel luminance, 3x3 tap zones, fullscreen, landscape panel scrolling,
TOC/Bookmarks/Long-press Quotes + Markdown export, and live SQLite sync persistence.
"""

import argparse
import os
import re
import sqlite3
import struct
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile
import zlib


SERIAL = os.environ.get("ADB_SERIAL", "emulator-5554")
PKG = "com.onyx.yandexbooks"
TEST_BOOK_UUID = "test-book-v170-real"
TEST_USER_BOOK_UUID = "test-file-v170-real"


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


def get_screen_size():
    out = shell("wm size", check=False)
    m = re.search(r"(\d+)x(\d+)", out)
    if m:
        return int(m.group(1)), int(m.group(2))
    return 758, 1024


def screencap(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    remote = "/data/local/tmp/emu_deep_cap.png"
    shell(f"screencap -p {remote}")
    adb("pull", remote, path, timeout=25)
    shell(f"rm -f {remote}", check=False)
    if not os.path.exists(path) or os.path.getsize(path) == 0:
        raise RuntimeError(f"screencap failed for {path}")
    print(f"[screenshot] saved -> {path} ({os.path.getsize(path)} bytes)")


def png_center_mean_luma(png_path):
    """Pure-Python PNG decoder computing mean luma (0..255) of central 50% crop."""
    with open(png_path, "rb") as f:
        data = f.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError("Not a valid PNG file")
    pos = 8
    width = height = bit_depth = color_type = 0
    idat = bytearray()
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if ctype == b"IHDR":
            width, height, bit_depth, color_type = struct.unpack(">IIBB", chunk[:10])
        elif ctype == b"IDAT":
            idat.extend(chunk)
        elif ctype == b"IEND":
            break
    if bit_depth != 8 or color_type not in (2, 6):
        return 128.0
    bpp = 3 if color_type == 2 else 4
    stride = width * bpp
    raw = zlib.decompress(bytes(idat))
    prev = bytearray(stride)
    rows = []
    idx = 0
    for y in range(height):
        ftype = raw[idx]
        idx += 1
        scan = bytearray(raw[idx:idx + stride])
        idx += stride
        if ftype == 1:
            for i in range(bpp, stride):
                scan[i] = (scan[i] + scan[i - bpp]) & 0xFF
        elif ftype == 2:
            for i in range(stride):
                scan[i] = (scan[i] + prev[i]) & 0xFF
        elif ftype == 3:
            for i in range(stride):
                a = scan[i - bpp] if i >= bpp else 0
                scan[i] = (scan[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:
            for i in range(stride):
                a = scan[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                scan[i] = (scan[i] + pr) & 0xFF
        prev = scan
        rows.append(scan)

    x0, x1 = width // 4, (width * 3) // 4
    y0, y1 = height // 4, (height * 3) // 4
    total = 0.0
    count = 0
    for y in range(y0, y1, 4):
        row = rows[y]
        for x in range(x0, x1, 4):
            off = x * bpp
            r, g, b = row[off], row[off + 1], row[off + 2]
            total += 0.299 * r + 0.587 * g + 0.114 * b
            count += 1
    return total / max(1, count)


def dump_ui(retries=6):
    last_out = ""
    last_xml = ""
    for _ in range(retries):
        shell("rm -f /data/local/tmp/ui_deep.xml", check=False)
        last_out = shell("uiautomator dump /data/local/tmp/ui_deep.xml", check=False)
        last_xml = shell("cat /data/local/tmp/ui_deep.xml", check=False)
        if last_xml.strip().startswith("<"):
            return ET.fromstring(last_xml)
        time.sleep(0.9)
    raise RuntimeError(f"uiautomator dump failed after {retries} attempts: {last_out}\nxml={last_xml[:200]}")


def iter_nodes(root):
    for node in root.iter("node"):
        yield node.attrib


def parse_rect(bounds_str):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", bounds_str or "")
    if not m:
        return None
    return tuple(map(int, m.groups()))


def parse_bounds(bounds_str):
    r = parse_rect(bounds_str)
    if not r:
        return None
    return (r[0] + r[2]) // 2, (r[1] + r[3]) // 2


def find_node(root, text_contains=None, text_equals=None, res_id_contains=None, clickable_only=False):
    for attrs in iter_nodes(root):
        text = attrs.get("text", "")
        desc = attrs.get("content-desc", "")
        rid = attrs.get("resource-id", "")
        if clickable_only and attrs.get("clickable") != "true":
            continue
        if res_id_contains and res_id_contains not in rid:
            continue
        if text_equals and (text.lower() != text_equals.lower() and desc.lower() != text_equals.lower()):
            continue
        if text_contains and (text_contains.lower() not in text.lower() and text_contains.lower() not in desc.lower()):
            continue
        return attrs
    return None


def all_visible_texts(root):
    return [(a.get("text") or "").strip() for a in iter_nodes(root) if (a.get("text") or "").strip()]


def tap_node(attrs, label=""):
    pt = parse_bounds(attrs.get("bounds", ""))
    if not pt:
        raise RuntimeError(f"Invalid bounds for tap: {attrs}")
    print(f"[tap] {label or attrs.get('text') or attrs.get('resource-id')} at {pt}")
    shell(f"input tap {pt[0]} {pt[1]}")


def wait_for_node(text_contains=None, text_equals=None, res_id_contains=None, clickable_only=False, timeout=20):
    deadline = time.time() + timeout
    last_texts = []
    while time.time() < deadline:
        try:
            root = dump_ui()
            last_texts = all_visible_texts(root)
            n = find_node(root, text_contains=text_contains, text_equals=text_equals,
                          res_id_contains=res_id_contains, clickable_only=clickable_only)
            if n:
                return root, n
        except Exception:
            pass
        time.sleep(0.8)
    raise RuntimeError(
        f"Timeout waiting for node (contains={text_contains}, equals={text_equals}, rid={res_id_contains}); "
        f"visible={last_texts}"
    )


def dismiss_notices(max_tries=4):
    for _ in range(max_tries):
        root = dump_ui()
        btn = (
            find_node(root, text_equals="Понятно", clickable_only=True)
            or find_node(root, text_equals="ОК", clickable_only=True)
            or find_node(root, text_equals="OK", clickable_only=True)
            or find_node(root, text_equals="Закрыть", clickable_only=True)
        )
        if not btn:
            return root
        tap_node(btn, f"Dismiss notice ({btn.get('text')})")
        time.sleep(0.9)
    return dump_ui()


def create_rich_test_epub(out_path):
    """Create a valid 3-chapter EPUB with Cyrillic prose, headings, styles, blockquotes, links & footnotes."""
    para = (
        "Электронные чернила обеспечивают высокий контраст и минимальную нагрузку на глаза при длительном чтении "
        "художественной и технической литературы на устройствах с дисплеями Carta Plus. Настоящий тестовый абзац "
        "предназначен для проверки математического пагинатора, алгоритма переносов слов ТеХ, межстрочных интервалов "
        "и корректного сквозного подсчёта страниц по всей книге без резких скачков при переходе между главами. "
    )
    ch1_body = "".join(f"<p>{i + 1}. {para}</p>\n" for i in range(10))
    ch2_body = "".join(f"<p>{i + 1}. {para}</p>\n" for i in range(7))
    ch3_body = "".join(f"<p>{i + 1}. {para}</p>\n" for i in range(5))

    ch1 = f"""<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Глава 1: Основы типографики</title></head>
<body>
  <h1>Глава 1: Основы типографики</h1>
  <h2>Тестовый подзаголовок первой главы</h2>
  <blockquote>Мудрость приходит с внимательным чтением и точными заметками на полях книги.</blockquote>
  <p>Этот фрагмент содержит <b>полужирный текст</b>, <i>курсивное выделение</i>, а также сноску<a id="ref1" epub:type="noteref" href="ch1.xhtml#fn1">[1]</a> и <a href="https://books.yandex.ru/">внешнюю ссылку</a>.</p>
  {ch1_body}
  <aside id="fn1" epub:type="footnote"><p>Примечание 1: корректная обработка всплывающей сноски в модуле чтения.</p></aside>
</body>
</html>"""

    ch2 = f"""<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Глава 2: Синхронизация и закладки</title></head>
<body>
  <h1>Глава 2: Синхронизация и закладки</h1>
  {ch2_body}
</body>
</html>"""

    ch3 = f"""<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Глава 3: Финальный раздел</title></head>
<body>
  <h1>Глава 3: Финальный раздел</h1>
  {ch3_body}
</body>
</html>"""

    opf = """<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:test-book-v170-real</dc:identifier>
    <dc:title>Архитектура E-Ink и Типографика</dc:title>
    <dc:creator>Тестовый Автор</dc:creator>
    <dc:language>ru</dc:language>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="item_ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="item_ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="item_ch3" href="ch3.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="item_ch1"/>
    <itemref idref="item_ch2"/>
    <itemref idref="item_ch3"/>
  </spine>
</package>"""

    ncx = """<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="urn:uuid:test-book-v170-real"/></head>
  <docTitle><text>Архитектура E-Ink и Типографика</text></docTitle>
  <navMap>
    <navPoint id="nav1" playOrder="1"><navLabel><text>Глава 1: Основы типографики</text></navLabel><content src="ch1.xhtml"/></navPoint>
    <navPoint id="nav2" playOrder="2"><navLabel><text>Глава 2: Синхронизация и закладки</text></navLabel><content src="ch2.xhtml"/></navPoint>
    <navPoint id="nav3" playOrder="3"><navLabel><text>Глава 3: Финальный раздел</text></navLabel><content src="ch3.xhtml"/></navPoint>
  </navMap>
</ncx>"""

    container = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>"""

    with zipfile.ZipFile(out_path, "w") as zf:
        zf.writestr("mimetype", "application/epub+zip", compress_type=zipfile.ZIP_STORED)
        zf.writestr("META-INF/container.xml", container, compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr("OEBPS/content.opf", opf, compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr("OEBPS/toc.ncx", ncx, compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr("OEBPS/ch1.xhtml", ch1, compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr("OEBPS/ch2.xhtml", ch2, compress_type=zipfile.ZIP_DEFLATED)
        zf.writestr("OEBPS/ch3.xhtml", ch3, compress_type=zipfile.ZIP_DEFLATED)


def get_app_uid():
    pkg_info = shell(f"dumpsys package {PKG}", check=False)
    m = re.search(r"(?:userId|appId)=(\d+)", pkg_info)
    return m.group(1) if m else None


def fix_app_ownership(path, uid):
    if uid:
        shell(f"chown -R {uid}:{uid} {path}", check=False)
    shell(f"restorecon -RF {path} 2>/dev/null || restorecon -R {path} 2>/dev/null || true", check=False)


def parse_page_counter(text):
    # Matches "1/12 (8%)" or "1 / 12 (8%)"
    m = re.search(r"(\d+)\s*/\s*(\d+)(?:\s*\((\d+)%\))?", text or "")
    if not m:
        raise AssertionError(f"Unexpected page counter format: '{text}'")
    return int(m.group(1)), int(m.group(2)), int(m.group(3) or "0")


def assert_tabs_unclipped(root, screen_w, label):
    tab_ids = [
        ("tab_reading_btn", "Читаю ("),
        ("tab_to_read_btn", "В планах ("),
        ("tab_user_books_btn", "Файлы ("),
        ("tab_catalog_btn", "Каталог"),
        ("tab_search_btn", "Поиск"),
    ]
    rects = []
    for rid, expected_prefix in tab_ids:
        node = find_node(root, res_id_contains=rid)
        if not node:
            raise AssertionError(f"[{label}] Tab '{rid}' missing from UI tree!")
        txt = node.get("text", "")
        if expected_prefix not in txt:
            raise AssertionError(f"[{label}] Tab '{rid}' expected '{expected_prefix}...', got '{txt}'")
        r = parse_rect(node.get("bounds"))
        if not r or r[2] <= r[0] or r[3] <= r[1]:
            raise AssertionError(f"[{label}] Tab '{rid}' has invalid/zero rect: {r}")
        if r[0] < 0 or r[2] > screen_w + 2:
            raise AssertionError(f"[{label}] Tab '{rid}' clipped horizontally: {r} vs screen_w={screen_w}")
        rects.append((rid, r, r[3] - r[1]))
    heights = [h for _, _, h in rects]
    if max(heights) - min(heights) > 3:
        raise AssertionError(f"[{label}] Tab heights not uniform! heights={[(rid, h) for rid, _, h in rects]}")
    for i in range(len(rects) - 1):
        if rects[i][1][2] > rects[i + 1][1][0] + 2:
            raise AssertionError(f"[{label}] Adjacent tabs overlap: {rects[i]} vs {rects[i+1]}")
    print(f"[check] All 5 tabs unclipped & uniform ({heights[0]}px tall) under '{label}': OK")


def _run_tests(args):
    root_out = adb("root", check=False).stdout.strip()
    print(f"[adb] root: {root_out}")
    adb("wait-for-device", timeout=30)

    sdk = shell("getprop ro.build.version.sdk")
    rel = shell("getprop ro.build.version.release")
    sdk_int = int(re.sub(r"[^0-9]", "", sdk) or "0")
    print(f"[device] Android {rel} (API {sdk})")

    # Keep network off throughout offline real-book verification so synthetic session doesn't get 401-logged-out
    shell("svc wifi disable", check=False)
    shell("svc data disable", check=False)
    adb("logcat", "-c", check=False)

    # 1. Clean install & runtime permissions
    shell(f"pm uninstall {PKG}", check=False)
    print(f"[install] Installing {args.apk} ...")
    adb("install", "-r", args.apk, timeout=60)
    if sdk_int >= 23:
        shell(f"pm grant {PKG} android.permission.WRITE_EXTERNAL_STORAGE")
        shell(f"pm grant {PKG} android.permission.READ_EXTERNAL_STORAGE")

    shell("rm -rf /sdcard/Books/YandexBooks/backup /sdcard/Books/YandexBooks/Quotes", check=False)

    uid = get_app_uid()
    prefs_dir = f"/data/data/{PKG}/shared_prefs"
    shell(f"mkdir -p {prefs_dir}")
    prefs_xml = (
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
        "<map>\n"
        "    <string name=\"access_token\">emu_deep_verify_token</string>\n"
        "    <string name=\"refresh_token\">emu_deep_verify_refresh</string>\n"
        "    <string name=\"user_login\">deep.tester</string>\n"
        "    <long name=\"expires_at\" value=\"4102444800000\" />\n"
        "</map>\n"
    )
    tmp_prefs = os.path.join(args.artifacts, f".tmp_{args.tag}_prefs.xml")
    with open(tmp_prefs, "w", encoding="utf-8") as f:
        f.write(prefs_xml)
    adb("push", tmp_prefs, f"{prefs_dir}/yandex_auth_prefs.xml", timeout=20)
    os.remove(tmp_prefs)
    fix_app_ownership(prefs_dir, uid)

    # First launch so DatabaseHelper creates yandex_books_lite.db with exact Android schema
    shell(f"am start -n {PKG}/.ui.MainActivity")
    time.sleep(3.5)
    dismiss_notices()
    wait_for_node(res_id_contains="btn_main_menu", timeout=15)

    # 2. Seed rich multi-chapter EPUB + reading & user_books rows into real DB
    shell(f"am force-stop {PKG}")
    time.sleep(0.8)

    epub_local = os.path.join(args.artifacts, f".tmp_{args.tag}_test.epub")
    create_rich_test_epub(epub_local)
    for b_uuid in (TEST_BOOK_UUID, TEST_USER_BOOK_UUID):
        remote_dir = f"/data/data/{PKG}/files/cached_books/{b_uuid}"
        shell(f"mkdir -p {remote_dir}")
        adb("push", epub_local, f"{remote_dir}/book.epub", timeout=30)
    os.remove(epub_local)
    fix_app_ownership(f"/data/data/{PKG}/files", uid)

    db_remote = f"/data/data/{PKG}/databases/yandex_books_lite.db"
    db_local = os.path.join(args.artifacts, f".tmp_{args.tag}_db.sqlite")
    for ext in ("", "-wal", "-shm", "-journal"):
        if os.path.exists(db_local + ext):
            os.remove(db_local + ext)
    adb("pull", db_remote, db_local, timeout=20)
    for ext in ("-wal", "-shm", "-journal"):
        adb("pull", db_remote + ext, db_local + ext, check=False, timeout=10)
    now_ms = int(time.time() * 1000)
    conn = sqlite3.connect(db_local)
    try:
        conn.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        conn.execute("PRAGMA journal_mode=DELETE")
        conn.execute(
            "INSERT OR REPLACE INTO books "
            "(uuid, card_id, title, author, annotation, cover_url, local_cover_path, percent, "
            "total_chapters, current_chapter, current_paragraph, shelf_type, is_downloaded, "
            "last_read_timestamp, document_uuid, cfi, file_url) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                TEST_BOOK_UUID, "card-v170-1", "Архитектура E-Ink и Типографика", "Тестовый Автор",
                "Многоглавная тестовая книга EPUB", "", "", 0.0,
                3, 0, 0, "reading", 1, now_ms, "doc-v170-1", "", "",
            ),
        )
        conn.execute(
            "INSERT OR REPLACE INTO books "
            "(uuid, card_id, title, author, annotation, cover_url, local_cover_path, percent, "
            "total_chapters, current_chapter, current_paragraph, shelf_type, is_downloaded, "
            "last_read_timestamp, document_uuid, cfi, file_url) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                TEST_USER_BOOK_UUID, "", "Личный Конспект Пользователя", "Мои файлы",
                "Загруженный пользователем EPUB", "", "", 0.0,
                3, 0, 0, "user_books", 1, now_ms - 5000, "", "", "https://example.invalid/file.epub",
            ),
        )
        conn.commit()
    finally:
        conn.close()
    adb("push", db_local, db_remote, timeout=20)
    for ext in ("", "-wal", "-shm", "-journal"):
        if os.path.exists(db_local + ext):
            os.remove(db_local + ext)
    shell(f"rm -f {db_remote}-wal {db_remote}-shm {db_remote}-journal", check=False)
    fix_app_ownership(f"/data/data/{PKG}/databases", uid)

    # Relaunch MainActivity with seeded real books
    shell(f"am start -n {PKG}/.ui.MainActivity")
    time.sleep(2.5)
    dismiss_notices()
    wait_for_node(text_contains="Архитектура E-Ink", timeout=15)
    screen_w, screen_h = get_screen_size()

    # Check all 5 tabs + user_books tab
    root = dump_ui()
    assert_tabs_unclipped(root, screen_w, "default")
    tap_node(find_node(root, res_id_contains="tab_user_books_btn"), "Tab Файлы (1)")
    wait_for_node(text_contains="Личный Конспект", timeout=10)
    tap_node(wait_for_node(res_id_contains="tab_catalog_btn", timeout=8)[1], "Tab Каталог")
    time.sleep(0.8)
    dismiss_notices()
    tap_node(wait_for_node(res_id_contains="tab_search_btn", timeout=8)[1], "Tab Поиск")
    time.sleep(0.8)
    dismiss_notices()
    tap_node(wait_for_node(res_id_contains="tab_reading_btn", timeout=8)[1], "Tab Читаю (1)")
    wait_for_node(text_contains="Архитектура E-Ink", timeout=10)

    # Toggle NeoReader -> AlReader -> Выбор -> Lite mode via header button + notice dismiss
    for _ in range(5):
        btn_mode = find_node(dump_ui(), res_id_contains="btn_toggle_reader_mode")
        if btn_mode and "lite" in (btn_mode.get("text") or "").lower():
            break
        if btn_mode:
            tap_node(btn_mode, f"Cycle reader mode ({btn_mode.get('text')})")
            time.sleep(0.8)
            dismiss_notices()
    wait_for_node(res_id_contains="btn_toggle_reader_mode", text_contains="Lite", timeout=8)
    print("[check] Header switch to Lite mode: OK")

    # 3. Test ALL UI Scaling & Appearance Modes (Panels + Card Density + OS UI Scale 145% & 200%)
    tap_node(find_node(dump_ui(), res_id_contains="btn_main_menu"), "Open Main Menu")
    wait_for_node(text_contains="Внешний вид и интерфейс ПО", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="Внешний вид и интерфейс ПО"), "Open Appearance Dialog")
    wait_for_node(text_equals="Максимум", timeout=8)
    tap_node(find_node(dump_ui(), text_equals="Максимум"), "Panel size: Максимум")
    tap_node(find_node(dump_ui(), text_equals="Крупные"), "Card density: Крупные")
    tap_node(find_node(dump_ui(), text_equals="Готово"), "Save Appearance (XL + Large cards)")
    time.sleep(1.0)
    xl_root = dump_ui()
    assert_tabs_unclipped(xl_root, screen_w, "PANEL_SIZE_XLARGE")
    xl_btn_r = parse_rect(find_node(xl_root, text_equals="Читать")["bounds"])
    xl_card_h = xl_btn_r[3] - xl_btn_r[1]
    screencap(os.path.join(args.artifacts, f"{args.tag}_deep_01_ui_xlarge.png"))

    tap_node(find_node(dump_ui(), res_id_contains="btn_main_menu"), "Open Main Menu")
    wait_for_node(text_contains="Внешний вид и интерфейс ПО", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="Внешний вид и интерфейс ПО"), "Open Appearance Dialog")
    wait_for_node(text_equals="Компактный", timeout=8)
    tap_node(find_node(dump_ui(), text_equals="Компактный"), "Panel size: Компактный")
    tap_node(find_node(dump_ui(), text_equals="Комп."), "Card density: Комп.")
    tap_node(find_node(dump_ui(), text_equals="Готово"), "Save Appearance (Compact + Compact cards)")
    time.sleep(1.0)
    cp_root = dump_ui()
    assert_tabs_unclipped(cp_root, screen_w, "PANEL_SIZE_COMPACT")
    cp_btn_r = parse_rect(find_node(cp_root, text_equals="Читать")["bounds"])
    cp_card_h = cp_btn_r[3] - cp_btn_r[1]
    if cp_card_h >= xl_card_h:
        raise AssertionError(f"Expected Large card 'Читать' height ({xl_card_h}px) > Compact ({cp_card_h}px)")
    print(f"[check] Panel & Card scaling verified: Compact button={cp_card_h}px < Large button={xl_card_h}px")
    screencap(os.path.join(args.artifacts, f"{args.tag}_deep_02_ui_compact.png"))

    # OS UI Scale 200% stress check -> verify Main Menu & Scale Dialog remain scrollable and unclipped
    tap_node(find_node(dump_ui(), res_id_contains="btn_main_menu"), "Open Main Menu")
    wait_for_node(text_contains="Внешний вид и интерфейс ПО", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="Внешний вид и интерфейс ПО"), "Open Appearance Dialog")
    wait_for_node(text_contains="Масштаб ОС:", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="Масштаб ОС:"), "Open UI Scale Dialog")
    wait_for_node(text_contains="200%", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="200%"), "Select UI Scale 200%")
    time.sleep(2.5)
    dismiss_notices()
    wait_for_node(res_id_contains="btn_main_menu", timeout=12)
    assert_tabs_unclipped(dump_ui(), screen_w, "UI_SCALE_200%")
    tap_node(find_node(dump_ui(), res_id_contains="btn_main_menu"), "Open Main Menu at 200%")
    wait_for_node(text_contains="Внешний вид и интерфейс ПО", timeout=8)
    screencap(os.path.join(args.artifacts, f"{args.tag}_deep_03_uiscale_200_menu.png"))
    tap_node(find_node(dump_ui(), text_contains="Внешний вид и интерфейс ПО"), "Open Appearance at 200%")
    wait_for_node(text_contains="1. Панели и кнопки", timeout=8)
    for _ in range(3):
        if find_node(dump_ui(), text_contains="Масштаб ОС:"):
            break
        shell(f"input swipe {screen_w // 2} {int(screen_h * 0.65)} {screen_w // 2} {int(screen_h * 0.30)} 300")
        time.sleep(0.5)
    wait_for_node(text_contains="Масштаб ОС:", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="Масштаб ОС:"), "Open UI Scale Dialog at 200%")
    wait_for_node(text_contains="100%", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="100%"), "Restore UI Scale 100%")
    time.sleep(2.5)
    dismiss_notices()

    # Reset panel/card visual back to Darwin default
    tap_node(find_node(dump_ui(), res_id_contains="btn_main_menu"), "Open Main Menu")
    wait_for_node(text_contains="Внешний вид и интерфейс ПО", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="Внешний вид и интерфейс ПО"), "Open Appearance Dialog")
    wait_for_node(text_contains="Сбросить визуал", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="Сбросить визуал"), "Reset Darwin Default Visual")
    tap_node(find_node(dump_ui(), text_equals="Готово"), "Close Appearance")
    time.sleep(1.0)

    # 4. Open Real EPUB Book -> ReaderActivity -> Pagination & Page Count Math Check
    tap_node(find_node(dump_ui(), text_equals="Читать"), "Open book 'Архитектура E-Ink и Типографика'")
    wait_for_node(res_id_contains="reader_canvas", timeout=15)
    time.sleep(3.5)  # allow pagination & background prepagination to complete
    dismiss_notices()
    p1_shot = os.path.join(args.artifacts, f"{args.tag}_deep_04_reader_p1.png")
    screencap(p1_shot)
    day_luma = png_center_mean_luma(p1_shot)
    print(f"[pixel] Day mode center canvas luma: {day_luma:.1f}")
    if day_luma < 180.0:
        raise AssertionError(f"Expected bright day canvas (>180 luma), got {day_luma:.1f}")

    # Open NeoReader Overlay (center tap)
    shell(f"input tap {screen_w // 2} {screen_h // 2}")
    wait_for_node(res_id_contains="reader_page_counter", timeout=8)
    screencap(os.path.join(args.artifacts, f"{args.tag}_deep_05_reader_overlay.png"))
    cur1, tot1, pct1 = parse_page_counter(find_node(dump_ui(), res_id_contains="reader_page_counter")["text"])
    ch_title1 = find_node(dump_ui(), res_id_contains="reader_chapter_title_view")["text"]
    print(f"[pages] Initial state: {cur1}/{tot1} ({pct1}%), chapter='{ch_title1}'")
    if cur1 != 1 or tot1 < 5:
        raise AssertionError(f"Expected page 1 of >=5 total pages, got {cur1}/{tot1}")

    # Turn pages via overlay slider > and hardware keys (93 / 92)
    tap_node(find_node(dump_ui(), res_id_contains="btn_slider_page_forward"), "Next page (> button)")
    time.sleep(0.8)
    cur2, tot2, pct2 = parse_page_counter(find_node(dump_ui(), res_id_contains="reader_page_counter")["text"])
    if cur2 != 2:
        raise AssertionError(f"Expected page 2 after '>', got {cur2}/{tot2}")

    tap_node(find_node(dump_ui(), res_id_contains="btn_reader_close_overlay"), "Close overlay")
    time.sleep(0.8)
    shell("input keyevent 93")  # KEYCODE_PAGE_DOWN
    time.sleep(0.8)
    shell(f"input tap {int(screen_w * 0.88)} {screen_h // 2}")  # Right tap zone -> forward
    time.sleep(0.8)
    shell(f"input tap {screen_w // 2} {screen_h // 2}")  # Reopen overlay
    wait_for_node(res_id_contains="reader_page_counter", timeout=8)
    cur4, tot4, pct4 = parse_page_counter(find_node(dump_ui(), res_id_contains="reader_page_counter")["text"])
    print(f"[pages] After 3 forward turns (button + hw key + tap zone): {cur4}/{tot4} ({pct4}%)")
    if cur4 != 4:
        raise AssertionError(f"Expected page 4 after forward navigation, got {cur4}/{tot4}")

    # Jump to next chapter >> and back <<
    tap_node(find_node(dump_ui(), res_id_contains="btn_next_chapter"), "Next chapter >>")
    time.sleep(1.2)
    ch_title2 = find_node(dump_ui(), res_id_contains="reader_chapter_title_view")["text"]
    cur_c2, tot_c2, _ = parse_page_counter(find_node(dump_ui(), res_id_contains="reader_page_counter")["text"])
    print(f"[chapter] Chapter 2 jump: page={cur_c2}/{tot_c2}, title='{ch_title2}'")
    if cur_c2 <= 2:
        raise AssertionError(f"Expected global page index in Chapter 2 > 2, got {cur_c2}")

    tap_node(find_node(dump_ui(), res_id_contains="btn_prev_chapter"), "Previous chapter <<")
    time.sleep(1.2)

    # 5. Format Panel: Font Size, All 8 Fonts, Tap Zones 3x3, Fullscreen, Night Mode, Line Spacing, Landscape
    tap_node(find_node(dump_ui(), res_id_contains="btn_tab_format"), "Tab [Формат]")
    wait_for_node(res_id_contains="btn_font_increase", timeout=8)
    base_fs = int(find_node(dump_ui(), res_id_contains="font_size_label")["text"])
    _, base_tot, _ = parse_page_counter(find_node(dump_ui(), res_id_contains="reader_page_counter")["text"])
    btn_inc = find_node(dump_ui(), res_id_contains="btn_font_increase")
    for _ in range(5):
        tap_node(btn_inc, "Font +")
        time.sleep(0.4)
    time.sleep(2.0)  # allow prepagination to recompute exact book pages
    inc_fs = int(find_node(dump_ui(), res_id_contains="font_size_label")["text"])
    _, inc_tot, _ = parse_page_counter(find_node(dump_ui(), res_id_contains="reader_page_counter")["text"])
    print(f"[typography] Font size {base_fs}->{inc_fs}: total pages {base_tot}->{inc_tot}")
    if inc_fs != base_fs + 5 or inc_tot <= base_tot:
        raise AssertionError(f"Expected font size {base_fs+5} & increased pages (>{base_tot}), got fs={inc_fs}, tot={inc_tot}")
    btn_dec = find_node(dump_ui(), res_id_contains="btn_font_decrease")
    for _ in range(5):
        tap_node(btn_dec, "Font -")
        time.sleep(0.35)
    time.sleep(1.2)

    # Verify all 8 fonts in font dialog
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_font_family"), "Open 8-font dialog")
    f_root, _ = wait_for_node(text_contains="Charis SIL", timeout=8)
    f_texts = " | ".join(all_visible_texts(f_root))
    for fname in ("Literata", "Charis SIL", "PT Serif", "PT Sans", "Inglobal", "Системный Serif", "Системный Sans", "Моноширинный"):
        if fname not in f_texts:
            raise AssertionError(f"Font '{fname}' missing in dialog! visible={f_texts}")
    print("[check] All 8 E-Ink fonts visible in picker: OK")
    tap_node(find_node(f_root, text_contains="Моноширинный"), "Select Monospace font")
    time.sleep(1.2)
    wait_for_node(res_id_contains="btn_toggle_font_family", text_contains="Моноширинный", timeout=8)
    screencap(os.path.join(args.artifacts, f"{args.tag}_deep_06_format_mono.png"))
    # Switch back to Charis SIL -> Literata
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_font_family"), "Reopen font dialog")
    wait_for_node(text_contains="Literata", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="Literata"), "Restore Literata font")
    time.sleep(1.0)

    # Custom 3x3 tap zones + Fullscreen
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_tap_zones"), "Open Tap Zones dialog")
    wait_for_node(text_contains="Настроить сетку 3", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="Настроить сетку 3"), "Open 3x3 custom grid")
    wait_for_node(text_contains="Шаблон: Левша", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="Шаблон: Левша"), "Apply Left-Handed 3x3 preset")
    tap_node(find_node(dump_ui(), text_contains="Сохранить и включить"), "Save Custom 3x3 Grid")
    wait_for_node(res_id_contains="btn_toggle_tap_zones", text_contains="3×3", timeout=8)
    print("[check] Custom 3x3 tap grid saved & activated: OK")
    # Restore standard tap zone mode
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_tap_zones"), "Reopen Tap Zones dialog")
    wait_for_node(text_contains="Стандарт", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="Стандарт"), "Restore Standard tap zones")
    time.sleep(0.8)

    # Fullscreen toggle
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_fullscreen"), "Toggle Fullscreen ON")
    wait_for_node(res_id_contains="btn_toggle_fullscreen", text_contains="Скрыт", timeout=8)
    screencap(os.path.join(args.artifacts, f"{args.tag}_deep_07_fullscreen_on.png"))
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_fullscreen"), "Toggle Fullscreen OFF")
    time.sleep(0.8)

    # Subtab [Формат]: Bold, Hyphenation, Contrast, EPUB fonts, Night mode pixel check
    tap_node(find_node(dump_ui(), res_id_contains="btn_subtab_format"), "Subtab [Формат]")
    wait_for_node(res_id_contains="btn_toggle_night_mode", timeout=8)
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_contrast"), "Toggle Bold text")
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_hyphenation"), "Toggle Hyphenation")
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_eink_contrast"), "Toggle E-Ink High Contrast")
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_night_mode"), "Toggle Night Mode ON")
    time.sleep(0.6)
    tap_node(find_node(dump_ui(), res_id_contains="btn_reader_close_overlay"), "Close overlay to verify Night canvas")
    time.sleep(1.0)
    night_shot = os.path.join(args.artifacts, f"{args.tag}_deep_08_night_mode.png")
    screencap(night_shot)
    night_luma = png_center_mean_luma(night_shot)
    print(f"[pixel] Night mode center canvas luma: {night_luma:.1f}")
    if night_luma > 70.0:
        raise AssertionError(f"Night Mode failed to invert canvas to dark! luma={night_luma:.1f}")

    # Reopen overlay & open [Формат] -> [Формат] subtab, toggle Night Mode back OFF
    shell(f"input tap {screen_w // 2} {screen_h // 2}")
    _, fmt_tab = wait_for_node(res_id_contains="btn_tab_format", timeout=8)
    tap_node(fmt_tab, "Reopen [Формат] tab")
    _, subfmt_tab = wait_for_node(res_id_contains="btn_subtab_format", timeout=8)
    tap_node(subfmt_tab, "Open [Формат] subtab")
    wait_for_node(res_id_contains="btn_toggle_night_mode", timeout=8)
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_night_mode"), "Toggle Night Mode OFF")
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_hyphenation"), "Restore Hyphenation")
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_contrast"), "Restore Text Weight")
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_epub_fonts"), "Toggle EPUB embedded fonts")
    time.sleep(1.0)
    dismiss_notices()

    # Subtab [Между строк]: Line spacing, margins, footer offset, Landscape layout non-overflow
    tap_node(find_node(dump_ui(), res_id_contains="btn_subtab_spacing"), "Subtab [Между строк]")
    wait_for_node(res_id_contains="btn_toggle_orientation", timeout=8)
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_line_spacing"), "Cycle Line Spacing")
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_margins"), "Cycle Side Margins")
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_vert_margins"), "Cycle Top/Bottom Margins")
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_footer_offset"), "Cycle Footer Offset")
    time.sleep(1.0)

    # Rotate to Landscape & assert format subtabs do NOT clip off top of screen!
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_orientation"), "Rotate Portrait -> Landscape")
    time.sleep(2.2)
    land_w, land_h = get_screen_size()
    land_root = dump_ui()
    subtab_view_node = find_node(land_root, res_id_contains="btn_subtab_view")
    orient_node = find_node(land_root, res_id_contains="btn_toggle_orientation")
    if not subtab_view_node or not orient_node:
        raise AssertionError("Landscape format panel subtab or orientation button missing!")
    sv_r = parse_rect(subtab_view_node["bounds"])
    or_r = parse_rect(orient_node["bounds"])
    if sv_r[1] < 0 or or_r[3] > land_h + 2:
        raise AssertionError(f"Landscape format panel overflowed! subtab={sv_r}, orient={or_r}, land_h={land_h}")
    print(f"[check] Landscape ({land_w}x{land_h}) format panel scroll fits cleanly (subtab y={sv_r[1]}, orient y2={or_r[3]}): OK")
    screencap(os.path.join(args.artifacts, f"{args.tag}_deep_09_landscape_format.png"))
    # Cycle Landscape -> Reverse Landscape -> Portrait
    tap_node(orient_node, "Landscape -> Reverse Landscape")
    time.sleep(1.5)
    tap_node(find_node(dump_ui(), res_id_contains="btn_toggle_orientation"), "Reverse Landscape -> Portrait")
    time.sleep(2.0)

    # 6. TOC + Bookmarks + Long-Press Quotes + Markdown Export
    _, toc_tab = wait_for_node(res_id_contains="btn_tab_toc", timeout=8)
    tap_node(toc_tab, "Open [Содержание] 3-tab dialog")
    toc_root, _ = wait_for_node(text_contains="Оглавление (", timeout=10)
    toc_texts = " | ".join(all_visible_texts(toc_root))
    for marker in ("Оглавление (", "Закладки (", "Цитаты ("):
        if marker not in toc_texts:
            raise AssertionError(f"TOC dialog missing tab '{marker}': {toc_texts}")
    tap_node(find_node(toc_root, text_contains="Закладки ("), "Switch to Bookmarks tab")
    wait_for_node(text_contains="Добавить текущую страницу в закладки", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="Добавить текущую страницу в закладки"), "+ Add Bookmark")
    _, ok_bm = wait_for_node(text_equals="ОК", timeout=8)
    tap_node(ok_bm, "Dismiss bookmark notice (ОК)")
    time.sleep(0.5)
    wait_for_node(text_contains="Закладки (1)", timeout=8)
    print("[check] Bookmark added & displayed as 'Закладки (1)': OK")
    tap_node(find_node(dump_ui(), text_equals="Закрыть"), "Close TOC dialog")
    time.sleep(0.8)
    if find_node(dump_ui(), res_id_contains="btn_reader_close_overlay"):
        tap_node(find_node(dump_ui(), res_id_contains="btn_reader_close_overlay"), "Close overlay")
        time.sleep(0.8)

    # Long press on paragraph text to select word -> Save quote with note
    lp_x, lp_y = int(screen_w * 0.45), int(screen_h * 0.36)
    print(f"[gesture] Long-press word selection at ({lp_x}, {lp_y})")
    shell(f"input touchscreen swipe {lp_x} {lp_y} {lp_x} {lp_y} 850")
    wait_for_node(text_contains="Сохранить с заметкой", timeout=10)
    tap_node(find_node(dump_ui(), text_contains="Сохранить с заметкой"), "Choose 'Сохранить с заметкой'")
    qroot, _ = wait_for_node(text_contains="Заметка к цитате", timeout=8)
    et_node = find_node(qroot, text_contains="Введите комментарий")
    if et_node:
        tap_node(et_node, "Focus note EditText")
        time.sleep(0.4)
    shell("input text E2E_note_ok")
    time.sleep(0.5)
    tap_node(find_node(dump_ui(), text_equals="Сохранить"), "Save Quote + Note")
    time.sleep(1.2)
    dismiss_notices()
    screencap(os.path.join(args.artifacts, f"{args.tag}_deep_10_quote_highlight.png"))

    # Open TOC -> Цитаты (1) -> Export to Markdown (.md)
    shell(f"input tap {screen_w // 2} {screen_h // 2}")
    wait_for_node(res_id_contains="btn_tab_toc", timeout=8)
    tap_node(find_node(dump_ui(), res_id_contains="btn_tab_toc"), "Open [Содержание]")
    wait_for_node(text_contains="Цитаты (1)", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="Цитаты (1)"), "Open Quotes tab")
    wait_for_node(text_contains="E2E_note_ok", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="Экспорт цитат в файл"), "Export Quotes to .md")
    _, ok_exp = wait_for_node(text_equals="ОК", timeout=8)
    tap_node(ok_exp, "Dismiss export notice (ОК)")
    time.sleep(0.5)

    md_ls_pub = shell("ls -la /sdcard/Books/YandexBooks/Quotes/", check=False)
    md_ls_app = shell("ls -la /sdcard/Android/data/com.onyx.yandexbooks/files/Quotes/", check=False)
    print(f"[fs] Quotes dirs:\n  public: {md_ls_pub}\n  app: {md_ls_app}")
    if ".md" in md_ls_pub:
        md_content = shell("cat /sdcard/Books/YandexBooks/Quotes/*.md", check=False)
    elif ".md" in md_ls_app:
        md_content = shell("cat /sdcard/Android/data/com.onyx.yandexbooks/files/Quotes/*.md", check=False)
    else:
        raise AssertionError(f"Exported Markdown file not found in public or app Quotes dir: pub={md_ls_pub} app={md_ls_app}")
    if "# Цитаты:" not in md_content or "E2E_note_ok" not in md_content:
        raise AssertionError(f"Exported Markdown missing quote header or note! Content:\n{md_content}")
    print("[check] Exported Quotes Markdown (.md) verified on storage: OK")

    # Exit back to MainActivity so progress + sync_queue + backup flush cleanly
    close_toc = find_node(dump_ui(), text_equals="Закрыть")
    if close_toc:
        tap_node(close_toc, "Close TOC dialog")
        time.sleep(0.8)
    else:
        shell("input keyevent 4")  # dismiss TOC
        time.sleep(0.6)
    if "ReaderActivity" in get_top_activity():
        back_btn = find_node(dump_ui(), res_id_contains="btn_reader_back")
        if back_btn:
            tap_node(back_btn, "Tap Reader Back")
        else:
            shell("input keyevent 4")
        time.sleep(1.5)
    if "ReaderActivity" in get_top_activity():
        shell("input keyevent 4")
        time.sleep(1.5)
    wait_for_node(res_id_contains="btn_main_menu", timeout=15)
    screencap(os.path.join(args.artifacts, f"{args.tag}_deep_11_shelf_after_read.png"))

    # 7. Inspect live SQLite DB tables (progress, sync_queue, bookmarks, quotes)
    shell(f"am force-stop {PKG}")
    time.sleep(0.8)
    verify_db = os.path.join(args.artifacts, f".tmp_{args.tag}_verify_db.sqlite")
    adb("pull", db_remote, verify_db, timeout=20)
    for ext in ("-wal", "-shm", "-journal"):
        adb("pull", db_remote + ext, verify_db + ext, check=False, timeout=10)
    vconn = sqlite3.connect(verify_db)
    try:
        prog_row = vconn.execute(
            "SELECT percent, chapter_index, cfi FROM progress WHERE book_uuid = ?",
            (TEST_BOOK_UUID,),
        ).fetchone()
        sync_cnt = vconn.execute(
            "SELECT COUNT(*) FROM sync_queue WHERE book_uuid = ?",
            (TEST_BOOK_UUID,),
        ).fetchone()[0]
        bm_cnt = vconn.execute(
            "SELECT COUNT(*) FROM bookmarks WHERE book_uuid = ?",
            (TEST_BOOK_UUID,),
        ).fetchone()[0]
        q_row = vconn.execute(
            "SELECT cfi, note, text FROM quotes WHERE book_uuid = ?",
            (TEST_BOOK_UUID,),
        ).fetchone()
    finally:
        vconn.close()
        for ext in ("", "-wal", "-shm", "-journal"):
            if os.path.exists(verify_db + ext):
                os.remove(verify_db + ext)

    print(f"[sqlite] progress={prog_row}, sync_queue={sync_cnt}, bookmarks={bm_cnt}, quote={q_row}")
    if not prog_row or prog_row[0] <= 0.0 or not (prog_row[2] or "").startswith("epubcfi(/6/"):
        raise AssertionError(f"Expected non-zero progress & canonical CFI in SQLite, got: {prog_row}")
    if sync_cnt < 1:
        raise AssertionError(f"Expected >=1 offline sync_queue entry for progress sync, got: {sync_cnt}")
    if bm_cnt < 1:
        raise AssertionError(f"Expected >=1 saved bookmark in SQLite, got: {bm_cnt}")
    if not q_row or "E2E_note_ok" not in (q_row[1] or "") or not (q_row[0] or "").startswith("epubcfi(/6/"):
        raise AssertionError(f"Expected quote with note & canonical CFI in SQLite, got: {q_row}")

    crashes = adb("logcat", "-d", "-s", "AndroidRuntime:E").stdout
    if "FATAL EXCEPTION" in crashes:
        raise AssertionError(f"FATAL EXCEPTION in AndroidRuntime logcat:\n{crashes}")
    print(f"=== DEEP REAL-BOOK E2E VERIFICATION PASSED ({args.tag} / API {sdk}) ===")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--artifacts", required=True)
    parser.add_argument("--tag", default="emu")
    args = parser.parse_args()
    try:
        _run_tests(args)
    finally:
        crash_log = adb("logcat", "-d", "-s", "AndroidRuntime:E", "ReaderActivity:E", check=False).stdout
        if crash_log.strip():
            print("--- Logcat AndroidRuntime/ReaderActivity errors ---")
            print(crash_log[-3000:])


if __name__ == "__main__":
    main()
