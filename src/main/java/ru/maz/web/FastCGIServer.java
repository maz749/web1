package ru.maz.web;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class FastCGIServer {

    private static final byte FCGI_VERSION_1 = 1;
    private static final byte FCGI_BEGIN_REQUEST = 1;
    private static final byte FCGI_ABORT_REQUEST = 2;
    private static final byte FCGI_END_REQUEST = 3;
    private static final byte FCGI_PARAMS = 4;
    private static final byte FCGI_STDIN = 5;
    private static final byte FCGI_STDOUT = 6;

    private static final int FCGI_REQUEST_COMPLETE = 0;

    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static class FcgiHeader {
        byte version, type;
        short requestId;
        short contentLength;
        byte paddingLength;
        byte reserved;
    }

    private static class ResultRow {
        final double x, y, r;
        final boolean hit;
        final String time;
        final double scriptTimeMs;

        ResultRow(double x, double y, double r, boolean hit, String time, double ms) {
            this.x = x; this.y = y; this.r = r; this.hit = hit; this.time = time; this.scriptTimeMs = ms;
        }
    }

    private static final List<ResultRow> HISTORY = new CopyOnWriteArrayList<>();

    private static void log(Socket socket, String message) {
        String timestamp = ZonedDateTime.now(ZoneId.of("Europe/Moscow")).format(LOG_TIME_FORMAT);
        String client = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
        System.err.printf("[%s] [Client: %s] %s%n", timestamp, client, message);
    }

    public static void main(String[] args) throws Exception {
        int port = (args.length > 0) ? Integer.parseInt(args[0]) : 14889;
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", port));
            System.err.println("НЕ МОЖЕТ БЫТЬ, ОНО РАБОТАЕТ НА 127.0.0.1:" + port);
            while (true) {
                Socket s = server.accept();
                try {
                    handleConnection(s);
                } catch (Throwable t) {
                    log(s, "Unhandled exception: " + t);
                    t.printStackTrace(System.err);
                } finally {
                    try { s.close(); } catch (IOException ignore) {}
                }
            }
        }
    }

    private static void handleConnection(Socket s) throws IOException {
        s.setSoTimeout(10000);
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();

        Map<String,String> env = new HashMap<>();
        ByteArrayOutputStream stdinBuf = new ByteArrayOutputStream();
        short requestId = -1;
        boolean paramsDone = false, stdinDone = false;

        log(s, "New FastCGI connection");

        while (!(paramsDone && stdinDone)) {
            FcgiHeader h = readHeader(in);
            if (h == null) {
                log(s, "Incomplete header, closing");
                return;
            }
            byte[] content = readFully(in, h.contentLength);
            if (h.paddingLength > 0) readFully(in, h.paddingLength);
            if (requestId == -1) requestId = h.requestId;

            switch (h.type) {
                case FCGI_BEGIN_REQUEST:
                    log(s, "BEGIN_REQUEST (id=" + requestId + ")");
                    break;
                case FCGI_PARAMS:
                    if (content.length == 0) {
                        paramsDone = true;
                        log(s, "PARAMS complete");
                    } else {
                        env.putAll(decodeNameValuePairs(content));
                    }
                    break;
                case FCGI_STDIN:
                    if (content.length == 0) {
                        stdinDone = true;
                        log(s, "STDIN complete");
                    } else {
                        stdinBuf.write(content);
                    }
                    break;
                case FCGI_ABORT_REQUEST:
                    log(s, "ABORT_REQUEST received");
                    return;
                default:
                    log(s, "Unknown record type: " + h.type);
            }
        }

        long t0 = System.nanoTime();

        String method = env.getOrDefault("REQUEST_METHOD", "GET");
        String ctype  = env.getOrDefault("CONTENT_TYPE", "");
        String body   = stdinBuf.toString(StandardCharsets.UTF_8);

        Map<String,String> form = parseFormUrlencoded(
                "POST".equalsIgnoreCase(method) && ctype.contains("application/x-www-form-urlencoded")
                        ? body : ""
        );

        log(s, String.format("Processing %s | x=%s, y=%s, r=%s", method, form.get("x"), form.get("y"), form.get("r")));

        ResponsePayload payload;
        try {
            double x = parseDouble(form.get("x"), "x");
            double y = parseDouble(form.get("y"), "y");
            double r = parseDouble(form.get("r"), "r");
            if (!(r > 0)) throw new IllegalArgumentException("r must be > 0");

            boolean hit = checkHit(x, y, r);
            double ms = (System.nanoTime() - t0) / 1_000_000.0;
            ms = Math.round(ms * 1000.0) / 1000.0;

            String now = ZonedDateTime.now(ZoneId.of("Europe/Moscow"))
                    .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

            ResultRow row = new ResultRow(x, y, r, hit, now, ms);
            HISTORY.add(row);

            payload = ResponsePayload.ok(row, HISTORY);

            log(s, String.format("SUCCESS | hit=%b | %.3f ms", hit, ms));
        } catch (Exception ex) {
            payload = ResponsePayload.error(ex.getMessage());
            log(s, "ERROR: " + ex.getMessage());
        }

        byte[] bodyJson = payload.toJson().getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream resp = new ByteArrayOutputStream();
        resp.write("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.UTF_8));
        resp.write("Content-Type: application/json; charset=utf-8\r\n".getBytes(StandardCharsets.UTF_8));
        resp.write("Connection: close\r\n".getBytes(StandardCharsets.UTF_8));
        resp.write("\r\n".getBytes(StandardCharsets.UTF_8));
        resp.write(bodyJson);

        writeRecord(out, FCGI_STDOUT, requestId, resp.toByteArray());
        writeRecord(out, FCGI_STDOUT, requestId, new byte[0]);
        writeEndRequest(out, requestId, FCGI_REQUEST_COMPLETE);

        log(s, "Response sent, connection closed");
    }

    private static boolean checkHit(double x, double y, double r) {
        final double EPS = 1e-9;

        if (x >= -EPS && y >= -EPS) {
            return x <= r + EPS && y <= r/2.0 + EPS;
        }

        if (x <= EPS && y >= -EPS) {
            return x*x + y*y <= (r*r)/4.0 + EPS;
        }

        if (x >= -EPS && y <= EPS) {
            return x >= -EPS && x <= r/2.0 + EPS && y >= -r - EPS && y <= EPS;
        }

        return false;
    }


    private static FcgiHeader readHeader(InputStream in) throws IOException {
        byte[] h = readFully(in, 8);
        if (h == null) return null;
        ByteBuffer b = ByteBuffer.wrap(h);
        FcgiHeader fh = new FcgiHeader();
        fh.version = b.get();
        fh.type = b.get();
        fh.requestId = (short) ((b.get() & 0xFF) << 8 | (b.get() & 0xFF));
        fh.contentLength = (short) ((b.get() & 0xFF) << 8 | (b.get() & 0xFF));
        fh.paddingLength = b.get();
        fh.reserved = b.get();
        return fh;
    }

    private static byte[] readFully(InputStream in, int len) throws IOException {
        if (len == 0) return new byte[0];
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(buf, off, len - off);
            if (r < 0) return null;
            off += r;
        }
        return buf;
    }

    private static Map<String, String> decodeNameValuePairs(byte[] content) {
        Map<String, String> m = new HashMap<>();
        int i = 0;
        while (i < content.length) {
            int[] nl = decodeLen(content, i); i = nl[1];
            int[] vl = decodeLen(content, i); i = vl[1];
            int nlen = nl[0], vlen = vl[0];
            String name = new String(content, i, nlen, StandardCharsets.UTF_8); i += nlen;
            String value = new String(content, i, vlen, StandardCharsets.UTF_8); i += vlen;
            m.put(name, value);
        }
        return m;
    }

    private static void writeRecord(OutputStream out, byte type, short reqId, byte[] payload) throws IOException {
        int len = payload.length;
        int pad = (8 - (len % 8)) % 8;
        byte[] h = new byte[8];
        h[0] = FCGI_VERSION_1;
        h[1] = type;
        h[2] = (byte) ((reqId >> 8) & 0xFF);
        h[3] = (byte) (reqId & 0xFF);
        h[4] = (byte) ((len >> 8) & 0xFF);
        h[5] = (byte) (len & 0xFF);
        h[6] = (byte) pad;
        h[7] = 0;
        out.write(h);
        out.write(payload);
        if (pad > 0) out.write(new byte[pad]);
    }

    private static void writeEndRequest(OutputStream out, short reqId, int appStatus) throws IOException {
        byte[] body = new byte[8];
        body[0] = (byte) ((appStatus >> 24) & 0xFF);
        body[1] = (byte) ((appStatus >> 16) & 0xFF);
        body[2] = (byte) ((appStatus >> 8) & 0xFF);
        body[3] = (byte) (appStatus & 0xFF);
        body[4] = (byte) FCGI_REQUEST_COMPLETE;

        writeRecord(out, FCGI_END_REQUEST, reqId, body);
    }

    private static Map<String, String> parseFormUrlencoded(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String p : s.split("&")) {
            int eq = p.indexOf('=');
            String k = eq >= 0 ? p.substring(0, eq) : p;
            String v = eq >= 0 ? p.substring(eq + 1) : "";
            try {
                m.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
            } catch (Exception e) {
                // ignore
            }
        }
        return m;
    }

    private static double parseDouble(String v, String name) {
        if (v == null) throw new IllegalArgumentException("missing param: " + name);
        return Double.parseDouble(v.trim().replace(',', '.'));
    }

    private static int[] decodeLen(byte[] a, int i) {
        int b0 = a[i] & 0xFF;
        if ((b0 & 0x80) == 0) return new int[]{ b0, i + 1 };
        int v = ((b0 & 0x7F) << 24) | ((a[i + 1] & 0xFF) << 16) | ((a[i + 2] & 0xFF) << 8) | (a[i + 3] & 0xFF);
        return new int[]{ v, i + 4 };
    }

    private static class ResponsePayload {
        final boolean ok;
        final String error;
        final Map<String,Object> current;
        final List<Map<String,Object>> history;

        static ResponsePayload ok(ResultRow r, List<ResultRow> all) {
            Map<String,Object> cur = rowToMap(r);
            List<Map<String,Object>> hist = new ArrayList<>();
            for (ResultRow rr : all) hist.add(rowToMap(rr));
            return new ResponsePayload(true, null, cur, hist);
        }
        static ResponsePayload error(String msg) {
            return new ResponsePayload(false, msg, null, Collections.emptyList());
        }
        private ResponsePayload(boolean ok, String error, Map<String,Object> current, List<Map<String,Object>> history) {
            this.ok = ok; this.error = error; this.current = current; this.history = history;
        }

        String toJson() {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"ok\":").append(ok);
            if (!ok) sb.append(",\"error\":").append(jsonStr(error));
            else {
                sb.append(",\"current\":").append(mapToJson(current));
                sb.append(",\"history\":").append(listToJson(history));
            }
            sb.append("}");
            return sb.toString();
        }

        private static Map<String,Object> rowToMap(ResultRow r) {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("x", r.x); m.put("y", r.y); m.put("r", r.r);
            m.put("hit", r.hit); m.put("time", r.time); m.put("scriptTimeMs", r.scriptTimeMs);
            return m;
        }
        private static String jsonStr(String s) {
            if (s == null) return "null";
            return "\"" + s.replace("\\","\\\\").replace("\"","\\\"") + "\"";
        }
        private static String valToJson(Object v) {
            if (v == null) return "null";
            if (v instanceof Number || v instanceof Boolean) return v.toString();
            return jsonStr(String.valueOf(v));
        }
        private static String mapToJson(Map<String,Object> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(jsonStr(e.getKey())).append(':').append(valToJson(e.getValue()));
            }
            sb.append('}');
            return sb.toString();
        }
        private static String listToJson(List<Map<String,Object>> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(mapToJson(l.get(i)));
            }
            sb.append(']');
            return sb.toString();
        }
    }
}