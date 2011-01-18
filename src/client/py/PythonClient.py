#!/usr/bin/python

import os.path
import sys
import unittest

# thrift imports
sys.path.append('../../../../thrift/lib/py/build/lib.macosx-10.6-x86_64-2.6/')
from thrift import Thrift
from thrift.transport import TSocket
from thrift.transport import TTransport
from thrift.protocol import TBinaryProtocol
from thrift.protocol import TCompactProtocol

# SAGA OSfA imports
sys.path.append('../../thrift/gen-py')
from org.ogf.saga.thrift import SAGAService
from org.ogf.saga.thrift.ttypes import *
from org.ogf.saga.thrift.constants import *

clnt = None
transport = None
num = 0

class OsfaTestCase(unittest.TestCase):
  """BaseClass for Osfa unittests.

  """
  def setUp(self):
    self.objs = []
    self.temp_files = []

  def tearDown(self):
    for obj in self.objs:
      clnt.free(obj)
    while self.temp_files:
      try:
        os.unlink(self.temp_files.pop())
      except OSError, err:
        if err.errno not in (errno.ENOENT, errno.EISDIR, 0):
          raise



class TestJob(OsfaTestCase):
  def test(self):
    sessionid = clnt.SessionCreate(True)
    urlid = clnt.URLCreate("any://localhost")
    jdid = clnt.JobDescriptionCreate()
    jsid = clnt.JobServiceCreate(sessionid, urlid)
    clnt.JobDescriptionSetAttribute(jdid, "Executable", "/bin/ls")
    clnt.JobDescriptionSetVectorAttribute(jdid, "Arguments", ["-l", "-a", "/tmp/"])
    outfile = "ls%d.out" % num
    clnt.JobDescriptionSetAttribute(jdid, "Output", outfile)
    clnt.JobDescriptionSetVectorAttribute(jdid, "FileTransfer",
                                          ["src/client/py/%s < %s" % (outfile, outfile)])
    jid = clnt.JobServiceCreateJob(jsid, jdid)
    expected = set(['ServiceURL', 'WorkingDirectory', 'Created', 'Started',
                    'ExitCode', 'JobID', 'Termsig', 'Finished',
                    'ExecutionHosts'])
    self.assert_(expected <= set(clnt.JobListAttributes(jid)))
    clnt.JobRun(jid)
    self.assertRaises(TNoSuccessException, clnt.JobGetResult, jid)
    self.objs.extend([sessionid, urlid, jdid, jsid, jid])
    self.assert_(os.path.isfile(outfile))
    #self.temp_files.append(outfile)


class TestURL(OsfaTestCase):
  def test(self):
    url1 = 'file://localhost/tmp/test.txt'
    urlid = clnt.URLCreate(url1)
    for i in range(100):
      url2 = 'file://localhost%d/tmp/test%d.txt' % (num, i)
      urlid2 = clnt.URLCreate(url2)
      self.assertEqual(clnt.URLGetHost(urlid2), 'localhost%d' % num)
      self.assertEqual(clnt.URLGetPath(urlid2), '/tmp/test%d.txt' % i)
    badurl = 'file://asdfljajks32j34klj2@#$@$#$@/tmp/test2.txt'
    self.assertRaises(TBadParameterException, clnt.URLCreate, badurl)
    clnt.URLSetHost(urlid, 'rosie.koltern.com')
    self.assertEqual(clnt.URLGetHost(urlid), 'rosie.koltern.com')
    urlid2 = clnt.URLTranslate(urlid, 'ftp')
    self.assertEqual(clnt.URLGetScheme(urlid2), 'ftp')
    self.objs.extend([urlid, urlid2])


def connect():
  global clnt, transport
  # Make socket
  transport = TSocket.TSocket('localhost', 9090)
  # Buffering is critical. Raw sockets are very slow
  transport = TTransport.TBufferedTransport(transport)
  # Wrap in a protocol
  protocol = TBinaryProtocol.TBinaryProtocol(transport)
  # Create a clnt to use the protocol encoder
  clnt = SAGAService.Client(protocol)
  try:
    transport.open()
  except TTransport.TTransportException, e:
    print "Problem connecting to OSfA server: %s. Exiting." % e
    sys.exit(1)

def runAll():
  testsuites = []
  testsuites.append(unittest.TestLoader().loadTestsFromTestCase(TestJob))
  testsuites.append(unittest.TestLoader().loadTestsFromTestCase(TestURL))
  suite = unittest.TestSuite(testsuites)
  unittest.TextTestRunner(verbosity=2).run(suite)

def testOsfa():
  global num
  disconnect = True
  try:
    num = int(sys.argv[1])
    disconnect = bool(num)
    sys.argv = sys.argv[1:]
  except (IndexError, ValueError):
    pass
  connect()
  try:
    clnt.free(clnt.URLCreate("file://localhost/tmp/file%d.txt" % num))
  except InvalidRequestException:
    pass
  else:
    print "Auth not working"
  clnt.login("admin", "password")
  runAll()
  print "%d objs were left, now all are free'd" % clnt.freeAll()
  if disconnect:
    print "Closing..."
    transport.close()

if __name__ == "__main__":
  testOsfa()
