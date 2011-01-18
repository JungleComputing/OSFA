import math
import sys
import time

BIG_FILE_SIZE = 1024 * 1024 * 100  # bytes, should be multiple of WRITE_BUF_SIZE
WRITE_BUF_SIZE = 1024 * 32         # bytes
READ_BUF_SIZE = 1024 * 32          # bytes
DIR_COUNT = 10
SUBDIR_COUNT = 10
FILE_COUNT = 10

INFO = False
DEBUG = False

def info(msg):
  if (INFO):
    print msg

def debug(msg):
  if (DEBUG):
    print msg

class BenchmarkRunner:
  def __init__(self, test, runs):
    self.test = test
    self.runs = runs

  def run(self):
    total_time = 0
    min_time = 10000
    max_time = 0
    times = []

    # run tests
    for run in range(self.runs):
      print "Run %2d:" % (run + 1),
      start = time.clock()
      self.test.run()
      sec = time.clock() - start
      print "%2.2f sec" % sec
      if (sec < min_time): min_time = sec
      if (sec > max_time): max_time = sec
      total_time += sec
      times.append(sec)

    # determine the median running time
    times.sort()
    if (self.runs % 2 == 0):
      left_middle = int(math.floor((self.runs / 2) - 1))
      right_middle = int(math.ceil(self.runs / 2))
      median_time = (times[left_middle] + times[right_middle]) / 2
    else:
      median_time = times[self.runs / 2]

    # print results
    print
    print "Results:"
    print "- min:    %.2f sec" % min_time
    print "- max:    %.2f sec" % max_time
    print "- median: %.2f sec" % median_time
    print "- avg:    %.2f sec" % (total_time / self.runs)
    print
    self.test.close()
