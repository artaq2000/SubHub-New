import com.artaq.subhub.UpdatePolicy;
public class UpdatePolicyTest {
 public static void main(String[] args) {
  long t=100000000L;
  if(!UpdatePolicy.isDue(t,0)) throw new AssertionError("first launch");
  if(UpdatePolicy.isDue(t+1,t)) throw new AssertionError("resume");
  if(UpdatePolicy.isDue(t+86399999,t)) throw new AssertionError("before 24h");
  if(!UpdatePolicy.isDue(t+86400000,t)) throw new AssertionError("after 24h");
  if(!UpdatePolicy.isDue(t-1,t)) throw new AssertionError("clock rollback recovery");
  System.out.println("PASS: initial check, repeated opens, 24-hour boundary, clock rollback");
 }
}
