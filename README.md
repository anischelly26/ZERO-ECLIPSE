# ZERO: ECLIPSE

Cinematic 2D action adventure game built in Java.

## Features

- Custom Java game architecture
- AI behavior systems
- Companion and enemy AI foundations
- Combat systems
- Exploration gameplay
- Time-based gameplay mechanics

## V9 Update

ZERO ECLIPSE V9 introduces the AI and movement overhaul foundation:

- Character movement improvements
- Companion behavior system
- Enemy decision system foundation
- Cleaner expandable architecture

## Run

Requires **Java 21 or newer**.

- [Play the small browser demo](https://anischelly26.github.io/treasure-hunter/zero-eclipse/)
- [Download the original V9 JAR](https://anischelly26.github.io/treasure-hunter/zero-eclipse/#desktop)
- Or download this repository using **Code → Download ZIP**, extract it fully, and run `RUN_GAME.bat` on Windows or `sh run-game.sh` on macOS/Linux.

The launchers join the two files in `release/` into the unmodified V9 JAR, containing all graphics and audio. The browser download does this automatically and verifies the file's SHA-256:

```text
35977412bb4a30462821cb0190efb4956e45dcfcac5508ec7258529fa200c08e
```

After downloading the JAR, you can also run:

```sh
java -jar ZERO_ECLIPSE_V9.jar
```

The browser demo is a short JavaScript adaptation using V9 artwork. It is not the full Java game and does not include all its AI systems.

## Source

Java source is under `src/com/zeroeclipse/`. With JDK 21 installed, compile the source and use the release JAR as the resource classpath when running. Assets are included in the release JAR.

## Verification

The uploaded release is byte-for-byte identical to the supplied V9 JAR. Its classes target Java 21 (class version 65).
