#!/bin/sh

set -e

ERL_THRIFT=../../../../thrift/lib/erl
ERL_GEN=../../thrift/gen-erl/

if ! [ -d ${ERL_THRIFT}/ebin ]; then
    echo "Please build the Thrift library by running \`make' in ${ERL_THRIFT}"
    exit 1
fi

if ! [ -d $ERL_GEN ]; then
    echo "Please run thrift first to generate $ERL_GEN/"
    exit 1
fi

erlc +native -I ${ERL_THRIFT}/include -I $ERL_GEN -o $ERL_GEN $ERL_GEN/*.erl
erlc +native -I ${ERL_THRIFT}/include -I $ERL_GEN $1.erl
erl +K true -pa ${ERL_THRIFT}/ebin -pa $ERL_GEN -noshell -s $1 t -s init stop
#erl +K true -pa ../../../../thrift/lib/erl/ebin -pa ../../thrift/gen-erl/ -noshell -s demo t -s init stop
