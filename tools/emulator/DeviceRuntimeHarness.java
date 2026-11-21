import android.os.Looper;

import com.onyx.yandexbooks.core.api.YandexBooksApiClient;
import com.onyx.yandexbooks.core.auth.LocalAuthServer;
import com.onyx.yandexbooks.core.epub.EpubFormatting;
import com.onyx.yandexbooks.core.epub.EpubParser;
import com.onyx.yandexbooks.core.typography.TeXHyphenator;
import com.onyx.yandexbooks.core.update.AppUpdateManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Runtime integration harness executing directly on real Android ART/Dalvik
 * inside the emulator against production compiled DEX classes.
 */
public class DeviceRuntimeHarness {

    private static void check(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError("FAIL: " + msg);
        }
    }

    private static void testLocalAuthServer() throws Exception {
        final AtomicReference<String> received = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        int port = 18889;
        LocalAuthServer server = new LocalAuthServer(port, new LocalAuthServer.ServerCallback() {
            @Override
            public void onTokenReceived(String token) {
                received.set(token);
                latch.countDown();
            }
        });
        server.start();
        try {
            Thread.sleep(250);
            String expectedToken = "y0_AgAAAABtestToken_206_UTF8";
            String body = "note=" + java.net.URLEncoder.encode("проверка_UTF8", "UTF-8")
                    + "&token=" + expectedToken;
            byte[] bodyBytes = body.getBytes("UTF-8");
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                s.setSoTimeout(3000);
                OutputStream os = s.getOutputStream();
                String req = "POST /save HTTP/1.1\r\n"
                        + "Host: 127.0.0.1:" + port + "\r\n"
                        + "Content-Type: application/x-www-form-urlencoded\r\n"
                        + "Content-Length: " + bodyBytes.length + "\r\n\r\n";
                os.write(req.getBytes("US-ASCII"));
                os.write(bodyBytes);
                os.flush();
                byte[] respBuf = new byte[256];
                int n = s.getInputStream().read(respBuf);
                String respHead = n > 0 ? new String(respBuf, 0, n, "UTF-8") : "";
                check(respHead.startsWith("HTTP/1.1 200 OK"), "Expected 200 OK from POST /save, got: " + respHead);
            } finally {
                try { s.close(); } catch (Throwable ignored) {}
            }
            check(latch.await(4, TimeUnit.SECONDS), "LocalAuthServer did not invoke callback on UTF-8 POST /save");
            check(expectedToken.equals(received.get()), "Expected token " + expectedToken + ", got: " + received.get());

            // Slow/truncated client must not hang server
            Socket bad = new Socket();
            try {
                bad.connect(new InetSocketAddress("127.0.0.1", port), 2000);
                bad.getOutputStream().write("GET /bad HTTP/1.1\r\n".getBytes("US-ASCII"));
                bad.getOutputStream().flush();
            } finally {
                try { bad.close(); } catch (Throwable ignored) {}
            }
            System.out.println("[harness] LocalAuthServer loopback /save UTF-8 & socket cleanup: OK");
        } finally {
            server.stop();
        }
    }

    private static void testAppUpdateSemVerAndJson() throws Exception {
        check(AppUpdateManager.isVersionNewer("2.0.6", "2.0.5"), "2.0.6 > 2.0.5");
        check(AppUpdateManager.isVersionNewer("2.0.6", "2.0.6-rc.1"), "stable > rc");
        check(AppUpdateManager.isVersionNewer("2.0.6-beta.10", "2.0.6-beta.2"), "beta.10 > beta.2 numeric SemVer");
        check(AppUpdateManager.isVersionNewer("2.0.6-rc.1", "2.0.6-beta.99"), "rc.1 > beta.99");
        check(!AppUpdateManager.isVersionNewer("2.0.6-beta", "2.0.6"), "beta not newer than stable");

        JSONObject draft = new JSONObject();
        draft.put("tag_name", "v9.9.9");
        draft.put("draft", true);
        check(AppUpdateManager.parseReleaseFromJson(draft) == null, "draft release must return null");

        JSONObject rel = new JSONObject();
        rel.put("tag_name", "v2.0.6");
        rel.put("name", "v2.0.6 Release");
        rel.put("body", "• Фикс 1\n**Важно**");
        JSONArray assets = new JSONArray();
        JSONObject asset = new JSONObject();
        asset.put("name", "yandex-books-lite-v2.0.6.apk");
        asset.put("browser_download_url", "https://example.invalid/app.apk");
        asset.put("size", 5401154);
        assets.put(asset);
        rel.put("assets", assets);
        AppUpdateManager.ReleaseInfo parsed = AppUpdateManager.parseReleaseFromJson(rel);
        check(parsed != null && "2.0.6".equals(parsed.versionName) && parsed.apkSize == 5401154, "parseReleaseFromJson failed");
        System.out.println("[harness] AppUpdateManager SemVer 2.0.0 & JSON parsing: OK");
    }

    private static File buildTrickyEpub() throws Exception {
        File f = new File("/data/local/tmp/harness_tricky.epub");
        if (f.exists()) f.delete();

        String ch1 = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n"
                + "<head><title>Ch1</title><style>\n"
                + "@media screen and (min-width: 1px) { .nested { font-style: italic; } }\n"
                + ".emph { font-style: italic; }\n"
                + "</style></head>\n"
                + "<body>\n"
                + "  <div id=\"wrapper\">\n"
                + "    <p>Первый абзац с сущностями &amp;lt;ok&amp;gt; и &lt;br/&gt; и смайлом &#128512;.</p>\n"
                + "    <p>Второй целевой абзац внутри обёртки wrapper для DOM CFI.</p>\n"
                + "  </div>\n"
                + "  <ul><li><p>Маркированный элемент списка</p></li></ul>\n"
                + "  <div id=\"local_sec\"><p>Содержимое локального якоря найдено успешно.</p></div>\n"
                + "</body></html>";

        String opf = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"2.0\" unique-identifier=\"uid\">\n"
                + "  <metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n"
                + "    <dc:title>Harness Book</dc:title><dc:creator>Tester</dc:creator>\n"
                + "  </metadata>\n"
                + "  <manifest>\n"
                + "    <item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>\n"
                + "    <item href=\"ch1.xhtml\" id=\"item_ch1\" media-type=\"application/xhtml+xml\"/>\n"
                + "  </manifest>\n"
                + "  <spine toc=\"ncx\"><itemref idref=\"item_ch1\"/></spine>\n"
                + "</package>";

        String ncx = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\">\n"
                + "  <navMap><navPoint id=\"n1\" playOrder=\"1\"><navLabel><text>Глава 1</text></navLabel><content src=\"ch1.xhtml\"/></navPoint></navMap>\n"
                + "</ncx>";

        String container = "<?xml version=\"1.0\"?><container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>";

        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(f));
        try {
            Charset utf8 = Charset.forName("UTF-8");
            zos.putNextEntry(new ZipEntry("mimetype"));
            zos.write("application/epub+zip".getBytes("US-ASCII"));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("META-INF/container.xml"));
            zos.write(container.getBytes(utf8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("OEBPS/content.opf"));
            zos.write(opf.getBytes(utf8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("OEBPS/toc.ncx"));
            zos.write(ncx.getBytes(utf8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("OEBPS/ch1.xhtml"));
            zos.write(ch1.getBytes(utf8));
            zos.closeEntry();
        } finally {
            zos.close();
        }
        return f;
    }

    private static void testEpubCssCfiHyphenation() throws Exception {
        File epub = buildTrickyEpub();
        try {
            EpubParser.ParseResult res = EpubParser.parseEpubFull(epub);
            check(res != null && res.chapters != null && res.chapters.size() == 1, "parseEpubFull chapter count != 1");
            String text = res.chapters.get(0).textContent;
            check(text.contains("&lt;ok&gt;"), "Double-escaped &amp;lt; decoded too early: " + text);
            check(text.contains("\uD83D\uDE00"), "Surrogate Unicode codepoint &#128512; missing in: " + text);
            check(text.contains("Маркированный элемент списка") && !text.contains("•\n"), "<li><p> split bullet from text!");

            String[] preview = EpubFormatting.extractInternalLinkPreview(epub, "#local_sec");
            check(preview != null && preview.length > 1 && preview[1].contains("Содержимое локального якоря"),
                    "#local_sec preview failed");

            EpubParser.CfiDomLocation loc = EpubParser.resolveCfiInEpub(
                    epub, "epubcfi(/6/2[item_ch1]!/4/2[wrapper]/4/1:5)");
            check(loc != null && loc.spineIndex == 0 && "item_ch1".equals(loc.opfItemId)
                            && loc.blockSnippet.contains("Второй целевой абзац") && loc.charOffsetInInline == 5,
                    "resolveCfiInEpub failed with intermediate [id] wrapper");

            List<Integer> pts = TeXHyphenator.getInstance().getHyphenationPoints("синхронизация");
            check(pts != null && !pts.isEmpty(), "TeXHyphenator returned empty points");
            for (int p : pts) {
                check(p >= 2 && ("синхронизация".length() - p) >= 2, "Hyphen point violates min 2-char rule: " + p);
            }
            System.out.println("[harness] EpubParser (attr-order OPF, [id] DOM CFI), EpubFormatting (@media, li>p, #local, entities) & TeXHyphenator: OK");
        } finally {
            if (epub.exists()) epub.delete();
        }
    }

    private static void testApiCfiHelpers() {
        YandexBooksApiClient.CfiPosition cp = YandexBooksApiClient.parseCfiPosition("epubcfi(/6/4[item_2]!/4/2/6/1:14)");
        check(cp.chapterIndex == 1 && cp.charOffset == 14 && "item_2".equals(cp.opfItemId),
                "parseCfiPosition mismatch: ch=" + cp.chapterIndex + " off=" + cp.charOffset + " id=" + cp.opfItemId);

        String cfi = YandexBooksApiClient.buildCanonicalCfiWithDomPath(1, "item_2", "/4/2/6", 14);
        check("epubcfi(/6/4[item_2]!/4/2/6/1:14)".equals(cfi), "Canonical CFI mismatch: " + cfi);

        String endCfi = YandexBooksApiClient.buildCanonicalCfiEndFromStart(cfi, 1, "item_2", 3, 20);
        check("epubcfi(/6/4[item_2]!/4/2/6/1:114)".equals(endCfi), "Canonical endCfi mismatch: " + endCfi);
        System.out.println("[harness] YandexBooksApiClient CFI parse/canonical/end helpers: OK");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== DeviceRuntimeHarness on Real Android Runtime ===");
        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    testLocalAuthServer();
                    testAppUpdateSemVerAndJson();
                    testEpubCssCfiHyphenation();
                    testApiCfiHelpers();
                    System.out.println("=== ALL DEVICE RUNTIME HARNESS TESTS PASSED ===");
                } catch (Throwable t) {
                    failure.set(t);
                    t.printStackTrace(System.err);
                } finally {
                    System.out.flush();
                    System.err.flush();
                    System.exit(failure.get() == null ? 0 : 1);
                }
            }
        });
        worker.start();
        Looper.loop();
        worker.join(5000);
        if (failure.get() != null) {
            throw new RuntimeException("DeviceRuntimeHarness failed", failure.get());
        }
    }
}
