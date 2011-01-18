#!/usr/bin/python

import os
import timeit
import sys
sys.path.append("../../../../thrift/lib/py/build/lib.macosx-10.6-x86_64-2.6/")
from thrift import Thrift
from thrift.transport import TSocket
from thrift.transport import TTransport
from thrift.protocol import TBinaryProtocol
sys.path.append("../../thrift/gen-py")
from org.ogf.saga.thrift import SAGAService
from org.ogf.saga.thrift.ttypes import TFlags
from org.ogf.saga.thrift.ttypes import TTaskMode

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

s = clnt.SessionCreate(True)
ubase = clnt.URLCreate("local://localhost/tmp/basedir")
nsdbase = clnt.NSDirectoryCreate(s, ubase, TFlags.NONE)

def foo():
  # create dir
  durl = clnt.URLCreate("dir1")
  clnt.NSDirectoryMakeDir(nsdbase, durl)
  clnt.NSDirectoryRemove(nsdbase, durl, TFlags.RECURSIVE)
  clnt.free(durl)

for i, t in enumerate(sorted(timeit.repeat(foo, repeat=5, number=100))):
  print "Run %d: %.2f milli" % (i+1, t * 1000)
