#!/bin/bash
################################################################################

################################################################################
# build_jar: Build the self-contained zmbackup.jar with the Gradle wrapper.
# Requires a Java JDK on PATH (see check_java_runtime/depDownload.sh) and, on
# first run, internet access to download Gradle and the project's Maven
# dependencies.
################################################################################
function build_jar() {
  echo "Building ${ZMBKP_JAR_NAME} - this can take a few minutes on first run."
  ( cd "$MYDIR" && ./gradlew :app:shadowJar -q )
  BASHERRCODE=$?

  JAR_PATH="$MYDIR/app/build/libs/$ZMBKP_JAR_NAME"

  if [[ $BASHERRCODE -ne 0 ]]; then
    echo "[FAIL] - Gradle build failed"
    echo "Please check the output above, fix the issue, and run the installer again."
    echo "You can also try building it manually with: ./gradlew :app:shadowJar"
    exit "$ERR_BUILD_FAILED"
  fi

  if [[ ! -f "$JAR_PATH" ]]; then
    echo "[FAIL] - Build succeeded but $JAR_PATH was not produced"
    exit "$ERR_BUILD_FAILED"
  fi

  echo "Build completed: $JAR_PATH"
}
