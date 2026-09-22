#!/bin/sh
set -eu
cd "$(dirname "$0")"
cat release/ZERO_ECLIPSE_V9.jar.part0 release/ZERO_ECLIPSE_V9.jar.part1 > ZERO_ECLIPSE_V9.jar
exec java -Dsun.java2d.uiScale=1.0 -jar ZERO_ECLIPSE_V9.jar
