echo "Compiling BitButcher..."
javac src/com/xanderx/bitbutcher/*.java
echo "Making JAR..."
jar cfm bin/BitButcher.jar etc/manifest -C src com

