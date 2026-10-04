package com.onyx.yandexbooks.core.auth;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Локальный легковесный HTTP-сервер для моментальной передачи токена со смартфона на читалку.
 * Позволяет войти без ввода паролей и длинных кодов на клавиатуре Onyx Boox.
 */
public class LocalAuthServer {

    private static final String TAG = "LocalAuthServer";
    public static final int DEFAULT_PORT = 8888;
    private static final String BOOKMATE_CLIENT_ID = "4483e97bab6e486a9822973109a14d05";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("y0_[A-Za-z0-9_-]{15,}");

    public interface ServerCallback {
        void onTokenReceived(String token);
    }

    private ServerSocket serverSocket;
    private Thread workerThread;
    private volatile boolean isRunning = false;
    private final int port;
    private final ServerCallback callback;
    private final Handler mainHandler;

    public LocalAuthServer(ServerCallback callback) {
        this(DEFAULT_PORT, callback);
    }

    public LocalAuthServer(int port, ServerCallback callback) {
        this.port = port;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public synchronized boolean start() {
        if (isRunning) return true;
        try {
            serverSocket = new ServerSocket(port);
            isRunning = true;
            workerThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    listenLoop();
                }
            }, "LocalAuthServerThread");
            workerThread.setDaemon(true);
            workerThread.start();
            Log.i(TAG, "Local auth server started on port " + port);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to start local auth server", e);
            return false;
        }
    }

    public synchronized void stop() {
        isRunning = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {}
            serverSocket = null;
        }
        if (workerThread != null) {
            workerThread.interrupt();
            workerThread = null;
        }
    }

    private void listenLoop() {
        while (isRunning && serverSocket != null && !serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                handleClient(clientSocket);
            } catch (Exception e) {
                if (!isRunning) break;
            }
        }
    }

    private void handleClient(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            OutputStream out = socket.getOutputStream();

            String requestLine = reader.readLine();
            if (requestLine == null) {
                socket.close();
                return;
            }

            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            if (requestLine.startsWith("OPTIONS ")) {
                sendCorsPreflight(out);
            } else if (requestLine.startsWith("GET /save")) {
                int qIdx = requestLine.indexOf('?');
                String query = (qIdx != -1) ? requestLine.substring(qIdx + 1) : "";
                int spaceIdx = query.indexOf(' ');
                if (spaceIdx != -1) query = query.substring(0, spaceIdx);
                
                String extractedToken = null;
                if (!query.isEmpty()) {
                    String decoded = URLDecoder.decode(query, "UTF-8");
                    Matcher m = TOKEN_PATTERN.matcher(decoded);
                    if (m.find()) {
                        extractedToken = m.group();
                    }
                }
                handleExtractedToken(out, extractedToken);
            } else if (requestLine.startsWith("POST /save")) {
                char[] bodyChars = new char[contentLength];
                int read = reader.read(bodyChars, 0, contentLength);
                String body = new String(bodyChars, 0, Math.max(0, read));
                
                String extractedToken = null;
                if (!body.isEmpty()) {
                    String decoded = URLDecoder.decode(body, "UTF-8");
                    Matcher m = TOKEN_PATTERN.matcher(decoded);
                    if (m.find()) {
                        extractedToken = m.group();
                    }
                }
                handleExtractedToken(out, extractedToken);
            } else if (requestLine.startsWith("GET /")) {
                sendHtmlResponse(out, buildAuthHtmlPage());
            } else {
                send404Response(out);
            }

            out.flush();
            socket.close();
        } catch (Exception e) {
            Log.e(TAG, "Error handling client request", e);
        }
    }

    private void handleExtractedToken(OutputStream out, String extractedToken) throws Exception {
        if (extractedToken != null) {
            final String token = extractedToken;
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (callback != null) {
                        callback.onTokenReceived(token);
                    }
                }
            });
            sendHtmlResponse(out, buildSuccessHtmlPage());
        } else {
            sendHtmlResponse(out, buildErrorHtmlPage());
        }
    }

    private void sendCorsPreflight(OutputStream out) throws Exception {
        String header = "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type, Authorization\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
    }

    private void sendHtmlResponse(OutputStream out, String html) throws Exception {
        byte[] bytes = html.getBytes("UTF-8");
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=UTF-8\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes("UTF-8"));
        out.write(bytes);
    }

    private void send404Response(OutputStream out) throws Exception {
        String msg = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(msg.getBytes("UTF-8"));
    }

    private String buildAuthHtmlPage() {
        String yandexAuthUrl = "https://oauth.yandex.ru/authorize?response_type=token&client_id=" + BOOKMATE_CLIENT_ID;
        return "<!DOCTYPE html>\n" +
                "<html lang=\"ru\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "  <title>Вход в Яндекс Книги — Onyx Boox</title>\n" +
                "  <style>\n" +
                "    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; background: #f4f6f8; color: #222; margin: 0; padding: 16px; }\n" +
                "    .card { background: #fff; max-width: 520px; margin: 0 auto; padding: 24px; border-radius: 14px; box-shadow: 0 4px 16px rgba(0,0,0,0.08); }\n" +
                "    h2 { margin-top: 0; font-size: 22px; color: #111; text-align: center; }\n" +
                "    .badge { display: inline-block; background: #e8f5e9; color: #2e7d32; font-weight: bold; font-size: 13px; padding: 4px 10px; border-radius: 12px; margin-bottom: 12px; }\n" +
                "    p { font-size: 15px; line-height: 1.5; color: #444; }\n" +
                "    .step-box { background: #fdfdfd; border: 1px solid #e2e8f0; border-radius: 10px; padding: 16px; margin: 16px 0; }\n" +
                "    .step-num { font-weight: bold; color: #000; font-size: 16px; margin-bottom: 8px; }\n" +
                "    .btn-yandex { display: block; background: #fc0; color: #000; font-weight: bold; text-decoration: none; padding: 14px; border-radius: 8px; font-size: 16px; text-align: center; box-shadow: 0 2px 4px rgba(0,0,0,0.1); margin-top: 10px; }\n" +
                "    .btn-yandex:hover { background: #f5b800; }\n" +
                "    .input-field { width: 100%; box-sizing: border-box; padding: 12px; font-size: 15px; border: 1px solid #cbd5e1; border-radius: 6px; margin: 10px 0; }\n" +
                "    .btn-submit { width: 100%; background: #000; color: #fff; border: none; font-weight: bold; padding: 14px; border-radius: 8px; font-size: 16px; cursor: pointer; }\n" +
                "    .btn-submit:hover { background: #222; }\n" +
                "    .note { font-size: 13px; color: #64748b; margin-top: 8px; line-height: 1.4; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class=\"card\">\n" +
                "    <div style=\"text-align: center;\"><span class=\"badge\">Связь с читалкой Onyx Boox установлена</span></div>\n" +
                "    <h2>Авторизация в Яндекс Книгах</h2>\n" +
                "    <p>Для входа с аккаунтом (включая <b>Яндекс.Ключ / 2FA</b>) пароль вводить не нужно — всё подтверждается в 1 клик:</p>\n" +
                "    <div class=\"step-box\">\n" +
                "      <div class=\"step-num\">Шаг 1. Получите доступ в Яндексе</div>\n" +
                "      <div class=\"note\">Нажмите кнопку ниже. Яндекс откроет страницу подтверждения для вашего аккаунта:</div>\n" +
                "      <a class=\"btn-yandex\" href=\"" + yandexAuthUrl + "\" target=\"_blank\">1. Открыть подтверждение в Яндекс ID</a>\n" +
                "    </div>\n" +
                "    <div class=\"step-box\">\n" +
                "      <div class=\"step-num\">Шаг 2. Передайте ключ на читалку</div>\n" +
                "      <div class=\"note\">После нажатия «Войти» или «Разрешить» скопируйте адрес страницы из браузера (или токен <code>y0_...</code>) и вставьте сюда:</div>\n" +
                "      <form method=\"POST\" action=\"/save\">\n" +
                "        <input class=\"input-field\" type=\"text\" name=\"token_input\" id=\"token_input\" placeholder=\"Вставьте скопированный адрес или токен...\" required autofocus>\n" +
                "        <button class=\"btn-submit\" type=\"submit\">2. Отправить на Onyx Boox</button>\n" +
                "      </form>\n" +
                "    </div>\n" +
                "  </div>\n" +
                "</body>\n" +
                "</html>";
    }

    private String buildSuccessHtmlPage() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"ru\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "  <title>Успешно — Onyx Boox</title>\n" +
                "  <style>\n" +
                "    body { font-family: -apple-system, sans-serif; background: #f4f6f8; color: #222; text-align: center; padding: 40px 20px; }\n" +
                "    .card { background: #fff; max-width: 460px; margin: 0 auto; padding: 30px; border-radius: 14px; box-shadow: 0 4px 16px rgba(0,0,0,0.08); }\n" +
                "    h2 { color: #16a34a; margin-bottom: 12px; }\n" +
                "    p { font-size: 15px; color: #475569; line-height: 1.5; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class=\"card\">\n" +
                "    <h2>Вход успешно выполнен!</h2>\n" +
                "    <p>Токен Яндекс Книг передан на читалку Onyx Boox.</p>\n" +
                "    <p>Читалка уже открывает вашу библиотеку и книжные полки.</p>\n" +
                "    <p style=\"color: #94a3b8; font-size: 13px;\">Эту страницу на смартфоне можно закрыть.</p>\n" +
                "  </div>\n" +
                "</body>\n" +
                "</html>";
    }

    private String buildErrorHtmlPage() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"ru\">\n" +
                "<head>\n" +
                "  <meta charset=\"UTF-8\">\n" +
                "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "  <title>Ошибка — Onyx Boox</title>\n" +
                "  <style>\n" +
                "    body { font-family: -apple-system, sans-serif; background: #f4f6f8; color: #222; text-align: center; padding: 40px 20px; }\n" +
                "    .card { background: #fff; max-width: 460px; margin: 0 auto; padding: 30px; border-radius: 14px; box-shadow: 0 4px 16px rgba(0,0,0,0.08); }\n" +
                "    h2 { color: #dc2626; margin-bottom: 12px; }\n" +
                "    p { font-size: 15px; color: #475569; line-height: 1.5; }\n" +
                "    a { color: #2563eb; text-decoration: none; font-weight: bold; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class=\"card\">\n" +
                "    <h2>Токен не распознан</h2>\n" +
                "    <p>В отправленном тексте не обнаружен OAuth-токен Яндекса (он должен начинаться с <code>y0_...</code>).</p>\n" +
                "    <p>Убедитесь, что скопировали адрес страницы целиком после подтверждения входа.</p>\n" +
                "    <p><a href=\"/\">Попробовать еще раз</a></p>\n" +
                "  </div>\n" +
                "</body>\n" +
                "</html>";
    }

    /**
     * Получение локального IPv4-адреса устройства в Wi-Fi сети.
     */
    public static String getLocalIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
