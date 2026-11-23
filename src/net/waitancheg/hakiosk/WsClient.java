package net.waitancheg.hakiosk;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal blocking RFC 6455 WebSocket client (text messages only).
 * ws:// over a plain Socket, wss:// over SSLSocket with hostname verification.
 * Pure JDK (no android.* imports) so it can be unit-tested on a desktop JVM.
 */
public final class WsClient implements Closeable {
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE = 16 * 1024 * 1024;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final SecureRandom random = new SecureRandom();
    private boolean closed;

    private WsClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new java.io.BufferedInputStream(socket.getInputStream(), 16384);
        this.out = socket.getOutputStream();
    }

    /** Connects and performs the opening handshake. */
    public static WsClient connect(String url, int timeoutMs) throws IOException {
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        boolean tls;
        if ("wss".equals(scheme) || "https".equals(scheme)) tls = true;
        else if ("ws".equals(scheme) || "http".equals(scheme)) tls = false;
        else throw new IOException("Unsupported scheme: " + scheme);
        String host = uri.getHost();
        if (host == null) throw new IOException("No host in " + url);
        int port = uri.getPort() > 0 ? uri.getPort() : (tls ? 443 : 80);
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), timeoutMs);
        raw.setSoTimeout(timeoutMs);
        raw.setTcpNoDelay(true);
        Socket s = raw;
        if (tls) {
            SSLSocketFactory f = (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ssl = (SSLSocket) f.createSocket(raw, host, port, true);
            enableSni(ssl, host);
            ssl.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
                ssl.close();
                throw new IOException("TLS hostname mismatch for " + host);
            }
            s = ssl;
        }
        WsClient c = new WsClient(s);
        try {
            c.handshake(host, port, tls, path);
        } catch (IOException e) {
            c.closeQuietly();
            throw e;
        }
        return c;
    }

    /** Old Android (Conscrypt/OpenSSLSocketImpl) only sends SNI after setHostname(). */
    private static void enableSni(SSLSocket ssl, String host) {
        try {
            ssl.getClass().getMethod("setHostname", String.class).invoke(ssl, host);
        } catch (Exception ignored) {
            // Not available (e.g. desktop JDK, which sends SNI by itself).
        }
    }

    private void handshake(String host, int port, boolean tls, String path) throws IOException {
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = base64(nonce);
        boolean defPort = (tls && port == 443) || (!tls && port == 80);
        String hostHdr = (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + (defPort ? "" : ":" + port);
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHdr + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "User-Agent: HAPanel/1.0\r\n"
                + "\r\n";
        out.write(req.getBytes(UTF8));
        out.flush();

        String status = readLine();
        if (status == null || !status.startsWith("HTTP/1.1 101")) {
            throw new IOException("Handshake failed: " + status);
        }
        String accept = null;
        String line;
        while ((line = readLine()) != null && !line.isEmpty()) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase("Sec-WebSocket-Accept")) {
                accept = line.substring(i + 1).trim();
            }
        }
        if (line == null) throw new EOFException("EOF in handshake");
        String expected;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            expected = base64(sha1.digest((key + GUID).getBytes(UTF8)));
        } catch (Exception e) {
            throw new IOException(e);
        }
        if (!expected.equals(accept)) throw new IOException("Bad Sec-WebSocket-Accept");
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("Header line too long");
        }
        if (c == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), UTF8);
    }

    public void setReadTimeout(int ms) throws IOException {
        socket.setSoTimeout(ms);
    }

    public synchronized void sendText(String text) throws IOException {
        sendFrame(0x1, text.getBytes(UTF8));
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
        if (closed) throw new IOException("closed");
        int len = payload.length;
        byte[] header = new byte[14];
        int h = 0;
        header[h++] = (byte) (0x80 | opcode);
        if (len < 126) {
            header[h++] = (byte) (0x80 | len);
        } else if (len <= 0xFFFF) {
            header[h++] = (byte) (0x80 | 126);
            header[h++] = (byte) (len >>> 8);
            header[h++] = (byte) len;
        } else {
            header[h++] = (byte) (0x80 | 127);
            long l = len;
            for (int i = 7; i >= 0; i--) header[h++] = (byte) (l >>> (8 * i));
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        System.arraycopy(mask, 0, header, h, 4);
        h += 4;
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++) masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        out.write(header, 0, h);
        out.write(masked);
        out.flush();
    }

    /** Reads the next complete text message. Answers pings; throws on close. */
    public String receiveText() throws IOException {
        ByteArrayOutputStream msg = null;
        while (true) {
            int b0 = readByte();
            int b1 = readByte();
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = ((long) readByte() << 8) | readByte();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
            }
            if (len < 0 || len > MAX_MESSAGE) throw new IOException("Frame too large: " + len);
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(mask);
            }
            byte[] payload = new byte[(int) len];
            readFully(payload);
            if (mask != null) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

            switch (opcode) {
                case 0x9: // ping
                    sendFrame(0xA, payload);
                    continue;
                case 0xA: // pong
                    continue;
                case 0x8: // close
                    try { sendFrame(0x8, new byte[0]); } catch (IOException ignored) { }
                    closeQuietly();
                    throw new EOFException("WebSocket closed by server");
                case 0x1: // text
                case 0x2: // binary
                    msg = new ByteArrayOutputStream(payload.length);
                    msg.write(payload);
                    break;
                case 0x0: // continuation
                    if (msg == null) throw new IOException("Unexpected continuation");
                    msg.write(payload);
                    if (msg.size() > MAX_MESSAGE) throw new IOException("Message too large");
                    break;
                default:
                    throw new IOException("Unknown opcode " + opcode);
            }
            if (fin) return new String(msg.toByteArray(), UTF8);
        }
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("WebSocket EOF");
        return b;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new EOFException("WebSocket EOF");
            off += n;
        }
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (!closed) {
            try { sendFrame(0x8, new byte[] {0x03, (byte) 0xE8}); } catch (IOException ignored) { }
        }
        closeQuietly();
    }

    private void closeQuietly() {
        closed = true;
        try { socket.close(); } catch (IOException ignored) { }
    }

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String base64(byte[] d) {
        StringBuilder sb = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int b = (d[i] & 0xFF) << 16;
            if (i + 1 < d.length) b |= (d[i + 1] & 0xFF) << 8;
            if (i + 2 < d.length) b |= d[i + 2] & 0xFF;
            sb.append(B64[(b >>> 18) & 63]).append(B64[(b >>> 12) & 63]);
            sb.append(i + 1 < d.length ? B64[(b >>> 6) & 63] : '=');
            sb.append(i + 2 < d.length ? B64[b & 63] : '=');
        }
        return sb.toString();
    }
}
