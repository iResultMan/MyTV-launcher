package com.gathin.tvhome.family;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/** The transport against a server on this machine: the headers the protocol asks for, the bodies and the status codes. */
public class CloudHttpTest {
    /** Reads one request, remembers "request line|X-Protocol|Authorization|body", answers with the status and body given. */
    private static Thread serve(ServerSocket server, int status, String answer, AtomicReference<String> seen) {
        Thread t = new Thread(() -> {
            try (Socket s = server.accept()) {
                InputStream in = s.getInputStream();
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                int last4 = 0;
                for (int b; (b = in.read()) >= 0; ) {
                    head.write(b);
                    last4 = (last4 << 8) | b;
                    if (last4 == 0x0d0a0d0a) break;
                }
                String text = head.toString("UTF-8");
                int length = 0;
                String protocol = "", auth = "";
                for (String line : text.split("\r\n")) {
                    String lower = line.toLowerCase();
                    if (lower.startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                    if (lower.startsWith("x-protocol:")) protocol = line.substring(11).trim();
                    if (lower.startsWith("authorization:")) auth = line.substring(14).trim();
                }
                byte[] body = new byte[length];
                for (int got = 0; got < length; ) {
                    int n = in.read(body, got, length - got);
                    if (n < 0) break;
                    got += n;
                }
                seen.set(text.split("\r\n")[0] + "|" + protocol + "|" + auth + "|" + new String(body, StandardCharsets.UTF_8));
                byte[] out = answer.getBytes(StandardCharsets.UTF_8);
                OutputStream o = s.getOutputStream();
                o.write(("HTTP/1.1 " + status + " X\r\nContent-Type: application/json\r\nContent-Length: " + out.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                o.write(out);
                o.flush();
            } catch (Exception e) {
                seen.set("server error: " + e);
            }
        });
        t.start();
        return t;
    }

    @Test
    public void everyRequestCarriesTheProtocolVersionTheTokenAndAUtf8Body() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>("");
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread t = serve(server, 200, "{\"rev\":4}", seen);
            CloudSession.Response r = new CloudHttp().request("POST", "http://127.0.0.1:" + server.getLocalPort() + "/v1/device/state",
                    "tok", "{\"label\":\"é\"}", 5000);
            t.join(5000);
            assertEquals(200, r.status);
            assertEquals("{\"rev\":4}", r.body);
            assertEquals("POST /v1/device/state HTTP/1.1|1|Bearer tok|{\"label\":\"é\"}", seen.get());
        }
    }

    @Test
    public void anErrorStatusKeepsItsBody() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>("");
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            Thread t = serve(server, 401, "{\"error\":\"revoked\"}", seen);
            CloudSession.Response r = new CloudHttp().request("GET", "http://127.0.0.1:" + server.getLocalPort() + "/v1/device/poll?since=0&wait=1",
                    null, null, 5000);
            t.join(5000);
            assertEquals(401, r.status);
            assertTrue(r.body.contains("revoked"));
            assertTrue(seen.get().startsWith("GET /v1/device/poll?since=0&wait=1 HTTP/1.1|1||"));
        }
    }
}
