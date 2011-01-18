import os
import time
import random
import sys
import traceback

from saga import job, url

import org.ogf.saga.session

import benchmark

class SagaJobBenchmark:

  def __init__(self, js_url, times, ex, args):
    self.js = job.JobService.create(url.URL.create(js_url))

    self.times = times

    self.jd = job.JobDescription.create()
    self.jd.set_attribute("Executable", ex)
    if (args != None):
      self.jd.set_vector_attribute("Arguments", args)

    benchmark.info("Job to run: %s %s" % (ex, args))

  def run(self):
    try:
      for i in range(0, self.times):
        job = self.js.create_job(self.jd)
        job.run()
        job.wait()
    except Exception, e:
      if (benchmark.DEBUG):
        traceback.print_exc()
      else:
        print "EEK: %s" % e

  def close(self):
    benchmark.info("Cleaning up")
    #defaultSession = org.ogf.saga.session.SessionFactory.createSession(True)
    #defaultSession.close()

if __name__ == "__main__":
  if (len(sys.argv) < 4):
    print "usage: jysaga %s <jobservice-url> <#runs> <#times> <executable> [arg]*" \
      % sys.argv[0]
    os._exit(1)

  js_url = sys.argv[1]
  runs = int(sys.argv[2])
  times = int(sys.argv[3])
  ex = sys.argv[4]
  if (len(sys.argv) > 5):
    args = sys.argv[5:]

  b = SagaJobBenchmark(js_url, times, ex, args)
  runner = benchmark.BenchmarkRunner(b, runs)
  runner.run()

