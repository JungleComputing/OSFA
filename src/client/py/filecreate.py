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
  # create file
  furl = clnt.URLCreate("file.txt")
  f = clnt.NSDirectoryOpen(nsdbase, furl, TFlags.CREATE)
  clnt.NSEntryRemove(f, TFlags.NONE)
  clnt.free(furl)
  clnt.free(f)


for i, t in enumerate(sorted(timeit.repeat(foo, repeat=50, number=1000))):
  print "Run %d: %.2f milli" % (i+1, t * 1000)

clnt.freeAll()
