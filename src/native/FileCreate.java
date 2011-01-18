import org.ogf.saga.*;
import org.ogf.saga.error.*;
import org.ogf.saga.url.*;
import org.ogf.saga.namespace.*;
import org.ogf.saga.session.*;

public class FileCreate {
  public static void main(String[] args) throws Exception {
    long startTimeMs, endTimeMs, taskTimeMs;
    Session s = SessionFactory.createSession(true);
    URL ubase = URLFactory.createURL("local://localhost/tmp/basedir");
    NSDirectory nsdbase = NSFactory.createNSDirectory(s, ubase, Flags.NONE.getValue());

    for(int j = 0; j<50; j++) {
      startTimeMs = System.currentTimeMillis();
      for(int i = 0; i<1000; i++) {
        URL furl = URLFactory.createURL("file.txt");
        NSEntry nse = nsdbase.open(furl, Flags.CREATE.getValue());
        nse.remove();
      }

      endTimeMs = System.currentTimeMillis() - startTimeMs;
      System.out.println("It took: " + endTimeMs + " milli");
    }
  }
}
