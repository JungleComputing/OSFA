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
from org.ogf.saga.thrift.ttypes import TFlags

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

bufsize = 1024 * 32
big_file_size = 1024 * 1024 * 100
write_buf = "0" * bufsize

def foo():
  basedir="local://localhost/tmp/basedir"
  s = clnt.SessionCreate(True)
  ubase = clnt.URLCreate(basedir)
  dbase = clnt.DirectoryCreate(s, ubase, TFlags.READ)
  ufoo = clnt.URLCreate("foo")
  f = clnt.DirectoryOpenFile(dbase, ufoo, TFlags.CREATE)
  b = clnt.BufferCreate(bufsize)
  clnt.BufferSetData(b, write_buf)
  written = 0
  while written < big_file_size:
    written += clnt.FileWrite(f, b)
  clnt.FileClose(f)

  ubar = clnt.URLCreate("bar")
  clnt.DirectoryCopy(dbase, ufoo, ubar, TFlags.NONE)
  fbar = clnt.DirectoryOpenFile(dbase, ubar, TFlags.READ)
  bbar = clnt.BufferCreate(bufsize)
  read = 1
  while read > 0:
    read = clnt.FileRead(fbar, bbar)
  clnt.FileClose(fbar)

  clnt.DirectoryRemove2(dbase, "*", TFlags.RECURSIVE)
  clnt.DirectoryClose(dbase)
  clnt.freeAll()

for i, t in enumerate(sorted(timeit.repeat(foo, repeat=5, number=1))):
  print "Run %d: %.2f sec" % (i+1, t)

transport.close()
