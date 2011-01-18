-module(sleep1x60).

-include("sAGAService_thrift.hrl").
-import(timer).

-compile(export_all).

run_job(_C, _E, _A, 0) ->
  ok;
run_job(C, E, A, N) ->
  run_job(C, E, A),
  run_job(C, E, A, N-1).

run_job(C, Jsid, Jdid) ->
  {_, {ok, Jid}} = thrift_client:call(C, 'JobServiceCreateJob', [Jsid, Jdid]),
  {_, {ok, ok}} = thrift_client:call(C, 'JobRun', [Jid]),
  {_, {ok, ok}} = thrift_client:call(C, 'JobWaitFor', [Jid]),
  ok.

run(C) ->
  T1 = now(),
  {_, {ok, Jdid}} = thrift_client:call(Client, 'JobDescriptionCreate', []),
  {_, {ok, ok}} = thrift_client:call(Client, 'JobDescriptionSetAttribute', [Jdid, "Executable", "/bin/sleep"]),
  {_, {ok, ok}} = thrift_client:call(Client, 'JobDescriptionSetVectorAttribute', [Jdid, "Arguments", ["1"]]),
  {_, {ok, Jsid}} = thrift_client:call(Client, 'JobServiceCreateDefault', []),
  run_job(C, Jsid, Jdid, 60),
  {_, {ok, _}} = thrift_client:call(C, 'freeAll', []),
  T2 = now(),
  io:format("It took ~b microseconds~n", [timer:now_diff(T2, T1)]),
  ok.

t() ->
  Port = 9090,
  {ok, C} = thrift_client_util:new("127.0.0.1", Port, sAGAService_thrift, []),
  {_, {ok, ok}} = thrift_client:call(C, login, ["admin", "password"]),
  run(C),
  run(C),
  run(C),
  run(C),
  run(C),
  ok.
