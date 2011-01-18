import os
import sys
import traceback
import benchmark
from saga import filesystem, namespace, url


class SagaNamespaceBenchmark:
  def __init__(self, basedir_url):
    self.basedir_url = url.URL.create(basedir_url)

  def run(self):
    try:
      # sanity check: is the base directory empty? If not, bail out
      basedir = namespace.NSDirectory.create(self.basedir_url)
      if (basedir.get_num_entries() != 0):
        s = basedir.get_url().get_string()
        raise Exception("basedir %s is not empty!" % s);
      # create DIR_COUNT directories ('/dir000' to '/dirXXX')
      for i in range(0, benchmark.DIR_COUNT):
        dir_url = url.URL.create("dir%03d" % i)
        basedir.make_dir(dir_url)
      # create SUBDIR_COUNT subdirs in each directory
      # ('/dir000/subdir000' to '/dirXXX/subdirYYY')
      for entry in basedir.list():
        d = basedir.open_dir(entry)
        for j in range(0, benchmark.SUBDIR_COUNT):
          subdir_url = url.URL.create("subdir%03d" % j)
          d.make_dir(subdir_url)
        d.close()
      # in each subdirectory, create FILE_COUNT text files ('file00' to
      # 'fileZZZ'). The contents of each file is its own filename
      for entry in basedir.list():
        d = basedir.open_dir(entry)
        for subentry in d.list():
          subdir = d.open_dir(subentry)
          for i in range(0, benchmark.FILE_COUNT):
            file_url = url.URL.create("file%03d" % i)
            f = subdir.open(file_url, namespace.Flags.CREATE)
            f.close()
          subdir.close()
        d.close()
      # print the type (file or directory) and size (in bytes) of all
      # entries in the volume
      fs_basedir = filesystem.Directory.create(self.basedir_url)
      self.list_dir(fs_basedir)
      fs_basedir.close()
      # move all directories 'dirXXX' to 'dXXX'
      for i in range(0, benchmark.DIR_COUNT):
        from_url = url.URL.create("dir%03d" % i)
        to_url = url.URL.create("d%03d" % i)
        basedir.move(from_url, to_url, namespace.Flags.RECURSIVE)
      # copy all files 'fileXXX' to 'fXXX'
      for entry in basedir.list():
        d = basedir.open_dir(entry)
        for subentry in d.list():
          subdir = d.open_dir(subentry)
          subdir_url = subdir.get_url()
          for name_url in subdir.list():
            new_name = name_url.get_string().replace("file", "f", 1)
            new_name_url = url.URL.create(new_name.encode())
            subdir.copy(name_url, new_name_url)
          subdir.close()
        d.close()
      # delete all files and directories
      for entry in basedir.list():
        basedir.remove(entry, filesystem.Flags.RECURSIVE)
      basedir.close()
    except Exception, e:
      print "EEK: %s" % e

  def list_dir(self, d):
    for entry in d.list():
      if (d.is_entry(entry)):
        size = d.get_size(entry)
        msg = "- %8d %s/%s" % (size, d.get_url(), entry)
      else:
        msg = "d          %s/%s" % (d.get_url(), entry)
        subdir = d.open_dir(entry)
        self.list_dir(subdir)
        subdir.close()

  def close(self):
    pass

if __name__ == "__main__":
  if (len(sys.argv) != 3):
    print "usage: jysaga %s <basedir-url> <#runs>" % sys.argv[0]
    os._exit(1)

  basedir_url = sys.argv[1]
  runs = int(sys.argv[2])

  b = SagaNamespaceBenchmark(basedir_url)
  runner = benchmark.BenchmarkRunner(b, runs)
  runner.run()

