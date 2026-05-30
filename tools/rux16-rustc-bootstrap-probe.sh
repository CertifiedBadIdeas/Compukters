#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_SRC="${RUX16_RUST_SRC:-$ROOT/toolchains/Compukter-Kraft-rust}"
LLVM_CONFIG="${RUX16_LLVM_CONFIG:-$ROOT/toolchains/Compukter-Kraft-llvm/build-rux-min/bin/llvm-config}"
EXPECTED_BRANCH="rux16"
BUILD_DIR="${RUX16_RUST_BUILD_DIR:-$RUST_SRC/build/rux16}"
HOST_TRIPLE="${RUX16_RUST_HOST:-x86_64-unknown-linux-gnu}"

require_dir() {
    local path="$1"
    if [[ ! -d "$path" ]]; then
        echo "required directory is missing: $path" >&2
        exit 1
    fi
}

require_executable() {
    local path="$1"
    if [[ ! -x "$path" ]]; then
        echo "required executable is missing: $path" >&2
        exit 1
    fi
}

require_output_contains() {
    local label="$1"
    local output="$2"
    local expected="$3"
    if ! grep -Fq "$expected" <<< "$output"; then
        echo "expected $label to contain '$expected'" >&2
        echo "----- $label -----" >&2
        printf '%s\n' "$output" >&2
        exit 1
    fi
}

require_dir "$RUST_SRC"
require_executable "$RUST_SRC/x.py"
require_executable "$LLVM_CONFIG"

if ! git -C "$RUST_SRC" rev-parse --is-inside-work-tree > /dev/null 2>&1; then
    echo "Rust source is not a git checkout: $RUST_SRC" >&2
    exit 1
fi

current_branch="$(git -C "$RUST_SRC" branch --show-current)"
if [[ "$current_branch" != "$EXPECTED_BRANCH" ]]; then
    echo "Rust source must be on branch '$EXPECTED_BRANCH', current branch is '$current_branch'." >&2
    echo "Run: git -C $RUST_SRC switch $EXPECTED_BRANCH" >&2
    exit 1
fi

llvm_version="$("$LLVM_CONFIG" --version)"
llvm_targets="$("$LLVM_CONFIG" --targets-built)"
require_output_contains "llvm-config --targets-built" "$llvm_targets" "Rux16"

"$RUST_SRC/x.py" --help > /dev/null

bootstrap_config="$(mktemp /tmp/rux16-rust-bootstrap.XXXXXX.toml)"

cat > "$bootstrap_config" <<TOML
[build]
build-dir = "$BUILD_DIR"

[llvm]
download-ci-llvm = false

[target.$HOST_TRIPLE]
llvm-config = "$LLVM_CONFIG"
TOML

echo "Rux16 rustc bootstrap probe passed"
echo "Rust source: $RUST_SRC"
echo "Rust branch: $current_branch"
echo "LLVM config: $LLVM_CONFIG"
echo "LLVM version: $llvm_version"
echo "LLVM targets: $llvm_targets"
echo
echo "Bootstrap config written to: $bootstrap_config"
echo
echo "Dry-run command:"
echo "  cd $RUST_SRC"
echo "  ./x.py build --config $bootstrap_config --dry-run compiler/rustc"
echo
echo "Build command:"
echo "  cd $RUST_SRC"
echo "  ./x.py build --config $bootstrap_config compiler/rustc"
