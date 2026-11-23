package net.waitancheg.hakiosk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Home Assistant Media Source access over the WebSocket API:
 * auth with a long-lived token, browse a Local Media folder, resolve items
 * to signed URLs, and download them. No android.* imports (unit-testable).
 */
public final class HaMedia implements Closeable {
    private static final int TIMEOUT_MS = 15000;
    private static final long MAX_DOWNLOAD = 40L * 1024 * 1024;

    private final String httpBase; // scheme://host[:port]
    private final String token;
    private WsClient ws;
    private int nextId = 1;

    public HaMedia(String haUrl, String token) throws IOException {
        this.httpBase = baseOf(haUrl);
        this.token = token;
    }

    /** "http://h:8123/lovelace/0" -> "http://h:8123" */
    static String baseOf(String haUrl) throws IOException {
        try {
            URI u = URI.create(haUrl.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) throw new IOException("URL must be http(s): " + haUrl);
            if (u.getHost() == null) throw new IOException("No host: " + haUrl);
            return scheme + "://" + u.getRawAuthority();
        } catch (IllegalArgumentException e) {
            throw new IOException("Bad URL: " + haUrl, e);
        }
    }

    static String wsUrlOf(String httpBase) {
        return (httpBase.startsWith("https") ? "wss" : "ws") + httpBase.substring(httpBase.indexOf(':')) + "/api/websocket";
    }

    /** media-source id for a folder under Local Media (HA's /media). */
    public static String folderId(String folder) {
        String f = folder == null ? "" : folder.trim();
        while (f.startsWith("/")) f = f.substring(1);
        while (f.endsWith("/")) f = f.substring(0, f.length() - 1);
        return f.isEmpty() ? "media-source://media_source/local" : "media-source://media_source/local/" + f;
    }

    public synchronized void ensureConnected() throws IOException {
        if (ws != null && !ws.isClosed()) return;
        closeWs();
        WsClient c = WsClient.connect(wsUrlOf(httpBase), TIMEOUT_MS);
        try {
            JSONObject m = json(c.receiveText());
            if (!"auth_required".equals(m.optString("type"))) throw new IOException("Unexpected: " + m);
            JSONObject auth = new JSONObject();
            auth.put("type", "auth");
            auth.put("access_token", token);
            c.sendText(auth.toString());
            m = json(c.receiveText());
            if (!"auth_ok".equals(m.optString("type"))) {
                throw new HaError("Auth failed: " + m.optString("message", m.optString("type")));
            }
        } catch (JSONException e) {
            c.close();
            throw new IOException(e);
        } catch (IOException e) {
            c.close();
            throw e;
        }
        ws = c;
        nextId = 1;
    }

    /** Error reported by HA itself (connection is still fine). */
    public static final class HaError extends IOException {
        private static final long serialVersionUID = 1L;
        HaError(String m) { super(m); }
    }

    private JSONObject call(JSONObject cmd) throws IOException {
        boolean reused = ws != null && !ws.isClosed();
        try {
            return callOnce(cmd);
        } catch (HaError e) {
            throw e;
        } catch (IOException e) {
            closeWs();
            if (!reused) throw e;
            // Stale connection (e.g. server closed it while idle): reconnect once.
            return callOnce(cmd);
        }
    }

    private JSONObject callOnce(JSONObject cmd) throws IOException {
        ensureConnected();
        int id = nextId++;
        try {
            cmd.put("id", id);
            ws.sendText(cmd.toString());
            while (true) {
                JSONObject m = json(ws.receiveText());
                if (m.optInt("id", -1) != id) continue; // events / other replies
                if (!"result".equals(m.optString("type"))) continue;
                if (!m.optBoolean("success")) {
                    JSONObject err = m.optJSONObject("error");
                    throw new HaError("HA error: " + (err == null ? m.toString() : err.optString("code") + " " + err.optString("message")));
                }
                Object r = m.opt("result");
                return r instanceof JSONObject ? (JSONObject) r : new JSONObject();
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Lists image items (media_class image or image/* mime) directly in the folder. */
    public synchronized List<String> listImages(String folder) throws IOException {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("type", "media_source/browse_media");
            cmd.put("media_content_id", folderId(folder));
            return imagesFromBrowse(call(cmd));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /** Resolves an item and returns an absolute download URL on our configured base. */
    public synchronized String resolve(String mediaContentId) throws IOException {
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("type", "media_source/resolve_media");
            cmd.put("media_content_id", mediaContentId);
            JSONObject res = call(cmd);
            return onBase(httpBase, res.optString("url", ""));
        } catch (JSONException e) {
            throw new IOException(e);
        } catch (IllegalArgumentException e) {
            throw new IOException(e);
        }
    }

    public void download(String url, File dest) throws IOException {
        download(url, dest, token);
    }

    /**
     * Downloads url into dest (overwrites). bearer may be null: HA's signed
     * (authSig) URLs from resolve_media work without any auth header.
     */
    public static void download(String url, File dest, String bearer) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS * 2);
        c.setInstanceFollowRedirects(true);
        if (bearer != null && !bearer.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + bearer);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code + " for download");
            long total = 0;
            byte[] buf = new byte[16384];
            try (InputStream in = new BufferedInputStream(c.getInputStream());
                 OutputStream out = new FileOutputStream(dest)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_DOWNLOAD) throw new IOException("File too large");
                    out.write(buf, 0, n);
                }
            }
        } finally {
            c.disconnect();
        }
    }

    /** Image items (media_class image or image/* mime) from a browse_media result. */
    public static List<String> imagesFromBrowse(JSONObject res) {
        List<String> ids = new ArrayList<>();
        JSONArray ch = res == null ? null : res.optJSONArray("children");
        if (ch == null) return ids;
        for (int i = 0; i < ch.length(); i++) {
            JSONObject c = ch.optJSONObject(i);
            if (c == null) continue;
            String cls = c.optString("media_class", "");
            String mime = c.optString("media_content_type", "");
            String id = c.optString("media_content_id", "");
            if (id.isEmpty() || c.optBoolean("can_expand", false)) continue;
            if ("image".equals(cls) || mime.startsWith("image/")) ids.add(id);
        }
        return ids;
    }

    /**
     * Absolute URL for a resolve_media "url" on our configured base. HA may return an
     * absolute URL built from its internal/external URL setting; the signed path is
     * host-independent, so always use our own scheme://host:port.
     */
    public static String onBase(String haUrl, String url) throws IOException {
        if (url == null || url.isEmpty()) throw new IOException("No url in resolve result");
        try {
            URI u = URI.create(url);
            String path = u.getRawPath() == null ? "/" : u.getRawPath();
            if (!path.startsWith("/")) path = "/" + path;
            return baseOf(haUrl) + path + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
        } catch (IllegalArgumentException e) {
            throw new IOException(e);
        }
    }

    private static JSONObject json(String s) throws IOException {
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            throw new IOException("Bad JSON from HA", e);
        }
    }

    private void closeWs() {
        if (ws != null) ws.close();
        ws = null;
    }

    @Override
    public synchronized void close() {
        closeWs();
    }
}
