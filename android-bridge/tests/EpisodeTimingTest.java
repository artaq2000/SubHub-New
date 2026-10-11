import com.artaq.subhub.EpisodeTiming;
public class EpisodeTimingTest {
 static void eq(long got, long want, String what){ if(got!=want) throw new AssertionError(what+": got "+got+" want "+want); }
 public static void main(String[] args) {
  long d=2580000L; // 43:00
  eq(EpisodeTiming.creditsAtMs(d, 2400000L), 2410000L, "10 s after the last line");
  eq(EpisodeTiming.creditsAtMs(d, -1L), 2540000L, "no subtitle: 40 s before the end");
  eq(EpisodeTiming.creditsAtMs(d, 1800000L), 2540000L, "last line too early (before 80%): ignored");
  eq(EpisodeTiming.creditsAtMs(d, 2576000L), 2577000L, "last line near the end: right after it");
  eq(EpisodeTiming.creditsAtMs(d, 2570000L), 2571000L, "near the end: right after the line (10 s countdown)");
  eq(EpisodeTiming.creditsAtMs(d, 2550000L), 2560000L, "10 s after, still before the latest (end - 11 s)");
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
  // 322.3.114: the translator's credit and a lone last line are not dialogue
  double[] st={100,200,2390,2570}, en={102,203,2400,2575}; String[] tx={"a","b","آخر كلام","ترجمة: أحمد"};
  eq(EpisodeTiming.lastDialogueEndMs(st,en,tx,0,d), 2400000L, "translator credit skipped");
  String[] tx2={"a","b","آخر كلام","Subtitles by @someone"};
  eq(EpisodeTiming.lastDialogueEndMs(st,en,tx2,0,d), 2400000L, "@ credit skipped");
  String[] tx3={"a","b","آخر كلام","مع السلامة"};
  eq(EpisodeTiming.lastDialogueEndMs(st,en,tx3,0,d), 2400000L, "lone line after 170 s of silence in the last 2 min skipped");
  double[] st4={100,200,2300,2420}, en4={102,203,2310,2425};
  eq(EpisodeTiming.lastDialogueEndMs(st4,en4,tx3,0,d), 2425000L, "a late line before the last 2 minutes is dialogue");
  double[] st5={100,200,2390,2420}, en5={102,203,2400,2425};
  eq(EpisodeTiming.lastDialogueEndMs(st5,en5,tx3,0,d), 2425000L, "a short silence is not a credit");
  eq(EpisodeTiming.lastDialogueEndMs(st,en,tx,2000,d), 2402000L, "subtitle offset moves the time");
  eq(EpisodeTiming.lastDialogueEndMs(new double[0],new double[0],new String[0],0,d), -1L, "no subtitle");
  String[] tx6={"ترجمة","ترجمة","ترجمة","ترجمة"};
  eq(EpisodeTiming.lastDialogueEndMs(st,en,tx6,0,d), -1L, "only credits");
  System.out.println("PASS: credits time (last line / no subtitle / early line / near the end / short video), smart waiting per step and usual time, translator credit / lone last line skipped");
 }
}
