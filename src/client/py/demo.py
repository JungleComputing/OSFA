#!/usr/bin/python

import random
import string
import sys

# thrift imports
sys.path.append('../../../../thrift/lib/py/build/lib.macosx-10.6-x86_64-2.6/')
from thrift import Thrift
from thrift.transport import TSocket
from thrift.transport import TTransport
from thrift.protocol import TBinaryProtocol

# SAGA OSfA imports
sys.path.append('../../thrift/gen-py')
from org.ogf.saga.thrift import SAGAService
from org.ogf.saga.thrift.ttypes import *
from org.ogf.saga.thrift.constants import *

transport = TSocket.TSocket('localhost', 9090)
transport = TTransport.TBufferedTransport(transport)
protocol = TBinaryProtocol.TBinaryProtocol(transport)
client = SAGAService.Client(protocol)

try:
  transport.open()
except TTransport.TTransportException, e:
  print "Problem connecting to OSfA server: %s. Exiting." % e
  sys.exit(1)

client.login("admin", "password")

bufsize = 512

u = client.URLCreate("file://localhost/tmp/demofile.txt")
s = client.SessionCreate(True)
f = client.FileCreate(s, u, TFlags.WRITE|TFlags.CREATE|TFlags.TRUNCATE)
b = client.BufferCreate(bufsize)

data = "One SAGA for All presentation, 13.12.2010\n"

written = 0
while written < len(data):
  client.BufferSetData(b, data)
  written += client.FileWrite(f, b)

path = client.URLGetPath(u)
print "Succssfully written %d bytes in file '%s'" % (written, path)

client.freeAll()
transport.close()
