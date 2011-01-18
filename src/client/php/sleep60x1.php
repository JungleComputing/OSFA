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

function run($i) {
  global $client;
  $time_start = microtime_float();
  $jdid = $client->JobDescriptionCreate();
  $jsid = $client->JobServiceCreateDefault();
  $client->JobDescriptionSetAttribute($jdid, "Executable", "/bin/sleep");
  $client->JobDescriptionSetVectorAttribute($jdid, "Arguments", array("60"));
  $jid = $client->JobServiceCreateJob($jsid, $jdid);
  $client->JobRun($jid);
  $client->JobWaitFor($jid);
  $time_end = microtime_float();
  return $time_end - $time_start;
}

connect();
$results = array();
for($i=1; $i<6; $i++) {
  array_push($results, run($i));
}
sort($results);
foreach($results as $k => $time) {
  printf("Run %d: %.2f\n", $k+1, $time);
}
disconnect();

?>
