#!/system/bin/sh
# 注意：本脚本需要一份"手机本地工具链"（JDK21 + kotlinc + aapt2 + R8 + apksig + zip），
# 默认放在仓库外层的 .probe/ 目录里（由维护者本地准备，不进仓库）。
# 只想拿到 APK 的话，用 build-local.sh / Dockerfile / GitHub Actions 即可。

set -u
ROOT=$(pwd)
P="$ROOT/.probe/tx"
AJ="$ROOT/.probe/lib/x/plat/android-34/android.jar"
SJ="$ROOT/.probe/lib/x/api/classes.jar"
PJ="$ROOT/.probe/lib/x/provider/classes.jar"
NJ="$ROOT/.probe/lib/annotation.jar"
R8="$ROOT/.probe/lib/r8.jar"
AS="$ROOT/.probe/lib/apksig.jar"
APP="$ROOT/qqbq-export"
OUT="$ROOT/build-apk"
PKG=com.qqbq.export
PKGPATH=com/qqbq/export

export LD_LIBRARY_PATH="$P/lib"
export PATH="$P/bin:$PATH"
export JAVA_HOME="$P"
export TMPDIR="$ROOT/.probe/tmp"
mkdir -p "$TMPDIR"

die() { echo "FAILED: $1"; exit 1; }

JAVA="$P/lib/jvm/java-21-openjdk/bin/java"; JAVAC="$P/lib/jvm/java-21-openjdk/bin/javac"; KEYTOOL="$P/lib/jvm/java-21-openjdk/bin/keytool"; AAPT2="$P/bin/aapt2"
KOTLINC_JAR="$P/opt/kotlin/lib/kotlin-compiler.jar"
STDLIB="$P/opt/kotlin/lib/kotlin-stdlib.jar"
ZIP=$(command -v zip 2>/dev/null)
for f in "$JAVA" "$JAVAC" "$KEYTOOL" "$AAPT2"; do [ -x "$f" ] || die "missing $f"; done
[ -f "$R8" ] || die "missing $R8"
[ -n "$KOTLINC_JAR" ] || die "missing kotlin-compiler jar"
[ -n "$STDLIB" ] || die "missing kotlin-stdlib jar"

echo "== 0) toolchain =="
echo "  java   $("$JAVA" -version 2>&1 | head -1)"
echo "  aapt2  $("$AAPT2" version 2>&1 | head -1)"
echo "  kotlinc $KOTLINC_JAR"
echo "  stdlib  $STDLIB"
echo "  r8      $R8"
echo "  zip     $ZIP"

CP="$AJ:$SJ:$PJ:$NJ:$STDLIB"

echo "== 1) manifest =="
mkdir -p "$OUT"
sed -e 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="'"$PKG"'">|' \
    "$APP/app/src/main/AndroidManifest.xml" > "$OUT/M1.xml" || die "sed manifest"
sed -e 's|${applicationId}|'"$PKG"'|g' "$OUT/M1.xml" > "$OUT/AndroidManifest.xml"
rm -f "$OUT/M1.xml"
grep -q 'package="'"$PKG"'"' "$OUT/AndroidManifest.xml" || die "no package attr"

echo "== 2) javac =="
rm -rf "$OUT/classes-java"; mkdir -p "$OUT/classes-java"
"$JAVAC" -nowarn -source 11 -target 11 -encoding UTF-8 -classpath "$CP" -d "$OUT/classes-java" \
  "$APP/app/src/main/java/$PKGPATH/IShell.java" "$APP/app/src/main/java/$PKGPATH/ShellStub.java" 2>&1 | head -20
[ -f "$OUT/classes-java/$PKGPATH/ShellStub.class" ] || die "javac failed"

echo "== 3) kotlinc =="
rm -rf "$OUT/classes-kotlin"; mkdir -p "$OUT/classes-kotlin"
"$JAVA" -Xmx1400m -cp "$KOTLINC_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -jvm-target 11 -nowarn \
  -classpath "$CP:$OUT/classes-java" \
  -d "$OUT/classes-kotlin" \
  $(ls "$APP"/app/src/main/java/$PKGPATH/*.kt) \
  "$APP/app/src/main/java/$PKGPATH/IShell.java" "$APP/app/src/main/java/$PKGPATH/ShellStub.java" 2>&1 | tail -30
[ -f "$OUT/classes-kotlin/$PKGPATH/MainActivity.class" ] || die "kotlinc failed"

echo "== 4) dex (R8/D8) =="
rm -rf "$OUT/classes-all" "$OUT/dex"
mkdir -p "$OUT/classes-all" "$OUT/dex"
cp -a "$OUT/classes-java/." "$OUT/classes-all/"
cp -a "$OUT/classes-kotlin/." "$OUT/classes-all/"
CLASSES=$(find "$OUT/classes-all" -name "*.class" | tr '\n' ' ')
"$JAVA" -Xmx1400m -cp "$R8" com.android.tools.r8.D8 --release --min-api 26 --lib "$AJ" \
  --output "$OUT/dex" $CLASSES "$SJ" "$PJ" "$STDLIB" "$NJ" 2>&1 | tail -15
ls "$OUT/dex"
[ -f "$OUT/dex/classes.dex" ] || die "dex failed"

echo "== 5) aapt2 link =="
rm -f "$OUT/base.apk"
"$AAPT2" link -o "$OUT/base.apk" -I "$AJ" --manifest "$OUT/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 34 --version-code 1 --version-name 1.0 2>&1 | tail -10
[ -f "$OUT/base.apk" ] || die "aapt2 link failed"

echo "== 6) add dex =="
ORIG=$(pwd)
cd "$OUT/dex" || die "cd dex"
for D in classes*.dex; do
  if [ -n "$ZIP" ]; then zip -q -X "$OUT/base.apk" "$D" || die "zip $D"; else die "no zip binary"; fi
done
cd "$ORIG" || die "cd back"
unzip -l "$OUT/base.apk" | grep dex

echo "== 7) sign (apksig) =="
KS="$ROOT/.probe/qqbq.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias qqbq -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=QQBqExport,OU=Local,O=Local,L=Local,ST=Local,C=CN" >/dev/null 2>&1 || die "keytool failed"
  echo "  keystore created"
fi
if [ ! -f "$OUT/Sign.class" ]; then
  "$JAVAC" -nowarn -encoding UTF-8 -classpath "$AS" -d "$OUT" "$ROOT/.probe/sign/Sign.java" 2>&1 | head -10
fi
[ -f "$OUT/Sign.class" ] || die "Sign.java 编译失败"
rm -f "$OUT/qqbq-export-signed.apk"
"$JAVA" -cp "$AS:$OUT" Sign "$KS" android qqbq "$OUT/base.apk" "$OUT/qqbq-export-signed.apk" 2>&1 | tail -8
[ -s "$OUT/qqbq-export-signed.apk" ] || die "sign failed"
ls -l "$OUT/qqbq-export-signed.apk"
"$AAPT2" dump badging "$OUT/qqbq-export-signed.apk" 2>&1 | head -6
echo "APK_DONE"
