#!/usr/bin/php
<?php

$GLOBALS['THRIFT_ROOT'] = '../../../../thrift/lib/php/src';

require_once $GLOBALS['THRIFT_ROOT'].'/Thrift.php';
require_once $GLOBALS['THRIFT_ROOT'].'/protocol/TBinaryProtocol.php';
require_once $GLOBALS['THRIFT_ROOT'].'/transport/TSocket.php';
require_once $GLOBALS['THRIFT_ROOT'].'/transport/THttpClient.php';
require_once $GLOBALS['THRIFT_ROOT'].'/transport/TBufferedTransport.php';

error_reporting(E_STRICT);
$GEN_DIR = '../../thrift/gen-php';
require_once $GEN_DIR.'/saga/saga_constants.php';
require_once $GEN_DIR.'/saga/saga_types.php';
require_once $GEN_DIR.'/saga/SAGAService.php';
error_reporting(E_ALL);

$s;
$client;
$transport;

function connect() {
  global $client, $transport;
  $socket = new TSocket('localhost', 9090);
  $transport = new TBufferedTransport($socket);
  $protocol = new TBinaryProtocol($transport);
  $client = new SAGAServiceClient($protocol);
  $transport->open();
  $client->login("admin", "password");
}

function disconnect() {
  global $transport;
  $transport->close();
}

function createURL($name) {
  global $client;
  return $client->URLCreate($name);
}

function URLTest() {
  global $client;
  printf("Host: %s\n", $client->URLGetHost($u));
  printf("Port: %d\n", $client->URLGetPort($u));
}

function createFile($u, $flags) {
  global $client, $s;
  return $client->FileCreate($s, $u, $flags);
}

function getFileSize($f, $async=False) {
  global $client;
  if($async) {
    $t = $client->FileGetSizeAsync($f, $GLOBALS['saga_E_TTaskMode']['TASK']);
    $client->TaskRun($t);
    $taskresult = $client->TaskGetResult($t);
    return $taskresult->reslong;
  } else {
    return $client->FileGetSize($f);
  }
}

function seekFile($f, $where) {
  global $client;
  $client->FileSeek($f, $where, $GLOBALS['saga_E_TSeekMode']['SEEKSTART']);
}

function writeFilee($f, $b) {
  global $client;
  //seekFile($f, 0);
  return $client->FileWrite($f, $b);
}

function readFilee($f, $b) {
  global $client;
  seekFile($f, 0);
  return $client->FileRead($f, $b);
}

function createBuffer($size) {
  global $client;
  return $client->BufferCreate($size);
}

try {

connect();
$s = $client->SessionCreate(True);
$u = createURL("file://localhost/tmp/test.txt");
$flags = $GLOBALS['saga_E_TFlags']['READWRITE'] | $GLOBALS['saga_E_TFlags']['APPEND'] | $GLOBALS['saga_E_TFlags']['CREATE'];
$f = createFile($u, $flags);
printf("File size async = %d\n", getFileSize($f, True));

$data = "
This script will not produce any output because the echo statement
refers to a local version of the \$a variable, and it has not been assigned a
value within this scope. You may notice that this is a little bit different
from the C language in that global variables in C are automatically availale
to functions unless specifically overridden by a local definition. This can
cause some problems in that people may inadvertently change a global
variable. In PHP global variables must be declared global inside a function
if they are going to be used in that function";

$size = 2048;

$b = createBuffer($size);
$client->BufferSetData($b, $data);
printf("%d bytes written to file %s\n", writeFilee($f, $b), $client->URLGetString($u));
printf("File size async = %d\n", getFileSize($f, True));

$b2 = createBuffer($size);
printf("%d bytes read from file %s\n", readFilee($f, $b2), $client->URLGetString($u));
//printf("Read: '%s'\n", $client->BufferGetData($b2));

printf("File size sync = %d\n", getFileSize($f));
} catch (Exception $e) {
  //var_dump($e);
  printf("%s: %s\n", get_class($e), $e->why);
}

?>
