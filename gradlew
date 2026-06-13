#!/bin/sh
##############################################################################
# Gradle start up script for UN*X — généré pour WscScreenApk
##############################################################################

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

# Determine the current dir
DIRNAME=$(cd "$(dirname "$0")" && pwd)
APP_HOME="$DIRNAME"

# Setup the command line
exec "$JAVACMD" \
  -classpath "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" \
  org.gradle.wrapper.GradleWrapperMain "$@"
