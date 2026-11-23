import net.waitancheg.hakiosk.HaMedia;
import java.io.File;
import java.nio.file.Files;
import java.util.List;

public class HaMediaTest {
    static void check(boolean c, String m) { if (!c) throw new AssertionError(m); System.out.println("PASS " + m); }

    public static void main(String[] a) throws Exception {
        String base = a[0];
        // Desktop JDK's default HostnameVerifier rejects everything (real check lives inside
        // HttpsClient); Android's default (OkHostnameVerifier) checks SANs. Emulate that here.
        javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier((host, sess) -> {
            try {
                java.security.cert.X509Certificate c = (java.security.cert.X509Certificate) sess.getPeerCertificates()[0];
                if (c.getSubjectAlternativeNames() == null) return false;
                for (List<?> san : c.getSubjectAlternativeNames()) if (host.equalsIgnoreCase(String.valueOf(san.get(1)))) return true;
            } catch (Exception e) { return false; }
            return false;
        });
        check(HaMedia.folderId("/slideshow/").equals("media-source://media_source/local/slideshow"), "folderId");
        try (HaMedia m = new HaMedia(base + "/lovelace/0", "test-token")) {
            List<String> ids = m.listImages("slideshow");
            check(ids.size() == 2 && ids.get(0).endsWith("/a.jpg") && ids.get(1).endsWith("/b.png"),
                    "browse returns only the 2 images (fragmented 64-bit frame, ping, foreign event) " + ids);
            for (int i = 0; i < 3; i++) {  // same connection reused
                String u = m.resolve(ids.get(i % 2));
                check(u.startsWith(base + "/media/local/slideshow/") && u.endsWith("?authSig=SIG"), "resolve -> " + u);
                File f = File.createTempFile("slide", ".img");
                m.download(u, f);
                byte[] want = Files.readAllBytes(new File(a[1]).toPath());
                check(java.util.Arrays.equals(Files.readAllBytes(f.toPath()), want), "download matches (" + f.length() + " bytes)");
                f.delete();
            }
            try { m.listImages("nope"); check(false, "should fail"); }
            catch (HaMedia.HaError e) { check(e.getMessage().contains("browse_media_failed"), "HA error surfaced: " + e.getMessage()); }
            check(m.listImages("slideshow").size() == 2, "connection still usable after HA error");
        }
        try (HaMedia m = new HaMedia(base, "wrong")) {
            m.listImages("slideshow"); check(false, "bad token should fail");
        } catch (HaMedia.HaError e) { check(e.getMessage().startsWith("Auth failed"), "bad token -> " + e.getMessage()); }
        System.out.println("ALL OK " + base);
    }
}
