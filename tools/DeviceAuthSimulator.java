import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.EncodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Виртуализатор экрана авторизации Onyx Boox Darwin (758x1024 E-Ink).
 * Выполняет реальный запрос к серверам Яндекса, генерирует настоящий QR-код
 * через библиотеку ZXing (ту же самую, что внутри APK) и проверяет его читаемость сканером.
 */
public class DeviceAuthSimulator {

    private static final String CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d";
    private static final String DEVICE_CODE_URL = "https://oauth.yandex.ru/device/code";

    public static void main(String[] args) throws Exception {
        System.out.println("=================================================");
        System.out.println("📱 СТАРТ ВИРТУАЛИЗАЦИИ И СРЕЗА ЭКРАНА ONYX BOOX");
        System.out.println("=================================================");

        // 1. Реальный вызов Яндекс OAuth API через OkHttp
        System.out.println("\n[1/4] Отправка запроса к " + DEVICE_CODE_URL + "...");
        OkHttpClient client = new OkHttpClient();
        RequestBody formBody = new FormBody.Builder()
                .add("client_id", CLIENT_ID)
                .build();

        Request request = new Request.Builder()
                .url(DEVICE_CODE_URL)
                .post(formBody)
                .header("User-Agent", "YandexBooksLite/1.0 (Onyx Boox Darwin)")
                .build();

        Response response = client.newCall(request).execute();
        if (!response.isSuccessful()) {
            throw new RuntimeException("HTTP Error: " + response.code());
        }

        String body = response.body().string();
        System.out.println("Ответ Яндекса: " + body);

        JSONObject json = new JSONObject(body);
        String deviceCode = json.getString("device_code");
        String userCode = json.getString("user_code");
        String verificationUrl = json.optString("verification_url", "https://ya.ru/device");
        String fullUrl = verificationUrl + "?user_code=" + userCode;

        System.out.println("✅ Получен user_code: " + userCode.toUpperCase());
        System.out.println("✅ Целевой URL для QR-кода: " + fullUrl);

        // 2. Генерация настоящего QR-кода через ZXing
        System.out.println("\n[2/4] Генерация BitMatrix через com.google.zxing.qrcode.QRCodeWriter...");
        int qrPixelSize = 300;
        QRCodeWriter writer = new QRCodeWriter();
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix bitMatrix = writer.encode(fullUrl, BarcodeFormat.QR_CODE, qrPixelSize, qrPixelSize, hints);

        BufferedImage qrImage = new BufferedImage(qrPixelSize, qrPixelSize, BufferedImage.TYPE_BYTE_BINARY);
        for (int x = 0; x < qrPixelSize; x++) {
            for (int y = 0; y < qrPixelSize; y++) {
                qrImage.setRGB(x, y, bitMatrix.get(x, y) ? 0x000000 : 0xFFFFFF);
            }
        }
        System.out.println("✅ QR-код успешно сформирован в памяти (" + qrPixelSize + "x" + qrPixelSize + " px)");

        // 3. Сканирование сформированного QR-кода обратно сканером для 100% подтверждения читаемости
        System.out.println("\n[3/4] Проверка считываемости QR-кода сканером (ZXing MultiFormatReader)...");
        LuminanceSource source = new LuminanceSource(qrPixelSize, qrPixelSize) {
            private final byte[] luminances = new byte[qrPixelSize * qrPixelSize];
            {
                for (int y = 0; y < qrPixelSize; y++) {
                    for (int x = 0; x < qrPixelSize; x++) {
                        int rgb = qrImage.getRGB(x, y);
                        int r = (rgb >> 16) & 0xFF;
                        int g = (rgb >> 8) & 0xFF;
                        int b = rgb & 0xFF;
                        luminances[y * qrPixelSize + x] = (byte) ((r + g + b) / 3);
                    }
                }
            }
            @Override
            public byte[] getRow(int y, byte[] row) {
                if (row == null || row.length < qrPixelSize) row = new byte[qrPixelSize];
                System.arraycopy(luminances, y * qrPixelSize, row, 0, qrPixelSize);
                return row;
            }
            @Override
            public byte[] getMatrix() { return luminances; }
        };

        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
        Result decodedResult = new MultiFormatReader().decode(bitmap);
        System.out.println("Результат оптического сканирования: " + decodedResult.getText());
        if (!fullUrl.equals(decodedResult.getText())) {
            throw new IllegalStateException("Ошибка: считанный QR-код не совпадает с исходным URL!");
        }
        System.out.println("✅ QR-код 100% валиден и успешно декодирован камерой/сканером!");

        // 4. Отрисовка 1:1 экрана электронной книги Onyx Boox Darwin (758x1024)
        System.out.println("\n[4/4] Рендеринг полного экрана Onyx Boox Darwin (758x1024 E-Ink)...");
        int width = 758;
        int height = 1024;
        BufferedImage screen = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = screen.createGraphics();

        // Высококачественное сглаживание текста для E-Ink
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Белый фон дисплея E-Ink Carta
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);

        // Системный статус-бар Onyx Boox
        g.setColor(new Color(245, 245, 245));
        g.fillRect(0, 0, width, 44);
        g.setColor(Color.BLACK);
        g.drawRect(0, 43, width, 1);

        Font statusFont = new Font("SansSerif", Font.PLAIN, 18);
        g.setFont(statusFont);
        g.drawString("Главная  |  Батарея: 92%  |  Wi-Fi: Подключено", 20, 28);
        g.drawString("20:58", width - 80, 28);

        // Шрифты для контента экрана
        Font titleFont = new Font("SansSerif", Font.BOLD, 34);
        Font promptFont = new Font("SansSerif", Font.PLAIN, 22);
        Font codeFont = new Font("Monospaced", Font.BOLD, 46);
        Font smallFont = new Font("SansSerif", Font.PLAIN, 18);

        int currentY = 100;

        // Заголовок: Вход в Яндекс
        g.setFont(titleFont);
        String title = "Вход в Яндекс";
        FontMetrics fmTitle = g.getFontMetrics();
        g.drawString(title, (width - fmTitle.stringWidth(title)) / 2, currentY);
        currentY += 60;

        // Инструкция 1: Перейдите на страницу ya.ru/device и введите код
        g.setFont(promptFont);
        FontMetrics fmPrompt = g.getFontMetrics();
        String prompt1 = "1. Перейдите на ya.ru/device и введите код:";
        g.drawString(prompt1, (width - fmPrompt.stringWidth(prompt1)) / 2, currentY);
        currentY += 40;

        // Контрастная рамка с кодом подтверждения
        g.setFont(codeFont);
        FontMetrics fmCode = g.getFontMetrics();
        String displayCode = " " + userCode.toUpperCase() + " ";
        int codeW = fmCode.stringWidth(displayCode);
        int codeH = fmCode.getHeight();

        int boxPadX = 30;
        int boxPadY = 16;
        int boxX = (width - codeW) / 2 - boxPadX;
        int boxY = currentY;

        // Заливка плашки кода (светло-серый фон и черная граница)
        g.setColor(new Color(238, 238, 238));
        g.fillRect(boxX, boxY, codeW + boxPadX * 2, codeH + boxPadY * 2);
        g.setColor(Color.BLACK);
        g.drawRect(boxX, boxY, codeW + boxPadX * 2, codeH + boxPadY * 2);

        g.drawString(displayCode, (width - codeW) / 2, boxY + codeH + boxPadY - 10);
        currentY += codeH + boxPadY * 2 + 45;

        // Инструкция 2: Или отсканируйте QR-код смартфоном
        g.setFont(promptFont);
        String prompt2 = "2. Или отсканируйте QR-код смартфоном:";
        g.drawString(prompt2, (width - fmPrompt.stringWidth(prompt2)) / 2, currentY);
        currentY += 35;

        // Отрисовка сгенерированного QR-кода по центру
        int qrDrawX = (width - qrPixelSize) / 2;
        g.drawImage(qrImage, qrDrawX, currentY, null);

        // Черная контрастная рамка вокруг QR-кода
        g.drawRect(qrDrawX - 4, currentY - 4, qrPixelSize + 8, qrPixelSize + 8);
        currentY += qrPixelSize + 45;

        // Статус ожидания
        g.setFont(smallFont);
        FontMetrics fmSmall = g.getFontMetrics();
        String status = "Ожидание подтверждения входа на смартфоне...";
        g.drawString(status, (width - fmSmall.stringWidth(status)) / 2, currentY);

        g.dispose();

        // Сохранение виртуального скриншота экрана в файл
        File outFile = new File("docs/live_screen_onyx_darwin.png");
        ImageIO.write(screen, "png", outFile);

        System.out.println("=================================================");
        System.out.println("🎉 ВИРТУАЛИЗАЦИЯ ЗАВЕРШЕНА УСПЕШНО!");
        System.out.println("Скриншот экрана книги сохранен: " + outFile.getAbsolutePath());
        System.out.println("Код для ввода на ya.ru/device: " + userCode.toUpperCase());
        System.out.println("=================================================");
    }
}
