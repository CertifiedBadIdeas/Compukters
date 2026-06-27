#!/usr/bin/env sh
set -eu

ROOT="$(git rev-parse --show-toplevel)"
TMPDIR="$(mktemp -d)"
trap 'rm -rf "$TMPDIR"' EXIT

fail() {
    echo "FAIL: $*" >&2
    exit 1
}

assert_file_contains() {
    file="$1"
    text="$2"
    if ! grep -F "$text" "$file" >/dev/null; then
        echo "Expected $file to contain: $text" >&2
        echo "--- $file ---" >&2
        cat "$file" >&2
        fail "missing expected text"
    fi
}

create_fake_cargo() {
    bin_dir="$1"
    log_file="$2"
    mkdir -p "$bin_dir"
    cat >"$bin_dir/cargo" <<'EOF'
#!/usr/bin/env sh
set -eu
printf '%s\n' "$*" >"$FAKE_CARGO_LOG"
cat <<'OUTPUT'
workload       vm           iterations   checksum   best_nanos   nanos/iter  vs_native  native_pct
compute-loop   k16                  10         10          420       42.000   210.000x    21000.0%
compute-loop   native-rust          10         10            2        0.200     1.000x      100.0%
OUTPUT
EOF
    chmod +x "$bin_dir/cargo"
}

create_temp_repo() {
    repo="$1"
    mkdir -p "$repo/host/k16-vm" "$repo/docs/benchmarks"
    git -C "$repo" init -q
    git -C "$repo" config user.email "test@example.invalid"
    git -C "$repo" config user.name "Benchmark Test"
}

test_recorder_writes_plaintext_snapshot() {
    repo="$TMPDIR/recorder-repo"
    fake_bin="$TMPDIR/recorder-bin"
    cargo_log="$TMPDIR/recorder-cargo.log"
    create_temp_repo "$repo"
    create_fake_cargo "$fake_bin" "$cargo_log"

    (
        cd "$repo"
        FAKE_CARGO_LOG="$cargo_log" PATH="$fake_bin:$PATH" \
            "$ROOT/scripts/record-k16-vm-benchmark-current.sh" 10 2
    )

    snapshot="$repo/docs/benchmarks/k16-vm-current.txt"
    test -f "$snapshot" || fail "snapshot was not written"
    assert_file_contains "$snapshot" "K16 VM benchmark current snapshot"
    assert_file_contains "$snapshot" "Command: cargo run --release --example vm_microbenchmarks -- 10 2"
    assert_file_contains "$snapshot" "workload       vm"
    assert_file_contains "$snapshot" "compute-loop   k16"
    assert_file_contains "$cargo_log" "run --release --example vm_microbenchmarks -- 10 2"
}

test_benchmark_snapshot_is_not_a_commit_hook() {
    if [ -e "$ROOT/scripts/git-hooks/pre-commit" ]; then
        fail "benchmark snapshot pre-commit hook is still shipped"
    fi

    if [ -e "$ROOT/scripts/install-git-hooks.sh" ]; then
        fail "benchmark snapshot hook installer is still shipped"
    fi
}

test_recorder_writes_plaintext_snapshot
test_benchmark_snapshot_is_not_a_commit_hook

echo "k16 benchmark current snapshot tests passed"
