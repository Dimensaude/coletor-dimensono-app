"""Point the generated Android scaffold at the Coletor release key.

`flutter create` regenerates android/app/build.gradle.kts with the debug signing config. Every
build that will be installed on someone's phone must be signed with the SAME key, or Android
refuses it as an update ("app não instalado") and the user has to uninstall and lose the pairing.

The key and its passwords live in android/key.properties + android/coletor-release.jks, both
git-ignored (keep a copy in a safe place: losing them means every user reinstalls).

Usage: python tools/apply-release-signing.py <flutter project dir>
"""
import pathlib
import sys

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
gradle = root / "android" / "app" / "build.gradle.kts"
props = root / "android" / "key.properties"
if not props.exists():
    sys.exit(f"{props} not found: copy it from the safe copy before building a release")

s = gradle.read_text(encoding="utf-8")
if "keystoreProperties" in s:
    print("release signing already configured")
    sys.exit(0)

header = '''import java.util.Properties
import java.io.FileInputStream

val keystoreProperties = Properties().apply {
    val f = rootProject.file("key.properties")
    if (f.exists()) load(FileInputStream(f))
}

'''
signing = '''    signingConfigs {
        create("release") {
            keyAlias = keystoreProperties["keyAlias"] as String
            keyPassword = keystoreProperties["keyPassword"] as String
            storeFile = file(keystoreProperties["storeFile"] as String)
            storePassword = keystoreProperties["storePassword"] as String
        }
    }

    buildTypes {'''
debug_line = 'signingConfig = signingConfigs.getByName("debug")'
assert "    buildTypes {" in s and debug_line in s, "unexpected build.gradle.kts layout"
s = header + s.replace("    buildTypes {", signing, 1).replace(debug_line, 'signingConfig = signingConfigs.getByName("release")', 1)
gradle.write_text(s, encoding="utf-8")
print("release signing configured")
