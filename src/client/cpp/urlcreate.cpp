#include <iostream>
#include <sstream>
#include <sys/time.h>
#include <stdio.h>
#include <unistd.h>

#include <protocol/TBinaryProtocol.h>
#include <transport/TSocket.h>
#include <transport/TTransportUtils.h>

#include "../../thrift/gen-cpp/SAGAService.h"
#include "../../thrift/gen-cpp/saga_types.h"

using namespace apache::thrift;
using namespace apache::thrift::protocol;
using namespace apache::thrift::transport;

using namespace saga;
using namespace boost;
using namespace std;

int main(int argc, char** argv) {
  struct timeval start, end;
  long mtime, seconds, useconds;
  double et;
  int u, nse, s;
  string path, name;
  stringstream ss;

  shared_ptr<TTransport> socket(new TSocket("localhost", 9090));
  shared_ptr<TTransport> transport(new TBufferedTransport(socket));
  shared_ptr<TProtocol> protocol(new TBinaryProtocol(transport));
  SAGAServiceClient client(protocol);

  transport->open();
  client.login("admin", "password");

  srand ( time(NULL) );
  ss << "file://localhost/tmp/file" << rand() % 1000 << ".txt";

  gettimeofday(&start, NULL);
  u = client.URLCreate(ss.str());
  gettimeofday(&end, NULL);

  client.URLGetPath(path, u);
  cout << "Path is " << path << endl;

  et  = (end.tv_sec - start.tv_sec) * 1000.0;
  et += (end.tv_usec - start.tv_usec) / 1000.0;
  cout <<  et << " ms.\n";

  /*
  mtime = ((seconds) * 1000 + useconds/1000.0);
  printf("Elapsed time: %ld milliseconds\n", mtime);
  */

  client.freeAll();
  transport->close();
  return EXIT_SUCCESS;
}
