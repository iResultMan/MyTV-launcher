package com.gathin.tvhome.family;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** CloudSession.Http over HttpURLConnection: every request names the protocol version, bodies are JSON in UTF-8, answers are cut at 64 KB. */
public final class CloudHttp implements CloudSession.Http {
    private static final int MAX_ANSWER = 64 * 1024;

    @Override
    public CloudSession.Response request(String method, String url, String bearer, String body, int timeoutMs) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(timeoutMs);
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("X-Protocol", Integer.toString(CloudCore.PROTOCOL));
            c.setRequestProperty("Accept", "application/json");
            if (bearer != null && !bearer.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            if (in != null) {
                try (InputStream stream = in) {
                    byte[] chunk = new byte[2048];
                    for (int n; (n = stream.read(chunk)) > 0; ) {
                        buf.write(chunk, 0, n);
                        if (buf.size() > MAX_ANSWER) return new CloudSession.Response(502, "");
                    }
                }
            }
            return new CloudSession.Response(status, buf.toString(StandardCharsets.UTF_8.name()));
        } finally {
            c.disconnect();
        }
    }
}
