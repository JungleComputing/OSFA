import org.ogf.saga.*;
import org.ogf.saga.buffer.*;
import org.ogf.saga.context.*;
import org.ogf.saga.error.*;
import org.ogf.saga.file.*;
import org.ogf.saga.job.*;
import org.ogf.saga.monitoring.*;
import org.ogf.saga.namespace.*;
import org.ogf.saga.session.*;
import org.ogf.saga.task.*;
import org.ogf.saga.url.*;

public class File1 {
  public static void main(String[] args) throws Exception {
    final int write_buf_size = 1024 * 32;
    final int read_buf_size = 1024 * 32;
    final int big_file_size = 1024 * 1024 * 100;
    for(int i=1; i<=5; i++) {
      long startTimeMs = System.currentTimeMillis();
      URL base = URLFactory.createURL("basedir");
      Directory basedir = FileFactory.createDirectory(base);
      URL foo_url = URLFactory.createURL("foo");
      File foo = basedir.openFile(foo_url, Flags.CREATE.getValue());
      Buffer b = BufferFactory.createBuffer(write_buf_size);
      String s = "";
      for(int j=0; j<write_buf_size; j++) {
        s += "a";
      }
      b.setData(s.getBytes());
      int written = 0;
      while(written < big_file_size) {
        written += foo.write(b);
      }
      foo.close();

      URL bar_url = URLFactory.createURL("bar");
      basedir.copy(foo_url, bar_url);
      File bar = basedir.openFile(bar_url, Flags.READ.getValue());
      Buffer b2 = BufferFactory.createBuffer(read_buf_size);
      int read = 1;
      while(read > 0) {
        read = bar.read(b2, read_buf_size);
      }
      bar.close();
      basedir.remove("*", Flags.RECURSIVE.getValue());
      basedir.close();
      long taskTimeMs = System.currentTimeMillis() - startTimeMs;
      System.out.println("Run  " + i + ": " + taskTimeMs + " ms");
    }
  }
}
