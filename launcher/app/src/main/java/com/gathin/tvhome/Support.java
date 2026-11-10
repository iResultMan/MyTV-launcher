package com.gathin.tvhome;

import android.content.Context;

import com.gathin.tvhome.family.SupportLinks;

import org.json.JSONObject;

import java.util.List;

/** The donation links for About: the build's defaults (res/values/support.xml), or the theme's own support_url and support_url_ru, empty ones hiding the code. */
final class Support {
    private Support() {
    }

    static List<SupportLinks.Link> links(Context ctx) {
        JSONObject theme = Config.overlay(ctx);
        String worldwide = theme != null && theme.has("support_url") ? theme.optString("support_url", "") : ctx.getString(R.string.support_url);
        String russia = theme != null && theme.has("support_url_ru") ? theme.optString("support_url_ru", "") : ctx.getString(R.string.support_url_ru);
        return SupportLinks.of(worldwide, russia);
    }
}
