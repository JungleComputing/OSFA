import os
import sys
import traceback
import benchmark
from saga import filesystem, url

class SagaFileBenchmark:
  def __init__(self, basedir_url):
    self.basedir_url = url.URL.create(basedir_url)
    print self.basedir_url.get_string()

  def run(self):
    try:
      # sanity check: is the base directory empty? If not, bail out
      basedir = filesystem.Directory.create(self.basedir_url)
      if (basedir.get_num_entries() != 0):
        s = basedir.get_url().get_string()
        raise Exception("basedir %s is not empty!" % s);
      # create a big file '/foo'
      foo_url = url.URL.create("foo")
      foo = basedir.open(foo_url, filesystem.Flags.CREATE)
      write_buf = ""
      for i in range(0, benchmark.WRITE_BUF_SIZE):
        write_buf += "0"
      written = 0
      while (written < benchmark.BIG_FILE_SIZE):
        foo.write(write_buf)
        written += benchmark.WRITE_BUF_SIZE
      foo.close()
      # copy '/foo' to '/bar'
      bar_url = url.URL.create("bar")
      basedir.copy(foo_url, bar_url)
      # read '/bar'
      bar = basedir.open(bar_url, filesystem.Flags.READ)
      read = 1
      while (read > 0):
        data = bar.read(benchmark.READ_BUF_SIZE)
        read = len(data)
      bar.close();
      # delete all files and directories
      basedir.remove("*", filesystem.Flags.RECURSIVE)
      basedir.close()
    except Exception, e:
      traceback.print_exc()
  def close(self):
    pass

if __name__ == "__main__":
  if (len(sys.argv) != 3):
    print "usage: jysaga %s <basedir-url> <#runs>" % sys.argv[0]
    os._exit(1)
  basedir_url = sys.argv[1]
  runs = int(sys.argv[2])
  b = SagaFileBenchmark(basedir_url)
  runner = benchmark.BenchmarkRunner(b, runs)
  runner.run()
