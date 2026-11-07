#!/usr/bin/env bash
#
# Собирает обновляемые компоненты FlowZapret:
#   - libbyedpi.so (ядро ByeDPI + JNI-шимы) для arm64-v8a и armeabi-v7a
#   - libtgwsproxy.so (Rust MTProto прокси) для arm64-v8a и armeabi-v7a
#   - flowseal_presets.json
#
# Результат: dist/components/ (components.json, подпись, артефакты, zip).
#
# Требования: Android NDK, Rust targets aarch64-linux-android /
# armv7-linux-androideabi, openssl.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-$HOME/Android/Sdk/ndk/27.2.12479018}}"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
SIGNING_KEY="$ROOT/tools/keys/components_signing.pem"
OUT="$ROOT/dist/components"

SKIP_GRADLE="${SKIP_GRADLE:-0}"

if [[ ! -d "$NDK" ]]; then
    echo "NDK not found: $NDK" >&2
    exit 1
fi
if [[ ! -f "$SIGNING_KEY" ]]; then
    echo "Signing key not found: $SIGNING_KEY" >&2
    echo "Generate it with: tools/generate-signing-key.sh" >&2
    exit 1
fi

echo "==> Building libbyedpi.so (release)"
if [[ "$SKIP_GRADLE" != "1" ]]; then
    ./gradlew :app:assembleRelease
fi

BYEDPI_LIB_DIR="$ROOT/app/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib"
if [[ ! -f "$BYEDPI_LIB_DIR/arm64-v8a/libbyedpi.so" ]]; then
    echo "libbyedpi.so not found; run ./gradlew :app:assembleRelease" >&2
    exit 1
fi

echo "==> Building libtgwsproxy.so (release)"
export PATH="$TOOLCHAIN/bin:$PATH"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/bin/aarch64-linux-android21-clang"
export CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER="$TOOLCHAIN/bin/armv7a-linux-androideabi21-clang"
export CC_aarch64_linux_android="$TOOLCHAIN/bin/aarch64-linux-android21-clang"
export AR_aarch64_linux_android="$TOOLCHAIN/bin/llvm-ar"
export CC_armv7_linux_androideabi="$TOOLCHAIN/bin/armv7a-linux-androideabi21-clang"
export AR_armv7_linux_androideabi="$TOOLCHAIN/bin/llvm-ar"

(
    cd "$ROOT/third_party/tgwsproxy"
    cargo build --release --target aarch64-linux-android
    cargo build --release --target armv7-linux-androideabi
)

echo "==> Collecting artifacts"
rm -rf "$OUT"
mkdir -p "$OUT"

cp "$BYEDPI_LIB_DIR/arm64-v8a/libbyedpi.so" "$OUT/libbyedpi-arm64-v8a.so"
cp "$BYEDPI_LIB_DIR/armeabi-v7a/libbyedpi.so" "$OUT/libbyedpi-armeabi-v7a.so"
cp "$ROOT/third_party/tgwsproxy/target/aarch64-linux-android/release/libtgwsproxy.so" "$OUT/libtgwsproxy-arm64-v8a.so"
cp "$ROOT/third_party/tgwsproxy/target/armv7-linux-androideabi/release/libtgwsproxy.so" "$OUT/libtgwsproxy-armeabi-v7a.so"
cp "$ROOT/app/src/main/assets/flowseal_presets.json" "$OUT/flowseal_presets.json"

echo "==> Writing manifest + signature"
python3 - "$OUT" "$ROOT" <<'PY'
import base64
import datetime
import hashlib
import json
import os
import pathlib
import subprocess
import sys
import zipfile

out = pathlib.Path(sys.argv[1])
root = pathlib.Path(sys.argv[2])

def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()

def artifact(name: str) -> dict:
    path = out / name
    return {
        'url': name,
        'sha256': sha256(path),
        'size': path.stat().st_size,
    }

def combined_hash(*names: str) -> str:
    h = hashlib.sha256()
    for name in sorted(names):
        h.update((out / name).read_bytes())
    return h.hexdigest()[:8]

byedpi_hash = combined_hash('libbyedpi-arm64-v8a.so', 'libbyedpi-armeabi-v7a.so')
tgws_hash = combined_hash('libtgwsproxy-arm64-v8a.so', 'libtgwsproxy-armeabi-v7a.so')
presets_hash = combined_hash('flowseal_presets.json')

byedpi_version = os.environ.get('BYEDPI_VERSION', f'0.16.6+flowzapret.{byedpi_hash}')
tgws_version = os.environ.get('TGWS_VERSION', f'1.0.0+flowzapret.{tgws_hash}')
presets_version = os.environ.get('PRESETS_VERSION', f'{datetime.date.today():%Y.%m.%d}+{presets_hash}')

manifest = {
    'schema': 1,
    'generatedAt': datetime.datetime.now(datetime.timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'),
    'components': {
        'byedpi': {
            'version': byedpi_version,
            'nativeApi': 1,
            'minAppVersionCode': 2,
            'abis': {
                'arm64-v8a': artifact('libbyedpi-arm64-v8a.so'),
                'armeabi-v7a': artifact('libbyedpi-armeabi-v7a.so'),
            },
        },
        'tgwsproxy': {
            'version': tgws_version,
            'nativeApi': 1,
            'minAppVersionCode': 2,
            'abis': {
                'arm64-v8a': artifact('libtgwsproxy-arm64-v8a.so'),
                'armeabi-v7a': artifact('libtgwsproxy-armeabi-v7a.so'),
            },
        },
        'presets': {
            'version': presets_version,
            'nativeApi': 1,
            'minAppVersionCode': 2,
            'artifact': artifact('flowseal_presets.json'),
        },
    },
}

manifest_path = out / 'components.json'
manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

sig_der = out / '.components.json.sig.der'
subprocess.run([
    'openssl', 'dgst', '-sha256', '-sign', str(root / 'tools/keys/components_signing.pem'),
    '-out', str(sig_der), str(manifest_path),
], check=True)
(out / 'components.json.sig').write_text(
    base64.b64encode(sig_der.read_bytes()).decode('ascii') + '\n',
    encoding='ascii',
)
sig_der.unlink()

# Zip для ручного импорта.
zip_path = out.parent / 'flowzapret-components.zip'
with zipfile.ZipFile(zip_path, 'w', zipfile.ZIP_DEFLATED) as z:
    for path in sorted(out.iterdir()):
        if path.is_file() and not path.name.startswith('.'):
            z.write(path, arcname=path.name)

print('byedpi :', byedpi_version)
print('tgws   :', tgws_version)
print('presets:', presets_version)
print('zip    :', zip_path)
PY

echo "==> Done: $OUT"
ls -lh "$OUT"
