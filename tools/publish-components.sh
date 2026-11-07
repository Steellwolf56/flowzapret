#!/usr/bin/env bash
#
# Публикует dist/components/ в GitHub-репозиторий компонентов.
# Использует SSH-ключ ~/.ssh/flowzapret_github (см. ~/.ssh/config).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/dist/components"
REPO_URL="${COMPONENTS_REPO_URL:-git@github.com:Steellwolf56/flowzapret-components.git}"
REPO_DIR="${COMPONENTS_REPO_DIR:-$HOME/flowzapret-components-repo}"

if [[ ! -d "$SRC" ]]; then
    echo "Run tools/build-components.sh first" >&2
    exit 1
fi

if [[ ! -d "$REPO_DIR/.git" ]]; then
    echo "==> Cloning $REPO_URL"
    git clone "$REPO_URL" "$REPO_DIR"
fi

echo "==> Copying artifacts"
find "$REPO_DIR" -maxdepth 1 -type f \( -name '*.so' -o -name 'components.json*' -o -name 'flowseal_presets.json' \) -delete
cp "$SRC"/* "$REPO_DIR"/

cd "$REPO_DIR"
git add -A
if git diff --cached --quiet; then
    echo "No changes to publish"
    exit 0
fi

VERSION="$(python3 -c "import json;print(json.load(open('components.json'))['generatedAt'])")"
git -c user.name="FlowZapret" -c user.email="flowzapret@local" commit -m "components: $VERSION"
git push origin HEAD:main

echo
echo "Published. Manifest URL:"
echo "  https://raw.githubusercontent.com/Steellwolf56/flowzapret-components/main/components.json"
