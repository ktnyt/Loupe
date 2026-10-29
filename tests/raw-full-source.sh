#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
BUILD=$(mktemp -d)
trap 'rm -rf "$BUILD"' EXIT
mkdir -p "$BUILD/android/graphics"
cat > "$BUILD/android/graphics/Rect.java" <<'JAVA'
package android.graphics;
public class Rect {public int left,top,right,bottom;public Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}public int width(){return right-left;}public int height(){return bottom-top;}}
JAVA
cat > "$BUILD/android/graphics/RectF.java" <<'JAVA'
package android.graphics;
public class RectF {public float left,top,right,bottom;public RectF(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}public float width(){return right-left;}public float height(){return bottom-top;}}
JAVA
javac -d "$BUILD" "$BUILD/android/graphics/Rect.java" "$BUILD/android/graphics/RectF.java" "$ROOT/app/src/main/java/jp/nano/rawgallery/RawEditFullSource.java" "$ROOT/app/src/main/java/jp/nano/rawgallery/RawEditTilePlan.java" "$ROOT/tests/RawEditFullSourceTest.java"
java -cp "$BUILD" jp.nano.rawgallery.RawEditFullSourceTest
