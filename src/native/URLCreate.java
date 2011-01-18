import org.ogf.saga.*;
import org.ogf.saga.error.*;
import org.ogf.saga.url.*;

public class URLCreate {
  public static void main(String[] args) throws Exception {
    long startTimeMs, endTimeMs, taskTimeMs;
    startTimeMs = System.nanoTime();
    URL foo_url = URLFactory.createURL("file://localhost/tmp/foo");
    endTimeMs = System.nanoTime();
    taskTimeMs = endTimeMs - startTimeMs;
    System.out.println("It took: " + taskTimeMs + " ms");
    startTimeMs = System.nanoTime();
    foo_url = URLFactory.createURL("file://localhost/tmp/foo");
    endTimeMs = System.nanoTime();
    taskTimeMs = endTimeMs - startTimeMs;
    System.out.println("It took: " + taskTimeMs + " ms");
  }
}
