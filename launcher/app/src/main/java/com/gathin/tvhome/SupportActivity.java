package com.gathin.tvhome;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.gathin.tvhome.family.QrCode;
import com.gathin.tvhome.family.SupportLinks;

import java.util.List;

/**
 * "Support the project": one QR code for each donation link (see Support), with a short note that support is voluntary
 * and that the launcher sends nothing itself. The codes are read by a phone; the TV makes no request for them.
 */
public final class SupportActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LauncherLocale.wrap(base));   // locale
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        float d = getResources().getDisplayMetrics().density;
        List<SupportLinks.Link> links = Support.links(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(0xFF101419);
        int side = (int) (72 * d);
        page.setPadding(side, (int) (38 * d), side, (int) (38 * d));
        setContentView(page);
        text(page, getString(R.string.support_title), 34, 0xFFFFFFFF, d);
        text(page, getString(R.string.support_text), 22, 0xFFD5DAE2, d);
        LinearLayout codes = new LinearLayout(this);
        codes.setOrientation(LinearLayout.HORIZONTAL);
        codes.setGravity(Gravity.CENTER_HORIZONTAL);
        page.addView(codes, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        for (SupportLinks.Link link : links) {
            LinearLayout one = new LinearLayout(this);
            one.setOrientation(LinearLayout.VERTICAL);
            one.setGravity(Gravity.CENTER_HORIZONTAL);
            one.setPadding((int) (28 * d), (int) (18 * d), (int) (28 * d), 0);
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap(link.url, (int) (230 * d)));
            one.addView(image);
            TextView caption = text(one, getString(link.kind == SupportLinks.Kind.RUSSIA ? R.string.support_russia : R.string.support_worldwide), 20, 0xFFFFFFFF, d);
            caption.setGravity(Gravity.CENTER_HORIZONTAL);
            codes.addView(one);
        }
        // Any key goes back; there is nothing to move through.
        page.setFocusable(true);
        page.requestFocus();
    }

    private TextView text(LinearLayout parent, String text, int size, int color, float d) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, (int) (12 * d), 0, (int) (12 * d));
        parent.addView(t);
        return t;
    }

    /** The QR code for the text on white with its quiet zone, as large as fits in target pixels. */
    private static Bitmap bitmap(String url, int target) {
        boolean[][] modules = QrCode.encode(url);
        int quiet = 4, n = modules.length + 2 * quiet;
        int px = Math.max(1, target / n);
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
