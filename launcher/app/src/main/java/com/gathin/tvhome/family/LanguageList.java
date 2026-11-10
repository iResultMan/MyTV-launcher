package com.gathin.tvhome.family;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The languages the launcher can be switched to. The list is a small JSON file on GitHub (languages.json at the root
 * of the repository, {"languages":[{"tag":"ru","name":"Русский"}, ...]}) that grows as translations are added; what
 * the installed build can actually show is what its resources hold, so a listed language that is not in the build is
 * offered but marked as waiting for an update.
 */
public final class LanguageList {
    public static final class Language {
        public final String tag;
        public final String name;

        public Language(String tag, String name) {
            this.tag = tag;
            this.name = name;
        }
    }

    private LanguageList() {
    }

    private static final Pattern ENTRY = Pattern.compile("\\{([^{}]*)\\}");
    private static final Pattern TAG = Pattern.compile("[a-z]{2,3}(-[A-Za-z0-9]{1,8})*");

    /**
     * The languages in a languages.json text, in file order: each flat {...} object after "languages" with a plain
     * "tag" and a "name". Anything else is skipped, so a damaged file gives fewer languages or none, never an error.
     */
    public static List<Language> parse(String json) {
        List<Language> out = new ArrayList<>();
        if (json == null) return out;
        int from = json.indexOf("\"languages\"");
        if (from < 0) return out;
        Matcher entries = ENTRY.matcher(json);
        entries.region(from, json.length());
        while (entries.find()) {
            String tag = field(entries.group(1), "tag");
            String name = field(entries.group(1), "name");
            if (tag == null || name == null || !TAG.matcher(tag).matches() || name.isEmpty() || name.length() > 60) continue;
            boolean seen = false;
            for (Language l : out) seen |= l.tag.equals(tag);
            if (!seen) out.add(new Language(tag, name));
        }
        return out;
    }

    private static String field(String object, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(object);
        return m.find() ? unescape(m.group(1)).trim() : null;
    }

    private static String unescape(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                out.append(c);
                continue;
            }
            char n = s.charAt(++i);
            if (n == 'u' && i + 4 < s.length()) {
                try {
                    out.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                    continue;
                } catch (RuntimeException e) {
                    // not a code: keep the letter
                }
            }
            out.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
        }
        return out.toString();
    }

    /** Android's old language codes, as the asset manager still reports them, in the codes a tag uses. */
    static String modern(String language) {
        switch (language) {
            case "in": return "id";
            case "iw": return "he";
            case "ji": return "yi";
            default: return language;
        }
    }

    /**
     * Whether the build has resources for the tag: one of the locales its asset manager reports (such as "ru", "pt-BR",
     * "es-419" or "sr-Latn") is the tag itself, or the tag is only a language that one of them belongs to.
     */
    public static boolean inBuild(String tag, String[] assetLocales) {
        Locale wanted = Locale.forLanguageTag(tag);
        for (String have : assetLocales) {
            Locale l = Locale.forLanguageTag(have.replace('_', '-'));
            String language = modern(l.getLanguage());
            if (!language.equals(modern(wanted.getLanguage()))) continue;
            if (wanted.getCountry().isEmpty() && wanted.getScript().isEmpty()) return true;
            if (wanted.getCountry().equals(l.getCountry()) && wanted.getScript().equals(l.getScript())) return true;
        }
        return false;
    }
}
