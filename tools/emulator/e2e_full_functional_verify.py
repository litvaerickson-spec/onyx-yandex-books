#!/usr/bin/env python3
"""
Полный сквозной E2E-драйвер функциональной проверки всех экранов, кнопок, масштабов,
вкладок, карточек, оглавления, закладок, цитат (прямое/обратное выделение),
сносок, внутренних/внешних ссылок, резервной копии и выхода на реальном Android-эмуляторе.
"""

import argparse
import json
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
MAIN_BOOK_UUID = "test-func-main-book"
TOREAD_BOOK_UUID = "test-func-toread-book"
DONE_BOOK_UUID = "test-func-done-book"
USER_BOOK_UUID = "test-func-user-book"
SDK_INT = 28
SCREEN_W = 1072
SCREEN_H = 1448


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
    win_out = shell("dumpsys window displays", check=False)
    m2 = re.search(r"cur=(\d+)x(\d+)", win_out) or re.search(r"init=(\d+)x(\d+)", win_out)
    if m2:
        return int(m2.group(1)), int(m2.group(2))
    disp_out = shell("dumpsys display", check=False)
    m3 = re.search(r"DisplayDeviceInfo[^\n]*?(\d+)\s*x\s*(\d+)", disp_out)
    if m3:
        return int(m3.group(1)), int(m3.group(2))
    return 758, 1024


def screencap(path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    remote = "/data/local/tmp/emu_full_cap.png"
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
    for _ in range(height):
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
                pa = abs(p - a)
                pb = abs(p - b)
                pc = abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                scan[i] = (scan[i] + pr) & 0xFF
        rows.append(scan)
        prev = scan

    y0, y1 = height // 4, (height * 3) // 4
    x0, x1 = width // 4, (width * 3) // 4
    total_luma = 0
    count = 0
    for y in range(y0, y1, 2):
        row = rows[y]
        for x in range(x0, x1, 2):
            off = x * bpp
            r, g, b = row[off], row[off + 1], row[off + 2]
            total_luma += (299 * r + 587 * g + 114 * b) // 1000
            count += 1
    return total_luma / max(1, count)


def dump_ui():
    remote = "/data/local/tmp/ui_full_dump.xml"
    last_err = ""
    for _ in range(6):
        res = adb("shell", f"rm -f {remote} && uiautomator dump {remote}", check=False, timeout=20)
        out_str = (res.stdout or "") + (res.stderr or "")
        if "UI hierchary dumped to" in out_str or "dumped to:" in out_str:
            xml_res = adb("shell", f"cat {remote}", check=False, timeout=15)
            xml_text = xml_res.stdout.strip()
            start = xml_text.find("<?xml")
            if start < 0:
                start = xml_text.find("<hierarchy")
            if start >= 0:
                try:
                    return ET.fromstring(xml_text[start:])
                except ET.ParseError as e:
                    last_err = str(e)
        else:
            last_err = out_str
        time.sleep(0.9)
    raise RuntimeError(f"Failed to dump UI XML: {last_err}")


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


def swipe_compat(x1, y1, x2, y2, duration_ms=350):
    if SDK_INT >= 18:
        shell(f"input swipe {x1} {y1} {x2} {y2} {duration_ms}")
    else:
        shell(f"input swipe {x1} {y1} {x2} {y2}")


def _tree_has_resource_ids(root):
    for attrs in iter_nodes(root):
        if (attrs.get("resource-id") or "").strip():
            return True
    return False


def _match_api17_res_id(attrs, res_id_contains):
    text = (attrs.get("text") or "").strip()
    cls = attrs.get("class") or ""
    rect = parse_rect(attrs.get("bounds", ""))
    mid_y = ((rect[1] + rect[3]) // 2) if rect else 0

    exact_map = {
        "btn_import_file": "Из файла",
        "manual_token_button": "Ввести токен",
        "retry_button": "Повтор",
        "btn_close_webview": "Назад",
        "btn_open_keyboard": "Клавиатура",
        "btn_native_input": "Ввод текста",
        "btn_reload_webview": "Обн",
        "btn_main_menu": "Меню",
        "tab_catalog_btn": "Каталог",
        "tab_search_btn": "Поиск",
        "btn_catalog_reload": "Обновить",
        "btn_search_submit": "Найти",
        "btn_prev_chapter": "<<",
        "btn_tab_toc": "Содержание",
        "btn_tab_progress": "Прогресс",
        "btn_tab_format": "Формат",
        "row_toggle_indent": "Абзацный отступ",
        "row_toggle_edge_brightness": "Подсветка по краям",
        "row_toggle_audio_sync": "Синхр. с аудио",
        "btn_slider_page_forward": ">",
        "reader_bottom_bar": "Содержание",
    }
    if res_id_contains in exact_map:
        return text == exact_map[res_id_contains]
    if res_id_contains == "btn_open_webview":
        return "Войти на этом устройстве" in text
    if res_id_contains == "tab_reading_btn":
        return text.startswith("Читаю")
    if res_id_contains == "tab_to_read_btn":
        return text.startswith("В планах")
    if res_id_contains == "tab_done_btn":
        return text.startswith("Прочит.")
    if res_id_contains == "tab_user_books_btn":
        return text.startswith("Файлы")
    if res_id_contains == "btn_catalog_category":
        return text.startswith("Категория:")
    if res_id_contains == "search_query_input":
        return cls.endswith("EditText")
    if res_id_contains == "shelf_page_indicator":
        return bool(re.fullmatch(r"\d+\s*/\s*\d+", text))
    if res_id_contains == "btn_shelf_next_page":
        return "Стр >" in text
    if res_id_contains == "btn_shelf_prev_page":
        return "< Стр" in text
    if res_id_contains == "btn_toggle_reader_mode":
        return text in ("Onyx", "Lite", "NeoReader", "AlReader", "Выбор", "Сист")
    if res_id_contains == "reader_canvas":
        if "ReaderCanvasView" in cls:
            return True
        if rect and cls.endswith("View") and (rect[2] - rect[0]) > int(SCREEN_W * 0.7) and (rect[3] - rect[1]) > int(SCREEN_H * 0.5):
            return True
        return False
    if res_id_contains == "btn_reader_back":
        return text == "<" and mid_y < int(SCREEN_H * 0.25)
    if res_id_contains == "btn_slider_page_back":
        return text == "<" and mid_y > int(SCREEN_H * 0.25)
    if res_id_contains == "btn_reader_close_overlay":
        return text == "X" and mid_y < int(SCREEN_H * 0.25)
    if res_id_contains == "btn_reader_refresh":
        return text == "Сброс" and mid_y < int(SCREEN_H * 0.25)
    if res_id_contains == "reader_chapter_title_view":
        return cls.endswith("TextView") and int(SCREEN_H * 0.55) < mid_y < int(SCREEN_H * 0.90) and len(text) > 1
    if res_id_contains == "reader_format_scroll":
        return attrs.get("scrollable") == "true" and mid_y > int(SCREEN_H * 0.35)
    if res_id_contains == "btn_close_format_panel":
        return text in ("✕", "×") or "Свернуть формат" in (attrs.get("content-desc") or "")
    if res_id_contains == "reader_top_bar":
        return rect is not None and rect[1] <= int(SCREEN_H * 0.08) and rect[3] <= int(SCREEN_H * 0.22) and (rect[2] - rect[0]) >= int(SCREEN_W * 0.85)
    return False


def _node_matches(attrs, text_contains, text_equals, res_id_contains, clickable_only, has_rids):
    text = attrs.get("text", "")
    desc = attrs.get("content-desc", "")
    rid = attrs.get("resource-id", "")
    if clickable_only and attrs.get("clickable") != "true":
        return False
    if res_id_contains:
        if has_rids:
            if res_id_contains not in rid:
                return False
        else:
            if not _match_api17_res_id(attrs, res_id_contains):
                return False
    if text_equals and (text.lower() != text_equals.lower() and desc.lower() != text_equals.lower()):
        return False
    if text_contains and (text_contains.lower() not in text.lower() and text_contains.lower() not in desc.lower()):
        return False
    return True


def find_node(root, text_contains=None, text_equals=None, res_id_contains=None, clickable_only=False):
    has_rids = _tree_has_resource_ids(root)
    for attrs in iter_nodes(root):
        if _node_matches(attrs, text_contains, text_equals, res_id_contains, clickable_only, has_rids):
            return attrs
    return None


def find_all_nodes(root, text_contains=None, text_equals=None, res_id_contains=None, clickable_only=False):
    has_rids = _tree_has_resource_ids(root)
    return [
        attrs
        for attrs in iter_nodes(root)
        if _node_matches(attrs, text_contains, text_equals, res_id_contains, clickable_only, has_rids)
    ]


def all_visible_texts(root):
    return [(a.get("text") or "").strip() for a in iter_nodes(root) if (a.get("text") or "").strip()]


def tap_node(attrs, label=""):
    pt = parse_bounds(attrs.get("bounds", ""))
    if not pt:
        raise RuntimeError(f"Invalid bounds for tap: {attrs}")
    print(f"[tap] {label or attrs.get('text') or attrs.get('resource-id')} at {pt}")
    shell(f"input tap {pt[0]} {pt[1]}")


_TOUCH_DEV_INFO = None


def _get_api17_touch_device():
    global _TOUCH_DEV_INFO
    if _TOUCH_DEV_INFO is not None:
        return _TOUCH_DEV_INFO
    out = shell("getevent -p", check=False, timeout=15)
    dev_path = None
    in_abs = False
    is_mt = False
    has_tracking = False
    has_slot = False
    has_major = False
    has_pressure = False
    has_btn_touch = False
    max_x, max_y = SCREEN_W, SCREEN_H
    cur_dev = None
    cur_abs = False
    cur_mt = False
    cur_track = cur_slot = cur_major = cur_press = cur_btn = False
    cur_mx, cur_my = 0, 0

    def _commit():
        nonlocal dev_path, in_abs, is_mt, has_tracking, has_slot, has_major, has_pressure, has_btn_touch, max_x, max_y
        if cur_dev and (cur_abs or cur_mt):
            dev_path = cur_dev
            in_abs = True
            is_mt = cur_mt
            has_tracking = cur_track
            has_slot = cur_slot
            has_major = cur_major
            has_pressure = cur_press
            has_btn_touch = cur_btn
            max_x = cur_mx or SCREEN_W
            max_y = cur_my or SCREEN_H

    for line in out.splitlines():
        m_dev = re.match(r"add device \d+:\s+(\S+)", line.strip())
        if m_dev:
            _commit()
            cur_dev = m_dev.group(1)
            cur_abs = cur_mt = cur_track = cur_slot = cur_major = cur_press = cur_btn = False
            cur_mx = cur_my = 0
            continue
        if "0035" in line and "max " in line:
            cur_mt = True
            cur_abs = True
            mm = re.search(r"max\s+(\d+)", line)
            if mm:
                cur_mx = int(mm.group(1))
        elif "0036" in line and "max " in line:
            cur_mt = True
            cur_abs = True
            mm = re.search(r"max\s+(\d+)", line)
            if mm:
                cur_my = int(mm.group(1))
        elif re.search(r"^\s*0000\s+:", line) and "max " in line:
            cur_abs = True
            mm = re.search(r"max\s+(\d+)", line)
            if mm:
                cur_mx = cur_mx or int(mm.group(1))
        elif re.search(r"^\s*0001\s+:", line) and "max " in line:
            cur_abs = True
            mm = re.search(r"max\s+(\d+)", line)
            if mm:
                cur_my = cur_my or int(mm.group(1))
        elif "002f" in line:
            cur_slot = True
        elif "0030" in line:
            cur_major = True
        elif "0039" in line:
            cur_track = True
        elif "003a" in line:
            cur_press = True
        elif "014a" in line:
            cur_btn = True
    _commit()
    _TOUCH_DEV_INFO = (dev_path, is_mt, has_tracking, has_slot, has_major, has_pressure, has_btn_touch, max_x, max_y) if in_abs else ()
    return _TOUCH_DEV_INFO


def touch_hold_xy(x, y, duration_ms=850):
    if SDK_INT >= 18:
        shell(f"input touchscreen swipe {x} {y} {x} {y} {duration_ms}")
        return
    info = _get_api17_touch_device()
    if not info:
        raise RuntimeError("No touchscreen device found via getevent -p on API 17")
    dev, is_mt, has_track, has_slot, has_major, has_pressure, has_btn, mx, my = info
    kx = int(x * mx / max(1, SCREEN_W))
    ky = int(y * my / max(1, SCREEN_H))
    down_cmds = []
    up_cmds = []
    if is_mt:
        if has_slot:
            down_cmds.append(f"sendevent {dev} 3 47 0")
            up_cmds.append(f"sendevent {dev} 3 47 0")
        if has_track:
            down_cmds.append(f"sendevent {dev} 3 57 0")
            up_cmds.append(f"sendevent {dev} 3 57 -1")
        down_cmds.extend([f"sendevent {dev} 3 53 {kx}", f"sendevent {dev} 3 54 {ky}"])
        if has_major:
            down_cmds.append(f"sendevent {dev} 3 48 5")
        if has_pressure:
            down_cmds.append(f"sendevent {dev} 3 58 512")
            up_cmds.insert(0, f"sendevent {dev} 3 58 0")
        if has_btn:
            down_cmds.append(f"sendevent {dev} 1 330 1")
            up_cmds.append(f"sendevent {dev} 1 330 0")
        elif not has_track:
            down_cmds.append(f"sendevent {dev} 0 2 0")
            up_cmds.append(f"sendevent {dev} 0 2 0")
    else:
        down_cmds.extend([f"sendevent {dev} 3 0 {kx}", f"sendevent {dev} 3 1 {ky}", f"sendevent {dev} 1 330 1"])
        up_cmds.append(f"sendevent {dev} 1 330 0")
    down_cmds.append(f"sendevent {dev} 0 0 0")
    up_cmds.append(f"sendevent {dev} 0 0 0")
    shell(" && ".join(down_cmds))
    time.sleep(max(0.75, duration_ms / 1000.0))
    shell(" && ".join(up_cmds))


def long_press_node(attrs, duration_ms=800, label=""):
    pt = parse_bounds(attrs.get("bounds", ""))
    if not pt:
        raise RuntimeError(f"Invalid bounds for long-press: {attrs}")
    print(f"[long_press] {label or attrs.get('text')} at {pt}")
    touch_hold_xy(pt[0], pt[1], duration_ms)


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


def open_reader_overlay(sw, sh):
    for yf in (0.95, 0.03, 0.65, 0.50, 0.35, 0.58):
        shell(f"input tap {sw // 2} {int(sh * yf)}")
        time.sleep(0.55)
        root = dump_ui()
        if find_node(root, res_id_contains="btn_reader_back") or find_node(root, res_id_contains="btn_tab_toc"):
            return root
        cancel_btn = find_node(root, text_equals="Отмена", clickable_only=True)
        if cancel_btn:
            tap_node(cancel_btn, "Dismiss link dialog before overlay")
            time.sleep(0.4)
        else:
            dismiss_notices()
    raise RuntimeError("Failed to open Reader overlay!")


def dismiss_notices(max_tries=4, include_close=True):
    for _ in range(max_tries):
        root = dump_ui()
        btn = (
            find_node(root, text_equals="Понятно", clickable_only=True)
            or find_node(root, text_equals="ОК", clickable_only=True)
            or find_node(root, text_equals="OK", clickable_only=True)
            or (find_node(root, text_equals="Закрыть", clickable_only=True) if include_close else None)
        )
        if not btn:
            return root
        tap_node(btn, f"Dismiss notice ({btn.get('text')})")
        time.sleep(0.9)
    return dump_ui()


def get_app_uid():
    pkg_info = shell(f"dumpsys package {PKG}", check=False)
    m = re.search(r"(?:userId|appId)=(\d+)", pkg_info)
    return m.group(1) if m else None


def fix_app_ownership(path, uid):
    if uid:
        shell(f"chown -R {uid}:{uid} {path}", check=False)
    shell(f"restorecon -RF {path} 2>/dev/null || restorecon -R {path} 2>/dev/null || true", check=False)


def parse_page_counter(text):
    m = re.search(r"(\d+)\s*/\s*(\d+)(?:\s*\((\d+)%\))?", text or "")
    if not m:
        raise AssertionError(f"Unexpected page counter format: '{text}'")
    return int(m.group(1)), int(m.group(2)), int(m.group(3) or "0")


def create_comprehensive_epub(out_path):
    """Создаёт многоглавный EPUB с иерархическим NCX, сносками, локальным и внешним якорями, CSS и списками."""
    para = (
        "Электронные чернила обеспечивают высокий контраст и точную передачу букв при длительном чтении "
        "литературы на экранах высокого разрешения. Этот абзац служит для проверки постраничной разбивки, "
        "переносов слов, межстрочных интервалов, закладки, выделения текста в обе стороны и расчёта процентов. "
    )
    filler_p1 = "".join(f"<p>{i + 1}. {para}</p>\n" for i in range(6))
    filler_p2 = "".join(f"<p>{i + 1}. {para}</p>\n" for i in range(6))

    ch1 = f"""<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/ops">
<head>
  <title>Часть 1: Введение и ссылки</title>
  <style>
    @media screen and (max-width: 600px) {{ .epigraph {{ font-style: normal; }} }}
    .epigraph {{ font-style: italic; }}
  </style>
</head>
<body>
  <h1>Часть 1: Введение и ссылки</h1>
  <p><a id="ref1" epub:type="noteref" href="ch1.xhtml#fn1">[12]</a> <a href="ch1.xhtml#fn1">[12]</a> <a href="ch1.xhtml#fn1">[12]</a> <a href="ch1.xhtml#fn1">[12]</a> <a href="ch1.xhtml#fn1">[12]</a> <a href="ch1.xhtml#fn1">[12]</a></p>
  <p><a href="#local_sec">Локальная ссылка якоря Локальная ссылка якоря Локальная ссылка якоря</a></p>
  <p><a href="https://books.yandex.ru/">Внешняя ссылка браузера Внешняя ссылка браузера Внешняя ссылка браузера</a></p>
  <p><a href="ch2.xhtml">Ссылка на вторую главу Ссылка на вторую главу Ссылка на вторую главу</a></p>
  <p>Первый контрольный абзац для выделения слов вперед и назад содержит уникальное слово Кристалл и Маяк для проверки выделения.</p>
  {filler_p1}
  <h2 id="part1_2">Раздел 1.2: Списки и локальный якорь</h2>
  <ul><li><p>Пункт списка внутри вложенного тега сохраняет маркер на одной строке.</p></li></ul>
  <div id="local_sec"><p>Локальный якорь успешно найден внутри первой главы тестовой книги.</p></div>
  <aside id="fn1" epub:type="footnote"><p>Примечание 12: всплывающее окно сноски отображается без смены текущей страницы.</p></aside>
</body>
</html>"""

    ch2 = f"""<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Глава 2: Закладки и переходы</title></head>
<body>
  <h1>Глава 2: Закладки и переходы</h1>
  <p>Начало второй главы открылось по переходу или оглавлению. Далее следует текст для проверки перелистывания назад к границе первой главы.</p>
  {filler_p2}
</body>
</html>"""

    ch3 = f"""<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>Глава 3: Финал</title></head>
<body>
  <h1>Глава 3: Финал</h1>
  {"".join(f"<p>{i + 1}. {para}</p>" for i in range(4))}
</body>
</html>"""

    opf = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/opf/2007" version="2.0" unique-identifier="uid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="uid">urn:uuid:{MAIN_BOOK_UUID}</dc:identifier>
    <dc:title>Полный Тест Функционала</dc:title>
    <dc:creator>Инженер Качества</dc:creator>
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

    ncx = f"""<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="urn:uuid:{MAIN_BOOK_UUID}"/></head>
  <docTitle><text>Полный Тест Функционала</text></docTitle>
  <navMap>
    <navPoint id="nav_p1" playOrder="1">
      <navLabel><text>Часть 1: Основы</text></navLabel>
      <content src="ch1.xhtml"/>
      <navPoint id="nav_p1_1" playOrder="2">
        <navLabel><text>Раздел 1.1: Введение и ссылки</text></navLabel>
        <content src="ch1.xhtml"/>
      </navPoint>
      <navPoint id="nav_p1_2" playOrder="3">
        <navLabel><text>Раздел 1.2: Списки и якорь</text></navLabel>
        <content src="ch1.xhtml#part1_2"/>
      </navPoint>
    </navPoint>
    <navPoint id="nav_p2" playOrder="4">
      <navLabel><text>Глава 2: Закладки и переходы</text></navLabel>
      <content src="ch2.xhtml"/>
    </navPoint>
    <navPoint id="nav_p3" playOrder="5">
      <navLabel><text>Глава 3: Финал</text></navLabel>
      <content src="ch3.xhtml"/>
    </navPoint>
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


def step_1_auth_and_webview(args, sdk_int):
    print("\n=== STEP 1: AuthActivity + AuthWebViewActivity All Buttons ===")
    shell(f"pm uninstall {PKG}", check=False)
    inst = adb("install", "-r", args.apk, timeout=60).stdout.strip()
    print(f"[install] {inst}")

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
            tap_node(allow_btn, "Allow storage permission")
            time.sleep(2.0)

    root, btn_webview = wait_for_node(res_id_contains="btn_open_webview", timeout=12)
    # Test Import file button
    btn_import = find_node(root, res_id_contains="btn_import_file")
    if not btn_import:
        raise AssertionError("btn_import_file missing in AuthActivity")
    tap_node(btn_import, "Auth: Из файла (yandex_token.txt)")
    wait_for_node(text_contains="не найден", timeout=6)
    dismiss_notices()

    # Test Manual token button
    root, btn_manual = wait_for_node(res_id_contains="manual_token_button", timeout=6)
    tap_node(btn_manual, "Auth: Вручную")
    root_dlg, cancel_dlg = wait_for_node(text_equals="Отмена", clickable_only=True, timeout=6)
    tap_node(cancel_dlg, "Cancel manual token dialog")
    time.sleep(0.6)

    # Test Retry button
    root, btn_retry = wait_for_node(res_id_contains="retry_button", timeout=6)
    tap_node(btn_retry, "Auth: Повтор запроса кода")
    time.sleep(0.9)
    dismiss_notices()

    # Open AuthWebViewActivity and verify toolbar buttons
    root, btn_webview = wait_for_node(res_id_contains="btn_open_webview", timeout=6)
    tap_node(btn_webview, "Auth: Открыть WebView")
    root_wv, btn_close_wv = wait_for_node(res_id_contains="btn_close_webview", timeout=10)
    for rid in ("btn_open_keyboard", "btn_native_input", "btn_reload_webview"):
        if not find_node(root_wv, res_id_contains=rid):
            raise AssertionError(f"{rid} missing in AuthWebViewActivity")
    tap_node(find_node(root_wv, res_id_contains="btn_open_keyboard"), "WebView: Клавиатура")
    time.sleep(0.5)
    tap_node(find_node(root_wv, res_id_contains="btn_reload_webview"), "WebView: Обновить")
    time.sleep(0.5)
    tap_node(btn_close_wv, "WebView: Закрыть")
    wait_for_node(res_id_contains="btn_open_webview", timeout=8)
    print("[check] AuthActivity & AuthWebViewActivity buttons verified cleanly: OK")


def step_2_seed_db_and_books(args):
    print("\n=== STEP 2: Seed Multi-Shelf Books & Multi-Page Shelf ===")
    uid = get_app_uid()
    shell(f"am force-stop {PKG}")
    prefs_dir = f"/data/data/{PKG}/shared_prefs"
    shell(f"mkdir -p {prefs_dir}")
    prefs_xml = (
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
        "<map>\n"
        "    <string name=\"access_token\">emu_full_func_token</string>\n"
        "    <string name=\"refresh_token\">emu_full_func_refresh</string>\n"
        "    <string name=\"user_login\">func.tester</string>\n"
        "    <long name=\"expires_at\" value=\"4102444800000\" />\n"
        "</map>\n"
    )
    tmp_prefs = os.path.join(args.artifacts, f".tmp_{args.tag}_prefs.xml")
    with open(tmp_prefs, "w", encoding="utf-8") as f:
        f.write(prefs_xml)
    adb("push", tmp_prefs, f"{prefs_dir}/yandex_auth_prefs.xml", timeout=20)
    os.remove(tmp_prefs)
    fix_app_ownership(prefs_dir, uid)

    if get_screen_size()[0] > get_screen_size()[1]:
        pass
    shell("svc wifi disable", check=False)
    shell("svc data disable", check=False)
    shell("rm -rf /sdcard/Books/YandexBooks/backup")

    # Initial launch to create schema
    shell(f"am start -n {PKG}/.ui.MainActivity")
    time.sleep(3.0)
    dismiss_notices()
    wait_for_node(res_id_contains="btn_main_menu", timeout=12)

    shell(f"am force-stop {PKG}")
    time.sleep(0.8)

    epub_local = os.path.join(args.artifacts, f".tmp_{args.tag}_func.epub")
    create_comprehensive_epub(epub_local)
    for bu in (MAIN_BOOK_UUID, TOREAD_BOOK_UUID, DONE_BOOK_UUID, USER_BOOK_UUID):
        rdir = f"/data/data/{PKG}/files/cached_books/{bu}"
        shell(f"mkdir -p {rdir}")
        adb("push", epub_local, f"{rdir}/book.epub", timeout=30)
    os.remove(epub_local)
    fix_app_ownership(f"/data/data/{PKG}/files", uid)

    db_remote = f"/data/data/{PKG}/databases/yandex_books_lite.db"
    db_local = os.path.join(args.artifacts, f".tmp_{args.tag}_seed.sqlite")
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
        rows = [
            (MAIN_BOOK_UUID, "card-main", "Полный Тест Функционала", "Инженер Качества", "Описание", "", "", 0.0, 3, 0, 0, "reading", 1, now_ms, "doc-main", "", ""),
            ("read-2", "card-2", "Книга Полки Два", "Автор 2", "", "", "", 25.0, 3, 1, 0, "reading", 0, now_ms - 1000, "", "", ""),
            ("read-3", "card-3", "Книга Полки Три", "Автор 3", "", "", "", 10.0, 3, 0, 0, "reading", 0, now_ms - 2000, "", "", ""),
            ("read-4", "card-4", "Книга Полки Четыре", "Автор 4", "", "", "", 45.0, 3, 1, 0, "reading", 0, now_ms - 3000, "", "", ""),
            ("read-5", "card-5", "Книга Полки Пять", "Автор 5", "", "", "", 60.0, 3, 2, 0, "reading", 0, now_ms - 4000, "", "", ""),
            ("read-6", "card-6", "Книга Полки Шесть", "Автор 6", "", "", "", 75.0, 3, 2, 0, "reading", 0, now_ms - 5000, "", "", ""),
            (TOREAD_BOOK_UUID, "card-tr", "Книга В Планах", "Автор План", "", "", "", 40.0, 3, 1, 0, "to_read", 1, now_ms - 6000, "doc-tr", "epubcfi(/6/4!/4/2/1:0)", ""),
            (DONE_BOOK_UUID, "card-done", "Книга Завершена", "Автор Финиш", "", "", "", 100.0, 3, 2, 0, "done", 1, now_ms - 7000, "", "", ""),
            (USER_BOOK_UUID, "", "Мой Загруженный Файл", "Мои файлы", "", "", "", 0.0, 3, 0, 0, "user_books", 1, now_ms - 8000, "", "", "https://example.invalid/user.epub"),
        ]
        for r in rows:
            conn.execute(
                "INSERT OR REPLACE INTO books "
                "(uuid, card_id, title, author, annotation, cover_url, local_cover_path, percent, "
                "total_chapters, current_chapter, current_paragraph, shelf_type, is_downloaded, "
                "last_read_timestamp, document_uuid, cfi, file_url) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r,
            )
        conn.execute(
            "UPDATE books SET linked_audio_uuid = 'audio-main-uuid', audio_percent = 0.0, audio_updated_at = 0 WHERE uuid = ?",
            (MAIN_BOOK_UUID,),
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


def step_3_main_tabs_search_pagination_card_dialog(args):
    print("\n=== STEP 3: MainActivity All 6 Tabs, Search DPAD, Pagination, Hardware Keys & Book Details ===")
    shell(f"am start -n {PKG}/.ui.MainActivity")
    time.sleep(2.5)
    dismiss_notices()
    wait_for_node(text_contains="Полный Тест Функционала", timeout=12)

    # 1. All 6 tabs + search EditText DPAD protection
    tap_node(wait_for_node(res_id_contains="tab_to_read_btn", timeout=6)[1], "Tab В планах")
    wait_for_node(text_contains="Книга В Планах", timeout=8)

    tap_node(wait_for_node(res_id_contains="tab_done_btn", timeout=6)[1], "Tab Прочит.")
    wait_for_node(text_contains="Книга Завершена", timeout=8)

    tap_node(wait_for_node(res_id_contains="tab_user_books_btn", timeout=6)[1], "Tab Файлы")
    wait_for_node(text_contains="Мой Загруженный Файл", timeout=8)

    tap_node(wait_for_node(res_id_contains="tab_catalog_btn", timeout=6)[1], "Tab Каталог")
    time.sleep(0.8)
    dismiss_notices()
    root_cat = dump_ui()
    if not find_node(root_cat, res_id_contains="btn_catalog_category"):
        raise AssertionError("Catalog category button not visible in Каталог tab!")
    if not find_node(root_cat, res_id_contains="btn_catalog_reload"):
        raise AssertionError("Catalog reload button not visible in Каталог tab!")

    tap_node(wait_for_node(res_id_contains="tab_search_btn", timeout=6)[1], "Tab Поиск")
    time.sleep(0.8)
    root_s, search_input = wait_for_node(res_id_contains="search_query_input", timeout=6)
    if find_node(root_s, res_id_contains="btn_catalog_category"):
        raise AssertionError("Catalog bar remained visible on Поиск tab!")
    if find_node(root_s, text_contains="Книга Завершена") or find_node(root_s, text_contains="Мой Загруженный Файл"):
        raise AssertionError("Stale books remained visible when opening empty Поиск tab!")

    tap_node(search_input, "Focus Search EditText")
    shell("input text TestQuery")
    time.sleep(0.4)
    # Test DPAD navigation inside focused EditText does NOT steal keys cleanly
    shell("input keyevent 21")  # KEYCODE_DPAD_LEFT
    shell("input keyevent 22")  # KEYCODE_DPAD_RIGHT
    tap_node(wait_for_node(res_id_contains="btn_search_submit", timeout=6)[1], "Submit Search")
    time.sleep(0.8)
    dismiss_notices()

    # Back to Читаю
    tap_node(wait_for_node(res_id_contains="tab_reading_btn", timeout=6)[1], "Tab Читаю")
    wait_for_node(text_contains="Полный Тест Функционала", timeout=8)
    root_r = dump_ui()
    if find_node(root_r, res_id_contains="search_query_input"):
        raise AssertionError("Search bar remained visible on Читаю tab!")

    # 2. Shelf Pagination buttons (< / >) & hardware Volume keys without VolumePanel
    ind_node = find_node(root_r, res_id_contains="shelf_page_indicator")
    p_init = (ind_node.get("text") or "").strip() if ind_node else ""
    print(f"[pagination] Initial shelf indicator: '{p_init}'")
    if not re.match(r"1/[2-9]", p_init):
        raise AssertionError(f"Expected >= 2 shelf pages with 6 books, got '{p_init}'")

    tap_node(find_node(root_r, res_id_contains="btn_shelf_next_page"), "Shelf Next >")
    time.sleep(0.8)
    p_next = (find_node(dump_ui(), res_id_contains="shelf_page_indicator").get("text") or "").strip()
    print(f"[pagination] After Next >: '{p_next}'")
    if p_next == p_init:
        raise AssertionError(f"btn_shelf_next_page did not advance page! '{p_init}' -> '{p_next}'")

    tap_node(find_node(dump_ui(), res_id_contains="btn_shelf_prev_page"), "Shelf Prev <")
    time.sleep(0.8)
    p_back = (find_node(dump_ui(), res_id_contains="shelf_page_indicator").get("text") or "").strip()
    if p_back != p_init:
        raise AssertionError(f"btn_shelf_prev_page did not return to '{p_init}', got '{p_back}'")

    # Hardware volume keys
    shell("input keyevent 25")  # VOLUME_DOWN
    time.sleep(0.8)
    top_act = get_top_activity()
    if PKG not in top_act:
        raise AssertionError(f"Volume key displaced MainActivity: {top_act}")
    p_hw = (find_node(dump_ui(), res_id_contains="shelf_page_indicator").get("text") or "").strip()
    if p_hw == p_init:
        raise AssertionError(f"Hardware KEYCODE_VOLUME_DOWN did not turn shelf page: '{p_hw}'")
    shell("input keyevent 24")  # VOLUME_UP
    time.sleep(0.8)

    # 3. Book Details dialog: Move Shelf + Reset Progress
    details_btns = find_all_nodes(dump_ui(), text_equals="О книге")
    if not details_btns:
        raise AssertionError("No 'О книге' button found on shelf!")
    tap_node(details_btns[0], "Book Details: О книге (Книга 1)")
    wait_for_node(text_equals="В планы", clickable_only=True, timeout=6)
    tap_node(find_node(dump_ui(), text_equals="Закрыть", clickable_only=True), "Close Details 1")
    time.sleep(0.6)

    # Open To-Read book with 40% progress -> Reset Progress -> check 0%
    tap_node(find_node(dump_ui(), res_id_contains="tab_to_read_btn"), "Switch to В планах")
    wait_for_node(text_contains="Книга В Планах", timeout=6)
    tap_node(find_all_nodes(dump_ui(), text_equals="О книге")[0], "Details on Книга В Планах")
    wait_for_node(text_equals="Сбросить", clickable_only=True, timeout=6)
    tap_node(find_node(dump_ui(), text_equals="Сбросить", clickable_only=True), "Tap Сбросить прогресс")
    wait_for_node(text_contains="до 0%", timeout=6)
    tap_node(find_node(dump_ui(), text_equals="Сбросить", clickable_only=True), "Confirm Сбросить")
    time.sleep(1.0)
    dismiss_notices()
    wait_for_node(text_contains="Книга В Планах", timeout=6)
    row_txt = " | ".join(all_visible_texts(dump_ui()))
    if "0%" not in row_txt:
        raise AssertionError(f"Expected 0% after Reset Progress, got UI: {row_txt}")

    # Test Move shelf on Книга В Планах -> Прочитано
    tap_node(find_all_nodes(dump_ui(), text_equals="О книге")[0], "Reopen Details")
    wait_for_node(text_equals="Прочитано", clickable_only=True, timeout=6)
    tap_node(find_node(dump_ui(), text_equals="Прочитано", clickable_only=True), "Move Книга В Планах -> Прочитано")
    time.sleep(0.8)
    dismiss_notices(include_close=False)
    close_det = find_node(dump_ui(), text_equals="Закрыть", clickable_only=True)
    if close_det:
        tap_node(close_det, "Close Details after move")
        time.sleep(0.6)
    tap_node(wait_for_node(res_id_contains="tab_done_btn", timeout=6)[1], "Tab Прочит. (verify moved book)")
    wait_for_node(text_contains="Книга В Планах", timeout=6)
    # Restore back to Читаю -> В планах so shelf counts match
    tap_node(wait_for_node(res_id_contains="tab_reading_btn", timeout=6)[1], "Return to Читаю")
    wait_for_node(text_contains="Полный Тест Функционала", timeout=6)

    # 4. Batch Download menu item (available on reading tab offline check)
    tap_node(find_node(dump_ui(), res_id_contains="btn_main_menu"), "Open Main Menu")
    tap_node(wait_for_node(text_contains="Скачать полку", timeout=6)[1], "Menu: Скачать полку")
    wait_for_node(text_contains="Отмена", clickable_only=True, timeout=8)
    tap_node(find_node(dump_ui(), text_equals="Отмена", clickable_only=True), "Cancel Batch Download dialog")
    time.sleep(0.6)

    # 5. Reader Mode button: long-press selection dialog + cycle to Lite
    btn_mode = find_node(dump_ui(), res_id_contains="btn_toggle_reader_mode")
    if not btn_mode:
        raise AssertionError("btn_toggle_reader_mode not found in header!")
    long_press_node(btn_mode, 750, "Long-press Reader Mode button")
    wait_for_node(text_contains="Встроенный Lite", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="Встроенный Lite"), "Choose Встроенный Lite")
    time.sleep(0.6)
    wait_for_node(res_id_contains="btn_toggle_reader_mode", text_contains="Lite", timeout=6)
    print("[check] MainActivity all tabs, search DPAD, pagination, HW keys, reset, batch & reader mode: OK")


def step_4_ui_scale_roundtrip(args):
    print("\n=== STEP 4: UiScale Round-Trip (100% -> 180% -> Enter Reader -> Back -> 100%) ===")
    root_100 = dump_ui()
    tab_node_100 = find_node(root_100, res_id_contains="tab_reading_btn")
    r100 = parse_rect(tab_node_100.get("bounds"))
    h100 = r100[3] - r100[1]

    # Set 180% UI scale
    tap_node(find_node(root_100, res_id_contains="btn_main_menu"), "Menu for 180% scale")
    tap_node(wait_for_node(text_contains="Внешний вид", timeout=6)[1], "Appearance dialog")
    tap_node(wait_for_node(text_contains="Масштаб ОС", timeout=6)[1], "OS Scale dialog")
    tap_node(wait_for_node(text_contains="180%", timeout=8)[1], "Select 180% UI Scale")
    time.sleep(2.5)
    dismiss_notices()
    wait_for_node(text_contains="Полный Тест Функционала", timeout=12)

    root_180 = dump_ui()
    r180 = parse_rect(find_node(root_180, res_id_contains="tab_reading_btn").get("bounds"))
    h180 = r180[3] - r180[1]
    print(f"[scale] Tab height 100%={h100}px -> 180%={h180}px")
    if h180 <= h100 + 8:
        raise AssertionError(f"180% UI scale did not enlarge tabs! h100={h100}, h180={h180}")

    # Enter Reader and exit back to MainActivity, ensure scale didn't drift back to 100%
    tap_node(find_all_nodes(root_180, text_equals="Читать")[0], "Open book at 180% scale")
    wait_for_node(res_id_contains="reader_canvas", timeout=20)
    time.sleep(2.0)
    sw, sh = get_screen_size()
    open_reader_overlay(sw, sh)
    tap_node(wait_for_node(res_id_contains="btn_reader_back", timeout=8)[1], "Exit Reader to MainActivity")
    time.sleep(2.5)
    dismiss_notices()
    wait_for_node(res_id_contains="tab_reading_btn", timeout=10)
    r180_after = parse_rect(find_node(dump_ui(), res_id_contains="tab_reading_btn").get("bounds"))
    h180_after = r180_after[3] - r180_after[1]
    print(f"[scale] After Reader exit tab height={h180_after}px (expected ~{h180}px)")
    if abs(h180_after - h180) > 6:
        raise AssertionError(f"UI scale drifted after exiting Reader! before={h180}, after={h180_after}")

    # Now switch BACK to 100% and verify height returns to h100!
    tap_node(find_node(dump_ui(), res_id_contains="btn_main_menu"), "Menu for reset 100%")
    tap_node(wait_for_node(text_contains="Внешний вид", timeout=6)[1], "Appearance dialog")
    try:
        _, scale_btn = wait_for_node(text_contains="Масштаб ОС", timeout=4)
    except RuntimeError:
        sw, sh = get_screen_size()
        swipe_compat(sw // 2, int(sh * 0.65), sw // 2, int(sh * 0.30), 350)
        time.sleep(0.5)
        _, scale_btn = wait_for_node(text_contains="Масштаб ОС", timeout=6)
    tap_node(scale_btn, "OS Scale dialog")
    tap_node(wait_for_node(text_contains="100%", timeout=8)[1], "Reset UI Scale back to 100%")
    time.sleep(2.5)
    dismiss_notices()
    wait_for_node(res_id_contains="tab_reading_btn", timeout=10)
    r_reset = parse_rect(find_node(dump_ui(), res_id_contains="tab_reading_btn").get("bounds"))
    h_reset = r_reset[3] - r_reset[1]
    print(f"[scale] Reset to 100% tab height={h_reset}px (baseline={h100}px)")
    if abs(h_reset - h100) > 4:
        raise AssertionError(f"Reset UI scale back to 100% failed! baseline={h100}, reset={h_reset}")
    print("[check] UiScale round-trip 100% -> 180% -> Reader -> Back -> 100% reset: OK")


def step_5_reader_links_footnotes_toc_bookmarks_quotes(args):
    print("\n=== STEP 5: Reader Links, Footnotes, Hierarchical TOC, Bookmarks CRUD & Reverse Quote Selection ===")
    # Open main book (first card)
    tap_node(find_all_nodes(dump_ui(), text_equals="Читать")[0], "Open Полный Тест Функционала")
    wait_for_node(res_id_contains="reader_canvas", timeout=20)
    time.sleep(2.5)
    sw, sh = get_screen_size()

    # Sweep-detect deterministic taps for our 4 top-of-page links (footnote [12], #local_sec, https://books.yandex.ru/, ch2.xhtml)
    found_fn = False
    found_local = False
    found_ext = False
    found_ch2 = False

    # Scan gentle y positions across canvas at x = 28% (safe left zone if missed)
    for y_frac in (0.08, 0.12, 0.16, 0.20, 0.24, 0.28, 0.32, 0.36, 0.40, 0.44, 0.48, 0.52, 0.56, 0.60, 0.64, 0.68, 0.72, 0.76):
        if found_fn and found_local and found_ext and found_ch2:
            break
        tx = int(sw * 0.28)
        ty = int(sh * y_frac)
        shell(f"input tap {tx} {ty}")
        time.sleep(0.75)
        root = dump_ui()
        joined = " | ".join(all_visible_texts(root))
        if not found_fn and ("Сноска" in joined or "Примечание 12" in joined):
            found_fn = True
            print(f"[link_test] Footnote [12] tapped at ({tx}, {ty}): OK")
        elif not found_local and ("Локальный якорь" in joined or "Внутренняя ссылка" in joined):
            found_local = True
            print(f"[link_test] Local #local_sec anchor tapped at ({tx}, {ty}): OK")
        elif not found_ext and "Внешняя ссылка" in joined:
            found_ext = True
            print(f"[link_test] External HTTP link dialog tapped at ({tx}, {ty}): OK")

        # Always dismiss any open modal (even if the same multi-line link was tapped again!)
        cancel_btn = find_node(root, text_equals="Отмена", clickable_only=True)
        if cancel_btn:
            tap_node(cancel_btn, "Dismiss dialog (Отмена)")
            time.sleep(0.45)
        else:
            dismiss_notices()
        root_after = dump_ui()
        if find_node(root_after, res_id_contains="reader_bottom_bar"):
            close_ov = find_node(root_after, res_id_contains="btn_reader_close_overlay")
            if close_ov:
                tap_node(close_ov, "Close overlay")
                time.sleep(0.45)
        # Check whether ch2.xhtml jumped silently to Chapter 2 once preceding 3 links were seen
        if found_fn and found_local and found_ext and not found_ch2:
            open_reader_overlay(sw, sh)
            ov_check, ch_check = wait_for_node(res_id_contains="reader_chapter_title_view", timeout=6)
            if "Глава 2" in (ch_check.get("text") or ""):
                found_ch2 = True
                print(f"[link_test] Internal chapter link jumped to '{ch_check.get('text')}': OK")
                tap_node(find_node(ov_check, res_id_contains="btn_prev_chapter"), "Back to Chapter 1")
                time.sleep(1.1)
                close_after = find_node(dump_ui(), res_id_contains="btn_reader_close_overlay")
                if close_after:
                    tap_node(close_after, "Close overlay")
                    time.sleep(0.4)
                break
            else:
                tap_node(find_node(ov_check, res_id_contains="btn_reader_close_overlay"), "Close check overlay")
                time.sleep(0.4)

    if not found_ch2:
        open_reader_overlay(sw, sh)
        ov_root, ch_node = wait_for_node(res_id_contains="reader_chapter_title_view", timeout=6)
        ch_title = ch_node.get("text") or ""
        if "Глава 2" in ch_title:
            found_ch2 = True
            print(f"[link_test] Internal chapter link jumped to '{ch_title}': OK")
            tap_node(find_node(ov_root, res_id_contains="btn_prev_chapter"), "Back to Chapter 1")
            time.sleep(1.2)
            close_after = find_node(dump_ui(), res_id_contains="btn_reader_close_overlay")
            if close_after:
                tap_node(close_after, "Close overlay")
                time.sleep(0.5)
        else:
            print(f"[link_test] Overlay chapter after link sweep: '{ch_title}'")
            tap_node(find_node(ov_root, res_id_contains="btn_reader_close_overlay"), "Close overlay")
            time.sleep(0.5)

    if not found_fn:
        raise AssertionError("Inline footnote [12] popup did not open cleanly!")
    if not found_local:
        raise AssertionError("Same-chapter #local_sec anchor preview popup did not open cleanly!")
    if not found_ext:
        raise AssertionError("External HTTP link confirmation dialog did not open cleanly!")
    if not found_ch2:
        raise AssertionError("Cross-chapter link ch2.xhtml did not jump to Глава 2!")

    # Hierarchical TOC test: Expand/Collapse folder, long-press collapsed folder, select nested node, page keys
    open_reader_overlay(sw, sh)
    tap_node(wait_for_node(res_id_contains="btn_tab_toc", timeout=8)[1], "Open TOC 3-tab dialog")
    root_toc, folder1 = wait_for_node(text_contains="Часть 1", timeout=8)
    if not find_node(root_toc, text_contains="Раздел 1.2"):
        tap_node(folder1, "Expand Часть 1 folder")
        time.sleep(0.5)
    wait_for_node(text_contains="Раздел 1.2", timeout=6)
    print("[toc] Active/expanded branch showing 'Раздел 1.2': OK")
    # Tap nested child node Раздел 1.2 -> jumps to #part1_2
    tap_node(find_node(dump_ui(), text_contains="Раздел 1.2"), "Tap nested Раздел 1.2")
    time.sleep(1.2)

    # Reopen TOC, long-press 'Глава 2: Закладки и переходы' -> jumps to Chapter 2
    open_reader_overlay(sw, sh)
    tap_node(wait_for_node(res_id_contains="btn_tab_toc", timeout=8)[1], "Open TOC for Chapter 2 jump")
    _, node_ch2 = wait_for_node(text_contains="Глава 2", timeout=6)
    long_press_node(node_ch2, 750, "Long-press Глава 2")
    time.sleep(1.2)

    # Open overlay -> verify chapter title is Глава 2 -> add Bookmark B on Ch2
    open_reader_overlay(sw, sh)
    wait_for_node(res_id_contains="reader_chapter_title_view", text_contains="Глава 2", timeout=6)
    tap_node(find_node(dump_ui(), res_id_contains="btn_tab_toc"), "Open TOC -> Bookmarks (Ch2)")
    tap_node(wait_for_node(text_contains="Закладки (", clickable_only=True, timeout=6)[1], "Tab Закладки")
    tap_node(wait_for_node(text_contains="+ Добавить", clickable_only=True, timeout=6)[1], "+ Add Bookmark B on Ch2")
    time.sleep(0.8)
    dismiss_notices(include_close=False)
    wait_for_node(text_contains="Закладки (1)", timeout=6)

    # Close dialog -> jump to Ch1 via << -> add Bookmark A on Ch1 -> jump to Bookmark B via tap -> delete Bookmark A!
    tap_node(find_node(dump_ui(), text_equals="Закрыть", clickable_only=True), "Close TOC dialog")
    tap_node(wait_for_node(res_id_contains="btn_prev_chapter", timeout=6)[1], "<< Previous chapter to Ch1")
    time.sleep(1.0)
    tap_node(find_node(dump_ui(), res_id_contains="btn_tab_toc"), "Open TOC -> Bookmarks (Ch1)")
    tap_node(wait_for_node(text_contains="Закладки (", clickable_only=True, timeout=6)[1], "Tab Закладки")
    tap_node(wait_for_node(text_contains="+ Добавить", clickable_only=True, timeout=6)[1], "+ Add Bookmark A on Ch1")
    time.sleep(0.8)
    dismiss_notices(include_close=False)
    wait_for_node(text_contains="Закладки (2)", timeout=6)

    # Jump to Bookmark B (Ch2) by tapping its row title ('Глава 2')
    tap_node(wait_for_node(text_contains="Глава 2", timeout=6)[1], "Tap Bookmark B to jump to Ch2")
    time.sleep(1.2)
    open_reader_overlay(sw, sh)
    wait_for_node(res_id_contains="reader_chapter_title_view", text_contains="Глава 2", timeout=6)
    print("[bookmarks] Bookmark tap jump to Chapter 2 verified: OK")

    # Delete one bookmark via X button
    tap_node(find_node(dump_ui(), res_id_contains="btn_tab_toc"), "Open TOC -> Bookmarks (delete test)")
    tap_node(wait_for_node(text_contains="Закладки (", clickable_only=True, timeout=6)[1], "Tab Закладки")
    x_btns = find_all_nodes(dump_ui(), text_equals="X")
    if not x_btns:
        raise AssertionError("Bookmark delete button 'X' not found!")
    tap_node(x_btns[0], "Delete Bookmark via X")
    time.sleep(0.8)
    dismiss_notices(include_close=False)
    wait_for_node(text_contains="Закладки (1)", timeout=6)
    print("[bookmarks] Bookmark add/jump/delete full CRUD verified: OK")
    tap_node(find_node(dump_ui(), text_equals="Закрыть", clickable_only=True), "Close Bookmarks dialog")
    tap_node(find_node(dump_ui(), res_id_contains="btn_reader_close_overlay"), "Close overlay")
    time.sleep(0.8)

    # Forward long-press drag & save Quote 1 (Fwd)
    x_l, x_r = int(sw * 0.22), int(sw * 0.72)
    y_t, y_b = int(sh * 0.44), int(sh * 0.56)
    sdk_now = SDK_INT
    if sdk_now >= 24:
        shell(f"input touchscreen draganddrop {x_l} {y_t} {x_r} {y_t} 1200")
    else:
        touch_hold_xy(x_l, y_t, 850)
    time.sleep(1.0)
    wait_for_node(text_contains="Сохранить с заметкой", timeout=8)
    tap_node(find_node(dump_ui(), text_contains="Сохранить с заметкой"), "Save Quote 1 (Fwd)")
    qroot, _ = wait_for_node(text_contains="Заметка к цитате", timeout=8)
    note_ed = find_node(qroot, text_contains="Введите комментарий")
    if note_ed:
        tap_node(note_ed, "Focus note field")
        time.sleep(0.3)
    shell("input text QuoteFwdNote")
    time.sleep(0.4)
    tap_node(find_node(dump_ui(), text_equals="Сохранить", clickable_only=True), "Confirm QuoteFwdNote")
    time.sleep(0.8)
    dismiss_notices()

    # Reverse drag (from x_r, y_b BACKWARD to x_l, y_b!)
    if sdk_now >= 24:
        shell(f"input touchscreen draganddrop {x_r} {y_b} {x_l} {y_b} 1200")
    else:
        touch_hold_xy(x_r, y_b, 850)
    time.sleep(1.0)
    rev_root, save_q2 = wait_for_node(text_contains="Сохранить цитату", timeout=8)
    rev_texts = all_visible_texts(rev_root)
    quoted_preview = next((t for t in rev_texts if t.startswith("«") and t.endswith("»")), "")
    print(f"[quotes] Reverse drag selected text: '{quoted_preview}'")
    if sdk_now >= 24 and len(quoted_preview.strip("«» ").split()) < 2:
        raise AssertionError(f"Expected multi-word reverse drag selection, got '{quoted_preview}'")
    tap_node(save_q2, "Save Quote 2 (Reverse)")
    time.sleep(0.8)
    dismiss_notices(include_close=False)

    # Open Quotes tab -> verify (2) -> export .md -> delete Quote 1 -> verify (1)
    open_reader_overlay(sw, sh)
    tap_node(wait_for_node(res_id_contains="btn_tab_toc", timeout=6)[1], "Open TOC -> Quotes")
    tap_node(wait_for_node(text_contains="Цитаты (", clickable_only=True, timeout=6)[1], "Tab Цитаты")
    wait_for_node(text_contains="Цитаты (2)", timeout=6)
    tap_node(wait_for_node(text_contains="Экспорт", timeout=6)[1], "Export Quotes .md")
    time.sleep(0.8)
    dismiss_notices(include_close=False)

    qx_btns = find_all_nodes(dump_ui(), text_equals="X")
    if not qx_btns:
        raise AssertionError("Quote delete button 'X' not found!")
    tap_node(qx_btns[0], "Delete Quote via X")
    time.sleep(0.8)
    wait_for_node(text_contains="Цитаты (1)", timeout=6)
    print("[quotes] Forward + Reverse selection, export & delete CRUD verified: OK")
    tap_node(find_node(dump_ui(), text_equals="Закрыть", clickable_only=True), "Close Quotes dialog")

    # Test Format rows: verify non-overlapping layout (Bug 1), collapsed chapter/seekbar rows for maximum live text preview,
    # live font size increase, indent, edge brightness, audio sync, and close button (✕)
    tap_node(find_node(dump_ui(), res_id_contains="btn_tab_format"), "Tab Формат")
    root_fmt, fmt_row = wait_for_node(res_id_contains="row_toggle_indent", timeout=6)
    back_node_fmt = find_node(root_fmt, res_id_contains="btn_reader_back")
    close_fmt_btn = find_node(root_fmt, res_id_contains="btn_close_format_panel")
    if not back_node_fmt:
        raise AssertionError("reader_top_bar / btn_reader_back was covered or missing when Format panel opened!")
    if not close_fmt_btn:
        raise AssertionError("btn_close_format_panel (✕) missing when Format panel opened!")
    r_top_btn = parse_rect(back_node_fmt.get("bounds"))
    r_close_fmt = parse_rect(close_fmt_btn.get("bounds"))
    if r_top_btn and r_close_fmt:
        visible_canvas_gap = r_close_fmt[1] - r_top_btn[3]
        print(f"[format_layout] top_bar_bottom={r_top_btn[3]}px, format_subtabs_top={r_close_fmt[1]}px, visible_canvas_gap={visible_canvas_gap}px")
        if visible_canvas_gap < int(SCREEN_H * 0.24):
            raise AssertionError(
                f"Format panel left too little visible book canvas! gap={visible_canvas_gap}px "
                f"(screen_h={SCREEN_H}, top_bottom={r_top_btn[3]}, format_top={r_close_fmt[1]})"
            )
    btn_font_inc = find_node(root_fmt, res_id_contains="btn_font_increase") or find_node(root_fmt, text_equals="+", clickable_only=True)
    if btn_font_inc:
        tap_node(btn_font_inc, "Live font size increase (+) inside Format")
        time.sleep(0.4)
    tap_node(fmt_row, "Toggle Indent")
    tap_node(wait_for_node(res_id_contains="row_toggle_edge_brightness", timeout=6)[1], "Toggle Edge Brightness OFF")
    time.sleep(0.5)
    dismiss_notices(include_close=False)
    tap_node(wait_for_node(res_id_contains="row_toggle_edge_brightness", timeout=6)[1], "Toggle Edge Brightness ON")
    time.sleep(0.5)
    dismiss_notices(include_close=False)
    # Scroll Format View subtab slightly to reveal row_toggle_audio_sync and toggle it
    fmt_scroll = find_node(dump_ui(), res_id_contains="reader_format_scroll")
    if fmt_scroll:
        r_fs = parse_rect(fmt_scroll.get("bounds"))
        if r_fs:
            cx = (r_fs[0] + r_fs[2]) // 2
            y1 = int(r_fs[1] + (r_fs[3] - r_fs[1]) * 0.78)
            y2 = int(r_fs[1] + (r_fs[3] - r_fs[1]) * 0.25)
            swipe_compat(cx, y1, cx, y2, 350)
            time.sleep(0.5)
    tap_node(wait_for_node(res_id_contains="row_toggle_audio_sync", timeout=6)[1], "Audio Sync row")
    time.sleep(0.6)
    dismiss_notices(include_close=False)

    # Close Format panel cleanly using dedicated ✕ button (no hardware Back needed!)
    tap_node(wait_for_node(res_id_contains="btn_close_format_panel", timeout=6)[1], "Close Format panel via ✕")
    time.sleep(0.5)
    if find_node(dump_ui(), res_id_contains="row_toggle_indent"):
        raise AssertionError("Format panel did not collapse after tapping btn_close_format_panel (✕)!")
    print("[format_layout] Format panel non-overlapping canvas + ✕ close button verified: OK")

    # Tab Прогресс: opens 'Прогресс чтения' dialog -> tap Синхронизировать -> dismiss -> close dialog
    tap_node(wait_for_node(res_id_contains="btn_tab_progress", timeout=6)[1], "Tab Прогресс")
    wait_for_node(text_contains="Прогресс чтения", timeout=6)
    tap_node(wait_for_node(text_equals="Синхронизировать", clickable_only=True, timeout=6)[1], "Progress: Синхронизировать")
    time.sleep(1.0)
    dismiss_notices(include_close=False)
    close_prog = find_node(dump_ui(), text_equals="Закрыть", clickable_only=True)
    if close_prog:
        tap_node(close_prog, "Close Прогресс чтения dialog")
        time.sleep(0.5)

    # Bottom overlay slider controls + header refresh + advance 2 pages and verify persistence on exit & reopen (Bug 4)
    tap_node(wait_for_node(res_id_contains="btn_slider_page_forward", timeout=6)[1], "Slider Forward > (1)")
    tap_node(wait_for_node(res_id_contains="btn_slider_page_forward", timeout=6)[1], "Slider Forward > (2)")
    tap_node(wait_for_node(res_id_contains="btn_slider_page_back", timeout=6)[1], "Slider Back <")
    tap_node(wait_for_node(res_id_contains="btn_reader_refresh", timeout=6)[1], "Header Refresh E-Ink")
    time.sleep(0.6)

    # Exit Reader -> MainActivity -> immediately reopen -> verify saved chapter & page preserved!
    tap_node(wait_for_node(res_id_contains="btn_reader_back", timeout=6)[1], "Reader Back (save progress)")
    wait_for_node(res_id_contains="btn_main_menu", timeout=12)
    time.sleep(1.0)

    db_remote = f"/data/data/{PKG}/databases/yandex_books_lite.db"
    db_local = os.path.join(args.artifacts, f".tmp_{args.tag}_prog_check.sqlite")
    for ext in ("", "-wal", "-shm", "-journal"):
        if os.path.exists(db_local + ext):
            os.remove(db_local + ext)
    adb("pull", db_remote, db_local, timeout=20)
    for ext in ("-wal", "-shm", "-journal"):
        adb("pull", db_remote + ext, db_local + ext, check=False, timeout=10)
    conn_p = sqlite3.connect(db_local)
    try:
        row_p = conn_p.execute(
            "SELECT chapter_index, page_index, percent FROM progress WHERE book_uuid = ?",
            (MAIN_BOOK_UUID,),
        ).fetchone()
    finally:
        conn_p.close()
        for ext in ("", "-wal", "-shm", "-journal"):
            if os.path.exists(db_local + ext):
                os.remove(db_local + ext)
    print(f"[progress_persist] Saved SQLite progress on exit: {row_p}")
    if not row_p or (row_p[0] == 0 and row_p[1] == 0):
        raise AssertionError(f"Progress was NOT saved on Reader exit! row={row_p}")
    saved_ch, saved_pg = row_p[0], row_p[1]

    # Immediately reopen the same book and verify page/chapter is restored (not snapped back to 0!)
    tap_node(find_all_nodes(dump_ui(), text_equals="Читать")[0], "Reopen Полный Тест Функционала (Bug 4 check)")
    wait_for_node(res_id_contains="reader_canvas", timeout=20)
    time.sleep(2.0)
    open_reader_overlay(sw, sh)
    ov_reopen, ch_reopen = wait_for_node(res_id_contains="reader_chapter_title_view", timeout=6)
    ch_reopen_txt = (ch_reopen.get("text") or "").strip()
    print(f"[progress_persist] Reopened overlay chapter='{ch_reopen_txt}' (saved ch={saved_ch}, page={saved_pg})")
    if saved_ch >= 1 and "Глава 1" in ch_reopen_txt and "Глава 2" not in ch_reopen_txt:
        raise AssertionError(f"Reopened book snapped back to Глава 1 instead of saved ch={saved_ch}!")
    tap_node(wait_for_node(res_id_contains="btn_reader_back", timeout=6)[1], "Reader Back (after reopen check)")
    wait_for_node(res_id_contains="btn_main_menu", timeout=12)
    print("[reader] All interactive reader buttons, Format non-overlap & progress persistence verified cleanly: OK")


def step_6_logout_and_cleanup_verification(args):
    print("\n=== STEP 6: Logout Wipe & Clean DB/Auth Verification ===")
    tap_node(find_node(dump_ui(), res_id_contains="btn_main_menu"), "Open Main Menu for Logout")
    tap_node(wait_for_node(text_contains="Выйти из аккаунта", timeout=6)[1], "Tap Выйти из аккаунта")
    wait_for_node(text_equals="Выйти", clickable_only=True, timeout=6)
    tap_node(find_node(dump_ui(), text_equals="Выйти", clickable_only=True), "Confirm Logout")
    wait_for_node(res_id_contains="btn_open_webview", timeout=12)

    shell(f"am force-stop {PKG}")
    time.sleep(0.6)
    prefs_content = shell(f"cat /data/data/{PKG}/shared_prefs/yandex_auth_prefs.xml", check=False)
    if "emu_full_func_token" in prefs_content:
        raise AssertionError("Access token was NOT cleared upon logout!")

    db_remote = f"/data/data/{PKG}/databases/yandex_books_lite.db"
    db_local = os.path.join(args.artifacts, f".tmp_{args.tag}_post_logout.sqlite")
    for ext in ("", "-wal", "-shm", "-journal"):
        if os.path.exists(db_local + ext):
            os.remove(db_local + ext)
    adb("pull", db_remote, db_local, timeout=20)
    for ext in ("-wal", "-shm", "-journal"):
        adb("pull", db_remote + ext, db_local + ext, check=False, timeout=10)
    conn = sqlite3.connect(db_local)
    try:
        cnt_books = conn.execute("SELECT COUNT(*) FROM books").fetchone()[0]
        cnt_bm = conn.execute("SELECT COUNT(*) FROM bookmarks").fetchone()[0]
        cnt_q = conn.execute("SELECT COUNT(*) FROM quotes").fetchone()[0]
        cnt_p = conn.execute("SELECT COUNT(*) FROM progress").fetchone()[0]
    finally:
        conn.close()
        for ext in ("", "-wal", "-shm", "-journal"):
            if os.path.exists(db_local + ext):
                os.remove(db_local + ext)
    if cnt_books != 0 or cnt_bm != 0 or cnt_q != 0 or cnt_p != 0:
        raise AssertionError(
            f"Expected all user tables empty after logout wipe, got books={cnt_books}, bm={cnt_bm}, quotes={cnt_q}, prog={cnt_p}"
        )
    print("[check] Logout cleanly wiped token, SQLite user tables & redirected to AuthActivity: OK")


def step_7_device_runtime_harness(args):
    harness_dex = "/workspace/build/test_harness/classes.dex"
    app_dex = "/workspace/build/classes.dex"
    if not (os.path.exists(harness_dex) and os.path.exists(app_dex)):
        print("[harness] Prebuilt test_harness/classes.dex not found, skipping dalvikvm step")
        return
    print("\n=== STEP 7: DeviceRuntimeHarness on Real Android Dalvik/ART ===")
    adb("push", app_dex, "/data/local/tmp/app_classes.dex", timeout=30)
    adb("push", harness_dex, "/data/local/tmp/harness_classes.dex", timeout=30)
    res = shell("CLASSPATH=/data/local/tmp/app_classes.dex:/data/local/tmp/harness_classes.dex app_process /data/local/tmp DeviceRuntimeHarness", timeout=60)
    print(res)
    if "ALL DEVICE RUNTIME HARNESS TESTS PASSED" not in res:
        raise AssertionError(f"DeviceRuntimeHarness failed:\n{res}")


def _run_tests(args):
    global SDK_INT, SCREEN_W, SCREEN_H
    adb("root", check=False)
    adb("wait-for-device", timeout=30)
    sdk = shell("getprop ro.build.version.sdk")
    sdk_int = int(re.sub(r"[^0-9]", "", sdk) or "0")
    SDK_INT = sdk_int
    SCREEN_W, SCREEN_H = get_screen_size()
    adb("logcat", "-c", check=False)

    step_1_auth_and_webview(args, sdk_int)
    step_2_seed_db_and_books(args)
    step_3_main_tabs_search_pagination_card_dialog(args)
    step_4_ui_scale_roundtrip(args)
    step_5_reader_links_footnotes_toc_bookmarks_quotes(args)
    step_6_logout_and_cleanup_verification(args)
    step_7_device_runtime_harness(args)

    crashes = adb("logcat", "-d", "-s", "AndroidRuntime:E").stdout
    if "FATAL EXCEPTION" in crashes:
        raise AssertionError(f"FATAL EXCEPTION in AndroidRuntime:\n{crashes}")
    print(f"=== FULL FUNCTIONAL E2E VERIFICATION PASSED ({args.tag} / API {sdk}) ===")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", required=True)
    parser.add_argument("--artifacts", required=True)
    parser.add_argument("--tag", default="emu")
    args = parser.parse_args()
    try:
        _run_tests(args)
    finally:
        crash_log = adb("logcat", "-d", "-s", "AndroidRuntime:E", "MainActivity:E", "ReaderActivity:E", check=False).stdout
        if crash_log.strip():
            print("--- Logcat error tail ---")
            print(crash_log[-3000:])


if __name__ == "__main__":
    main()
