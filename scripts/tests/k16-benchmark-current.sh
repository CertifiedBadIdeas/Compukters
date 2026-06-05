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
    mkdir -p "$repo/rust/host/k16-vm" "$repo/docs/benchmarks" "$repo/scripts/git-hooks"
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

test_pre_commit_hook_stages_snapshot() {
    repo="$TMPDIR/hook-repo"
    fake_bin="$TMPDIR/hook-bin"
    cargo_log="$TMPDIR/hook-cargo.log"
    create_temp_repo "$repo"
    create_fake_cargo "$fake_bin" "$cargo_log"
    cp "$ROOT/scripts/record-k16-vm-benchmark-current.sh" "$repo/scripts/record-k16-vm-benchmark-current.sh"
    cp "$ROOT/scripts/git-hooks/pre-commit" "$repo/scripts/git-hooks/pre-commit"
    chmod +x "$repo/scripts/record-k16-vm-benchmark-current.sh" "$repo/scripts/git-hooks/pre-commit"
    git -C "$repo" add scripts/record-k16-vm-benchmark-current.sh scripts/git-hooks/pre-commit

    (
        cd "$repo"
        FAKE_CARGO_LOG="$cargo_log" PATH="$fake_bin:$PATH" \
            scripts/git-hooks/pre-commit
    )

    git -C "$repo" diff --cached --name-only | grep -F "docs/benchmarks/k16-vm-current.txt" >/dev/null ||
        fail "pre-commit hook did not stage current snapshot"
}

test_pre_commit_hook_rejects_unstaged_changes() {
    repo="$TMPDIR/dirty-repo"
    fake_bin="$TMPDIR/dirty-bin"
    cargo_log="$TMPDIR/dirty-cargo.log"
    create_temp_repo "$repo"
    create_fake_cargo "$fake_bin" "$cargo_log"
    cp "$ROOT/scripts/record-k16-vm-benchmark-current.sh" "$repo/scripts/record-k16-vm-benchmark-current.sh"
    cp "$ROOT/scripts/git-hooks/pre-commit" "$repo/scripts/git-hooks/pre-commit"
    chmod +x "$repo/scripts/record-k16-vm-benchmark-current.sh" "$repo/scripts/git-hooks/pre-commit"
    printf 'clean\n' >"$repo/tracked.txt"
    git -C "$repo" add tracked.txt scripts/record-k16-vm-benchmark-current.sh scripts/git-hooks/pre-commit
    git -C "$repo" commit -q --no-verify -m "seed"
    printf 'dirty\n' >"$repo/tracked.txt"

    if (
        cd "$repo"
        FAKE_CARGO_LOG="$cargo_log" PATH="$fake_bin:$PATH" \
            scripts/git-hooks/pre-commit >"$TMPDIR/dirty-hook.out" 2>&1
    ); then
        fail "pre-commit hook accepted unrelated unstaged changes"
    fi

    assert_file_contains "$TMPDIR/dirty-hook.out" "unstaged changes"
}

test_recorder_writes_plaintext_snapshot
test_pre_commit_hook_stages_snapshot
test_pre_commit_hook_rejects_unstaged_changes

echo "k16 benchmark current snapshot tests passed"
