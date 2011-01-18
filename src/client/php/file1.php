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

function microtime_float()
{
  list($usec, $sec) = explode(" ", microtime());
  return ((float)$usec + (float)$sec);
}

function run($n) {
  global $client;
  $bufsize = 1024 * 32;
  $big_file_size = 1024 * 1024 * 100;
  $write_buf = str_repeat("0", $bufsize);

  $time_start = microtime_float();
  $s = $client->SessionCreate(True);
  $ubase = $client->URLCreate("local://localhost/tmp/basedir");
  $dbase = $client->DirectoryCreate($s, $ubase, $GLOBALS['saga_E_TFlags']['READ']);
  $ufoo = $client->URLCreate("foo");
  $f = $client->DirectoryOpenFile($dbase, $ufoo, $GLOBALS['saga_E_TFlags']['CREATE']);
  $b = $client->BufferCreate($bufsize);
  $client->BufferSetData($b, $write_buf);
  $written = 0;
  while($written < $big_file_size) {
    $written += $client->FileWrite($f, $b);
  }
  $client->FileClose($f);

  $ubar = $client->URLCreate("bar");
  $client->DirectoryCopy($dbase, $ufoo, $ubar, $GLOBALS['saga_E_TFlags']['NONE']);
  $fbar = $client->DirectoryOpenFile($dbase, $ubar, $GLOBALS['saga_E_TFlags']['READ']);
  $bbar = $client->BufferCreate($bufsize);
  $read = 1;
  while($read > 0) {
    $read = $client->FileRead($fbar, $bbar);
  }
  $client->FileClose($fbar);

  $client->DirectoryRemove2($dbase, "*", $GLOBALS['saga_E_TFlags']['RECURSIVE']);
  $client->DirectoryClose($dbase);


  $client->freeAll();
  $time_end = microtime_float();
  $time = $time_end - $time_start;
  return $time;
}

try {
  connect();
  $results = array();
  for($i=1; $i<6; $i++) {
    array_push($results, run($i));
  }
  sort($results);
  foreach($results as $k => $time) {
    printf("Run %d: %.2f\n", $k+1, $time);
  }
} catch (Exception $e) {
  //var_dump($e);
  printf("%s: %s\n", get_class($e), $e->why);
}

disconnect();
?>
