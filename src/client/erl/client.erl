-module(client).

-include("sAGAService_thrift.hrl").
-include("saga_types.hrl").
-include("saga_constants.hrl").
-import(timer).

-compile(export_all).
%-export([t/0]).

test_avg(M, F, A, N) when N > 0 ->
  L = test_loop(M, F, A, N, []),
  Length = length(L),
  Min = lists:min(L),
  Max = lists:max(L),
  Med = lists:nth(round((Length / 2)), lists:sort(L)),
  Avg = round(lists:foldl(fun(X, Sum) -> X + Sum end, 0, L) / Length),
  io:format("Range: ~b - ~b mics~n"
    "Median: ~b mics~n"
    "Average: ~b mics~n",
    [Min, Max, Med, Avg]),
  Med.

test_loop(_M, _F, _A, 0, List) ->
  List;
test_loop(M, F, A, N, List) ->
  {T, _Result} = timer:tc(M, F, A),
  test_loop(M, F, A, N - 1, [T|List]).

create_url(Client, Name) ->
  {_, {ok, Urlid}} = thrift_client:call(Client, 'URLCreate', [Name]),
  Urlid.

run_job(Client, Exec, Args) ->
  {_, {ok, Jdid}} = thrift_client:call(Client, 'JobDescriptionCreate', []),
  {_, {ok, ok}} = thrift_client:call(Client, 'JobDescriptionSetAttribute', [Jdid, "Executable", Exec]),
  {_, {ok, ok}} = thrift_client:call(Client, 'JobDescriptionSetVectorAttribute', [Jdid, "Arguments", Args]),
  {_, {ok, Jsid}} = thrift_client:call(Client, 'JobServiceCreateDefault', []),
  {_, {ok, Jid}} = thrift_client:call(Client, 'JobServiceCreateJob', [Jsid, Jdid]),
  {_, {ok, ok}} = thrift_client:call(Client, 'JobRun', [Jid]),
  {_, {ok, ok}} = thrift_client:call(Client, 'JobWaitFor', [Jid]),
  Jid.

t() ->
  Port = 9090,
  {ok, C} = thrift_client_util:new("127.0.0.1", Port, sAGAService_thrift, []),
  {_, {ok, ok}} = thrift_client:call(C, login, ["admin", "password"]),
  Name = "file://localhost/tmp/erltest.txt",
  Urlid = create_url(C, Name),
  io:format("URLCreate ~p~n", [Urlid]),
  test_avg(client, create_url, [Name], 100),
  {_, {ok, Sessionid}} = thrift_client:call(C, 'SessionCreate', [true]),
  {_, {ok, Fileid}} = thrift_client:call(C, 'FileCreate', [Sessionid, Urlid, 1544]),
  {_, {ok, Filesize}} = thrift_client:call(C, 'FileGetSize', [Fileid]),
  io:format("FileSize ~p~n", [Filesize]),

  L = test_loop(client, run_job, [C, "/bin/sleep", ["60"]], 10, []),
  Length = length(L),
  Min = lists:min(L),
  Max = lists:max(L),
  Med = lists:nth(round((Length / 2)), lists:sort(L)),
  Avg = round(lists:foldl(fun(X, Sum) -> X + Sum end, 0, L) / Length),
  io:format("Range: ~b - ~b mics~n"
    "Median: ~b mics~n"
    "Average: ~b mics~n",
    [Min, Max, Med, Avg]),
  %timer:tc(client, run_job, [C, "/bin/sleep", ["60"]]),

  {_, {ok, Freed}} = thrift_client:call(C, 'freeAll', []),
  io:format("Freed ~p objects~n", [Freed]),
  ok.
