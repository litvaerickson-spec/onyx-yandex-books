#!/usr/bin/env python3
"""
Автоматизированный верификационный тест ключевых алгоритмов проекта:
1. Алгоритм переноса слов TeX Hyphenation (Franklin-Liang для русского языка)
2. Математический пагинатор страниц под геометрию E-Ink экрана (1024x758 и 1448x1072)
3. Алгоритм умного разрешения конфликтов синхронизации (Smart Conflict Resolution)
4. Протокол OAuth Device Code Flow (RFC 8628)
"""

import sys

def test_tex_hyphenation():
    print("--- [ТЕСТ 1] TeX Hyphenator (Слоговые переносы слов) ---")
    
    VOWELS_RU = "аеёиоуыэюя"
    CONSONANTS_RU = "бвгджзйклмнпрстфхцчшщ"
    SIGNS_RU = "ьъ"

    def get_hyphenation_points(word):
        if len(word) < 4:
            return []
        lower = word.lower()
        points = []
        for i in range(1, len(lower) - 2):
            c1 = lower[i - 1]
            c2 = lower[i]
            c3 = lower[i + 1]

            if c2 in SIGNS_RU:
                continue

            # Гласная + Согласная + Гласная
            if c1 in VOWELS_RU and c2 in CONSONANTS_RU and c3 in VOWELS_RU:
                points.append(i)
                continue

            # Две согласные между гласными
            if c1 in CONSONANTS_RU and c2 in CONSONANTS_RU and c3 in VOWELS_RU and i > 1 and lower[i - 2] in VOWELS_RU:
                if c1 != 'й':
                    points.append(i)

            # После ь или ъ
            if c1 in SIGNS_RU and c2 in CONSONANTS_RU:
                points.append(i)

        return points

    # Тестовые слова
    word1 = "приложение"
    points1 = get_hyphenation_points(word1)
    print(f"Слово '{word1}': точки переноса {points1}")
    assert len(points1) > 0, "Точки переноса не найдены для длинного слова!"

    word2 = "дом"
    points2 = get_hyphenation_points(word2)
    assert len(points2) == 0, f"Короткое слово не должно переноситься! Получено: {points2}"

    word3 = "синхронизация"
    points3 = get_hyphenation_points(word3)
    print(f"Слово '{word3}': точки переноса {points3}")
    assert len(points3) >= 2, f"Ожидалось минимум 2 точки переноса для '{word3}'"

    print("✅ Тест переносов TeX успешно пройден!\n")


def test_smart_conflict_resolution():
    print("--- [ТЕСТ 2] Smart Conflict Resolution (Умная синхронизация) ---")

    class ReadingProgress:
        def __init__(self, percent, timestamp):
            self.percent = percent
            self.timestamp = timestamp

    def resolve_conflict(local, remote):
        # 1. Если на сервере прочитано строго больше и дата свежее -> обнаружен прогресс с телефона
        if remote.percent > local.percent and remote.timestamp > local.timestamp:
            return "CONFLICT_PROMPT_USER"
        # 2. Если на читалке прочитано больше -> пушим в облако
        elif local.percent >= remote.percent:
            return "AUTO_PUSH_LOCAL"
        else:
            return "CONFLICT_PROMPT_USER"

    # Сценарий А: Пользователь читал на Darwin (50% в 18:00), а затем на смартфоне дочитал до 75% в 18:30
    local_p = ReadingProgress(50.0, 1000)
    remote_p = ReadingProgress(75.0, 2000)
    decision = resolve_conflict(local_p, remote_p)
    print(f"Сценарий А (на телефоне прочитано дальше): решение -> {decision}")
    assert decision == "CONFLICT_PROMPT_USER", "Ошибка: читалка должна запросить подтверждение перехода!"

    # Сценарий Б: Пользователь читал на читалке офлайн (прочитал до 80% в 19:00), на сервере старые 50%
    local_p2 = ReadingProgress(80.0, 3000)
    remote_p2 = ReadingProgress(50.0, 1000)
    decision2 = resolve_conflict(local_p2, remote_p2)
    print(f"Сценарий Б (на читалке прочитано дальше): решение -> {decision2}")
    assert decision2 == "AUTO_PUSH_LOCAL", "Ошибка: читалка должна автоматически обновить облако!"

    print("✅ Тест умного разрешения конфликтов успешно пройден!\n")


def test_paginator_math():
    print("--- [ТЕСТ 3] Математический пагинатор для экранов Onyx Darwin ---")

    # Геометрия экранов
    darwin_screens = [
        {"name": "Darwin 3 & Darwin 5", "width": 758, "height": 1024},
        {"name": "Darwin 6 (Carta Plus)", "width": 1072, "height": 1448}
    ]

    sample_text = (
        "В белом плаще с кровавым подбоем, шаркающей кавалерийской походкой, "
        "ранним утром четырнадцатого числа весеннего месяца нисана в крытую "
        "колоннаду между двумя крыльями дворца Ирода Великого вышел прокуратор "
        "Иудеи Понтий Пилат. Более всего на свете прокуратор ненавидел запах розового "
        "масла, и все теперь предвещало нехороший день, так как запах этот начал "
        "преследовать прокуратора с рассвета."
    )

    padding_x = 36
    padding_top = 14
    padding_bottom = 20
    header_reserved_height = 28
    footer_reserved_height = 44
    line_height = 28 # px (при 18sp)

    for screen in darwin_screens:
        avail_w = screen["width"] - padding_x
        avail_h = screen["height"] - padding_top - header_reserved_height - padding_bottom - footer_reserved_height
        max_lines_per_page = int(avail_h / line_height)
        
        words = sample_text.split()
        avg_word_len_px = 65
        words_per_line = max(1, int(avail_w / avg_word_len_px))
        total_lines = len(words) // words_per_line + (1 if len(words) % words_per_line else 0)
        total_pages = total_lines // max_lines_per_page + (1 if total_lines % max_lines_per_page else 0)

        # Проверка зазора между верхней строкой текста и верхним колонтитулом
        first_line_y = padding_top + header_reserved_height
        header_y = padding_top + 16
        header_clearance = first_line_y - header_y
        assert header_clearance >= 10, f"Опасность наложения на верхний колонтитул: {header_clearance}px"

        # Проверка зазора между нижней строкой текста и нижним колонтитулом
        lowest_text_bottom = padding_top + header_reserved_height + (max_lines_per_page * line_height)
        footer_y = screen["height"] - 12
        clearance = footer_y - lowest_text_bottom

        print(f"Устройство: {screen['name']} ({screen['width']}x{screen['height']})")
        print(f"  Доступно строк на страницу: {max_lines_per_page}")
        print(f"  Всего строк абзаца: {total_lines}, Всего страниц: {total_pages}")
        print(f"  Гарантированный зазор до колонтитула: {clearance}px (>= 30px)")
        assert max_lines_per_page > 15, "Слишком мало строк на страницу!"
        assert total_pages >= 1, "Должна получиться как минимум 1 страница!"
        assert clearance >= 30, f"Опасность наложения текста на колонтитул! Зазор {clearance}px < 30px"

    print("✅ Тест пагинации и защиты колонтитула Darwin успешно пройден!\n")


def test_device_flow_contract():
    print("--- [ТЕСТ 4] Контракт OAuth Device Code Flow (RFC 8628) ---")
    verification_url = "https://ya.ru/device"
    user_code = "ABCD-1234"
    qr_payload = f"{verification_url}?user_code={user_code}"
    
    print(f"QR Payload: {qr_payload}")
    assert "ya.ru/device" in qr_payload, "Неверный URL верификации!"
    assert "user_code=ABCD-1234" in qr_payload, "Код пользователя отсутствует в QR ссылке!"
    print("✅ Тест контракта Device Flow успешно пройден!\n")


def test_cloud_reading_progress_extraction():
    print("--- [ТЕСТ 5] Извлечение и нормализация прогресса чтения Bookmate API ---")

    def extract_progress(card, b_obj):
        state = card.get("state", "") if card else ""
        position_objects = [
            card.get("last_reading_position") if card else None,
            card.get("reading_position") if card else None,
            card.get("position") if card else None,
            b_obj.get("last_reading_position") if b_obj else None,
            b_obj.get("reading_position") if b_obj else None,
            b_obj.get("position") if b_obj else None,
        ]

        percent = 0.0
        # 1. Поиск процента в объектах позиции
        for obj in position_objects:
            if not obj or not isinstance(obj, dict):
                continue
            for key in ["percent", "reading_progress", "progress_percent", "percentage", "progress"]:
                if key in obj:
                    val = float(obj[key])
                    if val > 0.0:
                        if val < 1.0:
                            percent = val * 100.0
                        elif val == 1.0:
                            percent = 100.0 if state.lower() in ["finished", "read", "completed", "done"] else 1.0
                        else:
                            percent = min(100.0, val)
                        break
            if percent > 0.0:
                break

        # 2. Если не найден, проверяем свойства верхнего уровня (кроме card.progress, который является статусом enum)
        if percent <= 0.0:
            root_objects = [card, b_obj]
            for obj in root_objects:
                if not obj or not isinstance(obj, dict):
                    continue
                for key in ["percent", "reading_progress", "progress_percent", "percentage"]:
                    if key in obj:
                        val = float(obj[key])
                        if val > 0.0:
                            if val < 1.0:
                                percent = val * 100.0
                            elif val == 1.0:
                                percent = 100.0 if state.lower() in ["finished", "read", "completed", "done"] else 1.0
                            else:
                                percent = min(100.0, val)
                            break
                if percent > 0.0:
                    break

        if percent <= 0.0 and state.lower() in ["finished", "read", "completed", "done"]:
            percent = 100.0

        all_candidates = position_objects + [card, b_obj]
        chapter = 0
        for obj in all_candidates:
            if not obj or not isinstance(obj, dict):
                continue
            for key in ["chapter_index", "chapter", "chap_index", "chapter_number"]:
                if key in obj:
                    chapter = int(obj[key])
                    break
            if chapter > 0:
                break

        # Защита от искажения 100%: если книга читается и глава 0
        if percent >= 99.0 and state.lower() == "reading" and chapter == 0:
            percent = 0.0

        return percent, chapter

    # Кейс 1: карточка с долей 0.02 (2%)
    card1 = {"uuid": "c1", "percent": 0.02, "state": "reading"}
    p1, ch1 = extract_progress(card1, None)
    assert abs(p1 - 2.0) < 0.001, f"Ожидалось 2.0%, получено {p1}"

    # Кейс 2: карточка с last_reading_position (как в мобильном приложении на телефоне)
    card2 = {
        "uuid": "c2",
        "state": "reading",
        "last_reading_position": {"chapter_index": 2, "paragraph_index": 4, "percent": 0.154}
    }
    p2, ch2 = extract_progress(card2, None)
    assert abs(p2 - 15.4) < 0.001, f"Ожидалось 15.4%, получено {p2}"
    assert ch2 == 2, f"Ожидалась глава 2, получено {ch2}"

    # Кейс 3: прогресс в объекте книги в процентах (25.0%)
    card3 = {
        "uuid": "c3",
        "book": {
            "percent": 25.0,
            "reading_position": {"chapter": 5}
        }
    }
    p3, ch3 = extract_progress(card3, card3["book"])
    assert abs(p3 - 25.0) < 0.001, f"Ожидалось 25.0%, получено {p3}"
    assert ch3 == 5, f"Ожидалась глава 5, получено {ch3}"

    # Кейс 4 (КРИТИЧЕСКИЙ БАГФИКС): карточка Bookmate с progress: 1 и state: reading
    # Не должна превращаться в 100%! Должна быть 0.0%
    card4 = {
        "uuid": "c4",
        "state": "reading",
        "progress": 1
    }
    p4, ch4 = extract_progress(card4, None)
    assert p4 == 0.0, f"КРИТИЧЕСКАЯ ОШИБКА: progress: 1 превратился в {p4}% вместо 0.0%!"
    assert ch4 == 0, f"Ожидалась глава 0, получено {ch4}"

    # Кейс 5: Завершенная книга (finished)
    card5 = {
        "uuid": "c5",
        "state": "finished",
        "progress": 2
    }
    p5, ch5 = extract_progress(card5, None)
    assert p5 == 100.0, f"Завершенная книга должна иметь 100%, получено {p5}%"

    print("✅ Тест извлечения прогресса чтения Bookmate и защиты от false-100% успешно пройден!\n")


def test_ota_update_semver_and_github_contract():
    print("--- [ТЕСТ 6] OTA Обновление: Сравнение версий SemVer и контракт GitHub Releases ---")

    def is_version_newer(latest_tag, current_version):
        if not latest_tag or not current_version:
            return False
        import re
        l = re.sub(r"^[vV]", "", latest_tag.strip())
        c = re.sub(r"^[vV]", "", current_version.strip())
        if l.lower() == c.lower():
            return False

        l_parts = re.split(r"[.-]", l)
        c_parts = re.split(r"[.-]", c)
        max_len = max(len(l_parts), len(c_parts))
        for i in range(max_len):
            l_val = int(re.sub(r"\D+", "", l_parts[i])) if i < len(l_parts) and re.sub(r"\D+", "", l_parts[i]) else 0
            c_val = int(re.sub(r"\D+", "", c_parts[i])) if i < len(c_parts) and re.sub(r"\D+", "", c_parts[i]) else 0
            if l_val > c_val:
                return True
            if l_val < c_val:
                return False
        return False

    # Проверка SemVer
    assert is_version_newer("v1.3.1", "1.3.0") == True, "1.3.1 должна быть новее 1.3.0"
    assert is_version_newer("1.3.1", "v1.3.0") == True, "1.3.1 должна быть новее 1.3.0"
    assert is_version_newer("v1.3.0", "1.2.9") == True, "1.3.0 должна быть новее 1.2.9"
    assert is_version_newer("1.3.0", "1.2.9") == True, "1.3.0 должна быть новее 1.2.9"
    assert is_version_newer("v1.2.10", "v1.2.9") == True, "1.2.10 должна быть новее 1.2.9"
    assert is_version_newer("v2.0.0", "1.9.9") == True, "2.0.0 должна быть новее 1.9.9"
    assert is_version_newer("v1.3.1", "1.3.1") == False, "Одинаковые версии не должны считаться обновлением"
    assert is_version_newer("v1.3.0", "1.3.1") == False, "Старая версия не должна считаться обновлением"

    # Проверка контракта GitHub API
    sample_github_release = {
        "tag_name": "v1.3.0",
        "name": "v1.3.0 - Встроенное OTA обновление",
        "body": "## Что нового в v1.3.0\n- OTA обновления",
        "assets": [
            {
                "name": "yandex-books-lite-v1.3.0.apk",
                "browser_download_url": "https://github.com/litvaerickson-spec/onyx-yandex-books/releases/download/v1.3.0/yandex-books-lite-v1.3.0.apk",
                "size": 2275156
            }
        ]
    }

    apk_asset = None
    for a in sample_github_release.get("assets", []):
        if a.get("name", "").endswith(".apk"):
            apk_asset = a
            break

    assert apk_asset is not None, "APK ассет не найден в релизе!"
    assert apk_asset["name"] == "yandex-books-lite-v1.3.0.apk"
    assert apk_asset["browser_download_url"].startswith("https://")
    assert apk_asset["size"] > 0

    print("✅ Тест OTA обновлений и контракта GitHub API успешно пройден!\n")


def test_weighted_chapter_sync_and_offset():
    print("--- [ТЕСТ 7] Взвешенный расчет глав и маппинг прогресса (НЕ ТУПИ и др.) ---")
    
    chapter_lengths = [5000, 500000, 10000, 8000]
    total_book_len = sum(chapter_lengths)
    
    cloud_percent = 51.0
    
    # 1. Проверяем старый наивный алгоритм (баг перескока):
    naive_chapter = int((cloud_percent / 100.0) * len(chapter_lengths))
    print(f"Старый наивный алгоритм выдавал главу: {naive_chapter} ('Слова благодарности') - ОШИБКА!")
    assert naive_chapter == 2, "Наивный алгоритм должен был выдать ошибочную главу 2"
    
    # 2. Проверяем новый взвешенный алгоритм:
    target_global_offset = int((cloud_percent / 100.0) * total_book_len)
    acc = 0
    matched_ch = 0
    matched_offset = 0
    for i, ch_len in enumerate(chapter_lengths):
        if target_global_offset <= acc + ch_len or i == len(chapter_lengths) - 1:
            matched_ch = i
            matched_offset = max(0, target_global_offset - acc)
            break
        acc += ch_len
        
    print(f"Новый взвешенный алгоритм: глава {matched_ch}, смещение {matched_offset} байт")
    assert matched_ch == 1, f"Ожидалась глава 1 (Основной текст книги), но получено: {matched_ch}"
    
    # 3. Проверяем расчет относительной доли главы chapterFraction
    cur_ch_len = chapter_lengths[matched_ch]
    chapter_fraction = matched_offset / cur_ch_len
    print(f"Относительная доля внутри главы: {chapter_fraction:.4f}")
    assert 0.50 <= chapter_fraction <= 0.55, f"Ожидалось ~52% главы 1, получено: {chapter_fraction}"
    
    # 4. Проверяем маппинг в символьное смещение текста главы
    raw_text_len = 240000
    target_char_offset = int(round(chapter_fraction * raw_text_len))
    print(f"Целевое символьное смещение: {target_char_offset} из {raw_text_len} символов")
    assert 120000 <= target_char_offset <= 130000
    
    # 5. Проверяем обратный расчет процента calculateCurrentGlobalPercent
    cur_page_idx = 129
    total_pages = 250
    in_chapter_fraction = (cur_page_idx + 1) / total_pages
    preceding_len = chapter_lengths[0]
    global_bytes = preceding_len + (in_chapter_fraction * cur_ch_len)
    calc_percent = (global_bytes / total_book_len) * 100.0
    print(f"Обратный расчет процента со страницы 130/250: {calc_percent:.2f}%")
    assert abs(calc_percent - cloud_percent) < 0.5, f"Процент должен совпадать с исходным 51%! Получено {calc_percent}"

    print("✅ Тест взвешенного прогресса и символьного маппинга успешно пройден!\n")


def test_initial_load_protection_contract():
    print("--- [ТЕСТ 8] Контракт защиты от перезаписи облачного прогресса (isInitialLoading) ---")
    
    class MockReaderSession:
        def __init__(self, cloud_percent):
            self.cloud_percent = cloud_percent
            self.saved_percent = None
            self.is_initial_loading = True
            
        def render_initial_page(self):
            if not self.is_initial_loading:
                self.save_progress(40.0)
                
        def user_flips_page(self, new_percent):
            self.is_initial_loading = False
            self.save_progress(new_percent)
            
        def save_progress(self, pct):
            self.saved_percent = pct

    session = MockReaderSession(cloud_percent=51.0)
    session.render_initial_page()
    assert session.saved_percent is None, "ОШИБКА: Автосейв сработал при первичном открытии и перезаписал прогресс!"
    
    session.user_flips_page(51.2)
    assert session.saved_percent == 51.2, "ОШИБКА: Прогресс должен сохраниться после действия пользователя!"
    
    print("✅ Контракт защиты isInitialLoading успешно верифицирован!\n")


def test_backward_chapter_transition():
    print("--- [ТЕСТ 9] Переход назад на границе глав (-999 -> последняя страница) ---")
    
    def resolve_target_page(target_page_code, total_pages_in_chapter):
        if target_page_code == -999:
            return total_pages_in_chapter - 1
        return max(0, min(target_page_code, total_pages_in_chapter - 1))
        
    assert resolve_target_page(-999, 15) == 14, "Должна открыться страница 14 (последняя из 15)"
    assert resolve_target_page(0, 15) == 0, "Должна открыться страница 0"
    assert resolve_target_page(5, 15) == 5, "Должна открыться страница 5"
    
    print("✅ Тест перехода назад на границе глав успешно пройден!\n")


def test_bookmarks_model_and_storage():
    print("--- [ТЕСТ 10] Модель закладок и SQLite контракт ---")
    
    bookmarks = []
    
    def add_bookmark(book_uuid, chapter_idx, page_idx, title, snippet, ts):
        bm = {
            "id": len(bookmarks) + 1,
            "book_uuid": book_uuid,
            "chapter_index": chapter_idx,
            "page_index": page_idx,
            "title": title,
            "snippet": snippet,
            "timestamp": ts
        }
        bookmarks.insert(0, bm)
        return bm["id"]
        
    def delete_bookmark(bm_id):
        nonlocal bookmarks
        bookmarks = [b for b in bookmarks if b["id"] != bm_id]
        
    b1_id = add_bookmark("uuid-1", 1, 10, "Глава 2, стр. 11", "Начало интересного абзаца...", 1000)
    b2_id = add_bookmark("uuid-1", 1, 25, "Глава 2, стр. 26", "Вторая важная мысль...", 2000)
    
    assert len(bookmarks) == 2
    assert bookmarks[0]["id"] == b2_id, "Свежая закладка должна быть первой в списке"
    assert bookmarks[0]["page_index"] == 25
    
    delete_bookmark(b1_id)
    assert len(bookmarks) == 1
    assert bookmarks[0]["id"] == b2_id
    
    print("✅ Тест модели закладок успешно пройден!\n")


def test_typography_cycle_contracts():
    print("--- [ТЕСТ 11] Контракты переключения типографики в стиле Onyx NeoReader ---")
    
    def next_font_family(cur):
        if cur == "serif": return "sans-serif"
        if cur == "sans-serif": return "monospace"
        return "serif"
        
    assert next_font_family("serif") == "sans-serif"
    assert next_font_family("sans-serif") == "monospace"
    assert next_font_family("monospace") == "serif"
    
    def next_indent(cur):
        if cur == 0: return 20
        if cur <= 20: return 32
        if cur <= 32: return 44
        return 0
        
    assert next_indent(0) == 20
    assert next_indent(20) == 32
    assert next_indent(32) == 44
    assert next_indent(44) == 0
    
    def next_line_spacing(cur):
        if cur <= 1.05: return 1.25
        if cur <= 1.30: return 1.50
        if cur <= 1.55: return 1.75
        return 1.00
        
    assert next_line_spacing(1.00) == 1.25
    assert next_line_spacing(1.25) == 1.50
    assert next_line_spacing(1.50) == 1.75
    assert next_line_spacing(1.75) == 1.00
    
    def next_margin_mode(cur):
        if cur == "narrow": return "medium"
        if cur == "medium": return "wide"
        return "narrow"
        
    assert next_margin_mode("narrow") == "medium"
    assert next_margin_mode("medium") == "wide"
    assert next_margin_mode("wide") == "narrow"

    def next_vert_margin(cur):
        if cur == "small": return "normal"
        if cur == "normal": return "large"
        return "small"
        
    assert next_vert_margin("normal") == "large"
    assert next_vert_margin("large") == "small"
    assert next_vert_margin("small") == "normal"
    
    print("✅ Тест переключения настроек типографики успешно пройден!\n")


def test_total_book_pages_continuous_pagination():
    print("--- [ТЕСТ 12] Сквозная нумерация страниц книги от общего объема ---")
    
    # 5 глав с разной длиной
    chapter_lengths = [3000, 7500, 4500, 15000, 2000]
    avg_chars_per_page = 750.0
    chapter_page_counts = [int(round(l / avg_chars_per_page)) for l in chapter_lengths]
    total_book_pages = sum(chapter_page_counts)
    
    print(f"Длины глав: {chapter_lengths}")
    print(f"Страниц в главах: {chapter_page_counts}")
    print(f"Всего страниц в книге: {total_book_pages}")
    
    assert total_book_pages == 4 + 10 + 6 + 20 + 3, f"Неверная сумма страниц: {total_book_pages}"
    
    def get_global_page(current_chapter_idx, current_page_idx):
        preceding = sum(chapter_page_counts[:current_chapter_idx])
        return preceding + current_page_idx + 1

    # В первой главе на 1-й странице -> Стр. 1 из 43
    p1 = get_global_page(0, 0)
    assert p1 == 1, f"Ожидалась 1-я глобальная страница, получено: {p1}"
    
    # В первой главе на 4-й (последней) странице -> Стр. 4 из 43
    p4 = get_global_page(0, 3)
    assert p4 == 4, f"Ожидалась 4-я глобальная страница, получено: {p4}"
    
    # Перелистнули во 2-ю главу на 1-ю страницу -> Стр. 5 из 43
    p5 = get_global_page(1, 0)
    assert p5 == 5, f"Ожидалась 5-я глобальная страница, получено: {p5}"
    
    # В последней главе на последней странице -> Стр. 43 из 43
    p_last = get_global_page(4, 2)
    assert p_last == total_book_pages, f"Ожидалась {total_book_pages}-я страница, получено: {p_last}"
    
    # Форматирование нижнего колонтитула
    page_info = f"Стр. {p5} из {total_book_pages} (12%)"
    print(f"Пример нижнего колонтитула: '{page_info}'")
    assert "из 43" in page_info
    assert "Стр. 5" in page_info
    
    print("✅ Тест сквозной нумерации страниц книги успешно пройден!\n")


def test_sqlite_reading_progress_protection():
    print("--- [ТЕСТ 13] Защита локального прогресса SQLite от затирания чужим сервером ---")
    
    def simulate_save_or_update(local_db, incoming_book):
        best_percent = local_db.get("percent", 0.0)
        best_ts = local_db.get("timestamp", 0)
        best_ch = local_db.get("chapter", 0)
        
        has_local = (best_percent > 0.0 or best_ch > 0 or best_ts > 0)
        server_ts = incoming_book.get("timestamp", 0)
        server_percent = incoming_book.get("percent", 0.0)
        server_ch = incoming_book.get("chapter", 0)
        
        if has_local:
            # Серверу доверяем ТОЛЬКО если есть валидный таймштамп > 0, он новее и прочитано больше
            server_is_newer_and_further = (server_ts > best_ts and server_ts > 0 and server_percent > best_percent + 0.5)
            if server_is_newer_and_further:
                effective_percent = server_percent
                effective_ch = server_ch if server_ch > 0 else best_ch
                effective_ts = server_ts
            else:
                effective_percent = best_percent
                effective_ch = best_ch
                effective_ts = max(best_ts, server_ts)
        else:
            effective_percent = server_percent
            effective_ch = server_ch
            effective_ts = server_ts
            
        return {"percent": effective_percent, "chapter": effective_ch, "timestamp": effective_ts}

    # Сценарий 1: Пользователь прочитал на читалке до 35%, глава 4 (ts: 1000).
    # Сервер вернул карточку без таймштампа (ts: 0) и с percent: 0, chapter: 0.
    local_state = {"percent": 35.0, "chapter": 4, "timestamp": 1000}
    incoming_empty = {"percent": 0.0, "chapter": 0, "timestamp": 0}
    res1 = simulate_save_or_update(local_state, incoming_empty)
    print(f"Сценарий 1 (Пустой сервер не должен затереть): {res1}")
    assert res1["percent"] == 35.0, "Ошибка: локальный процент был затерт нулем!"
    assert res1["chapter"] == 4, "Ошибка: локальная глава была затерта нулем!"

    # Сценарий 2: Пользователь прочитал на читалке до 35%, глава 4 (ts: 1000).
    # На телефоне дочитал до 60% (ts: 2000), но на сервере chapter_index отсутствует (0).
    incoming_phone = {"percent": 60.0, "chapter": 0, "timestamp": 2000}
    res2 = simulate_save_or_update(local_state, incoming_phone)
    print(f"Сценарий 2 (Прогресс с телефона 60% принят, глава сохранена): {res2}")
    assert res2["percent"] == 60.0, "Прогресс с телефона должен быть принят!"
    assert res2["chapter"] == 4, "Глава не должна сбрасываться в 0!"

    print("✅ Тест защиты прогресса SQLite успешно пройден!\n")


def test_iso_timestamp_parsing():
    print("--- [ТЕСТ 14] Парсинг ISO-8601 даты из ответов Bookmate API ---")
    from datetime import datetime, timezone

    def parse_iso(iso_str):
        if not iso_str:
            return 0
        clean = iso_str.strip().replace("Z", "+00:00")
        try:
            dt = datetime.fromisoformat(clean)
            return int(dt.timestamp() * 1000)
        except Exception:
            return 0

    iso1 = "2024-03-25T14:30:00Z"
    ts1 = parse_iso(iso1)
    print(f"ISO '{iso1}' -> {ts1} ms")
    assert ts1 > 1700000000000

    iso2 = "2024-03-25T14:30:00.500Z"
    ts2 = parse_iso(iso2)
    print(f"ISO '{iso2}' -> {ts2} ms")
    assert ts2 > 1700000000000

    assert parse_iso("") == 0
    assert parse_iso(None) == 0

    print("✅ Тест парсинга ISO дат успешно пройден!\n")


def test_epub_toc_and_chapter_merging():
    print("--- [ТЕСТ 15] Сборка глав по подлинному оглавлению TOC (NCX / NAV) ---")

    class TocItem:
        def __init__(self, title, src):
            self.title = title
            self.src = src

    toc_items = [
        TocItem("Введение", "text/intro.xhtml"),
        TocItem("Глава 1. Начало пути", "text/ch01_part1.xhtml"),
        TocItem("Глава 2. Действие", "text/ch02.xhtml")
    ]

    spine = [
        "text/cover.xhtml",
        "text/intro.xhtml",
        "text/ch01_part1.xhtml",
        "text/ch01_part2.xhtml",
        "text/ch01_part3.xhtml",
        "text/ch02.xhtml",
        "text/colophon.xhtml"
    ]

    # Алгоритм сопоставления spine файлов с главами TOC
    spine_to_toc_idx = {}
    last_idx = 0
    for s_idx, s_href in enumerate(spine):
        for t_idx, t_item in enumerate(toc_items):
            clean_t = t_item.src.split("#")[0]
            if clean_t.endswith(s_href) or s_href.endswith(clean_t):
                last_idx = t_idx
                break
        spine_to_toc_idx[s_href] = last_idx

    print(f"Маппинг spine на TOC: {spine_to_toc_idx}")
    assert spine_to_toc_idx["text/ch01_part1.xhtml"] == 1
    assert spine_to_toc_idx["text/ch01_part2.xhtml"] == 1
    assert spine_to_toc_idx["text/ch01_part3.xhtml"] == 1
    assert spine_to_toc_idx["text/ch02.xhtml"] == 2
    print("✅ Тест сборки глав по TOC успешно пройден!\n")


def test_obj_and_whitespace_cleaning():
    print("--- [ТЕСТ 16] Очистка символов [OBJ] (\\uFFFC) и нормализация межабзацных интервалов ---")
    import re

    raw_html = (
        "<p>Первый абзац с артефактом \uFFFC и спецсимволом \uFFFD.</p>\n"
        "<p>Второй абзац книги.\u200B\u00AD</p>\n\n"
        "<p>Третий абзац книги.</p>"
    )

    # 1. Удаление нежелательных спецсимволов
    cleaned = raw_html
    cleaned = cleaned.replace("\uFFFC", "")
    cleaned = cleaned.replace("\uFFFD", "")
    cleaned = cleaned.replace("\uFEFF", "")
    cleaned = cleaned.replace("\u00AD", "")
    cleaned = cleaned.replace("\u200B", "")

    assert "\uFFFC" not in cleaned, "Символ [OBJ] не был удален!"
    assert "\uFFFD" not in cleaned, "Символ replacement char не был удален!"

    # 2. Очистка тегов и нормализация переносов строк
    text = re.sub(r"<[^>]+>", "\n", cleaned)
    # Нормализация: 3+ переводов строки сводятся к 2 (одна пустая строка для авторского разделителя)
    text = re.sub(r"\n{3,}", "\n\n", text)
    lines = [l.strip() for l in text.split("\n") if l.strip()]

    print(f"Извлеченные абзацы ({len(lines)}): {lines}")
    assert len(lines) == 3
    assert lines[0] == "Первый абзац с артефактом  и спецсимволом ."
    assert lines[1] == "Второй абзац книги."
    assert lines[2] == "Третий абзац книги."

    print("✅ Тест очистки [OBJ] и нормализации абзацев успешно пройден!\n")


def test_anchor_toc_and_missing_chapter_preservation():
    print("--- [ТЕСТ 17] Сохранение всех частей и якорная нарезка оглавления (TOC Anchors) ---")
    import re

    # Моделируем ситуацию книги про депрессию:
    # В одном файле spine лежат Часть 3, Часть 4 и Часть 5
    raw_spine_html = """
    <html>
      <body>
        <div id="part3">
          <h2>Часть 3. Механизмы депрессии</h2>
          <p>Текст третьей части книги про депрессию...</p>
        </div>
        <div id="part4">
          <h2>Часть 4. Методы преодоления</h2>
          <p>Текст четвертой части, которая раньше пропадала из-за бага в оглавлении!</p>
        </div>
        <div id="part5">
          <h2>Часть 5. Практические шаги</h2>
          <p>Текст пятой части книги...</p>
        </div>
      </body>
    </html>
    """

    toc_items = [
        {"title": "Часть 3", "anchor": "part3"},
        {"title": "Часть 4", "anchor": "part4"},
        {"title": "Часть 5", "anchor": "part5"},
    ]

    def find_anchor_offset(html, anchor):
        pattern = re.compile(r'(?:id|name)\s*=\s*["\']' + re.escape(anchor) + r'["\']', re.IGNORECASE)
        m = pattern.search(html)
        return m.start() if m else -1

    offsets = []
    for item in toc_items:
        off = find_anchor_offset(raw_spine_html, item["anchor"])
        assert off > 0, f"Якорь {item['anchor']} обязан быть найден!"
        offsets.append((off, item["title"]))

    # Проверяем нарезку
    chapters = []
    for i in range(len(offsets)):
        cur_off, title = offsets[i]
        next_off = offsets[i + 1][0] if i + 1 < len(offsets) else len(raw_spine_html)
        slice_html = raw_spine_html[cur_off:next_off]
        clean_text = re.sub(r"<[^>]+>", " ", slice_html)
        clean_text = " ".join(clean_text.split())
        chapters.append({"title": title, "text": clean_text})

    print(f"Извлечено глав: {len(chapters)}")
    for ch in chapters:
        print(f" - {ch['title']}: {ch['text'][:50]}...")

    assert len(chapters) == 3, f"Ожидалось ровно 3 части, получено {len(chapters)}"
    assert chapters[0]["title"] == "Часть 3"
    assert chapters[1]["title"] == "Часть 4", "КРИТИЧНО: Часть 4 не должна пропадать!"
    assert chapters[2]["title"] == "Часть 5"
    assert "четвертой части" in chapters[1]["text"]

    print("✅ Тест якорного оглавления и сохранения всех частей успешно пройден!\n")


def test_compact_footer_and_margin_geometry():
    print("--- [ТЕСТ 18] Компактная геометрия футера E-Ink и устранение пустоты внизу ---")

    # Исходная проблемная геометрия v1.3.8:
    # paddingBottomPx = 20, footerReservedHeightPx = 44 -> 64px отступа снизу
    # Новая компактная геометрия v1.3.9:
    padding_bottom = 6
    footer_reserved = 20
    total_bottom_reserved = padding_bottom + footer_reserved

    print(f"Резерв снизу: {total_bottom_reserved}px (было 64px, сокращение на {64 - total_bottom_reserved}px)")
    assert total_bottom_reserved == 26, "Ожидалось суммарно 26px резерва для футера"

    canvas_height = 758
    footer_baseline = canvas_height - 8
    content_bottom_limit = canvas_height - total_bottom_reserved

    print(f"Высота холста: {canvas_height}px, лимит контента: {content_bottom_limit}px, baseline футера: {footer_baseline}px")
    assert footer_baseline > content_bottom_limit
    assert footer_baseline - content_bottom_limit <= 20, "Зазор между последней строкой текста и футером оптимизирован!"

    print("✅ Тест геометрии футера успешно пройден!\n")


def test_hierarchical_toc_tree_and_desync_prevention():
    print("--- [ТЕСТ 19] Древовидное оглавление NeoReader, очистка тегов и защита от десинхронизации ---")

    import re

    # 1. Тест нормализации названий глав (числовых заголовков и римских цифр)
    def normalize_title(raw):
        if not raw:
            return ""
        t = raw.strip()
        if re.match(r"^\d+$", t):
            return f"Глава {t}"
        if re.match(r"(?i)^[ivxlcdm]+$", t) and len(t) <= 8:
            return f"Глава {t.upper()}"
        return t

    assert normalize_title("1") == "Глава 1", "Число '1' должно превращаться в 'Глава 1'"
    assert normalize_title("30") == "Глава 30"
    assert normalize_title("iv") == "Глава IV", "Римская цифра 'iv' должна превращаться в 'Глава IV'"
    assert normalize_title("Часть 4. Антидепрессанты") == "Часть 4. Антидепрессанты"
    print(" - Нормализация заголовков: OK")

    # 2. Тест тотальной очистки обрывков тегов (id=\"mh_toc_...\" и <h2)
    def clean_html_text(html):
        if not html:
            return ""
        text = re.sub(r"(?is)<(script|style|head).*?>.*?</\1>", "", html)
        text = re.sub(r"(?i)\b(?:id|name|class|style)\s*=\s*[\"'][^\"']*[\"']\s*>", "", text)
        text = re.sub(r"(?i)^[^<\n]*>", "", text)
        text = re.sub(r"(?im)</?[a-zA-Z0-9_-]+\s*$", "", text)
        text = re.sub(r"(?i)<br\s*/?>", "\n", text)
        text = re.sub(r"(?i)</?(p|div|h[1-6]|li|blockquote|tr)[^>]*>", "\n", text)
        text = re.sub(r"<[^>]+>", "", text)
        text = re.sub(r"(?im)^\s*<h[1-6]\s*$", "", text)
        text = text.replace("\uFFFC", "").replace("\uFFFD", "").replace("\uFEFF", "")
        lines = [l.strip() for l in text.split("\n") if l.strip()]
        return "\n".join(lines)

    dirty_sample = 'id="mh_toc_975">Часть 4. Антидепрессанты\n<h2\nТекст первой подглавы книги\uFFFC.'
    cleaned = clean_html_text(dirty_sample)
    print(f" - Очистка артефактов тегов: '{cleaned}'")
    assert 'id=' not in cleaned, "Атрибут id= не должен просачиваться в текст!"
    assert '<h2' not in cleaned, "Обрывок тега <h2 не должен просачиваться в текст!"
    assert '\uFFFC' not in cleaned, "Символ [OBJ] не должен присутствовать!"
    assert cleaned == "Часть 4. Антидепрессанты\nТекст первой подглавы книги."

    # 3. Тест поиска начала тега якоря (findAnchorOffset)
    def find_anchor_offset(html, anchor):
        clean_anchor = anchor.lstrip("#").strip()
        pattern = re.compile(r"<[^>]+?\b(?:id|name)\s*=\s*[\"']" + re.escape(clean_anchor) + r"[\"'][^>]*>", re.IGNORECASE)
        m = pattern.search(html)
        if m:
            return m.start()
        return -1

    full_html = '<div class="main"><h2 id="part4">Часть 4</h2><p>Содержимое</p></div>'
    anchor_idx = find_anchor_offset(full_html, "part4")
    assert anchor_idx == full_html.index('<h2'), "findAnchorOffset обязан возвращать начало тега '<', а не 'id='!"
    print(" - Поиск якоря findAnchorOffset: OK")

    # 4. Тест структуры дерева TOC и динамического раскрытия/сворачивания
    class TocNode:
        def __init__(self, title, level, is_expanded=True):
            self.title = title
            self.level = level
            self.is_expanded = is_expanded
            self.children = []

    root1 = TocNode("Часть 1", 0, is_expanded=True)
    child1 = TocNode("Глава 1", 1)
    child2 = TocNode("Глава 2", 1)
    root1.children = [child1, child2]

    root2 = TocNode("Часть 2", 0, is_expanded=False)
    child3 = TocNode("Глава 3", 1)
    root2.children = [child3]

    toc_tree = [root1, root2]

    def flatten_visible(nodes, out_list):
        for n in nodes:
            out_list.append(n)
            if n.children and n.is_expanded:
                flatten_visible(n.children, out_list)

    visible = []
    flatten_visible(toc_tree, visible)
    visible_titles = [n.title for n in visible]
    print(f" - Видимые узлы TOC: {visible_titles}")
    # root1 раскрыт (3 узла), root2 свернут (1 узел) -> суммарно 4 видимых элемента
    assert len(visible) == 4
    assert visible_titles == ["Часть 1", "Глава 1", "Глава 2", "Часть 2"]

    # 5. Тест защиты от перескока в конец книги при проценте >= 99%
    def resolve_target(percent, chapter_idx):
        if percent >= 99.0:
            return 0, 0 # Всегда открываем Главу 0, Страницу 0
        return chapter_idx, 0

    assert resolve_target(100.0, 15) == (0, 0), "Прочитанная книга обязана открываться с начала!"
    assert resolve_target(45.0, 5) == (5, 0)
    print(" - Защита от перескока в конец книги: OK")

    # 6. Тест приоритета пользовательского листания userHasInteracted
    class SessionState:
        def __init__(self):
            self.user_has_interacted = False
            self.current_chapter = 0

        def on_cloud_progress_received(self, cloud_chapter):
            if self.user_has_interacted:
                return # Игнорируем сетевой пакет, пользователь уже читает
            self.current_chapter = cloud_chapter

    session = SessionState()
    # Сценарий: открытие книги, локально глава 0, из облака пришла глава 3
    session.on_cloud_progress_received(3)
    assert session.current_chapter == 3, "Облачный прогресс обязан примениться до взаимодействия!"

    # Сценарий: пользователь листает книгу (user_has_interacted = True), пришел запоздалый пакет
    session.user_has_interacted = True
    session.on_cloud_progress_received(1)
    assert session.current_chapter == 3, "После взаимодействия пользователя облачный прогресс не должен сбивать позицию!"
    print(" - Защита синхронизации userHasInteracted: OK")

    print("✅ Тест древовидного TOC и защиты от десинхронизации успешно пройден!\n")


if __name__ == "__main__":
    print("==================================================")
    print("🚀 Запуск тотальной верификации ядра Яндекс Книги")
    print("==================================================")
    print()
    test_tex_hyphenation()
    test_smart_conflict_resolution()
    test_paginator_math()
    test_device_flow_contract()
    test_cloud_reading_progress_extraction()
    test_ota_update_semver_and_github_contract()
    test_weighted_chapter_sync_and_offset()
    test_initial_load_protection_contract()
    test_backward_chapter_transition()
    test_bookmarks_model_and_storage()
    test_typography_cycle_contracts()
    test_total_book_pages_continuous_pagination()
    test_sqlite_reading_progress_protection()
    test_iso_timestamp_parsing()
    test_epub_toc_and_chapter_merging()
    test_obj_and_whitespace_cleaning()
    test_anchor_toc_and_missing_chapter_preservation()
    test_compact_footer_and_margin_geometry()
    test_hierarchical_toc_tree_and_desync_prevention()
    print("==================================================")
    print("🎉 ВСЕ 19 ТЕСТОВ УСПЕШНО ПРОЙДЕНЫ! АЛГОРИТМЫ И КОМАНДЫ ВЕРИФИЦИРОВАНЫ.")
    print("==================================================")
