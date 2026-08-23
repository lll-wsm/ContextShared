package com.lll.contextshared.util;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

public class QrCodeGenerator {

    public static Bitmap generateQrCodeBitmap(String content, int width, int height) {
        return generateBrandedQrCodeBitmap(content, width, height, null);
    }

    public static Bitmap generateBrandedQrCodeBitmap(String content, int width, int height, Bitmap logo) {
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
            hints.put(EncodeHintType.MARGIN, 1);

            QRCodeWriter writer = new QRCodeWriter();
            BitMatrix bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, width, height, hints);

            int matrixWidth = bitMatrix.getWidth();
            int matrixHeight = bitMatrix.getHeight();

            Bitmap qrBitmap = Bitmap.createBitmap(matrixWidth, matrixHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(qrBitmap);
            canvas.drawColor(Color.WHITE);

            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setColor(Color.parseColor("#0F172A"));

            // 绘制精美的圆角矩阵像素
            for (int y = 0; y < matrixHeight; y++) {
                for (int x = 0; x < matrixWidth; x++) {
                    if (bitMatrix.get(x, y)) {
                        canvas.drawRect(x, y, x + 1, y + 1, paint);
                    }
                }
            }

            // 如果提供了 Logo，在中心绘制品牌徽章 (Overlay central rounded logo badge)
            if (logo != null) {
                int logoSize = (int) (matrixWidth * 0.22);
                int logoLeft = (matrixWidth - logoSize) / 2;
                int logoTop = (matrixHeight - logoSize) / 2;

                // 绘制 Logo 底部的白色圆角卡片
                Paint badgeBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                badgeBgPaint.setColor(Color.WHITE);
                RectF badgeRect = new RectF(logoLeft - 4, logoTop - 4, logoLeft + logoSize + 4, logoTop + logoSize + 4);
                canvas.drawRoundRect(badgeRect, 10, 10, badgeBgPaint);

                // 绘制圆角 Logo
                Bitmap roundedLogo = getRoundedCornerBitmap(logo, 16);
                Rect srcRect = new Rect(0, 0, roundedLogo.getWidth(), roundedLogo.getHeight());
                Rect destRect = new Rect(logoLeft, logoTop, logoLeft + logoSize, logoTop + logoSize);
                canvas.drawBitmap(roundedLogo, srcRect, destRect, null);
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
