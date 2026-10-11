import com.artaq.subhub.EpisodeTiming;
public class EpisodeTimingTest {
 static void eq(long got, long want, String what){ if(got!=want) throw new AssertionError(what+": got "+got+" want "+want); }
 public static void main(String[] args) {
  long d=2580000L; // 43:00
  eq(EpisodeTiming.creditsAtMs(d, 2400000L), 2410000L, "10 s after the last line");
  eq(EpisodeTiming.creditsAtMs(d, -1L), 2540000L, "no subtitle: 40 s before the end");
  eq(EpisodeTiming.creditsAtMs(d, 1800000L), 2540000L, "last line too early (before 80%): ignored");
  eq(EpisodeTiming.creditsAtMs(d, 2576000L), 2577000L, "last line near the end: right after it");
  eq(EpisodeTiming.creditsAtMs(d, 2570000L), 2574000L, "near the end: the countdown fits before the end");
  eq(EpisodeTiming.creditsAtMs(d, 2600000L), 2540000L, "a line after the end (offset): ignored");
  eq(EpisodeTiming.creditsAtMs(120000L, -1L), 120000L, "short video: only its end");
  if(EpisodeTiming.creditsAtMs(0L, -1L)!=Long.MAX_VALUE) throw new AssertionError("unknown duration");
  eq(EpisodeTiming.autoPhaseLimitMs(1, 0), 10000L, "page");
  eq(EpisodeTiming.autoPhaseLimitMs(2, 0), 10000L, "page, choosing");
  eq(EpisodeTiming.autoPhaseLimitMs(3, 0), 12000L, "server");
  eq(EpisodeTiming.autoPhaseLimitMs(4, 0), 20000L, "video link");
  eq(EpisodeTiming.autoPhaseLimitMs(5, 0), 15000L, "found");
  eq(EpisodeTiming.autoPhaseLimitMs(6, 0), 15000L, "starting");
  eq(EpisodeTiming.autoPhaseLimitMs(4, 12000L), 24000L, "a 12 s server gets 24 s");
  eq(EpisodeTiming.autoPhaseLimitMs(4, 5000L), 20000L, "a fast server keeps the normal limit");
  eq(EpisodeTiming.autoPhaseLimitMs(6, 60000L), 30000L, "never more than 30 s");
  System.out.println("PASS: credits time (last line / no subtitle / early line / near the end / short video), smart waiting per step and usual time");
 }
}
