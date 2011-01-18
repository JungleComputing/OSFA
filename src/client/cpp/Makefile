BOOST_DIR = /usr/local/include/boost-1_37/
THRIFT_DIR = /usr/local/include/thrift
LIB_DIR = /usr/local/lib

GEN_SRC = ../../thrift/gen-cpp/SAGAService.cpp ../../thrift/gen-cpp/saga_types.cpp

default: demo

client: CppClient.cpp
	g++ -o CppClient -I${THRIFT_DIR} -I${BOOST_DIR}  -I../../thrift/gen-cpp/ -L${LIB_DIR} -lthrift CppClient.cpp ${GEN_SRC}

demo: demo.cpp
	g++ -o $@ -I${THRIFT_DIR} -I${BOOST_DIR}  -I../../thrift/gen-cpp/ -L${LIB_DIR} -lthrift $< ${GEN_SRC}

urlcreate: urlcreate.cpp
	g++ -o $@ -I${THRIFT_DIR} -I${BOOST_DIR}  -I../../thrift/gen-cpp/ -L${LIB_DIR} -lthrift $< ${GEN_SRC}

clean:
	$(RM) CppClient demo urlcreate
