import org.ogf.saga.*;
import org.ogf.saga.error.*;
import org.ogf.saga.url.*;
import org.ogf.saga.namespace.*;
import org.ogf.saga.session.*;

public class DirCreate {
  public static void main(String[] args) throws Exception {
    long startTimeMs, endTimeMs, taskTimeMs;
    Session s = SessionFactory.createSession(true);
    URL ubase = URLFactory.createURL("local://localhost/tmp/basedir");
    NSDirectory nsdbase = NSFactory.createNSDirectory(s, ubase, Flags.NONE.getValue());

    for(int j = 0; j<50; j++) {
      startTimeMs = System.currentTimeMillis();
      for(int i = 0; i<100; i++) {
        URL durl = URLFactory.createURL("dir1");
        nsdbase.makeDir(durl);
        nsdbase.remove(durl, Flags.RECURSIVE.getValue());
      }
      endTimeMs = System.currentTimeMillis() - startTimeMs;
      System.out.println("It took: " + endTimeMs + " milli");
    }
  }
}
