#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
RUNS=5
REQUIRED_WINS=4
MIN_IMPROVEMENT_PERCENT=2
ITERATIONS="${1:-1000}"
SAMPLES="${2:-21}"
ARTIFACTS="$(mktemp -d)"
trap 'rm -rf "$ARTIFACTS"' EXIT HUP INT TERM
RATIOS="$ARTIFACTS/ratios.tsv"
: > "$RATIOS"

for run in $(seq 1 "$RUNS"); do
    cargo run --manifest-path "$ROOT/host/compukter-vm/Cargo.toml" --release \
        --example rv32_decoder_benchmarks -- "$ITERATIONS" "$SAMPLES" \
        > "$ARTIFACTS/run-$run.txt"
    echo "===== decoder extraction run $run ====="
    cat "$ARTIFACTS/run-$run.txt"
    awk -F '\t' -v run="$run" '
        $1 == "legal-decode" && $2 == "eager" { legal_eager = $4 }
        $1 == "legal-decode" && $2 == "opcode-first" { legal_product = $4 }
        $1 == "bounded-cache-forced-miss" && $2 == "eager" { cache_eager = $4 }
        $1 == "bounded-cache-forced-miss" && $2 == "opcode-first" { cache_product = $4 }
        END {
            if (!legal_eager || !legal_product || !cache_eager || !cache_product) exit 2
            printf "legal-decode\t%d\t%.0f\t%.0f\t%.6f\n",
                run, legal_eager, legal_product,
                100.0 * (legal_eager - legal_product) / legal_eager
            printf "bounded-cache-forced-miss\t%d\t%.0f\t%.0f\t%.6f\n",
                run, cache_eager, cache_product,
                100.0 * (cache_eager - cache_product) / cache_eager
        }
    ' "$ARTIFACTS/run-$run.txt" >> "$RATIOS"
done

accepted=1
echo "===== decoder extraction gate summary ====="
echo $'scenario\twins\tmedian_improvement_percent\tdecision'
for scenario in legal-decode bounded-cache-forced-miss; do
    wins="$(awk -F '\t' -v scenario="$scenario" '
        $1 == scenario && $4 < $3 { wins += 1 }
        END { print wins + 0 }
    ' "$RATIOS")"
    median="$(awk -F '\t' -v scenario="$scenario" '$1 == scenario { print $5 }' \
        "$RATIOS" | sort -n | awk 'NR == 3 { print $1 }')"
    decision=accepted
    if [[ "$wins" -lt "$REQUIRED_WINS" ]] || \
        ! awk -v actual="$median" -v minimum="$MIN_IMPROVEMENT_PERCENT" \
            'BEGIN { exit !(actual + 0 >= minimum + 0) }'; then
        decision=rejected
        accepted=0
    fi
    printf '%s\t%s\t%s\t%s\n' "$scenario" "$wins" "$median" "$decision"
done

if [[ "$accepted" -ne 1 ]]; then
    exit 1
fi
