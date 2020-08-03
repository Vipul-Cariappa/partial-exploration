Set-up Instructions
===================

1. Clone the repository
2. Run `git submodule update --init` to populate `lib/models`
3. Follow the instructions to compile PRISM in `lib/models/README`
   For windows users:
   1. Install cygwin
   2. Run `cygwin.bat` in the install dir to start cygwin bash
   3. `cd /cygdrive/path/to/lib/prism/prism`
   4. `dos2unix * ../cudd/*` (to fix incorrect line endings)
   5. `make JAVA_DIR=/cygdrive/path/to/jdk JAVAC=/cygdrive/path/to/jdk/bin/javac`
     * Make sure that this JDK is <= the version you are using to develop.
     * If there are spaces in the JAVAC path, escape them with `\`

Users:

Run `./gradlew distZip` to build and package the tool.
The package can be found under `build/distributions/`

Developers:

1. Import project in IntelliJ, enable gradle auto-import
2. Run `./gradlew compileJava` to create the prism.jar and run the annotation processor
