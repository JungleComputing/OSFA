#!/usr/bin/python

import time
import sys
sys.path.append("/Users/livewire/PDCS/Thesis/thrift/lib/py/build/lib.macosx-10.6-x86_64-2.6/")
from thrift import Thrift
from thrift.transport import TSocket
from thrift.transport import TTransport
from thrift.protocol import TBinaryProtocol
sys.path.append("/Users/livewire/PDCS/Thesis/onesagaforall/src/thrift/gen-py")
from org.ogf.saga.thrift import SAGAService
from org.ogf.saga.thrift.ttypes import TFlags
from org.ogf.saga.thrift.ttypes import TTaskMode

transport = TSocket.TSocket("localhost", 9090)
transport = TTransport.TBufferedTransport(transport)
protocol = TBinaryProtocol.TBinaryProtocol(transport)
clnt = SAGAService.Client(protocol)
try:
    transport.open()
except TTransport.TTransportException, e:
    print "Problem connecting to OSfA server: %s. Exiting." % e
    sys.exit(1)

clnt.login("admin", "password")
host = sys.argv[1]
u = clnt.URLCreate("file://%s/tmp/test.txt" % host)
assert(host == clnt.URLGetHost(u))
time.sleep(float("0.%d" % u))
print "%d: %s" % (u, host)
clnt.freeAll()

