#!/usr/bin/env bash
# Publica una versión nueva del plugin en GitHub para que Android Studio la ofrezca como actualización.
#
# Uso:
#   1. Sube pluginVersion en gradle.properties (por ejemplo, 0.3.0 → 0.3.1).
#   2. Haz commit de tus cambios.
#   3. ./scripts/release.sh
#
# Qué hace: compila y corre los tests, regenera updatePlugins.xml con la nueva versión, lo sube a main
# y crea un Release de GitHub con el zip. Android Studio lo detecta en la siguiente comprobación de actualizaciones.
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="santolan31/kmp-interview-coach"
VERSION=$(grep '^pluginVersion=' gradle.properties | cut -d= -f2)
SINCE=$(grep '^pluginSinceBuild=' gradle.properties | cut -d= -f2)
TAG="v$VERSION"
ZIP="build/distributions/kmp-interview-coach-$VERSION.zip"

if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    echo "La versión $VERSION ya está publicada. Sube pluginVersion en gradle.properties." >&2
    exit 1
fi
if [ -n "$(git status --porcelain)" ]; then
    echo "Hay cambios sin commit. Haz commit antes de publicar." >&2
    exit 1
fi

# En la terminal de Android Studio, ANDROID_PREFS_ROOT choca con ANDROID_USER_HOME y rompe Gradle.
env -u ANDROID_PREFS_ROOT ./gradlew clean test buildPlugin --console=plain

cat > updatePlugins.xml <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!-- Repositorio de plugins propio. Android Studio lee este archivo para saber cuál es la última versión.
     Lo regenera scripts/release.sh: no lo edites a mano. -->
<plugins>
    <plugin id="dev.kmpcoach.interview-coach"
            url="https://github.com/$REPO/releases/download/$TAG/kmp-interview-coach-$VERSION.zip"
            version="$VERSION">
        <idea-version since-build="$SINCE"/>
        <name>KMP Interview Coach</name>
        <vendor>KMP Interview Coach</vendor>
        <description>Tutor de IA dentro del IDE que te guía con pistas sin darte el código.</description>
    </plugin>
</plugins>
EOF

if [ -n "$(git status --porcelain updatePlugins.xml)" ]; then
    git add updatePlugins.xml
    git commit -m "release: $VERSION"
fi
git push origin HEAD:main
gh release create "$TAG" "$ZIP" --repo "$REPO" --title "$VERSION" --generate-notes

echo "Publicada la $VERSION. En Android Studio: Settings › Plugins › Installed › Update (o espera el aviso de actualización)."
