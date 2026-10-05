#!/usr/bin/env python3
"""
Генератор дизайнерских предложений иконки лаунчера «Яндекс Книги Lite» для Onyx Boox.
Использует нативные быстрые операции PIL (ImageChops, Lanczos) для мгновенного рендера.
"""

import os
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageChops

OUTPUT_DIR = "docs/icon_proposals"
os.makedirs(OUTPUT_DIR, exist_ok=True)

# Шрифты
FONT_MONTSERRAT_BLACK = "/Users/sergej/Library/Fonts/MontserratBlack.ttf"
FONT_MONTSERRAT_EXTRA_BOLD = "/Users/sergej/Library/Fonts/MontserratExtraBold.ttf"
FONT_MONTSERRAT_BOLD = "/Users/sergej/Library/Fonts/MontserratBold.ttf"

def create_base_canvas(scale=2):
    size = 512 * scale
    img = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    draw = ImageDraw.Draw(img)
    return img, draw, size

def draw_squircle_card(draw, size, stroke_width=14, corner_radius=96, padding=28, fill_color=(255, 255, 255, 255), stroke_color=(0, 0, 0, 255)):
    """Карточка сквиркла со скругленными углами в едином стиле Onyx Boox."""
    p = padding
    draw.rounded_rectangle(
        [p, p, size - p, size - p],
        radius=corner_radius,
        fill=fill_color,
        outline=stroke_color,
        width=stroke_width
    )

def finalize_image(img, filename):
    out = img.resize((512, 512), Image.Resampling.LANCZOS)
    path = os.path.join(OUTPUT_DIR, filename)
    out.save(path, "PNG")
    print(f"Saved: {path}")
    return out


# =========================================================================
# ВАРИАНТ 1: Канонический эскиз пользователя (Контурная «Я» + плашка «КНИГИ»)
# =========================================================================
def generate_option_1():
    scale = 2
    img, draw, size = create_base_canvas(scale)
    draw_squircle_card(draw, size, stroke_width=14 * scale, corner_radius=96 * scale, padding=28 * scale)

    # 1. Рендерим силуэт буквы Я на отдельном маске-слое
    ya_mask = Image.new("L", (size, size), 0)
    ya_draw = ImageDraw.Draw(ya_mask)
    font_ya = ImageFont.truetype(FONT_MONTSERRAT_BLACK, 310 * scale)
    bbox_ya = font_ya.getbbox("Я")
    ya_w = bbox_ya[2] - bbox_ya[0]
    ya_h = bbox_ya[3] - bbox_ya[1]
    ya_x = (size - ya_w) // 2 - bbox_ya[0]
    ya_y = (size - ya_h) // 2 - bbox_ya[1] - (24 * scale)
    ya_draw.text((ya_x, ya_y), "Я", fill=255, font=font_ya)

    # 2. Создаем чистый контур буквы Я через фильтры без циклов по пикселям
    outline_thickness = 15 * scale
    dilated = ya_mask.filter(ImageFilter.MaxFilter(outline_thickness * 2 + 1))
    eroded = ya_mask.filter(ImageFilter.MinFilter(outline_thickness * 2 + 1))
    contour_ya = ImageChops.subtract(dilated, eroded)

    # 3. Подготовка плашки и надписи "КНИГИ"
    font_books = ImageFont.truetype(FONT_MONTSERRAT_EXTRA_BOLD, 64 * scale)
    text_books = "КНИГИ"
    
    spacing = 8 * scale
    total_text_w = sum(font_books.getbbox(ch)[2] - font_books.getbbox(ch)[0] for ch in text_books) + spacing * (len(text_books) - 1)
    books_y = ya_y + ya_h - (36 * scale)
    books_x = (size - total_text_w) // 2

    # Создаем защитный ореол (белый вырез) вокруг плашки
    mask_cutout = Image.new("L", (size, size), 0)
    cutout_draw = ImageDraw.Draw(mask_cutout)
    pad_h = 16 * scale
    pad_v = 12 * scale
    cutout_draw.rounded_rectangle(
        [books_x - pad_h, books_y - pad_v, books_x + total_text_w + pad_h, books_y + (58 * scale) + pad_v],
        radius=14 * scale,
        fill=255
    )

    # Вырезаем область плашки из контура буквы Я
    final_contour = ImageChops.subtract(contour_ya, mask_cutout)
    black_layer = Image.new("RGBA", (size, size), (0, 0, 0, 255))
    img.paste(black_layer, (0, 0), mask=final_contour)

    # Рисуем черную плашку
    draw.rounded_rectangle(
        [books_x - (12 * scale), books_y - (6 * scale), books_x + total_text_w + (12 * scale), books_y + (56 * scale)],
        radius=12 * scale,
        fill=(0, 0, 0, 255)
    )

    # Рисуем буквы "КНИГИ" чисто белым цветом
    cur_x = books_x
    for ch in text_books:
        bb = font_books.getbbox(ch)
        cw = bb[2] - bb[0]
        draw.text((cur_x - bb[0], books_y - bb[1]), ch, fill=(255, 255, 255, 255), font=font_books)
        cur_x += cw + spacing

    return finalize_image(img, "option_1_contour_ya_with_badge.png")


# =========================================================================
# ВАРИАНТ 2: Премиальная открытая книга + Монограмма Я (Brand Metaphor)
# =========================================================================
def generate_option_2():
    scale = 2
    img, draw, size = create_base_canvas(scale)
    draw_squircle_card(draw, size, stroke_width=14 * scale, corner_radius=96 * scale, padding=28 * scale)

    center_x = size // 2
    book_top_y = 210 * scale
    book_bottom_y = 410 * scale
    page_w = 150 * scale
    stroke_w = 12 * scale

    # Верхние дуги страниц
    draw.arc([center_x - page_w, book_top_y, center_x, book_top_y + (50 * scale)], start=180, end=360, fill=(0, 0, 0, 255), width=stroke_w)
    draw.arc([center_x, book_top_y, center_x + page_w, book_top_y + (50 * scale)], start=180, end=360, fill=(0, 0, 0, 255), width=stroke_w)

    # Боковые срезы страниц
    draw.line([center_x - page_w, book_top_y + (25 * scale), center_x - page_w, book_bottom_y - (20 * scale)], fill=(0, 0, 0, 255), width=stroke_w)
    draw.line([center_x + page_w, book_top_y + (25 * scale), center_x + page_w, book_bottom_y - (20 * scale)], fill=(0, 0, 0, 255), width=stroke_w)

    # Нижние дуги страниц
    draw.arc([center_x - page_w, book_bottom_y - (45 * scale), center_x, book_bottom_y + (5 * scale)], start=0, end=180, fill=(0, 0, 0, 255), width=stroke_w)
    draw.arc([center_x, book_bottom_y - (45 * scale), center_x + page_w, book_bottom_y + (5 * scale)], start=0, end=180, fill=(0, 0, 0, 255), width=stroke_w)
    
    # Центральный корешок
    draw.line([center_x, book_top_y + (25 * scale), center_x, book_bottom_y - (20 * scale)], fill=(0, 0, 0, 255), width=stroke_w)

    # Строки текста на страницах (книжная текстура)
    line_w = 6 * scale
    for dy in [55 * scale, 90 * scale, 125 * scale]:
        draw.line([center_x - page_w + (25 * scale), book_top_y + dy, center_x - (25 * scale), book_top_y + dy - (5 * scale)], fill=(0, 0, 0, 255), width=line_w)
        draw.line([center_x + (25 * scale), book_top_y + dy - (5 * scale), center_x + page_w - (25 * scale), book_top_y + dy], fill=(0, 0, 0, 255), width=line_w)

    # Над книгой парит уверенная буква "Я"
    font_ya = ImageFont.truetype(FONT_MONTSERRAT_BLACK, 150 * scale)
    bbox_ya = font_ya.getbbox("Я")
    ya_w = bbox_ya[2] - bbox_ya[0]
    ya_h = bbox_ya[3] - bbox_ya[1]
    ya_x = (size - ya_w) // 2 - bbox_ya[0]
    ya_y = (105 * scale) - bbox_ya[1]

    draw.rectangle([ya_x - (8 * scale), ya_y - (8 * scale), ya_x + ya_w + (8 * scale), ya_y + ya_h + (8 * scale)], fill=(255, 255, 255, 255))
    draw.text((ya_x, ya_y), "Я", fill=(0, 0, 0, 255), font=font_ya)

    return finalize_image(img, "option_2_open_book_with_ya.png")


# =========================================================================
# ВАРИАНТ 3: Монохромный книжный переплет с тиснением «Я»
# =========================================================================
def generate_option_3():
    scale = 2
    img, draw, size = create_base_canvas(scale)
    draw_squircle_card(draw, size, stroke_width=14 * scale, corner_radius=96 * scale, padding=28 * scale)

    pad = 85 * scale
    book_x0 = pad
    book_y0 = pad
    book_x1 = size - pad
    book_y1 = size - pad

    # Черный блок обложки книги
    draw.rounded_rectangle(
        [book_x0, book_y0, book_x1, book_y1],
        radius=36 * scale,
        fill=(0, 0, 0, 255)
    )

    # Белая линия корешка книги слева
    spine_x = book_x0 + (44 * scale)
    draw.line([spine_x, book_y0 + (10 * scale), spine_x, book_y1 - (10 * scale)], fill=(255, 255, 255, 255), width=8 * scale)

    # Белая ленточка-закладка (ляссе) снизу
    ribbon_w = 30 * scale
    ribbon_x = book_x1 - (74 * scale)
    ribbon_y0 = book_y1 - (10 * scale)
    ribbon_y1 = book_y1 + (32 * scale)
    
    ribbon_poly = [
        (ribbon_x, ribbon_y0),
        (ribbon_x + ribbon_w, ribbon_y0),
        (ribbon_x + ribbon_w, ribbon_y1),
        (ribbon_x + ribbon_w // 2, ribbon_y1 - (12 * scale)),
        (ribbon_x, ribbon_y1)
    ]
    draw.polygon(ribbon_poly, fill=(0, 0, 0, 255), outline=(255, 255, 255, 255))

    # Вырезанная белая буква «Я» в центре обложки
    font_ya = ImageFont.truetype(FONT_MONTSERRAT_BLACK, 210 * scale)
    bbox_ya = font_ya.getbbox("Я")
    ya_w = bbox_ya[2] - bbox_ya[0]
    ya_h = bbox_ya[3] - bbox_ya[1]
    ya_x = (book_x0 + spine_x + (book_x1 - spine_x) - ya_w) // 2 - bbox_ya[0]
    ya_y = (size - ya_h) // 2 - bbox_ya[1]
    draw.text((ya_x, ya_y), "Я", fill=(255, 255, 255, 255), font=font_ya)

    return finalize_image(img, "option_3_book_cover_embossed_ya.png")


# =========================================================================
# ВАРИАНТ 4: Воздушная контурная «Я» с чистой типографикой
# =========================================================================
def generate_option_4():
    scale = 2
    img, draw, size = create_base_canvas(scale)
    draw_squircle_card(draw, size, stroke_width=14 * scale, corner_radius=96 * scale, padding=28 * scale)

    ya_mask = Image.new("L", (size, size), 0)
    ya_draw = ImageDraw.Draw(ya_mask)
    font_ya = ImageFont.truetype(FONT_MONTSERRAT_BLACK, 310 * scale)
    bbox_ya = font_ya.getbbox("Я")
    ya_w = bbox_ya[2] - bbox_ya[0]
    ya_h = bbox_ya[3] - bbox_ya[1]
    ya_x = (size - ya_w) // 2 - bbox_ya[0]
    ya_y = (size - ya_h) // 2 - bbox_ya[1] - (24 * scale)
    ya_draw.text((ya_x, ya_y), "Я", fill=255, font=font_ya)

    outline_thickness = 17 * scale
    dilated = ya_mask.filter(ImageFilter.MaxFilter(outline_thickness * 2 + 1))
    eroded = ya_mask.filter(ImageFilter.MinFilter(outline_thickness * 2 + 1))
    contour_ya = ImageChops.subtract(dilated, eroded)

    font_books = ImageFont.truetype(FONT_MONTSERRAT_BLACK, 54 * scale)
    text_books = "К Н И Г И"
    bbox_b = font_books.getbbox(text_books)
    bw = bbox_b[2] - bbox_b[0]
    bx = (size - bw) // 2 - bbox_b[0]
    by = ya_y + ya_h - (24 * scale)

    books_mask = Image.new("L", (size, size), 0)
    b_draw = ImageDraw.Draw(books_mask)
    b_draw.text((bx, by), text_books, fill=255, font=font_books)

    halo = books_mask.filter(ImageFilter.MaxFilter(16 * scale + 1))
    final_contour = ImageChops.subtract(contour_ya, halo)

    black_layer = Image.new("RGBA", (size, size), (0, 0, 0, 255))
    img.paste(black_layer, (0, 0), mask=final_contour)
    draw.text((bx, by), text_books, fill=(0, 0, 0, 255), font=font_books)

    return finalize_image(img, "option_4_airy_contour_ya.png")


# =========================================================================
# СВОДНЫЙ ЛИСТ СРАВНЕНИЯ И МОКАП ЭКРАНА ONYX BOOX
# =========================================================================
def generate_comparison_sheet(im1, im2, im3, im4):
    """Генерирует лист презентации 4 вариантов для пользователя."""
    w, h = 1200, 1400
    sheet = Image.new("RGB", (w, h), (248, 248, 248))
    draw = ImageDraw.Draw(sheet)

    font_title = ImageFont.truetype(FONT_MONTSERRAT_EXTRA_BOLD, 36)
    font_sub = ImageFont.truetype(FONT_MONTSERRAT_BOLD, 20)
    font_desc = ImageFont.truetype(FONT_MONTSERRAT_BOLD, 15)

    draw.text((60, 40), "ДИЗАЙНЕРСКИЕ КОНЦЕПТЫ ИКОНКИ «ЯНДЕКС КНИГИ»", fill=(0, 0, 0), font=font_title)
    draw.text((60, 90), "Адаптировано для E-Ink Carta (высокий контраст, выверенная геометрия, 0 артефактов)", fill=(100, 100, 100), font=font_sub)
    draw.line([60, 130, w - 60, 130], fill=(200, 200, 200), width=2)

    cards = [
        (im1, "ВАРИАНТ 1 (Эскиз с плашкой)", "Точное воплощение эскиза:\nконтурная «Я» + четкая плашка «КНИГИ»\nс защитным белым вырезом без грязи.", 60, 160),
        (im2, "ВАРИАНТ 2 (Раскрытая книга)", "Каноническая книга Onyx Boox:\nразворот страниц в едином стиле со «Словарем»\nи парящая монограмма «Я».", 640, 160),
        (im3, "ВАРИАНТ 3 (Книжный переплет)", "Солидный контраст:\nчерный томик с белой закладкой-ляссе\nи вырезанным тиснением «Я». Не блекнет.", 60, 760),
        (im4, "ВАРИАНТ 4 (Воздушный контур)", "Премиальный минимализм:\nвыверенная по толщине «Я» с чистым\nнабором «К Н И Г И» без плашки.", 640, 760),
    ]

    for im, title, desc, cx, cy in cards:
        # Фон карточки
        draw.rounded_rectangle([cx, cy, cx + 500, cy + 540], radius=16, fill=(255, 255, 255), outline=(220, 220, 220), width=2)
        # Иконка 360x360
        im_thumb = im.resize((360, 360), Image.Resampling.LANCZOS)
        sheet.paste(im_thumb, (cx + 70, cy + 25), mask=im_thumb.split()[3])
        # Текст
        draw.text((cx + 30, cy + 410), title, fill=(0, 0, 0), font=font_sub)
        y_d = cy + 445
        for line in desc.split("\n"):
            draw.text((cx + 30, y_d), line, fill=(80, 80, 80), font=font_desc)
            y_d += 22

    sheet.save(os.path.join(OUTPUT_DIR, "presentation_proposals.png"), "PNG")
    print("Saved presentation sheet: docs/icon_proposals/presentation_proposals.png")


def generate_onyx_screen_mockup(im1, im2, im3, im4):
    """
    Берет реальную фотографию экрана Onyx Boox (media_1791230964848.jpg)
    и монтирует в верхний левый угол каждый из вариантов, чтобы пользователь
    увидел иконку прямо среди других системных приложений лаунчера.
    """
    user_photo_path = "/Users/sergej/.gemini/antigravity/brain/21c87693-d340-4f64-862a-c313c546a304/.user_uploaded/media_1791230964848.jpg"
    if not os.path.exists(user_photo_path):
        return

    photo = Image.open(user_photo_path).convert("RGB")
    # Координаты иконки "Яндекс Книги" на оригинальной фотографии:
    # По фото: 768x1024 пропорции. Найдем прямоугольник иконки.
    # Верхний левый угол иконки: x ~ 140, y ~ 225, w ~ 80, h ~ 80
    pw, ph = photo.size
    
    # Создадим сравнительный коллаж экрана для Варианта 1 и Варианта 2
    # Координаты оригинальной иконки на фото:
    # Иконка Яндекс Книги находится примерно на x=142, y=228, размер ~ 80x80 (при pw=768)
    box_x = int(pw * 0.185)
    box_y = int(ph * 0.225)
    box_s = int(pw * 0.105)

    variants = [
        (im1, "mockup_screen_option_1.jpg"),
        (im2, "mockup_screen_option_2.jpg"),
        (im3, "mockup_screen_option_3.jpg"),
        (im4, "mockup_screen_option_4.jpg"),
    ]

    for im, out_name in variants:
        screen_copy = photo.copy()
        im_sized = im.resize((box_s, box_s), Image.Resampling.LANCZOS)
        # Вклеиваем с учетом альфа-канала
        screen_copy.paste(im_sized, (box_x, box_y), mask=im_sized.split()[3])
        screen_copy.save(os.path.join(OUTPUT_DIR, out_name), "JPEG", quality=92)
        print(f"Saved launcher mockup: docs/icon_proposals/{out_name}")


if __name__ == "__main__":
    print("Генерация 4 дизайнерских концептов иконки для Onyx Boox...")
    im1 = generate_option_1()
    im2 = generate_option_2()
    im3 = generate_option_3()
    im4 = generate_option_4()
    generate_comparison_sheet(im1, im2, im3, im4)
    generate_onyx_screen_mockup(im1, im2, im3, im4)
    print("Готово!")
