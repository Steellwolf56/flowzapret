#!/usr/bin/env bash
#
# Генерирует ECDSA P-256 ключ для подписи манифеста компонентов.
# Публичная часть кладётся в APK (res/raw/components_pubkey.pem),
# приватная остаётся в tools/keys/ и не коммитится.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KEY="$ROOT/tools/keys/components_signing.pem"
PUB="$ROOT/app/src/main/res/raw/components_pubkey.pem"

if [[ -f "$KEY" && "${1:-}" != "--force" ]]; then
    echo "Key already exists: $KEY"
    echo "Use --force to regenerate (old signatures become invalid)."
    exit 0
fi

mkdir -p "$(dirname "$KEY")" "$(dirname "$PUB")"
openssl ecparam -name prime256v1 -genkey -noout -out "$KEY"
openssl ec -in "$KEY" -pubout -out "$PUB" 2>/dev/null
chmod 600 "$KEY"

echo "Private key: $KEY"
echo "Public key : $PUB"
echo
echo "ВАЖНО: после замены ключа нужно пересобрать APK — иначе старые"
echo "подписи манифеста перестанут проверяться."
