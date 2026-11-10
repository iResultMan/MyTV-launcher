package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A small HTTP server on the TV for children's mode: the phone talks to the TV directly, no polling and no other
 * machine needed. One connection at a time, short timeouts, bodies of 2 KB at most; every answer goes through
 * KidsLockCore.
 */
final class KidsLockServer {
    private static final int MAX_HEAD = 8192, MAX_BODY = 2048, TIMEOUT_MS = 3000;

    private final int port;
    private final KidsLockCore core;
    private volatile ServerSocket socket;
    private Thread thread;

    KidsLockServer(int port, KidsLockCore core) {
        this.port = port;
        this.core = core;
    }

    /** The port in use (the one asked for, or the one the system gave for 0). */
    int start() throws IOException {
        socket = new ServerSocket(port);
        thread = new Thread(this::loop, "kids-lock-server");
        thread.setDaemon(true);
        thread.start();
        return socket.getLocalPort();
    }

    void stop() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    private void loop() {
        ServerSocket s = socket;
        while (s != null && !s.isClosed()) {
            try (Socket c = s.accept()) {
                if (!fromHomeNetwork(c.getInetAddress())) continue;
                c.setSoTimeout(TIMEOUT_MS);
                serve(c);
            } catch (IOException e) {
                // A broken or slow client: on to the next one.
            }
        }
    }

    /**
     * Only the home network and the owner's own Tailscale network talk to the TV: private (192.168.x.x, 10.x.x.x,
     * 172.16-31.x.x), link-local, loopback, or Tailscale's 100.64.0.0/10. A Tailscale address only reaches the TV once the
     * Tailscale app runs on it, and then only from devices signed in to the same account.
     */
    static boolean fromHomeNetwork(java.net.InetAddress a) {
        if (a == null) return false;
        byte[] b = a.getAddress();
        boolean tailscale = b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 0x40;
        return a.isSiteLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || tailscale;
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return " OK";
            case 400: return " Bad Request";
            case 401: return " Unauthorized";
            case 403: return " Forbidden";
            case 503: return " Service Unavailable";
            default: return " Not Found";
        }
    }

    private void serve(Socket c) throws IOException {
        InputStream in = c.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;                       // matching \r\n\r\n
        while (state < 4) {
            int b = in.read();
            if (b < 0 || head.size() > MAX_HEAD) return;
            head.write(b);
            state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3)) ? state + 1 : (b == '\r' ? 1 : 0);
        }
        String[] lines = new String(head.toByteArray(), StandardCharsets.UTF_8).split("\r\n");
        String[] req = lines[0].split(" ");
        if (req.length < 2) return;
        int length = 0;
        String by = null, auth = null;
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i].toLowerCase(Locale.ROOT);
            if (l.startsWith("content-length:")) {
                try {
                    length = Math.max(0, Math.min(MAX_BODY, Integer.parseInt(l.substring(15).trim())));
                } catch (NumberFormatException ignored) {
                }
            } else if (l.startsWith("authorization:")) {
                auth = lines[i].substring(14).trim();
            } else if (l.startsWith("x-by:")) {
                try {
                    by = java.net.URLDecoder.decode(lines[i].substring(5).trim(), "UTF-8");
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        byte[] body = new byte[length];
        for (int got = 0; got < length; ) {
            int n = in.read(body, got, length - got);
            if (n < 0) break;
            got += n;
        }
        KidsLockCore.Reply r = core.handle(req[0], req[1], by, new String(body, StandardCharsets.UTF_8), auth);
        byte[] out = r.body.getBytes(StandardCharsets.UTF_8);
        OutputStream o = c.getOutputStream();
        o.write(("HTTP/1.1 " + r.status + reason(r.status) + "\r\nContent-Type: " + r.type
                + "\r\nContent-Length: " + out.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        o.write(out);
        o.flush();
    }
}
