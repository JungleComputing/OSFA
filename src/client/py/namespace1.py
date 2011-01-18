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
dir_count = 10
subdir_count = 10
file_count = 10

def list_dir(clnt, d):
  for entry in clnt.DirectoryList(d, "."):
    if clnt.DirectoryIsEntry(d, entry):
      clnt.DirectoryGetSize(d, entry)
      #clnt.free(clnt.DirectoryGetURL(d))
    else:
      #clnt.free(clnt.DirectoryGetURL(d))
      subdir = clnt.DirectoryOpenDirectory(d, entry, TFlags.READ)
      list_dir(clnt, subdir)
      clnt.DirectoryClose(subdir)
    clnt.free(entry)

def foo():
  s = clnt.SessionCreate(True)
  ubase = clnt.URLCreate("local://localhost/tmp/basedir")
  nsdbase = clnt.NSDirectoryCreate(s, ubase, TFlags.NONE)
  # create dirs
  for i in range(0, dir_count):
    durl = clnt.URLCreate("dir%03d" % i)
    clnt.NSDirectoryMakeDir(nsdbase, durl)
    clnt.free(durl)
  # create subdirs
  for uentry in clnt.NSDirectoryList(nsdbase, "."):
    d = clnt.NSDirectoryOpenDir(nsdbase, uentry, TFlags.READ)
    for j in range(0, subdir_count):
      subdurl = clnt.URLCreate("subdir%03d" % j)
      clnt.NSDirectoryMakeDir(d, subdurl)
      clnt.free(subdurl)
    clnt.NSDirectoryClose(d)
  # create files
  for uentry in clnt.NSDirectoryList(nsdbase, "."):
    d = clnt.NSDirectoryOpenDir(nsdbase, uentry, TFlags.READ)
    for usubentry in clnt.NSDirectoryList(d, "."):
      subdir = clnt.NSDirectoryOpenDir(d, usubentry, TFlags.READ)
      for i in range(0, file_count):
        furl = clnt.URLCreate("file%03d" % i)
        f = clnt.NSDirectoryOpen(subdir, furl, TFlags.CREATE)
        clnt.NSEntryClose(f)
        clnt.free(furl)
      clnt.NSDirectoryClose(subdir)
      clnt.free(usubentry)
    clnt.NSDirectoryClose(d)
    clnt.free(uentry)
  # list dir
  d = clnt.DirectoryCreate(s, ubase, TFlags.READ)
  list_dir(clnt, d)
  clnt.DirectoryClose(d)
  # move all directories 'dirXXX' to 'dXXX'
  for i in range(0, dir_count):
    from_url = clnt.URLCreate("dir%03d" % i)
    to_url = clnt.URLCreate("d%03d" % i)
    clnt.NSDirectoryMove(nsdbase, from_url, to_url, TFlags.RECURSIVE)
    clnt.free(from_url)
    clnt.free(to_url)
  # copy all files 'fileXXX' to 'fXXX'
  for uentry in clnt.NSDirectoryList(nsdbase, "."):
    d = clnt.NSDirectoryOpenDir(nsdbase, uentry, TFlags.READ)
    for usubentry in clnt.NSDirectoryList(d, "."):
      subdir = clnt.NSDirectoryOpenDir(d, usubentry, TFlags.READ)
      clnt.free(clnt.NSDirectoryGetURL(subdir))
      for name_url in clnt.NSDirectoryList(subdir, "."):
        new_name = clnt.URLGetString(name_url).replace("file", "f", 1)
        new_name_url = clnt.URLCreate(new_name.encode())
        clnt.NSDirectoryCopy(subdir, name_url, new_name_url, TFlags.NONE)
        clnt.free(new_name_url)
        clnt.free(name_url)
      clnt.NSDirectoryClose(subdir)
      clnt.free(usubentry)
    clnt.NSDirectoryClose(d)
    clnt.free(uentry)
  # delete all files and directories
  for uentry in clnt.NSDirectoryList(nsdbase, "."):
    clnt.NSDirectoryRemove(nsdbase, uentry, TFlags.RECURSIVE)
    clnt.free(uentry)
  clnt.NSDirectoryClose(nsdbase)
  clnt.freeAll()

for i, t in enumerate(sorted(timeit.repeat(foo, repeat=5, number=1))):
  print "Run %d: %.2f sec" % (i+1, t)
