-module(sleep1x60).

-include("sAGAService_thrift.hrl").
-import(timer).

-compile(export_all).


run_job(Client, Exec, Args) ->
  {_, {ok, Jdid}} = thrift_client:call(Client, 'JobDescriptionCreate', []),
  {_, {ok, ok}} = thrift_client:call(Client, 'JobDescriptionSetAttribute', [Jdid, "Executable", Exec]),
  {_, {ok, ok}} = thrift_client:call(Client, 'JobDescriptionSetVectorAttribute', [Jdid, "Arguments", Args]),
  {_, {ok, Jsid}} = thrift_client:call(Client, 'JobServiceCreateDefault', []),
  {_, {ok, Jid}} = thrift_client:call(Client, 'JobServiceCreateJob', [Jsid, Jdid]),
  {_, {ok, ok}} = thrift_client:call(Client, 'JobRun', [Jid]),
  {_, {ok, ok}} = thrift_client:call(Client, 'JobWaitFor', [Jid]),
  Jid.

run(C) ->
  T1 = now(),
  run_job(C, "/bin/sleep", ["60"]),
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
