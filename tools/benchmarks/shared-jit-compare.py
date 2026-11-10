#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
"""Compare one experimental JIT binary with its interpreter mode; retain all observations."""
import argparse
import csv
import fcntl
import hashlib
import json
from pathlib import Path
import platform
import statistics
import subprocess
import time
import threading


def rows(path):
    with path.open() as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def summarize(root):
    observations = []
    protocol = json.loads((root / "protocol.json").read_text())
    for run in protocol["runs"]:
        directory = root / run["directory"]
        summary = rows(directory / "measurements.tsv")
        telemetry = rows(directory / "jit.tsv")
        for row in summary:
            samples = [x for x in rows(directory / "samples.tsv") if x["id"] == row["id"]]
            assert len(samples) == protocol["samples"]
            cpu = statistics.median(int(x["hot_cpu_ns"]) for x in samples)
            assert cpu == int(row["hot_cpu_median_ns"])
            before, after = [x for x in telemetry if x["id"] == row["id"]]
            assert before["phase"] == "warmup" and after["phase"] == "measured"
            for name in ["compile_ns", "compiled", "code_bytes", "mapped_bytes_upper_bound", "state_bytes"]:
                assert before[name] == after[name], (run, row["id"], name, "tier changed during timing")
            assert int(after["pending"]) == int(after["failures"]) == 0
            if run["label"].startswith("on") or protocol.get("off_mode", "untraced") != "untraced":
                assert int(after["compiled"]) > 0
                if after.get("hot_telemetry", "true") == "true":
                    assert int(after["hits"]) > int(before["hits"])
                else:
                    assert int(after["hits"]) == int(after["native_attempts"]) == 0
            else:
                assert int(after["compiled"]) == int(after["hits"]) == 0
            observations.append(dict(run, id=row["id"], cpu_ns=cpu, work={k: v for k, v in row.items() if k.startswith("hot_") and (k.endswith("units") or k.endswith("instructions") or k.endswith("blocks") or k.endswith("requests") or k.endswith("responses"))}, telemetry=after))
    result = {}
    for case in sorted({x["id"] for x in observations}):
        selected = [x for x in observations if x["id"] == case]
        assert all(x["work"] == selected[0]["work"] for x in selected), case
        rounds = []
        for round_index in range(protocol["rounds"]):
            group = {x["label"]: x["cpu_ns"] for x in selected if x["round"] == round_index}
            assert set(group) == {"off-a", "off-b", "on-a", "on-b"}
            before = statistics.median([group["off-a"], group["off-b"]])
            after = statistics.median([group["on-a"], group["on-b"]])
            aa = max(abs(group["off-a"] / group["off-b"] - 1), abs(group["on-a"] / group["on-b"] - 1))
            rounds.append(dict(off_ns=before, on_ns=after, change=after / before - 1, aa=aa))
        off = statistics.median(x["cpu_ns"] for x in selected if x["label"].startswith("off"))
        on = statistics.median(x["cpu_ns"] for x in selected if x["label"].startswith("on"))
        result[case] = dict(off_ns=off, on_ns=on, change=on / off - 1, speedup=off / on,
            qualified_gain=all(x["change"] < -x["aa"] and x["aa"] <= .10 for x in rounds), rounds=rounds,
            work=selected[0]["work"], code=[x["telemetry"] for x in selected if x["label"].startswith("on")])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path)
    parser.add_argument("--numeric", type=Path)
    parser.add_argument("--collections", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--lock", type=Path, action="append", default=[])
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--samples", type=int, default=21)
    parser.add_argument("--cpu", type=int, default=6)
    parser.add_argument("--off-mode", choices=["untraced", "jit-no-inline-untraced", "jit-no-ssa-untraced", "jit-no-block-budget-untraced"], default="untraced",
                        help="reference arm: interpreter, or JIT with cold leaf/SSA/block-budget optimization disabled")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        actual = summarize(args.output)
        expected = json.loads((args.output / "summary.json").read_text())
        assert actual == expected
        print("PASS: sample medians, stable JIT tiers, same work and A/A gates")
        return
    assert args.binary and args.numeric and args.collections
    assert args.rounds >= 3 and args.samples >= 3 and args.samples % 2 == 1
    args.output.mkdir(parents=True, exist_ok=False)
    locks = []
    for path in args.lock:
        stream = path.open("rb")
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        locks.append(stream)
    inputs = {"numeric": args.numeric.resolve(), "collections": args.collections.resolve()}
    protocol = dict(binary=str(args.binary.resolve()), binary_sha256=sha(args.binary),
        rounds=args.rounds, samples=args.samples, cpu=args.cpu, off_mode=args.off_mode, locks=[str(p.resolve()) for p in args.lock],
        platform=platform.platform(), runs=[], artifacts={str(path): sha(path) for directory in inputs.values() for path in directory.glob("*.cpkt")})
    (args.output / "host-before.txt").write_text(subprocess.check_output(["ps", "-eo", "pid,pcpu,comm", "--sort=-pcpu"], text=True))
    def monitor():
        with (args.output / "host-activity.jsonl").open("w") as output:
            while not stop.is_set():
                snapshot = subprocess.check_output(["ps", "-eo", "pid,pcpu,comm", "--sort=-pcpu"], text=True).splitlines()[:16]
                output.write(json.dumps(dict(epoch=time.time(), processes=snapshot)) + "\n")
                output.flush()
                stop.wait(2)
    stop = threading.Event()
    watcher = threading.Thread(target=monitor, daemon=True)
    watcher.start()
    labels = ["off-a", "on-a", "on-b", "off-b"]
    for round_index in range(args.rounds):
        order = labels[round_index:] + labels[:round_index]
        for label in order:
            for kind, directory in inputs.items():
                relative = f"round-{round_index + 1}/{label}/{kind}"
                destination = args.output / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                command = ["taskset", "-c", str(args.cpu), str(args.binary.resolve()), str(directory), str(destination.resolve()), str(args.samples), "65536", "jit-untraced" if label.startswith("on") else args.off_mode]
                start = time.time()
                with destination.with_suffix(".log").open("w") as log:
                    subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
                protocol["runs"].append(dict(round=round_index, label=label, kind=kind, directory=relative, command=command, start_epoch=start, end_epoch=time.time()))
                (args.output / "protocol.json").write_text(json.dumps(protocol, indent=2) + "\n")
                print(f"round {round_index + 1}/{args.rounds}: {label} {kind}", flush=True)
    (args.output / "host-after.txt").write_text(subprocess.check_output(["ps", "-eo", "pid,pcpu,comm", "--sort=-pcpu"], text=True))
    stop.set()
    watcher.join()
    result = summarize(args.output)
    (args.output / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({key: {k: v for k, v in row.items() if k in ["off_ns", "on_ns", "speedup", "change", "qualified_gain"]} for key, row in result.items()}, indent=2))


if __name__ == "__main__":
    main()
