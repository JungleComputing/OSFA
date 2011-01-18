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
require_once $GEN_DIR.'/saga/saga_types.php';
require_once $GEN_DIR.'/saga/SAGAService.php';
error_reporting(E_ALL);

$socket = new TSocket('localhost', 9090);
$transport = new TBufferedTransport($socket);
$protocol = new TBinaryProtocol($transport);
$client = new SAGAServiceClient($protocol);

$transport->open();
$client->login("admin", "password");

$bufsize = 512;

$s = $client->SessionCreate(True);
$u = $client->URLCreate("file://localhost/tmp/demofile.txt");
$f = $client->FileCreate($s, $u, $GLOBALS['saga_E_TFlags']['READ']);
$b = $client->BufferCreate($bufsize);

$read_bytes = 1;
$total = 0;
while($read_bytes > 0) {
  $read_bytes = $client->FileRead($f, $b);
  $total += $read_bytes;
}

printf("Data read: %s\n", $client->BufferGetData($b));
printf("%d bytes read from file '%s'\n", $total, $client->URLGetPath($u));

$client->freeAll();
$transport->close();

?>
