package com.onyx.yandexbooks.core.epub;

import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Stack;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Структурный HTML/CSS разметчик EPUB-контента для нативного Canvas-движка.
 * Сохраняет в тексте компактные управляющие маркеры (Private Use Area U+E000..U+E01F)
 * для заголовков (h1-h6), выравнивания, отступов, цитат, разрывов страниц,
 * курсива/жирности/подчеркивания/моноширины и кликабельных ссылок, не создавая
 * коллизий с символами реальных книг.
 */
public class EpubFormatting {

    private static final String TAG = "EpubFormatting";

    public static final char BLK_START = '\uE001';
    public static final char BLK_END = '\uE000';
    public static final char PAGE_BREAK_CHAR = '\uE002';
    public static final String PAGE_BREAK_TOKEN = "\uE002";

    public static final char BOLD_ON = '\uE010';
    public static final char BOLD_OFF = '\uE011';
    public static final char ITALIC_ON = '\uE012';
    public static final char ITALIC_OFF = '\uE013';
    public static final char UNDERLINE_ON = '\uE014';
    public static final char UNDERLINE_OFF = '\uE015';
    public static final char STRIKE_ON = '\uE016';
    public static final char STRIKE_OFF = '\uE017';
    public static final char MONO_ON = '\uE018';
    public static final char MONO_OFF = '\uE019';
    public static final char LINK_START = '\uE01A';
    public static final char LINK_SEP = '\uE01B';
    public static final char LINK_END = '\uE01C';

    public static final class BlockStyle {
        public char align = 'D'; // D=default/justify, L=left, C=center, R=right
        public char role = 'P';  // P=paragraph, 1..6=heading, Q=quote, E=epigraph, N=no-indent
        public boolean spaceTop = false;
        public boolean spaceBottom = false;
        public boolean bold = false;
        public boolean italic = false;
        public boolean underline = false;
        public boolean strike = false;
        public boolean mono = false;
        public boolean pageBreakBefore = false;
        public boolean pageBreakAfter = false;

        public BlockStyle copy() {
            BlockStyle c = new BlockStyle();
            c.align = this.align;
            c.role = this.role;
            c.spaceTop = this.spaceTop;
            c.spaceBottom = this.spaceBottom;
            c.bold = this.bold;
            c.italic = this.italic;
            c.underline = this.underline;
            c.strike = this.strike;
            c.mono = this.mono;
            c.pageBreakBefore = this.pageBreakBefore;
            c.pageBreakAfter = this.pageBreakAfter;
            return c;
        }

        public String encodeHeader() {
            StringBuilder sb = new StringBuilder(12);
            sb.append(BLK_START).append(align).append(':').append(role).append(':');
            if (spaceTop) sb.append('T');
            if (spaceBottom) sb.append('B');
            if (bold) sb.append('b');
            if (italic) sb.append('i');
            sb.append(BLK_END);
            return sb.toString();
        }
    }

    public static final class ParsedBlockHeader {
        public char align = 'D';
        public char role = 'P';
        public boolean spaceTop = false;
        public boolean spaceBottom = false;
        public boolean blockBold = false;
        public boolean blockItalic = false;
        public String bodyText = "";
    }

    public static ParsedBlockHeader parseBlockHeader(String rawParagraph) {
        ParsedBlockHeader h = new ParsedBlockHeader();
        if (rawParagraph == null || rawParagraph.isEmpty()) {
            h.bodyText = "";
            return h;
        }
        if (rawParagraph.charAt(0) == BLK_START) {
            int endIdx = rawParagraph.indexOf(BLK_END);
            if (endIdx > 1 && endIdx <= 20) {
                String meta = rawParagraph.substring(1, endIdx);
                String[] parts = meta.split(":", -1);
                if (parts.length >= 1 && !parts[0].isEmpty()) h.align = parts[0].charAt(0);
                if (parts.length >= 2 && !parts[1].isEmpty()) h.role = parts[1].charAt(0);
                if (parts.length >= 3) {
                    String flags = parts[2];
                    h.spaceTop = flags.indexOf('T') >= 0;
                    h.spaceBottom = flags.indexOf('B') >= 0;
                    h.blockBold = flags.indexOf('b') >= 0;
                    h.blockItalic = flags.indexOf('i') >= 0;
                }
                h.bodyText = rawParagraph.substring(endIdx + 1);
                return h;
            }
        }
        h.bodyText = rawParagraph;
        return h;
    }

    private static final class CssRuleProps {
        char align = '\0'; // L, C, R, D
        Boolean bold = null;
        Boolean italic = null;
        Boolean underline = null;
        Boolean strike = null;
        Boolean mono = null;
        Boolean noIndent = null;
        Boolean quote = null;
        Boolean spaceTop = null;
        Boolean spaceBottom = null;
        Boolean pageBreakBefore = null;
        Boolean pageBreakAfter = null;

        void mergeFrom(CssRuleProps other) {
            if (other == null) return;
            if (other.align != '\0') align = other.align;
            if (other.bold != null) bold = other.bold;
            if (other.italic != null) italic = other.italic;
            if (other.underline != null) underline = other.underline;
            if (other.strike != null) strike = other.strike;
            if (other.mono != null) mono = other.mono;
            if (other.noIndent != null) noIndent = other.noIndent;
            if (other.quote != null) quote = other.quote;
            if (other.spaceTop != null) spaceTop = other.spaceTop;
            if (other.spaceBottom != null) spaceBottom = other.spaceBottom;
            if (other.pageBreakBefore != null) pageBreakBefore = other.pageBreakBefore;
            if (other.pageBreakAfter != null) pageBreakAfter = other.pageBreakAfter;
        }
    }

    private static final Pattern CSS_COMMENT_PATTERN = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern CSS_AT_RULE_PATTERN = Pattern.compile("(?is)@(?:font-face|page|import|charset|namespace)[^{;]*(?:\\{[^}]*\\}|;)");
    private static final Pattern CSS_RULE_PATTERN = Pattern.compile("([^{}@]+)\\{([^}]*)\\}");
    private static final Pattern ZERO_CSS_LEN_PATTERN = Pattern.compile("^0(?:\\.?0+)?(?:px|em|rem|pt|%|mm|cm|ex)?$");
    private static final Pattern POS_CSS_LEN_PATTERN = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*(em|rem|pt|px|%)?$");
    private static final Pattern HTML_ENTITY_NUM_PATTERN = Pattern.compile("&#(x[0-9a-fA-F]+|[0-9]+);");
    private static final Pattern IMG_TAG_PATTERN = Pattern.compile("<img\\b[^>]*?src\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SVG_IMG_PATTERN = Pattern.compile("<image\\b[^>]*?(?:xlink:href|href)\\s*=\\s*[\"']([^\"']+)[\"'][^>]*?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BODY_TAG_PATTERN = Pattern.compile("(?is)<body\\b[^>]*>(.*)</body>");
    private static final Pattern STRIP_HEAD_PATTERN = Pattern.compile("(?is)<(script|style|head)\\b[^>]*>.*?</\\1>");
    private static final Pattern HR_TAG_PATTERN = Pattern.compile("(?i)<hr\\b[^>]*/?>");
    private static final Pattern EMPTY_P_TAG_PATTERN = Pattern.compile("(?i)<p[^>]*>(\\s*|&nbsp;|<br\\s*/?>|\\*\\s*\\*\\s*\\*)</p>");
    private static final Pattern ATTR_ID_PATTERN = Pattern.compile("(?is)(?:^|\\s)id\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");
    private static final Pattern ATTR_CLASS_PATTERN = Pattern.compile("(?is)(?:^|\\s)class\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");
    private static final Pattern ATTR_ALIGN_PATTERN = Pattern.compile("(?is)(?:^|\\s)align\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");
    private static final Pattern ATTR_STYLE_PATTERN = Pattern.compile("(?is)(?:^|\\s)style\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");
    private static final Pattern ATTR_HREF_PATTERN = Pattern.compile("(?is)(?:^|\\s)href\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");

    public static final class CssSheet {
        private final Map<String, CssRuleProps> rules = new HashMap<>();

        public void mergeSheet(CssSheet other) {
            if (other == null || other.rules.isEmpty()) return;
            for (Map.Entry<String, CssRuleProps> e : other.rules.entrySet()) {
                CssRuleProps existing = rules.get(e.getKey());
                if (existing == null) {
                    CssRuleProps copy = new CssRuleProps();
                    copy.mergeFrom(e.getValue());
                    rules.put(e.getKey(), copy);
                } else {
                    existing.mergeFrom(e.getValue());
                }
            }
        }

        public void parseCss(String cssText) {
            if (cssText == null || cssText.isEmpty()) return;
            String cleaned = CSS_COMMENT_PATTERN.matcher(cssText).replaceAll(" ");
            // Игнорируем @font-face / @page / @media оболочки при разборе простых правил
            cleaned = CSS_AT_RULE_PATTERN.matcher(cleaned).replaceAll(" ");
            Matcher m = CSS_RULE_PATTERN.matcher(cleaned);
            while (m.find()) {
                String selectorGroup = m.group(1);
                String decls = m.group(2);
                if (selectorGroup == null || decls == null) continue;
                CssRuleProps props = parseDeclarations(decls);
                if (isEmptyProps(props)) continue;

                String[] selectors = selectorGroup.split(",");
                for (String selRaw : selectors) {
                    String sel = normalizeSelector(selRaw);
                    if (!sel.isEmpty()) {
                        CssRuleProps existing = rules.get(sel);
                        if (existing == null) {
                            CssRuleProps copy = new CssRuleProps();
                            copy.mergeFrom(props);
                            rules.put(sel, copy);
                        } else {
                            existing.mergeFrom(props);
                        }
                    }
                }
            }
        }

        private static String normalizeSelector(String selRaw) {
            if (selRaw == null) return "";
            String s = selRaw.trim().toLowerCase(Locale.US);
            // Берем последний простой селектор из цепочки (например, "div.body p.title" -> "p.title")
            int lastSpace = Math.max(s.lastIndexOf(' '), Math.max(s.lastIndexOf('>'), s.lastIndexOf('+')));
            if (lastSpace >= 0 && lastSpace + 1 < s.length()) {
                s = s.substring(lastSpace + 1).trim();
            }
            int pseudo = s.indexOf(':');
            if (pseudo > 0) {
                s = s.substring(0, pseudo).trim();
            }
            return s;
        }

        private static boolean isEmptyProps(CssRuleProps p) {
            return p.align == '\0' && p.bold == null && p.italic == null && p.underline == null
                    && p.strike == null && p.mono == null && p.noIndent == null && p.quote == null
                    && p.spaceTop == null && p.spaceBottom == null && p.pageBreakBefore == null
                    && p.pageBreakAfter == null;
        }

        private static CssRuleProps parseDeclarations(String decls) {
            CssRuleProps p = new CssRuleProps();
            if (decls == null || decls.isEmpty()) return p;
            String[] items = decls.split(";");
            for (String item : items) {
                int colon = item.indexOf(':');
                if (colon <= 0) continue;
                String prop = item.substring(0, colon).trim().toLowerCase(Locale.US);
                String val = item.substring(colon + 1).trim().toLowerCase(Locale.US);
                val = val.replace("!important", "").trim();

                if ("text-align".equals(prop)) {
                    if (val.contains("center")) p.align = 'C';
                    else if (val.contains("right")) p.align = 'R';
                    else if (val.contains("left")) p.align = 'L';
                    else if (val.contains("justify")) p.align = 'D';
                } else if ("font-weight".equals(prop)) {
                    if (val.contains("bold") || "600".equals(val) || "700".equals(val)
                            || "800".equals(val) || "900".equals(val)) {
                        p.bold = Boolean.TRUE;
                    } else if ("normal".equals(val) || "400".equals(val) || "300".equals(val)) {
                        p.bold = Boolean.FALSE;
                    }
                } else if ("font-style".equals(prop)) {
                    if (val.contains("italic") || val.contains("oblique")) {
                        p.italic = Boolean.TRUE;
                    } else if ("normal".equals(val)) {
                        p.italic = Boolean.FALSE;
                    }
                } else if ("text-decoration".equals(prop) || "text-decoration-line".equals(prop)) {
                    if (val.contains("underline")) p.underline = Boolean.TRUE;
                    if (val.contains("line-through")) p.strike = Boolean.TRUE;
                } else if ("font-family".equals(prop)) {
                    if (val.contains("monospace") || val.contains("courier") || val.contains("consolas")) {
                        p.mono = Boolean.TRUE;
                    }
                } else if ("text-indent".equals(prop)) {
                    if (isZeroCssLength(val)) {
                        p.noIndent = Boolean.TRUE;
                    } else if (isPositiveCssLength(val)) {
                        p.noIndent = Boolean.FALSE;
                    }
                } else if ("margin-left".equals(prop) || "padding-left".equals(prop)) {
                    if (isSignificantIndent(val)) {
                        p.quote = Boolean.TRUE;
                    }
                } else if ("margin-top".equals(prop) || "padding-top".equals(prop)) {
                    if (isPositiveCssLength(val)) {
                        p.spaceTop = Boolean.TRUE;
                    }
                } else if ("margin-bottom".equals(prop) || "padding-bottom".equals(prop)) {
                    if (isPositiveCssLength(val)) {
                        p.spaceBottom = Boolean.TRUE;
                    }
                } else if ("margin".equals(prop) || "padding".equals(prop)) {
                    String[] parts = val.split("\\s+");
                    if (parts.length >= 1 && isPositiveCssLength(parts[0])) p.spaceTop = Boolean.TRUE;
                    if (parts.length >= 3 && isPositiveCssLength(parts[2])) p.spaceBottom = Boolean.TRUE;
                    else if (parts.length == 1 && isPositiveCssLength(parts[0])) p.spaceBottom = Boolean.TRUE;
                    if (parts.length >= 4 && isSignificantIndent(parts[3])) p.quote = Boolean.TRUE;
                    else if (parts.length >= 2 && isSignificantIndent(parts[1])) p.quote = Boolean.TRUE;
                } else if ("page-break-before".equals(prop) || "break-before".equals(prop)) {
                    if (val.contains("always") || val.contains("page") || val.contains("left") || val.contains("right")) {
                        p.pageBreakBefore = Boolean.TRUE;
                    }
                } else if ("page-break-after".equals(prop) || "break-after".equals(prop)) {
                    if (val.contains("always") || val.contains("page")) {
                        p.pageBreakAfter = Boolean.TRUE;
                    }
                }
            }
            return p;
        }

        private static boolean isZeroCssLength(String val) {
            if (val == null || val.isEmpty()) return false;
            return ZERO_CSS_LEN_PATTERN.matcher(val).matches();
        }

        private static boolean isPositiveCssLength(String val) {
            if (val == null || val.isEmpty()) return false;
            Matcher m = POS_CSS_LEN_PATTERN.matcher(val);
            if (!m.find()) return false;
            try {
                float num = Float.parseFloat(m.group(1));
                String unit = m.group(2) != null ? m.group(2) : "";
                if ("px".equals(unit) || "pt".equals(unit)) return num >= 4f;
                return num >= 0.25f;
            } catch (Exception e) {
                return false;
            }
        }

        private static boolean isSignificantIndent(String val) {
            if (val == null || val.isEmpty()) return false;
            Matcher m = POS_CSS_LEN_PATTERN.matcher(val);
            if (!m.find()) return false;
            try {
                float num = Float.parseFloat(m.group(1));
                String unit = m.group(2) != null ? m.group(2) : "";
                if ("px".equals(unit) || "pt".equals(unit)) return num >= 14f;
                if ("%".equals(unit)) return num >= 4f;
                return num >= 1.0f;
            } catch (Exception e) {
                return false;
            }
        }

        CssRuleProps resolveElement(String tagName, String idAttr, String classAttr, String alignAttr, String inlineStyle) {
            CssRuleProps merged = new CssRuleProps();
            String tag = tagName != null ? tagName.toLowerCase(Locale.US) : "";
            merged.mergeFrom(rules.get(tag));

            if (classAttr != null && !classAttr.trim().isEmpty()) {
                String[] classes = classAttr.trim().split("\\s+");
                for (String cls : classes) {
                    String c = cls.toLowerCase(Locale.US).trim();
                    if (c.isEmpty()) continue;
                    merged.mergeFrom(rules.get("." + c));
                    merged.mergeFrom(rules.get(tag + "." + c));
                }
            }

            if (idAttr != null && !idAttr.trim().isEmpty()) {
                String id = idAttr.trim().toLowerCase(Locale.US);
                merged.mergeFrom(rules.get("#" + id));
                merged.mergeFrom(rules.get(tag + "#" + id));
            }

            if (alignAttr != null && !alignAttr.trim().isEmpty()) {
                String a = alignAttr.trim().toLowerCase(Locale.US);
                if ("center".equals(a)) merged.align = 'C';
                else if ("right".equals(a)) merged.align = 'R';
                else if ("left".equals(a)) merged.align = 'L';
                else if ("justify".equals(a)) merged.align = 'D';
            }

            if (inlineStyle != null && !inlineStyle.trim().isEmpty()) {
                merged.mergeFrom(parseDeclarations(inlineStyle));
            }
            return merged;
        }
    }

    private static final Pattern LINK_STYLE_HREF_PATTERN =
            Pattern.compile("(?is)<link\\b[^>]*?rel\\s*=\\s*[\"'][^\"']*stylesheet[^\"']*[\"'][^>]*?>");
    private static final Pattern HREF_ATTR_PATTERN =
            Pattern.compile("(?is)\\bhref\\s*=\\s*[\"']([^\"']+)[\"']");
    private static final Pattern INLINE_STYLE_BLOCK_PATTERN =
            Pattern.compile("(?is)<style\\b[^>]*>(.*?)</style>");

    /**
     * Загружает стили из подключенных .css файлов в EPUB и встроенных блоков &lt;style&gt;.
     */
    public static CssSheet buildCssSheet(ZipFile zip, String html, String chapterFileHref, Map<String, String> cssFileCache) {
        return buildCssSheet(zip, html, chapterFileHref, cssFileCache, null);
    }

    public static CssSheet buildCssSheet(ZipFile zip, String html, String chapterFileHref,
                                         Map<String, String> cssFileCache,
                                         Map<String, CssSheet> parsedCssSheetCache) {
        CssSheet sheet = new CssSheet();
        if (html == null || html.isEmpty()) return sheet;
        String baseDir = "";
        if (chapterFileHref != null && chapterFileHref.contains("/")) {
            baseDir = chapterFileHref.substring(0, chapterFileHref.lastIndexOf('/') + 1);
        }

        if (zip != null) {
            Matcher lm = LINK_STYLE_HREF_PATTERN.matcher(html);
            while (lm.find()) {
                Matcher hm = HREF_ATTR_PATTERN.matcher(lm.group(0));
                if (hm.find()) {
                    String rawHref = hm.group(1);
                    String resolved = resolveZipPath(baseDir, rawHref);
                    if (!resolved.isEmpty()) {
                        CssSheet cachedSheet = (parsedCssSheetCache != null) ? parsedCssSheetCache.get(resolved) : null;
                        if (cachedSheet != null) {
                            sheet.mergeSheet(cachedSheet);
                        } else {
                            String cssText = (cssFileCache != null) ? cssFileCache.get(resolved) : null;
                            if (cssText == null) {
                                cssText = readZipEntryText(zip, resolved);
                                if (cssFileCache != null && cssText != null) {
                                    cssFileCache.put(resolved, cssText);
                                }
                            }
                            if (cssText != null && !cssText.isEmpty()) {
                                if (parsedCssSheetCache != null) {
                                    CssSheet fileSheet = new CssSheet();
                                    fileSheet.parseCss(cssText);
                                    parsedCssSheetCache.put(resolved, fileSheet);
                                    sheet.mergeSheet(fileSheet);
                                } else {
                                    sheet.parseCss(cssText);
                                }
                            }
                        }
                    }
                }
            }
        }

        Matcher sm = INLINE_STYLE_BLOCK_PATTERN.matcher(html);
        while (sm.find()) {
            sheet.parseCss(sm.group(1));
        }
        return sheet;
    }

    /**
     * Конвертирует HTML-главу в размеченный текст с сохранением абзацев, отступов,
     * заголовков (h1-h6), выравнивания, цитат, разрывов страниц, начертаний и ссылок.
     */
    public static String extractRichHtmlText(String html, String chapterFileHref, CssSheet cssSheet) {
        if (html == null || html.isEmpty()) return "";
        try {
            String baseDir = "";
            String normChapterHref = resolveZipPath("", chapterFileHref != null ? chapterFileHref : "");
            if (chapterFileHref != null && chapterFileHref.contains("/")) {
                baseDir = chapterFileHref.substring(0, chapterFileHref.lastIndexOf('/') + 1);
            }

            if (cssSheet == null) {
                cssSheet = buildCssSheet(null, html, chapterFileHref, null);
            }

            String work = html;
            Matcher bodyMatcher = BODY_TAG_PATTERN.matcher(work);
            if (bodyMatcher.find()) {
                work = bodyMatcher.group(1);
            } else {
                work = STRIP_HEAD_PATTERN.matcher(work).replaceAll("");
            }

            // 1. Картинки <img> и <image> переводим в маркеры до удаления прочих тегов
            work = replaceImagesWithTokens(work, baseDir);

            // 2. Разделители <hr> и пустые ***-абзацы
            work = HR_TAG_PATTERN.matcher(work).replaceAll("\n___SECTION_BREAK___\n");
            work = EMPTY_P_TAG_PATTERN.matcher(work).replaceAll("\n___SECTION_BREAK___\n");

            // 3. Короткие ссылки-сноски ([1], [*]) нормализуем к виду [1] для существующих всплывающих сносок
            work = EpubParser.normalizeInlineFootnoteLinks(work);

            // 4. Однопроходный разбор тегов с отслеживанием стека блоков и инлайн-стилей
            List<String> outputBlocks = tokenizeHtmlToRichLines(work, baseDir, normChapterHref, cssSheet);

            StringBuilder out = new StringBuilder(work.length());
            boolean lastWasEmpty = false;
            boolean hasAnyContent = false;
            for (int i = 0; i < outputBlocks.size(); i++) {
                String blk = outputBlocks.get(i);
                if (blk == null) continue;
                if (PAGE_BREAK_TOKEN.equals(blk)) {
                    if (hasAnyContent) {
                        if (out.length() > 0) out.append('\n');
                        out.append(PAGE_BREAK_TOKEN);
                        lastWasEmpty = false;
                    }
                    continue;
                }
                if (blk.isEmpty()) {
                    if (hasAnyContent && !lastWasEmpty) {
                        if (out.length() > 0) out.append('\n');
                        lastWasEmpty = true;
                    }
                    continue;
                }
                if (out.length() > 0) out.append('\n');
                out.append(blk);
                lastWasEmpty = false;
                hasAnyContent = true;
            }
            return trimTrailingNewlines(out.toString());
        } catch (Throwable t) {
            Log.w(TAG, "Rich HTML formatting failed, falling back to plain cleaner", t);
            return EpubParser.cleanHtmlText(html, chapterFileHref);
        }
    }

    /**
     * Полностью очищает форматирующие служебные токены, оставляя только читаемый текст книги.
     */
    public static String stripFormattingTokens(String richText) {
        if (richText == null || richText.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(richText.length());
        int len = richText.length();
        int i = 0;
        while (i < len) {
            char c = richText.charAt(i);
            if (c == BLK_START) {
                int end = richText.indexOf(BLK_END, i + 1);
                if (end >= 0 && end - i <= 24) {
                    i = end + 1;
                    continue;
                }
            } else if (c == BLK_END || c == PAGE_BREAK_CHAR || (c >= '\uE010' && c <= '\uE019')) {
                i++;
                continue;
            } else if (c == LINK_START) {
                int sep = richText.indexOf(LINK_SEP, i + 1);
                int end = richText.indexOf(LINK_END, i + 1);
                if (sep > i && end > sep) {
                    sb.append(richText, sep + 1, end);
                    i = end + 1;
                    continue;
                }
            } else if (c == LINK_SEP || c == LINK_END) {
                i++;
                continue;
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static String replaceImagesWithTokens(String html, String baseDir) {
        if (html == null || (!html.contains("<img") && !html.contains("<IMG") && !html.contains("<image") && !html.contains("<IMAGE"))) {
            return html;
        }
        Matcher mImg = IMG_TAG_PATTERN.matcher(html);
        StringBuffer sbImg = new StringBuffer();
        while (mImg.find()) {
            String resolved = resolveZipPath(baseDir, mImg.group(1));
            if (!resolved.isEmpty()) {
                mImg.appendReplacement(sbImg, "\n___IMG_MARKER___:" + Matcher.quoteReplacement(resolved) + "\n");
            } else {
                mImg.appendReplacement(sbImg, "");
            }
        }
        mImg.appendTail(sbImg);
        String text = sbImg.toString();

        Matcher mSvg = SVG_IMG_PATTERN.matcher(text);
        StringBuffer sbSvg = new StringBuffer();
        while (mSvg.find()) {
            String resolved = resolveZipPath(baseDir, mSvg.group(1));
            if (!resolved.isEmpty()) {
                mSvg.appendReplacement(sbSvg, "\n___IMG_MARKER___:" + Matcher.quoteReplacement(resolved) + "\n");
            } else {
                mSvg.appendReplacement(sbSvg, "");
            }
        }
        mSvg.appendTail(sbSvg);
        return sbSvg.toString();
    }

    private static final class Frame {
        String tag;
        boolean isBlock;
        BlockStyle blockStyle;
        boolean boldDelta;
        boolean italicDelta;
        boolean underlineDelta;
        boolean strikeDelta;
        boolean monoDelta;
        String linkTarget;
    }

    private static List<String> tokenizeHtmlToRichLines(
            String html,
            String baseDir,
            String normChapterHref,
            CssSheet cssSheet) {
        List<String> lines = new ArrayList<>();
        Stack<Frame> stack = new Stack<>();

        BlockStyle rootStyle = new BlockStyle();
        Frame root = new Frame();
        root.tag = "body";
        root.isBlock = true;
        root.blockStyle = rootStyle;
        stack.push(root);

        StringBuilder curBody = new StringBuilder();
        BlockStyle activeLineStyle = rootStyle.copy();
        boolean activeLineStyleLocked = false;

        boolean bold = false;
        boolean italic = false;
        boolean underline = false;
        boolean strike = false;
        boolean mono = false;
        String activeLinkTarget = null;
        StringBuilder linkTextBuffer = null;

        int len = html.length();
        int i = 0;
        while (i < len) {
            int lt = html.indexOf('<', i);
            if (lt < 0) lt = len;

            if (lt > i) {
                String chunk = decodeAndCleanTextChunk(html.substring(i, lt));
                if (!chunk.isEmpty()) {
                    processTextChunk(chunk, lines, curBody, activeLineStyle, stack,
                            bold, italic, underline, strike, mono, activeLinkTarget, linkTextBuffer);
                    if (curBody.length() > 0 && !activeLineStyleLocked) {
                        activeLineStyleLocked = true;
                    }
                }
            }
            if (lt >= len) break;

            // Обработка комментариев <!-- ... --> и CDATA
            if (html.startsWith("<!--", lt)) {
                int endCom = html.indexOf("-->", lt + 4);
                i = (endCom >= 0) ? endCom + 3 : len;
                continue;
            }
            if (html.startsWith("<![CDATA[", lt)) {
                int endCdata = html.indexOf("]]>", lt + 9);
                if (endCdata >= 0) {
                    String chunk = html.substring(lt + 9, endCdata);
                    processTextChunk(chunk, lines, curBody, activeLineStyle, stack,
                            bold, italic, underline, strike, mono, activeLinkTarget, linkTextBuffer);
                    i = endCdata + 3;
                } else {
                    i = len;
                }
                continue;
            }

            int gt = findTagEnd(html, lt + 1);
            if (gt < 0) {
                break; // Незакрытый осколок тега на границе игнорируем
            }
            String tagRaw = html.substring(lt + 1, gt).trim();
            i = gt + 1;
            if (tagRaw.isEmpty() || tagRaw.startsWith("!") || tagRaw.startsWith("?")) continue;

            boolean isClosing = tagRaw.startsWith("/");
            boolean isSelfClosing = tagRaw.endsWith("/");
            String tagInner = isClosing ? tagRaw.substring(1).trim() : tagRaw;
            if (isSelfClosing && tagInner.endsWith("/")) {
                tagInner = tagInner.substring(0, tagInner.length() - 1).trim();
            }

            int spaceIdx = firstWhitespace(tagInner);
            String tagName = (spaceIdx > 0 ? tagInner.substring(0, spaceIdx) : tagInner).toLowerCase(Locale.US);
            String attrsStr = (spaceIdx > 0 && !isClosing) ? tagInner.substring(spaceIdx + 1) : "";

            if ("br".equals(tagName)) {
                flushRichLine(lines, curBody, activeLineStyle, stack.peek().blockStyle,
                        activeLineStyleLocked, activeLinkTarget, linkTextBuffer);
                activeLinkTarget = null;
                linkTextBuffer = null;
                activeLineStyle = stack.peek().blockStyle.copy();
                activeLineStyleLocked = false;
                continue;
            }

            boolean blockTag = isBlockTagName(tagName);

            if (isClosing) {
                if ("a".equals(tagName) && activeLinkTarget != null) {
                    closePendingLink(curBody, activeLinkTarget, linkTextBuffer);
                    activeLinkTarget = null;
                    linkTextBuffer = null;
                }
                // Откатываем стек до ближайшего совпадения тега
                int matchIdx = -1;
                for (int s = stack.size() - 1; s >= 1; s--) {
                    if (stack.get(s).tag.equals(tagName)) {
                        matchIdx = s;
                        break;
                    }
                }
                if (matchIdx >= 1) {
                    boolean poppedBlock = false;
                    boolean emitBreakAfter = false;
                    while (stack.size() - 1 >= matchIdx) {
                        Frame f = stack.pop();
                        if (f.linkTarget != null && activeLinkTarget != null) {
                            closePendingLink(curBody, activeLinkTarget, linkTextBuffer);
                            activeLinkTarget = null;
                            linkTextBuffer = null;
                        }
                        if (f.boldDelta) bold = !bold;
                        if (f.italicDelta) italic = !italic;
                        if (f.underlineDelta) underline = !underline;
                        if (f.strikeDelta) strike = !strike;
                        if (f.monoDelta) mono = !mono;
                        if (f.isBlock) {
                            poppedBlock = true;
                            if (f.blockStyle != null && f.blockStyle.pageBreakAfter) {
                                emitBreakAfter = true;
                            }
                        }
                    }
                    if (poppedBlock) {
                        flushRichLine(lines, curBody, activeLineStyle, stack.peek().blockStyle,
                                activeLineStyleLocked, activeLinkTarget, linkTextBuffer);
                        activeLineStyle = stack.peek().blockStyle.copy();
                        activeLineStyleLocked = false;
                        if (emitBreakAfter) {
                            lines.add(PAGE_BREAK_TOKEN);
                        }
                    }
                }
                continue;
            }

            // Открывающий тег
            String idAttr = extractAttr(attrsStr, "id");
            String classAttr = extractAttr(attrsStr, "class");
            String alignAttr = extractAttr(attrsStr, "align");
            String styleAttr = extractAttr(attrsStr, "style");
            CssRuleProps css = cssSheet.resolveElement(tagName, idAttr, classAttr, alignAttr, styleAttr);

            if (blockTag) {
                flushRichLine(lines, curBody, activeLineStyle, stack.peek().blockStyle,
                        activeLineStyleLocked, activeLinkTarget, linkTextBuffer);
                activeLinkTarget = null;
                linkTextBuffer = null;

                BlockStyle inherited = stack.peek().blockStyle.copy();
                inherited.pageBreakBefore = false;
                inherited.pageBreakAfter = false;
                applyTagAndCssToBlockStyle(tagName, classAttr, css, inherited);

                if (inherited.pageBreakBefore) {
                    lines.add(PAGE_BREAK_TOKEN);
                }

                Frame f = new Frame();
                f.tag = tagName;
                f.isBlock = true;
                f.blockStyle = inherited;

                // Вычисляем инлайн-дельты от блока/CSS
                boolean targetBold = Boolean.TRUE.equals(css.bold) || isHeadingTag(tagName);
                boolean targetItalic = Boolean.TRUE.equals(css.italic) || "blockquote".equals(tagName);
                boolean targetUnderline = Boolean.TRUE.equals(css.underline);
                boolean targetStrike = Boolean.TRUE.equals(css.strike);
                boolean targetMono = Boolean.TRUE.equals(css.mono) || "pre".equals(tagName);

                if (targetBold && !bold) { f.boldDelta = true; bold = true; }
                if (targetItalic && !italic) { f.italicDelta = true; italic = true; }
                if (targetUnderline && !underline) { f.underlineDelta = true; underline = true; }
                if (targetStrike && !strike) { f.strikeDelta = true; strike = true; }
                if (targetMono && !mono) { f.monoDelta = true; mono = true; }

                activeLineStyle = inherited.copy();
                activeLineStyleLocked = false;

                if (!isSelfClosing) {
                    stack.push(f);
                    if ("li".equals(tagName)) {
                        curBody.append("• ");
                        activeLineStyleLocked = true;
                    }
                }
                continue;
            }

            // Инлайн-тег
            if ("a".equals(tagName) && !isSelfClosing) {
                String href = extractAttr(attrsStr, "href");
                String resolvedHref = resolveLinkHref(href, baseDir, normChapterHref);
                Frame f = new Frame();
                f.tag = tagName;
                f.isBlock = false;
                f.blockStyle = stack.peek().blockStyle;
                if (resolvedHref != null && !resolvedHref.isEmpty()) {
                    if (activeLinkTarget != null) {
                        closePendingLink(curBody, activeLinkTarget, linkTextBuffer);
                    }
                    activeLinkTarget = resolvedHref;
                    linkTextBuffer = new StringBuilder();
                    f.linkTarget = resolvedHref;
                }
                stack.push(f);
                continue;
            }

            if (isSelfClosing) {
                continue;
            }

            Frame f = new Frame();
            f.tag = tagName;
            f.isBlock = false;
            f.blockStyle = stack.peek().blockStyle;

            boolean addBold = ("b".equals(tagName) || "strong".equals(tagName) || Boolean.TRUE.equals(css.bold));
            boolean addItalic = ("i".equals(tagName) || "em".equals(tagName) || "cite".equals(tagName)
                    || "dfn".equals(tagName) || "var".equals(tagName) || Boolean.TRUE.equals(css.italic));
            boolean addUnderline = ("u".equals(tagName) || "ins".equals(tagName) || Boolean.TRUE.equals(css.underline));
            boolean addStrike = ("s".equals(tagName) || "del".equals(tagName) || "strike".equals(tagName) || Boolean.TRUE.equals(css.strike));
            boolean addMono = ("code".equals(tagName) || "tt".equals(tagName) || "kbd".equals(tagName)
                    || "samp".equals(tagName) || Boolean.TRUE.equals(css.mono));

            if (addBold && !bold) { f.boldDelta = true; bold = true; }
            if (addItalic && !italic) { f.italicDelta = true; italic = true; }
            if (addUnderline && !underline) { f.underlineDelta = true; underline = true; }
            if (addStrike && !strike) { f.strikeDelta = true; strike = true; }
            if (addMono && !mono) { f.monoDelta = true; mono = true; }

            stack.push(f);
        }

        if (activeLinkTarget != null) {
            closePendingLink(curBody, activeLinkTarget, linkTextBuffer);
        }
        flushRichLine(lines, curBody, activeLineStyle, stack.peek().blockStyle,
                activeLineStyleLocked, null, null);
        return lines;
    }

    private static void processTextChunk(
            String chunk,
            List<String> lines,
            StringBuilder curBody,
            BlockStyle activeLineStyle,
            Stack<Frame> stack,
            boolean bold,
            boolean italic,
            boolean underline,
            boolean strike,
            boolean mono,
            String activeLinkTarget,
            StringBuilder linkTextBuffer) {
        if (chunk.contains("___IMG_MARKER___:") || chunk.contains("___SECTION_BREAK___")) {
            String[] rawParts = chunk.split("\n");
            for (int k = 0; k < rawParts.length; k++) {
                String p = rawParts[k].trim();
                if (p.isEmpty()) continue;
                if (p.startsWith("___IMG_MARKER___:")) {
                    flushRichLine(lines, curBody, activeLineStyle, stack.peek().blockStyle, true, activeLinkTarget, linkTextBuffer);
                    String imgPath = p.substring("___IMG_MARKER___:".length()).trim();
                    if (!imgPath.isEmpty()) {
                        lines.add("[IMG:" + imgPath + "]");
                    }
                } else if ("___SECTION_BREAK___".equals(p)) {
                    flushRichLine(lines, curBody, activeLineStyle, stack.peek().blockStyle, true, activeLinkTarget, linkTextBuffer);
                    lines.add("");
                } else {
                    appendStyledText(curBody, p, bold, italic, underline, strike, mono, activeLinkTarget, linkTextBuffer);
                }
            }
        } else {
            // Схлопываем внутритекстовые переводы строк в обычный пробел для нормального переноса абзаца
            String normalized = collapseSpacesPreserveSingle(chunk);
            if (!normalized.isEmpty()) {
                appendStyledText(curBody, normalized, bold, italic, underline, strike, mono, activeLinkTarget, linkTextBuffer);
            }
        }
    }

    private static String collapseSpacesPreserveSingle(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean prevSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                if (!prevSpace) {
                    sb.append(' ');
                    prevSpace = true;
                }
            } else {
                sb.append(c);
                prevSpace = false;
            }
        }
        return sb.toString();
    }

    private static void appendStyledText(
            StringBuilder curBody,
            String text,
            boolean bold,
            boolean italic,
            boolean underline,
            boolean strike,
            boolean mono,
            String activeLinkTarget,
            StringBuilder linkTextBuffer) {
        if (text == null || text.isEmpty()) return;
        if (activeLinkTarget != null && linkTextBuffer != null) {
            if (bold) linkTextBuffer.append(BOLD_ON);
            if (italic) linkTextBuffer.append(ITALIC_ON);
            if (mono) linkTextBuffer.append(MONO_ON);
            linkTextBuffer.append(text);
            if (mono) linkTextBuffer.append(MONO_OFF);
            if (italic) linkTextBuffer.append(ITALIC_OFF);
            if (bold) linkTextBuffer.append(BOLD_OFF);
            return;
        }
        if (curBody.length() == 0) {
            // Убираем ведущие пробелы в начале абзаца
            int firstNonSpace = 0;
            while (firstNonSpace < text.length() && text.charAt(firstNonSpace) == ' ') {
                firstNonSpace++;
            }
            if (firstNonSpace >= text.length()) return;
            text = text.substring(firstNonSpace);
        }
        if (bold) curBody.append(BOLD_ON);
        if (italic) curBody.append(ITALIC_ON);
        if (underline) curBody.append(UNDERLINE_ON);
        if (strike) curBody.append(STRIKE_ON);
        if (mono) curBody.append(MONO_ON);
        curBody.append(text);
        if (mono) curBody.append(MONO_OFF);
        if (strike) curBody.append(STRIKE_OFF);
        if (underline) curBody.append(UNDERLINE_OFF);
        if (italic) curBody.append(ITALIC_OFF);
        if (bold) curBody.append(BOLD_OFF);
    }

    private static void closePendingLink(StringBuilder curBody, String target, StringBuilder linkTextBuffer) {
        if (linkTextBuffer == null || target == null) return;
        String inner = linkTextBuffer.toString().trim();
        linkTextBuffer.setLength(0);
        if (inner.isEmpty()) return;
        String safeTarget = target.replace(LINK_START, ' ').replace(LINK_SEP, ' ').replace(LINK_END, ' ').trim();
        if (safeTarget.isEmpty()) {
            curBody.append(inner);
            return;
        }
        curBody.append(LINK_START).append(safeTarget).append(LINK_SEP).append(inner).append(LINK_END);
    }

    private static void flushRichLine(
            List<String> lines,
            StringBuilder curBody,
            BlockStyle activeStyle,
            BlockStyle fallbackStyle,
            boolean styleLocked,
            String activeLinkTarget,
            StringBuilder linkTextBuffer) {
        if (activeLinkTarget != null && linkTextBuffer != null && linkTextBuffer.length() > 0) {
            closePendingLink(curBody, activeLinkTarget, linkTextBuffer);
        }
        String raw = curBody.toString().trim();
        curBody.setLength(0);
        if (raw.isEmpty()) return;

        // Проверяем, остался ли вообще видимый текст после удаления пустых токенов
        String visible = stripFormattingTokens(raw).trim();
        if (visible.isEmpty()) return;

        if (visible.startsWith("[IMG:") && visible.endsWith("]")) {
            lines.add(visible);
            return;
        }

        BlockStyle bs = styleLocked ? activeStyle : (fallbackStyle != null ? fallbackStyle : activeStyle);
        if (bs.align == 'D' && bs.role == 'P' && !bs.spaceTop && !bs.spaceBottom && !bs.bold && !bs.italic) {
            // Оптимизация: стандартный абзац без спецфлагов оставляем без префикса
            lines.add(raw);
        } else {
            lines.add(bs.encodeHeader() + raw);
        }
    }

    private static boolean isContainerBlockTag(String tagName) {
        return "div".equals(tagName) || "section".equals(tagName) || "article".equals(tagName)
                || "header".equals(tagName) || "footer".equals(tagName) || "aside".equals(tagName);
    }

    private static void applyTagAndCssToBlockStyle(String tagName, String classAttr, CssRuleProps css, BlockStyle style) {
        String lowerCls = classAttr != null ? classAttr.toLowerCase(Locale.US) : "";
        boolean isContainer = isContainerBlockTag(tagName);

        if (isHeadingTag(tagName)) {
            style.role = tagName.charAt(1); // '1'..'6'
            if (style.align == 'D') style.align = 'C';
            style.spaceTop = true;
            style.spaceBottom = true;
            style.bold = true;
        } else if ("center".equals(tagName)) {
            style.align = 'C';
            style.role = 'N';
        } else if ("blockquote".equals(tagName)) {
            style.role = 'Q';
            style.spaceTop = true;
            style.spaceBottom = true;
        } else if ("li".equals(tagName) || "dd".equals(tagName) || "pre".equals(tagName)) {
            style.role = 'N';
        } else if (lowerCls.contains("epigraph") || lowerCls.contains("dedication")) {
            style.role = 'E';
            style.spaceTop = true;
            style.spaceBottom = true;
        } else if (!isContainer && (lowerCls.contains("footnote") || lowerCls.contains("endnote"))) {
            style.role = 'N';
        } else if (!isContainer && (lowerCls.contains("subtitle") || lowerCls.contains("chapter-title") || lowerCls.contains("chapter_title"))) {
            if (style.role == 'P') {
                if (style.align == 'D') style.align = 'C';
                style.spaceTop = true;
                style.spaceBottom = true;
            }
        }

        if (css != null) {
            if (css.align != '\0') style.align = css.align;
            // Не переводим обычный абзац <p> с text-indent:0 в роль 'N' (сноска), чтобы сохранялись пользовательские отступ и выравнивание по ширине
            if (Boolean.TRUE.equals(css.quote) && !isContainer) style.role = 'Q';
            if (Boolean.TRUE.equals(css.spaceTop) && !isContainer) style.spaceTop = true;
            if (Boolean.TRUE.equals(css.spaceBottom) && !isContainer) style.spaceBottom = true;
            if (Boolean.TRUE.equals(css.pageBreakBefore)) style.pageBreakBefore = true;
            if (Boolean.TRUE.equals(css.pageBreakAfter)) style.pageBreakAfter = true;
        }
    }

    private static boolean isHeadingTag(String tagName) {
        return tagName != null && tagName.length() == 2 && tagName.charAt(0) == 'h'
                && tagName.charAt(1) >= '1' && tagName.charAt(1) <= '6';
    }

    private static boolean isBlockTagName(String tagName) {
        return "p".equals(tagName) || "div".equals(tagName) || isHeadingTag(tagName)
                || "blockquote".equals(tagName) || "li".equals(tagName) || "dd".equals(tagName)
                || "dt".equals(tagName) || "pre".equals(tagName) || "section".equals(tagName)
                || "article".equals(tagName) || "header".equals(tagName) || "footer".equals(tagName)
                || "aside".equals(tagName) || "center".equals(tagName) || "tr".equals(tagName);
    }

    private static String resolveLinkHref(String rawHref, String baseDir, String normChapterHref) {
        if (rawHref == null) return null;
        String href = rawHref.trim();
        if (href.isEmpty() || href.startsWith("javascript:")) return null;
        String lower = href.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:")) {
            return href;
        }
        int hashIdx = href.indexOf('#');
        String filePart = (hashIdx >= 0) ? href.substring(0, hashIdx).trim() : href;
        String anchorPart = (hashIdx >= 0) ? href.substring(hashIdx + 1).trim() : "";
        String resolvedFile = filePart.isEmpty() ? normChapterHref : resolveZipPath(baseDir, filePart);
        if (resolvedFile.isEmpty() && anchorPart.isEmpty()) return null;
        return anchorPart.isEmpty() ? resolvedFile : (resolvedFile + "#" + anchorPart);
    }

    private static String decodeAndCleanTextChunk(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String text = raw
                .replace("\uFFFC", "")
                .replace("\uFFFD", "")
                .replace("\uFEFF", "")
                .replace("\u200B", "")
                .replace("\u200C", "")
                .replace("\u200D", "")
                .replace("\u00AD", "");

        text = text.replace("&nbsp;", " ")
                .replace("&laquo;", "«")
                .replace("&raquo;", "»")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&hellip;", "…")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");

        if (text.contains("&#")) {
            Matcher matcher = HTML_ENTITY_NUM_PATTERN.matcher(text);
            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String val = matcher.group(1);
                try {
                    int code = (val.startsWith("x") || val.startsWith("X"))
                            ? Integer.parseInt(val.substring(1), 16)
                            : Integer.parseInt(val);
                    if (code == 0xFFFC || code == 0xFFFD || code == 0xFEFF || (code < 32 && code != 10 && code != 9)) {
                        matcher.appendReplacement(sb, "");
                    } else {
                        matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) code)));
                    }
                } catch (Exception e) {
                    matcher.appendReplacement(sb, "");
                }
            }
            matcher.appendTail(sb);
            text = sb.toString();
        }
        return text;
    }

    private static int findTagEnd(String html, int from) {
        char quote = '\0';
        int len = html.length();
        for (int i = from; i < len; i++) {
            char c = html.charAt(i);
            if (quote != '\0') {
                if (c == quote) quote = '\0';
            } else {
                if (c == '"' || c == '\'') quote = c;
                else if (c == '>') return i;
            }
        }
        return -1;
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static String extractAttr(String attrsStr, String attrName) {
        if (attrsStr == null || attrsStr.isEmpty() || attrsStr.indexOf('=') < 0) return null;
        Pattern p;
        if ("id".equals(attrName)) p = ATTR_ID_PATTERN;
        else if ("class".equals(attrName)) p = ATTR_CLASS_PATTERN;
        else if ("align".equals(attrName)) p = ATTR_ALIGN_PATTERN;
        else if ("style".equals(attrName)) p = ATTR_STYLE_PATTERN;
        else if ("href".equals(attrName)) p = ATTR_HREF_PATTERN;
        else p = Pattern.compile("(?is)(?:^|\\s)" + Pattern.quote(attrName) + "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'=<>`]+))");
        Matcher m = p.matcher(attrsStr);
        if (m.find()) {
            if (m.group(1) != null) return m.group(1);
            if (m.group(2) != null) return m.group(2);
            return m.group(3);
        }
        return null;
    }

    private static String trimTrailingNewlines(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == ' ')) {
            end--;
        }
        int start = 0;
        while (start < end && (s.charAt(start) == '\n' || s.charAt(start) == ' ')) {
            start++;
        }
        return s.substring(start, end);
    }

    public static String resolveZipPath(String baseDir, String relativePath) {
        if (relativePath == null) return "";
        String clean = relativePath.trim();
        if (clean.contains("#")) clean = clean.substring(0, clean.indexOf('#'));
        if (clean.contains("?")) clean = clean.substring(0, clean.indexOf('?'));
        try {
            clean = URLDecoder.decode(clean, "UTF-8");
        } catch (Exception ignored) {}
        clean = clean.replace("\\", "/");
        while (clean.startsWith("/")) clean = clean.substring(1);

        String combined = (baseDir != null && !baseDir.isEmpty()) ? (baseDir + clean) : clean;
        String[] parts = combined.split("/");
        List<String> resolved = new ArrayList<>();
        for (String p : parts) {
            if (p == null || p.isEmpty() || ".".equals(p)) continue;
            if ("..".equals(p)) {
                if (!resolved.isEmpty()) resolved.remove(resolved.size() - 1);
            } else {
                resolved.add(p);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < resolved.size(); i++) {
            if (i > 0) sb.append("/");
            sb.append(resolved.get(i));
        }
        return sb.toString();
    }

    private static String readZipEntryText(ZipFile zip, String path) {
        if (zip == null || path == null || path.isEmpty()) return null;
        try {
            ZipEntry entry = zip.getEntry(path);
            if (entry == null) {
                Enumeration<? extends ZipEntry> all = zip.entries();
                while (all.hasMoreElements()) {
                    ZipEntry ze = all.nextElement();
                    if (ze.getName().equalsIgnoreCase(path)) {
                        entry = ze;
                        break;
                    }
                }
            }
            if (entry == null) return null;
            InputStream is = zip.getInputStream(entry);
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                return sb.toString();
            } finally {
                is.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Извлекает встроенные в EPUB шрифты (.ttf/.otf) в кэш книги и возвращает карту:
     * keys: "regular", "bold", "italic", "bold_italic" -> пути к локальным файлам.
     */
    public static Map<String, String> extractEpubFonts(ZipFile zip, File destFontsDir) {
        Map<String, String> result = new HashMap<>();
        if (zip == null || destFontsDir == null) return result;
        try {
            List<ZipEntry> fontEntries = new ArrayList<>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry ze = entries.nextElement();
                String name = ze.getName().toLowerCase(Locale.US);
                if (!ze.isDirectory() && (name.endsWith(".ttf") || name.endsWith(".otf"))
                        && ze.getSize() <= 6L * 1024L * 1024L) {
                    fontEntries.add(ze);
                }
            }
            if (fontEntries.isEmpty()) return result;
            if (!destFontsDir.exists()) destFontsDir.mkdirs();

            // Сортируем по имени для детерминированности; пропускаем моноширинные/иконочные шрифты на роль regular
            Collections.sort(fontEntries, new Comparator<ZipEntry>() {
                @Override
                public int compare(ZipEntry a, ZipEntry b) {
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });

            int saved = 0;
            for (ZipEntry ze : fontEntries) {
                if (saved >= 8) break;
                String lower = ze.getName().toLowerCase(Locale.US);
                String baseName = ze.getName().contains("/")
                        ? ze.getName().substring(ze.getName().lastIndexOf('/') + 1)
                        : ze.getName();
                baseName = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
                File outFile = new File(destFontsDir, baseName);
                if (!outFile.exists() || outFile.length() == 0) {
                    copyZipEntryToFile(zip, ze, outFile);
                }
                if (!outFile.exists() || outFile.length() < 1024) continue;
                saved++;

                String slot = classifyFontSlot(lower);
                if (!result.containsKey(slot)) {
                    result.put(slot, outFile.getAbsolutePath());
                }
            }
            if (!result.containsKey("regular")) {
                // Если все шрифты имели суффиксы, берем первый доступный
                for (ZipEntry ze : fontEntries) {
                    String baseName = ze.getName().contains("/")
                            ? ze.getName().substring(ze.getName().lastIndexOf('/') + 1)
                            : ze.getName();
                    baseName = baseName.replaceAll("[^a-zA-Z0-9._-]", "_");
                    File f = new File(destFontsDir, baseName);
                    if (f.exists() && f.length() >= 1024) {
                        result.put("regular", f.getAbsolutePath());
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed extracting EPUB embedded fonts", t);
        }
        return result;
    }

    private static String classifyFontSlot(String lowerName) {
        boolean isBold = lowerName.contains("bold") || lowerName.contains("-bd") || lowerName.contains("_bd");
        boolean isItalic = lowerName.contains("italic") || lowerName.contains("oblique")
                || lowerName.contains("-it") || lowerName.contains("_it");
        if (isBold && isItalic) return "bold_italic";
        if (isBold) return "bold";
        if (isItalic) return "italic";
        return "regular";
    }

    private static void copyZipEntryToFile(ZipFile zip, ZipEntry ze, File outFile) throws Exception {
        InputStream is = zip.getInputStream(ze);
        FileOutputStream fos = new FileOutputStream(outFile);
        try {
            byte[] buf = new byte[8192];
            int r;
            long total = 0;
            while ((r = is.read(buf)) > 0) {
                total += r;
                if (total > 6L * 1024L * 1024L) break;
                fos.write(buf, 0, r);
            }
        } finally {
            try { fos.close(); } catch (Exception ignored) {}
            try { is.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * Извлекает текст по внутренней EPUB-ссылке (file.xhtml#anchor или file.xhtml)
     * для отображения во всплывающем окне без перехода с текущей страницы.
     */
    public static String[] extractInternalLinkPreview(File epubFile, String internalTarget) {
        if (epubFile == null || !epubFile.exists() || internalTarget == null || internalTarget.trim().isEmpty()) {
            return null;
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(epubFile);
            String target = internalTarget.trim();
            int hashIdx = target.indexOf('#');
            String filePart = (hashIdx >= 0) ? target.substring(0, hashIdx).trim() : target;
            String anchorPart = (hashIdx >= 0) ? target.substring(hashIdx + 1).trim() : "";
            try {
                anchorPart = URLDecoder.decode(anchorPart, "UTF-8");
            } catch (Exception ignored) {}

            String html = readZipEntryText(zip, filePart);
            if ((html == null || html.isEmpty()) && !filePart.isEmpty()) {
                // Пробуем поиск по суффиксу пути в архиве
                Enumeration<? extends ZipEntry> all = zip.entries();
                String shortName = filePart.contains("/") ? filePart.substring(filePart.lastIndexOf('/') + 1) : filePart;
                while (all.hasMoreElements()) {
                    ZipEntry ze = all.nextElement();
                    if (ze.getName().endsWith("/" + shortName) || ze.getName().equalsIgnoreCase(shortName)) {
                        html = readZipEntryText(zip, ze.getName());
                        filePart = ze.getName();
                        break;
                    }
                }
            }
            if (html == null || html.isEmpty()) return null;

            String heading = EpubParser.extractTitle(html, "Ссылка");
            if (!anchorPart.isEmpty()) {
                String anchored = EpubParser.extractElementOrSectionById(html, anchorPart, filePart);
                if (anchored != null && !anchored.isEmpty()) {
                    return new String[]{heading, anchored};
                }
            }

            String clean = stripFormattingTokens(extractRichHtmlText(html, filePart, null));
            clean = clean.replaceAll("\\[IMG:[^\\]]+\\]", "").trim();
            if (clean.length() > 1400) {
                clean = clean.substring(0, 1397).trim() + "…";
            }
            if (clean.isEmpty()) return null;
            return new String[]{heading, clean};
        } catch (Throwable t) {
            Log.w(TAG, "Failed resolving internal EPUB link: " + internalTarget, t);
            return null;
        } finally {
            if (zip != null) {
                try { zip.close(); } catch (Exception ignored) {}
            }
        }
    }
}
