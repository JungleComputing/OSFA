#!/bin/sh

for i in 0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
  mkdir /tmp/basedir$i
  ./file1.py local://localhost/tmp/basedir$i 1> $i.out &
done

#rm -rf /tmp/basedir*;

