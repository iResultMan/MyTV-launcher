package com.gathin.tvhome;

import android.graphics.Bitmap;

import com.gathin.tvhome.family.QrCode;

/** A QR code as a picture: black modules on white with the four-module quiet zone, as large as fits in the target size. */
final class QrBitmap {
    private QrBitmap() {
    }

    static Bitmap of(String text, int targetPx) {
        boolean[][] modules = QrCode.encode(text);
        int quiet = 4, n = modules.length + 2 * quiet;
        int px = Math.max(1, targetPx / n);
        Bitmap bitmap = Bitmap.createBitmap(n * px, n * px, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFFFFFFFF);
        for (int y = 0; y < modules.length; y++) {
            for (int x = 0; x < modules.length; x++) {
                if (!modules[y][x]) continue;
                for (int dy = 0; dy < px; dy++) for (int dx = 0; dx < px; dx++) bitmap.setPixel((x + quiet) * px + dx, (y + quiet) * px + dy, 0xFF000000);
            }
        }
        return bitmap;
    }
}
