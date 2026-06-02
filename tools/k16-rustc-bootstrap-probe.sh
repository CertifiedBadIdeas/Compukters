#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUST_SRC="${K16_RUST_SRC:-$ROOT/toolchains/Compukter-Kraft-rust}"
LLVM_CONFIG="${K16_LLVM_CONFIG:-$ROOT/.toolchain/build/llvm/k16-min/bin/llvm-config}"
LLVM_BIN_DIR="$(cd "$(dirname "$LLVM_CONFIG")" && pwd)"
EXPECTED_BRANCH="k16"
BUILD_DIR="${K16_RUST_BUILD_DIR:-$ROOT/.toolchain/build/rust/k16}"
HOST_TRIPLE="${K16_RUST_HOST:-x86_64-unknown-linux-gnu}"
REQUIRED_LLVM_TOOLS=(
    llvm-ar
    llvm-as
    llvm-cov
    llvm-dis
    llvm-link
    llvm-nm
    llvm-objcopy
    llvm-objdump
    llvm-profdata
    llvm-readobj
    llvm-size
    llvm-strip
    llc
    opt
)

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

for tool in "${REQUIRED_LLVM_TOOLS[@]}"; do
    require_executable "$LLVM_BIN_DIR/$tool"
done

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
llvm_obj_root="$("$LLVM_CONFIG" --obj-root)"
require_output_contains "llvm-config --targets-built" "$llvm_targets" "K16"

cmake_cache="$llvm_obj_root/CMakeCache.txt"
if [[ ! -f "$cmake_cache" ]]; then
    echo "LLVM CMake cache is missing: $cmake_cache" >&2
    exit 1
fi

llvm_source_dir="$(sed -n 's/^LLVM_SOURCE_DIR:STATIC=//p' "$cmake_cache" | head -n 1)"
if [[ -z "$llvm_source_dir" ]]; then
    echo "LLVM_SOURCE_DIR is missing from $cmake_cache" >&2
    exit 1
fi

if ! git -C "$llvm_source_dir" rev-parse --is-inside-work-tree > /dev/null 2>&1; then
    echo "LLVM source is not a git checkout: $llvm_source_dir" >&2
    exit 1
fi

expected_llvm_commit="$(git -C "$RUST_SRC" ls-tree HEAD src/llvm-project | awk '{ print $3 }')"
if [[ -z "$expected_llvm_commit" ]]; then
    echo "Rust source does not pin src/llvm-project at HEAD." >&2
    exit 1
fi

if ! git -C "$llvm_source_dir" cat-file -e "$expected_llvm_commit^{commit}" 2> /dev/null; then
    echo "K16 LLVM source does not contain Rust-pinned llvm-project commit: $expected_llvm_commit" >&2
    echo "LLVM source: $llvm_source_dir" >&2
    exit 1
fi

if ! git -C "$llvm_source_dir" merge-base --is-ancestor "$expected_llvm_commit" HEAD; then
    echo "K16 LLVM HEAD is not based on Rust-pinned llvm-project commit: $expected_llvm_commit" >&2
    echo "LLVM source: $llvm_source_dir" >&2
    exit 1
fi

"$RUST_SRC/x.py" --help > /dev/null

bootstrap_config="$(mktemp /tmp/k16-rust-bootstrap.XXXXXX.toml)"

cat > "$bootstrap_config" <<TOML
[build]
build-dir = "$BUILD_DIR"

[llvm]
download-ci-llvm = false

[target.$HOST_TRIPLE]
llvm-config = "$LLVM_CONFIG"
TOML

echo "K16 rustc bootstrap probe passed"
echo "Rust source: $RUST_SRC"
echo "Rust branch: $current_branch"
echo "LLVM config: $LLVM_CONFIG"
echo "LLVM source: $llvm_source_dir"
echo "LLVM version: $llvm_version"
echo "LLVM targets: $llvm_targets"
echo "Rust-pinned LLVM commit: $expected_llvm_commit"
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
