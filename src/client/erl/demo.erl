-module(demo).
-include("sAGAService_thrift.hrl").
-compile(export_all).

t() ->
  {ok, C} = thrift_client_util:new("127.0.0.1", 9090, sAGAService_thrift, []),
  {_, {ok, ok}} = thrift_client:call(C, login, ["admin", "password"]),

  Urlname = "file://localhost/tmp/demofile.txt",
  {_, {ok, Urlid}} = thrift_client:call(C, 'URLCreate', [Urlname]),
  {_, {ok, Sessionid}} = thrift_client:call(C, 'SessionCreate', [true]),
  {_, {ok, Fileid}} = thrift_client:call(C, 'FileCreate', [Sessionid, Urlid, 512]),

  {_, {ok, Filesize}} = thrift_client:call(C, 'FileGetSize', [Fileid]),
  {_, {ok, Path}} = thrift_client:call(C, 'URLGetPath', [Urlid]),
  io:format("Filesize of '~s' is ~p bytes~n", [Path, Filesize]),

  thrift_client:call(C, 'freeAll', []),
  ok.
