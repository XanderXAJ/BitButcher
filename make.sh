#!/bin/sh
# Script for compiling BitButcher in the shell
# Copyright Alex Palmer 2010
# Licensed under the GNU General Public License version 3
# See /doc/COPYING for the full license
echo "Compiling BitButcher..."
mkdir -p class
javac -d class src/com/xanderx/bitbutcher/*.java
echo "Making JAR..."
jar cfm bin/BitButcher.jar etc/manifest -C class com
echo "Finished! You can find the JAR in '/bin'"
