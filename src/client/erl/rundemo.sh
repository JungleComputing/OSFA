#!/bin/sh

erl +K true -pa ../../../../thrift/lib/erl/ebin \
  -pa ../../thrift/gen-erl/ -noshell -s demo t -s init stop
