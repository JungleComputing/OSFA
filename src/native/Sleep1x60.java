import org.ogf.saga.*;
import org.ogf.saga.context.*;
import org.ogf.saga.error.*;
import org.ogf.saga.file.*;
import org.ogf.saga.job.*;
import org.ogf.saga.monitoring.*;
import org.ogf.saga.namespace.*;
import org.ogf.saga.session.*;
import org.ogf.saga.task.*;
import org.ogf.saga.url.*;

public class Sleep1x60 {
  public static void main(String[] args) throws Exception {
    long startTimeMs = System.currentTimeMillis();
    JobDescription jd = JobFactory.createJobDescription();
    jd.setAttribute("Executable", "/bin/sleep");
    jd.setVectorAttribute("Arguments", new String []{"60"});
    JobService js = JobFactory.createJobService();
    Job j = js.createJob(jd);
    j.run();
    j.waitFor();
    long taskTimeMs = System.currentTimeMillis() - startTimeMs;
    System.out.println(taskTimeMs + " ms");
  }
}
