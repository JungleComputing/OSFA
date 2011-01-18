-module(file1).

-include("sAGAService_thrift.hrl").
-import(string).
-import(timer).

-compile(export_all).


create_url(Client, Name) ->
  {_, {ok, Urlid}} = thrift_client:call(Client, 'URLCreate', [Name]),
  Urlid.

write_data(_C, _F, _B, 0) ->
  ok;
write_data(C, F, B, FS) ->
  {_, {ok, W}} = thrift_client:call(C, 'FileWrite', [F, B]),
  write_data(C, F, B, FS - W).

read_data(_C, _F, _B, 0) ->
  ok;
read_data(C, F, B, _FS) ->
  {_, {ok, R}} = thrift_client:call(C, 'FileRead', [F, B]),
  read_data(C, F, B, R).


filebenchmark(C) ->
  Bufsize = 1024 * 32,
  {_, {ok, Sid}} = thrift_client:call(C, 'SessionCreate', [true]),
  Ubase = create_url(C, "file://localhost/tmp/basedir"),
  {_, {ok, D}} = thrift_client:call(C, 'DirectoryCreate', [Sid, Ubase, 512]),

  Ufoo = create_url(C, "foo"),
  {_, {ok, F}} = thrift_client:call(C, 'DirectoryOpenFile', [D, Ufoo, 8]),
  {_, {ok, B}} = thrift_client:call(C, 'BufferCreate', [Bufsize]),
  thrift_client:call(C, 'BufferSetData', [B, string:copies("0", Bufsize)]),
  write_data(C, F, B, 1024 * 1024 * 100),
  thrift_client:call(C, 'FileClose', [F]),

  Ubar = create_url(C, "bar"),
  thrift_client:call(C, 'DirectoryCopy', [D, Ufoo, Ubar, 0]),
  {_, {ok, Fbar}} = thrift_client:call(C, 'DirectoryOpenFile', [D, Ufoo, 512]),
  {_, {ok, Bbar}} = thrift_client:call(C, 'BufferCreate', [Bufsize]),
  read_data(C, Fbar, Bbar, 1),
  thrift_client:call(C, 'FileClose', [Fbar]),
  thrift_client:call(C, 'DirectoryRemove2', [D, "*", 2]),
  thrift_client:call(C, 'DirectoryClose', [D]),
  ok.

run(Client_handle) ->
  T1 = now(),
  filebenchmark(Client_handle),
  {_, {ok, _}} = thrift_client:call(Client_handle, 'freeAll', []),
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
