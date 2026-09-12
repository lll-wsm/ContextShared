package com.lll.contextshared.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import com.google.zxing.qrcode.encoder.QRCode;

import java.util.HashMap;
import java.util.Map;

/**
 * 二维码生成。
 *
 * <p>旧实现用 {@code QRCodeWriter.encode(..., 450, 450)}：ZXing 会把矩阵<b>放大到 450×450 像素</b>，
 * 于是绘制循环要跑 450×450 = 202,500 次、并对约 10 万个像素逐个调用带抗锯齿的
 * {@code canvas.drawRect(1×1)}。这段代码跑在主线程（{@code onServerStarted} → {@code runOnUiThread}），
 * 每次绑定服务都会重算，直接造成界面卡顿。
 *
 * <p>现在改为：取未放大的模块矩阵（每个元素 = 1 个模块），一次性写进像素数组建小位图，
 * 再用一次 {@code drawBitmap} 放大到目标尺寸（关闭双线性过滤以保持锐利），绘制调用从十万级降到个位数。
 */
public class QrCodeGenerator {

    private static final int QUIET_ZONE_MODULES = 2;
    private static final int DARK_COLOR = Color.parseColor("#0F172A");

    public static Bitmap generateQrCodeBitmap(String content, int width, int height) {
        return generateBrandedQrCodeBitmap(content, width, height, null);
    }

    public static Bitmap generateBrandedQrCodeBitmap(String content, int width, int height, Bitmap logo) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

            QRCode code = Encoder.encode(content, ErrorCorrectionLevel.H, hints);
            ByteMatrix matrix = code.getMatrix();
            if (matrix == null) {
                return null;
            }

            int modules = matrix.getWidth();
            int totalModules = modules + QUIET_ZONE_MODULES * 2;
            int target = Math.max(width, height);
            int multiple = Math.max(1, target / totalModules);
            int outputSize = totalModules * multiple;
            int offset = QUIET_ZONE_MODULES * multiple;

            // 1. 按模块写像素（每个模块写 multiple×multiple 个像素，一次内存操作）
            int[] pixels = new int[outputSize * outputSize];
            java.util.Arrays.fill(pixels, Color.WHITE);
            for (int y = 0; y < matrix.getHeight(); y++) {
                int rowStart = (offset + y * multiple) * outputSize + offset;
                for (int x = 0; x < modules; x++) {
                    if (matrix.get(x, y) != 1) {
                        continue;
                    }
                    int cellStart = rowStart + x * multiple;
                    for (int dy = 0; dy < multiple; dy++) {
                        int base = cellStart + dy * outputSize;
                        for (int dx = 0; dx < multiple; dx++) {
                            pixels[base + dx] = DARK_COLOR;
                        }
                    }
                }
            }

            Bitmap qrBitmap = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888);
            qrBitmap.setPixels(pixels, 0, outputSize, 0, 0, outputSize, outputSize);

            // 2. 中心品牌徽章
            if (logo != null) {
                Canvas canvas = new Canvas(qrBitmap);
                int logoSize = (int) (outputSize * 0.22);
                int logoLeft = (outputSize - logoSize) / 2;
                int logoTop = (outputSize - logoSize) / 2;

                Paint badgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                badgeBgPaint.setColor(Color.WHITE);
                RectF badgeRect = new RectF(logoLeft - 4, logoTop - 4, logoLeft + logoSize + 4, logoTop + logoSize + 4);
                canvas.drawRoundRect(badgeRect, 10, 10, badgeBgPaint);

                Bitmap roundedLogo = getRoundedCornerBitmap(logo, 16);
                Rect srcRect = new Rect(0, 0, roundedLogo.getWidth(), roundedLogo.getHeight());
                Rect destRect = new Rect(logoLeft, logoTop, logoLeft + logoSize, logoTop + logoSize);
                Paint logoPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
                canvas.drawBitmap(roundedLogo, srcRect, destRect, logoPaint);
            }

            return qrBitmap;
        } catch (Exception e) {
            return null;
        }
    }

    private static Bitmap getRoundedCornerBitmap(Bitmap bitmap, float cornerRadius) {
        Bitmap output = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);

        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Rect rect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        final RectF rectF = new RectF(rect);

        canvas.drawARGB(0, 0, 0, 0);
        paint.setColor(Color.BLACK);
        canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, paint);

        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        canvas.drawBitmap(bitmap, rect, rect, paint);

        return output;
    }
}
