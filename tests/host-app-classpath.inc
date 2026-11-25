# Sourced by host checks that compile against the actual Java and Kotlin app classes.
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
(cd "$project_dir" && python3 scripts/ci_gradle.py :app:compileDebugJavaWithJavac --quiet)
classpath="$project_dir/app/build/intermediates/javac/debug/compileDebugJavaWithJavac/classes"
classpath="$classpath:$project_dir/app/build/tmp/kotlin-classes/debug"
classpath="$classpath:$ANDROID_HOME/platforms/android-35/android.jar"
