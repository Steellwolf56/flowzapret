#!/usr/bin/env bash
#
# Собирает релизные APK, раскладывает их в dist/ и (если доступен gh)
# создаёт/обновляет GitHub-релиз с тегом.
#
# Использование:
#   tools/publish-app.sh              # собрать dist + создать релиз
#   SKIP_GRADLE=1 tools/publish-app.sh
#   VERSION=1.3.0 tools/publish-app.sh
#
# Переменные:
#   VERSION      версия без префикса (по умолчанию из build.gradle.kts)
#   SKIP_GRADLE  1 — не пересобирать APK
#   REPO         owner/name (по умолчанию Steellwolf56/flowzapret)
#   WITH_COMPONENTS  1 — также приложить dist/flowzapret-components.zip
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

REPO="${REPO:-Steellwolf56/flowzapret}"
SKIP_GRADLE="${SKIP_GRADLE:-0}"
WITH_COMPONENTS="${WITH_COMPONENTS:-1}"
VERSION="${VERSION:-$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)}"
TAG="v$VERSION"
DIST="$ROOT/dist"
OUT="$ROOT/app/build/outputs/apk/release"

if [[ -z "$VERSION" ]]; then
    echo "Cannot detect VERSION" >&2
    exit 1
fi

if [[ "$SKIP_GRADLE" != "1" ]]; then
    echo "==> Building release APKs"
    ./gradlew :app:assembleRelease
fi

echo "==> Collecting APKs ($VERSION)"
for abi in universal arm64-v8a armeabi-v7a; do
    src="$OUT/app-$abi-release.apk"
    [[ -f "$src" ]] || { echo "missing $src" >&2; exit 1; }
    cp "$src" "$DIST/FlowZapret-$VERSION-$abi.apk"
done

( cd "$DIST" && sha256sum "FlowZapret-$VERSION-"*.apk > "SHA256SUMS-$VERSION" )
echo "==> Checksums written to dist/SHA256SUMS-$VERSION"

if ! command -v gh >/dev/null 2>&1; then
    echo "gh CLI not found — skipping GitHub release."
    echo "Assets are ready in dist/."
    exit 0
fi

if ! gh auth status >/dev/null 2>&1; then
    echo "gh is not authenticated — skipping GitHub release."
    exit 0
fi

echo "==> Preparing release notes"
NOTES="$(mktemp)"
{
    echo "## FlowZapret $VERSION"
    echo
    awk -v ver="$VERSION" '
        $0 ~ "^## \\[" ver "\\]" {flag=1; next}
        /^## \[/ {flag=0}
        flag {print}
    ' CHANGELOG.md
    echo
    echo "### Downloads"
    echo
    echo "| File | Notes |"
    echo "| --- | --- |"
    echo "| \`FlowZapret-$VERSION-universal.apk\` | any ARM device |"
    echo "| \`FlowZapret-$VERSION-arm64-v8a.apk\` | 64-bit ARM |"
    echo "| \`FlowZapret-$VERSION-armeabi-v7a.apk\` | 32-bit ARM |"
    echo
    echo "Verify downloads with \`SHA256SUMS-$VERSION\`."
} > "$NOTES"

ASSETS=(
    "$DIST/FlowZapret-$VERSION-universal.apk"
    "$DIST/FlowZapret-$VERSION-arm64-v8a.apk"
    "$DIST/FlowZapret-$VERSION-armeabi-v7a.apk"
    "$DIST/SHA256SUMS-$VERSION"
)
if [[ "$WITH_COMPONENTS" == "1" && -f "$DIST/flowzapret-components.zip" ]]; then
    ASSETS+=("$DIST/flowzapret-components.zip")
fi

if gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
    echo "==> Release $TAG already exists, uploading assets"
    gh release upload "$TAG" --repo "$REPO" --clobber "${ASSETS[@]}"
    gh release edit "$TAG" --repo "$REPO" --title "FlowZapret $VERSION" --notes-file "$NOTES"
else
    echo "==> Creating release $TAG"
    gh release create "$TAG" --repo "$REPO" \
        --title "FlowZapret $VERSION" \
        --notes-file "$NOTES" \
        "${ASSETS[@]}"
fi

rm -f "$NOTES"
echo "==> Done: https://github.com/$REPO/releases/tag/$TAG"
