package com.gathin.tvhome.family;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The donation links the About screen offers as QR codes. There are up to two, a worldwide one and one for donors in
 * Russia (cards from Russia do not reach the worldwide service), and a link that is empty or unusable is left out:
 * with neither, About has no support row at all. A link goes into the QR exactly as it was given, apart from
 * surrounding spaces.
 */
public final class SupportLinks {
    public enum Kind { WORLDWIDE, RUSSIA }

    public static final class Link {
        public final Kind kind;
        public final String url;

        Link(Kind kind, String url) {
            this.kind = kind;
            this.url = url;
        }
    }

    /** The longest address the launcher's QR encoder takes (QrCode: version 6, byte mode, level M). */
    static final int MAX_BYTES = 106;

    private SupportLinks() {
    }

    /** The links to show, worldwide first; empty when neither is a usable https address. */
    public static List<Link> of(String worldwide, String russia) {
        List<Link> links = new ArrayList<>();
        String w = usable(worldwide);
        if (w != null) links.add(new Link(Kind.WORLDWIDE, w));
        String r = usable(russia);
        if (r != null) links.add(new Link(Kind.RUSSIA, r));
        return links;
    }

    /** The address without surrounding spaces when it is a plain https link a QR code can hold, else null. */
    static String usable(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (!u.startsWith("https://") || u.length() == "https://".length()) return null;
        for (int i = 0; i < u.length(); i++) if (Character.isWhitespace(u.charAt(i)) || Character.isISOControl(u.charAt(i))) return null;
        return u.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES ? u : null;
    }
}
