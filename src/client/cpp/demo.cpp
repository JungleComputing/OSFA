#include <iostream>

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
  int u, nse, s;
  string path;

  shared_ptr<TTransport> socket(new TSocket("localhost", 9090));
  shared_ptr<TTransport> transport(new TBufferedTransport(socket));
  shared_ptr<TProtocol> protocol(new TBinaryProtocol(transport));
  SAGAServiceClient client(protocol);

  transport->open();
  client.login("admin", "password");

  u = client.URLCreate("file://localhost/tmp/demofile.txt");
  s = client.SessionCreate(true);
  nse = client.NSEntryCreate(s, u, 512);

  client.NSEntryRemove(nse, 0);
  client.URLGetPath(path, u);
  cout << "Successfully deleted file '" << path << "'" << endl;

  client.freeAll();
  transport->close();
  return EXIT_SUCCESS;
}
