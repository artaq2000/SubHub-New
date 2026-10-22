package com.artaq.subhub;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 322.3.122: «🩺 فحص الأسماء» — what one site name answered, in one word. Kept free
 * of Android so it is tested on its own (tests/NameCheckRulesTest.java).
 *
 * The check opens the name the way the player does (a page of this app, on this
 * device and this connection), so «blocked» means blocked HERE — on the connection
 * the check ran on — not for everyone: a UK court order is applied by Cloudflare to
 * connections it sees in the UK, and some Saudi home lines reach Cloudflare through
 * London (seen 22 Oct 2026: IPv6 via LHR blocked, IPv4 via RUH open, mobile data open).
 */
public final class NameCheckRules {
    private NameCheckRules() {}

    public static final String OK = "ok";                 // the site opened (a film page: its servers are listed)
    public static final String NO_SERVERS = "noServers";  // the site opened, but no server list for this film
    public static final String BLOCKED = "blocked";       // 451 «Unavailable For Legal Reasons» on this connection
    public static final String NOT_FOUND = "notFound";    // 404 / 410
    public static final String DOWN = "down";             // the site's server is down (5xx, Cloudflare 52x / 530)
    public static final String NO_ANSWER = "noAnswer";    // no answer at all (unknown name, refused, timed out, TLS)
    public static final String REDIRECT = "redirect";     // sends to another name
    public static final String CHECK_PAGE = "checkPage";  // stays on a «verify / continue» page
    public static final String NOT_SITE = "notSite";      // opened, but it is not the site (parked, ads, another site)
    public static final String HTTP_ERROR = "httpError";  // another HTTP error (403 with no check page…)

    /** A film page listing this many servers works (fbox lists about 50). */
    public static final int SERVERS_ALIVE = 3;
    /** A front page with this many links to films is clearly the site (the check can stop early). */
    public static final int LINKS_ALIVE = 3;

    /** What was seen while one name was opened. */
    public static final class Facts {
        public boolean filmPage;          // a film page was opened (else the front page)
        public String redirectHost = "";  // the page itself sent to another name
        public int netError;              // WebView error code of the page itself (negative), 0 = none
        public int httpStatus;            // the HTTP error status of the page now open (>= 400), 0 = none
        public boolean finished;          // a page of this name finished loading
        public int servers = -1;          // servers listed (-1 = not read)
        public int filmLinks = -1;        // links to film pages (-1 = not read)
        public boolean checkPage;         // a «verify / continue» page is showing
        public boolean blockedText;       // «Unavailable For Legal Reasons» in the page
    }

    /** The page already shows that this name works: the check of this name can stop. */
    public static boolean clearlyWorks(Facts f) {
        if (f == null || !f.redirectHost.isEmpty() || f.httpStatus == 451 || f.blockedText) return false;
        if (f.httpStatus == 404 || f.httpStatus == 410) return false;
        return f.filmPage ? f.servers >= SERVERS_ALIVE : f.filmLinks >= LINKS_ALIVE;
    }

    /** One word for what this name answered. */
    public static String verdict(Facts f) {
        if (f == null) return NO_ANSWER;
        if (!f.redirectHost.isEmpty()) return REDIRECT;
        int s = f.httpStatus;
        if (s == 451 || f.blockedText) return BLOCKED;
        if (s == 404 || s == 410) return NOT_FOUND;
        // A check page that went on by itself to the real page counts as the real page.
        if (f.filmPage ? f.servers >= SERVERS_ALIVE : (f.filmLinks >= 1 || f.servers >= SERVERS_ALIVE)) return OK;
        if (f.checkPage) return CHECK_PAGE;
        if (s >= 500 && s <= 599) return DOWN;
        if (s >= 400) return HTTP_ERROR;
        if (f.netError != 0) return NO_ANSWER;
        if (!f.finished) return NO_ANSWER;   // nothing came back in time
        if (f.filmPage && (f.servers > 0 || f.filmLinks > 0)) return NO_SERVERS;
        return NOT_SITE;
    }

    /** An answer of the page that settles it at once (no need to wait for the page). */
    public static boolean finalStatus(int code) {
        return code == 404 || code == 410 || (code >= 500 && code <= 599 && code != 503);
    }

    private static final Pattern HOST = Pattern.compile("([a-z0-9-]+\\.)+[a-z]{2,12}");
    private static final Pattern FILM_PATH = Pattern.compile("/watch/(movie|tv)-[a-z0-9-]{1,120}");
    private static final Pattern TRACE_VALUE = Pattern.compile("[A-Za-z0-9:._-]{0,60}");

    /** «HTTPS://WWW.Name.SX/x» → «name.sx»; "" when it is not a site name. */
    public static String cleanHost(String raw) {
        if (raw == null) return "";
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.startsWith("https://")) v = v.substring(8);
        else if (v.startsWith("http://")) v = v.substring(7);
        int cut = v.indexOf('/');
        if (cut >= 0) v = v.substring(0, cut);
        cut = v.indexOf(':');
        if (cut >= 0) v = v.substring(0, cut);
        if (v.startsWith("www.")) v = v.substring(4);
        return v.length() <= 80 && HOST.matcher(v).matches() ? v : "";
    }

    /** The host is this name or one of its sub-names (www.…). */
    public static boolean sameSite(String host, String name) {
        if (host == null || name == null || name.isEmpty()) return false;
        String h = host.trim().toLowerCase(Locale.ROOT);
        return h.equals(name) || h.endsWith("." + name);
    }

    /** The page a check opens: the front page, or a film / series page of the family. */
    public static boolean validPath(String path) {
        return "/".equals(path) || (path != null && FILM_PATH.matcher(path).matches());
    }

    /** One value of Cloudflare's /cdn-cgi/trace text («colo=LHR»), "" when absent or odd. */
    public static String traceValue(String text, String key) {
        if (text == null || key == null || key.isEmpty()) return "";
        for (String line : text.split("\\r?\\n")) {
            String l = line.trim();
            if (l.startsWith(key + "=")) {
                String v = l.substring(key.length() + 1).trim();
                return TRACE_VALUE.matcher(v).matches() ? v : "";
            }
        }
        return "";
    }

    /** Cloudflare data centres in the United Kingdom (London, Manchester, Edinburgh). */
    public static boolean ukColo(String colo) {
        String c = colo == null ? "" : colo.trim().toUpperCase(Locale.ROOT);
        return c.equals("LHR") || c.equals("MAN") || c.equals("EDI");
    }
}
