#!/usr/bin/python

import timeit
import sys
#sys.path.append("../../../../thrift/lib/py/build/lib.macosx-10.6-x86_64-2.6/")
sys.path.append("../../../../thrift/lib/py/build/lib.linux-i686-2.6")
from thrift import Thrift
from thrift.transport import TSocket
from thrift.transport import TTransport
from thrift.protocol import TBinaryProtocol
sys.path.append("../../thrift/gen-py")
from org.ogf.saga.thrift import SAGAService
from org.ogf.saga.thrift.constants import TJobDescription

if len(sys.argv) == 2:
  host = sys.argv[1]
else:
  host = "localhost"
transport = TSocket.TSocket(host, 9090)
transport = TTransport.TBufferedTransport(transport)
protocol = TBinaryProtocol.TBinaryProtocol(transport)
clnt = SAGAService.Client(protocol)
try:
  transport.open()
except TTransport.TTransportException, e:
  print "Problem connecting to OSfA server: %s. Exiting." % e
  sys.exit(1)

clnt.login("admin", "password")


def run_job(js, jd):
  j = clnt.JobServiceCreateJob(js, jd)
  clnt.JobRun(j)
  clnt.JobWaitFor(j)

def foo():
  jd = clnt.JobDescriptionCreate()
  clnt.JobDescriptionSetAttribute(jd, TJobDescription["EXECUTABLE"], "/bin/sleep")
  clnt.JobDescriptionSetVectorAttribute(jd, TJobDescription["ARGUMENTS"], ["1"])
  js = clnt.JobServiceCreateDefault()
  for _ in range(2):
    run_job(js, jd)
  clnt.freeAll()

for i, t in enumerate(timeit.repeat(foo, repeat=5, number=1)):
  print "Run %d: %.2f sec" % (i+1, t)

