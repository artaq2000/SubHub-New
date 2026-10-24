import com.artaq.subhub.NameCheckRules;
import com.artaq.subhub.NameCheckRules.Facts;

public class NameCheckRulesTest {
  static int n = 0;
  static void eq(Object got, Object want, String what) {
    n++;
    if (want == null ? got != null : !want.equals(got)) throw new AssertionError(what + ": got " + got + ", want " + want);
  }
  static Facts film() { Facts f = new Facts(); f.filmPage = true; return f; }
  static Facts front() { Facts f = new Facts(); f.filmPage = false; return f; }

  public static void main(String[] a) {
    // works
    Facts f = film(); f.finished = true; f.servers = 48; f.filmLinks = 20;
    eq(NameCheckRules.verdict(f), NameCheckRules.OK, "film page with its servers");
    eq(NameCheckRules.clearlyWorks(f), true, "film page with servers stops early");
    f = front(); f.finished = true; f.filmLinks = 40;
    eq(NameCheckRules.verdict(f), NameCheckRules.OK, "front page with film links");
    f = front(); f.finished = true; f.filmLinks = 1;
    eq(NameCheckRules.verdict(f), NameCheckRules.OK, "front page with one film link (at the end)");
    eq(NameCheckRules.clearlyWorks(f), false, "one link is not enough to stop early");
    // a check page that went on to the real page: the real page counts
    f = film(); f.finished = true; f.httpStatus = 0; f.servers = 30; f.checkPage = false;
    eq(NameCheckRules.verdict(f), NameCheckRules.OK, "after the check page");
    // blocked on this connection
    f = film(); f.finished = true; f.httpStatus = 451;
    eq(NameCheckRules.verdict(f), NameCheckRules.BLOCKED, "451");
    eq(NameCheckRules.clearlyWorks(f), false, "451 never works");
    f = film(); f.finished = true; f.blockedText = true; f.servers = 0;
    eq(NameCheckRules.verdict(f), NameCheckRules.BLOCKED, "451 page without the status");
    f = film(); f.finished = true; f.httpStatus = 451; f.servers = 50;
    eq(NameCheckRules.verdict(f), NameCheckRules.BLOCKED, "451 wins over anything read");
    // moved
    f = film(); f.redirectHost = "moviesjoy.to"; f.httpStatus = 451;
    eq(NameCheckRules.verdict(f), NameCheckRules.REDIRECT, "sent to another name");
    eq(NameCheckRules.clearlyWorks(f), false, "a moved name is not «works»");
    // not found / down / other errors
    f = film(); f.finished = true; f.httpStatus = 404; f.filmLinks = 12;
    eq(NameCheckRules.verdict(f), NameCheckRules.NOT_FOUND, "404 even with menu links");
    f = film(); f.finished = true; f.httpStatus = 410;
    eq(NameCheckRules.verdict(f), NameCheckRules.NOT_FOUND, "410");
    for (int c : new int[]{500, 502, 504, 520, 521, 522, 523, 525, 526, 530}) {
      f = film(); f.finished = true; f.httpStatus = c;
      eq(NameCheckRules.verdict(f), NameCheckRules.DOWN, "down " + c);
      eq(NameCheckRules.finalStatus(c), true, "final " + c);
    }
    f = film(); f.finished = true; f.httpStatus = 503; f.checkPage = true;
    eq(NameCheckRules.verdict(f), NameCheckRules.CHECK_PAGE, "503 check page (Cloudflare)");
    eq(NameCheckRules.finalStatus(503), false, "503 waits: it may be a check page");
    eq(NameCheckRules.finalStatus(403), false, "403 waits: it may be a check page");
    f = film(); f.finished = true; f.httpStatus = 503;
    eq(NameCheckRules.verdict(f), NameCheckRules.DOWN, "503 with no check page");
    f = film(); f.finished = true; f.httpStatus = 403; f.checkPage = true;
    eq(NameCheckRules.verdict(f), NameCheckRules.CHECK_PAGE, "403 check page");
    f = film(); f.finished = true; f.httpStatus = 403;
    eq(NameCheckRules.verdict(f), NameCheckRules.HTTP_ERROR, "403 with no check page");
    f = front(); f.finished = true; f.checkPage = true; f.filmLinks = 0;
    eq(NameCheckRules.verdict(f), NameCheckRules.CHECK_PAGE, "their own «continue» page");
    // no answer
    f = film(); f.netError = -2;
    eq(NameCheckRules.verdict(f), NameCheckRules.NO_ANSWER, "unknown name (DNS)");
    f = film(); f.netError = -8;
    eq(NameCheckRules.verdict(f), NameCheckRules.NO_ANSWER, "timed out");
    f = film();
    eq(NameCheckRules.verdict(f), NameCheckRules.NO_ANSWER, "nothing at all in time");
    eq(NameCheckRules.verdict(null), NameCheckRules.NO_ANSWER, "no facts");
    // opened but no list / not the site
    f = film(); f.finished = true; f.servers = 0; f.filmLinks = 8;
    eq(NameCheckRules.verdict(f), NameCheckRules.NO_SERVERS, "the site, but no server list for this film");
    f = film(); f.finished = true; f.servers = 2; f.filmLinks = 0;
    eq(NameCheckRules.verdict(f), NameCheckRules.NO_SERVERS, "too few servers");
    f = film(); f.finished = true; f.servers = 0; f.filmLinks = 0;
    eq(NameCheckRules.verdict(f), NameCheckRules.NOT_SITE, "parked / ads page");
    f = front(); f.finished = true; f.filmLinks = 0;
    eq(NameCheckRules.verdict(f), NameCheckRules.NOT_SITE, "front page that is not the site");

    // names
    eq(NameCheckRules.cleanHost("HTTPS://WWW.MoviesJoy.SX/watch/movie-x-1"), "moviesjoy.sx", "clean name from a link");
    eq(NameCheckRules.cleanHost("watch32.gd:443"), "watch32.gd", "port dropped");
    eq(NameCheckRules.cleanHost("not a name"), "", "not a name");
    eq(NameCheckRules.cleanHost("localhost"), "", "no dot");
    eq(NameCheckRules.cleanHost(null), "", "null");
    eq(NameCheckRules.sameSite("www.dopebox.sx", "dopebox.sx"), true, "www is the same site");
    eq(NameCheckRules.sameSite("dopebox.sx", "dopebox.sx"), true, "same name");
    eq(NameCheckRules.sameSite("notdopebox.sx", "dopebox.sx"), false, "a longer name is another site");
    eq(NameCheckRules.sameSite("dopebox.to", "dopebox.sx"), false, "another ending is another site");
    // pages
    eq(NameCheckRules.validPath("/"), true, "front page");
    eq(NameCheckRules.validPath("/watch/movie-animals-li2ws86x"), true, "film page");
    eq(NameCheckRules.validPath("/watch/tv-the-bear-x1y2z3"), true, "series page");
    // 322.3.123: Moviesmod family pages (TMDB numbers)
    eq(NameCheckRules.validPath("/movie/27205"), true, "Moviesmod film page");
    eq(NameCheckRules.validPath("/tv/1416"), true, "Moviesmod series page");
    eq(NameCheckRules.validPath("/watch/tv/1416/2/5"), true, "Moviesmod episode page");
    eq(NameCheckRules.validPath("/watch/tv/220542"), true, "Moviesmod series watch page");
    eq(NameCheckRules.validPath("/movie/abc"), false, "Moviesmod pages are numbers");
    eq(NameCheckRules.validPath("/movie/123/x"), false, "nothing after the number");
    eq(NameCheckRules.validPath("/watch/tv/1/2"), false, "an episode has season and episode");
    eq(NameCheckRules.validPath("/watch/movie-a?x=1"), false, "no query");
    eq(NameCheckRules.validPath("/search/x"), false, "only the two kinds");
    eq(NameCheckRules.validPath("//evil.com"), false, "never another site");
    eq(NameCheckRules.validPath(null), false, "null path");
    // Cloudflare trace (the very lines seen on 22 Oct 2026)
    String t = "fl=736f185\nh=putlockerz.gd\nip=2a02:cb80:4286:9a1e:5484:4489:6b96:9616\nts=1792695437.000\n"
        + "visit_scheme=https\nuag=Mozilla/5.0 (Linux; Android 10; K)\ncolo=LHR\nsliver=010-tier1\nhttp=http/3\nloc=SA\n";
    eq(NameCheckRules.traceValue(t, "colo"), "LHR", "colo");
    eq(NameCheckRules.traceValue(t, "loc"), "SA", "loc");
    eq(NameCheckRules.traceValue(t, "ip").contains(":"), true, "ip is IPv6");
    eq(NameCheckRules.traceValue(t, "uag"), "", "odd value is not passed on");
    eq(NameCheckRules.traceValue("<html>Error 451</html>", "colo"), "", "no trace text");
    eq(NameCheckRules.ukColo("LHR"), true, "London");
    eq(NameCheckRules.ukColo("man"), true, "Manchester");
    eq(NameCheckRules.ukColo("EDI"), true, "Edinburgh");
    eq(NameCheckRules.ukColo("RUH"), false, "Riyadh");
    eq(NameCheckRules.ukColo(""), false, "unknown");
    System.out.println("PASS: name check verdicts (works / blocked 451 / moved / not found / down / check page / no answer / no servers / not the site), names, pages, Cloudflare trace: " + n + " checks");
  }
}
