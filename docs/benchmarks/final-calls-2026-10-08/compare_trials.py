#!/usr/bin/env python3
# Copyright 2026 Vsevolod Petrov (lazyhat)
# SPDX-License-Identifier: Apache-2.0
"""Validate the archived paired trials and print timing comparisons as JSON."""
import csv
import json
from collections import defaultdict
from pathlib import Path
from statistics import median

ROOT = Path(__file__).resolve().parent
COUNTERS = ["hot_fixed_units", "hot_dynamic_units", "hot_maintenance_units", "hot_blocks",
            "hot_executed_instructions", "hot_retired_instructions", "hot_requests", "hot_responses"]
samples = defaultdict(list)
for row in csv.DictReader((ROOT / "samples.tsv").open(), delimiter="\t"):
    samples[row["id"], row["variant"], row["trial"]].append(row)
assert len(samples) == 36
work = {}
seen = set()
for row in csv.DictReader((ROOT / "measurements.tsv").open(), delimiter="\t"):
    key = row["id"], row["variant"], row["trial"]
    assert key not in seen
    seen.add(key)
    records = samples[key]
    assert {int(r["sample"]) for r in records} == set(range(1, 8)) and len(records) == 7
    assert all(r["mode"] == "untraced" and r["heap_bytes"] == row["heap_bytes"] for r in records)
    for raw, summary, operation in [
        ("create_ns", "create_median_ns", median),
        ("hot_ns", "hot_min_ns", min),
        ("hot_ns", "hot_median_ns", median),
        ("hot_ns", "hot_max_ns", max),
        ("create_cpu_ns", "create_cpu_median_ns", median),
        ("hot_cpu_ns", "hot_cpu_median_ns", median),
    ]:
        assert operation([int(r[raw]) for r in records]) == int(row[summary]), (key, summary)
    counters = [int(row[c]) for c in COUNTERS]
    assert work.setdefault((row["id"], row["variant"]), counters) == counters, (key, "work changed within variant")

assert seen == set(samples) and len(work) == 12
assert all((case, variant, trial) in samples for case in {case for case, _ in work}
           for variant in ["before", "after"] for trial in ["1", "2", "3"])
result = []
for case in sorted({case for case, _ in work}):
    before_work, after_work = work[case, "before"], work[case, "after"]
    assert before_work[1:] == after_work[1:], (case, "non-fixed work changed")
    assert after_work[0] < before_work[0], (case, "direct calls did not reduce fixed units")
    entry = {"id": case, "work": {variant: dict(zip(COUNTERS, work[case, variant]))
                                     for variant in ["before", "after"]}}
    for clock, field in [("wall", "hot_ns"), ("cpu", "hot_cpu_ns")]:
        values = {variant: [int(r[field]) for trial in ["1", "2", "3"]
                           for r in samples[case, variant, trial]] for variant in ["before", "after"]}
        before, after = median(values["before"]), median(values["after"])
        entry[clock] = {"before_median_ns": before, "after_median_ns": after,
                        "time_change_percent": 100 * (after / before - 1),
                        "before_min_max_ns": [min(values["before"]), max(values["before"])],
                        "after_min_max_ns": [min(values["after"]), max(values["after"])],
                        "round_time_change_percent": [100 * (
                            median([int(r[field]) for r in samples[case, "after", t]]) /
                            median([int(r[field]) for r in samples[case, "before", t]]) - 1
                        ) for t in ["1", "2", "3"]]}
    result.append(entry)
print(json.dumps(result, indent=2))
