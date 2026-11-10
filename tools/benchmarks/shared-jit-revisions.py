#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
"""Compare frozen JIT revisions through one executable inode and identical argv."""
import argparse
import csv
import fcntl
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess
import threading
import time


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rows(path):
    with path.open() as stream:
        return list(csv.DictReader(stream, delimiter="\t"))


def summarize(root):
    protocol = json.loads((root / "protocol.json").read_text())
    observations = []
    commands = {}
    inodes = set()
    for run in protocol["runs"]:
        inodes.add(run["inode"])
        revision = run["label"].split("-")[0]
        assert run["sha256"] == protocol["binaries"][revision]["sha256"]
        assert commands.setdefault(run["kind"], run["command"]) == run["command"]
        directory = root / run["directory"]
        for row in rows(directory / "measurements.tsv"):
            samples = [x for x in rows(directory / "samples.tsv") if x["id"] == row["id"]]
            assert len(samples) == protocol["samples"]
            cpu = statistics.median(int(x["hot_cpu_ns"]) for x in samples)
            assert cpu == int(row["hot_cpu_median_ns"])
            if protocol.get("telemetry", True):
                before, after = [x for x in rows(directory / "jit.tsv") if x["id"] == row["id"]]
                assert (before["phase"], after["phase"]) == ("warmup", "measured")
                for name in ["compile_ns", "compiled", "code_bytes", "mapped_bytes_upper_bound", "state_bytes"]:
                    assert before[name] == after[name], (run, row["id"], name, "tier changed")
                assert int(after["pending"]) == int(after["failures"]) == 0
                if protocol.get("mode", "jit-untraced") == "jit-untraced":
                    assert int(after["compiled"]) > 0
                    if after.get("hot_telemetry", "true") == "true":
                        assert int(after["hits"]) > int(before["hits"])
                    else:
                        assert int(after["hits"]) == int(after["native_attempts"]) == 0
                else:
                    assert int(after["compiled"]) == int(after["hits"]) == 0
            else:
                # A build without baseline-jit has no telemetry file. Do not
                # invent zero-valued observations for that independent baseline.
                assert protocol.get("mode", "jit-untraced") == "untraced"
                assert not (directory / "jit.tsv").exists()
                before = after = None
            work = {k: v for k, v in row.items() if k.startswith("hot_") and k.endswith(("units", "instructions", "blocks", "requests", "responses"))}
            assert len(work) == 8
            observations.append(dict(run, id=row["id"], cpu_ns=cpu, work=work, before=before, after=after))
    assert len(inodes) == 1
    assert len(protocol["runs"]) == protocol["rounds"] * 4 * len(protocol["inputs"])
    result = {}
    for case in sorted({x["id"] for x in observations}):
        selected = [x for x in observations if x["id"] == case]
        assert all(x["work"] == selected[0]["work"] for x in selected)
        rounds = []
        for index in range(protocol["rounds"]):
            group = {x["label"]: x["cpu_ns"] for x in selected if x["round"] == index}
            assert set(group) == {"old-a", "old-b", "new-a", "new-b"}
            old = statistics.median([group["old-a"], group["old-b"]])
            new = statistics.median([group["new-a"], group["new-b"]])
            aa = max(abs(group["old-a"] / group["old-b"] - 1), abs(group["new-a"] / group["new-b"] - 1))
            rounds.append(dict(old_ns=old, new_ns=new, change=new / old - 1, aa=aa))
        old = statistics.median(x["cpu_ns"] for x in selected if x["label"].startswith("old"))
        new = statistics.median(x["cpu_ns"] for x in selected if x["label"].startswith("new"))
        result[case] = dict(old_ns=old, new_ns=new, speedup=old / new, change=new / old - 1,
            qualified_gain=all(x["change"] < -x["aa"] and x["aa"] <= .10 for x in rounds), rounds=rounds,
            work=selected[0]["work"], telemetry=[dict(label=x["label"], round=x["round"], before=x["before"], after=x["after"]) for x in selected])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--old", type=Path)
    parser.add_argument("--new", type=Path)
    parser.add_argument("--numeric", type=Path)
    parser.add_argument("--collections", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--lock", action="append", type=Path, default=[])
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--samples", type=int, default=21)
    parser.add_argument("--cpu", type=int, default=6)
    parser.add_argument("--mode", choices=["jit-untraced", "untraced"], default="jit-untraced")
    parser.add_argument("--without-jit-telemetry", action="store_true",
                        help="both executables were built without baseline-jit; only valid for untraced")
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        assert summarize(args.output) == json.loads((args.output / "summary.json").read_text())
        print("PASS: fixed inode/argv, input revisions, samples, work, stable tiers and AA gates")
        return
    assert args.old and args.new and args.numeric and args.collections
    assert not args.without_jit_telemetry or args.mode == "untraced"
    assert args.rounds >= 3 and args.samples >= 3 and args.samples % 2 == 1
    args.output.mkdir(parents=True, exist_ok=False)
    locks = []
    for path in args.lock:
        stream = path.open("rb")
        fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        locks.append(stream)
    inputs = {"numeric": args.numeric.resolve(), "collections": args.collections.resolve()}
    binaries = {"old": args.old.resolve(), "new": args.new.resolve()}
    images = {name: path.read_bytes() for name, path in binaries.items()}
    protocol = dict(rounds=args.rounds, samples=args.samples, cpu=args.cpu, mode=args.mode, telemetry=not args.without_jit_telemetry,
        inputs={k: str(v) for k, v in inputs.items()}, locks=[str(x.resolve()) for x in args.lock],
        binaries={k: dict(path=str(v), sha256=sha(v)) for k, v in binaries.items()},
        artifacts={str(p): sha(p) for directory in inputs.values() for p in [directory / "manifest.tsv", *sorted(directory.glob("*.cpkt"))]}, runs=[])
    execution = args.output / "execution"
    execution.mkdir()
    binary = execution / "runner"
    binary.write_bytes(images["old"])
    binary.chmod(0o755)
    inode = binary.stat().st_ino
    stop = threading.Event()
    def monitor():
        with (args.output / "host-activity.jsonl").open("w") as output:
            while not stop.is_set():
                output.write(json.dumps(dict(epoch=time.time(), processes=subprocess.check_output(["ps", "-eo", "pid,pcpu,comm", "--sort=-pcpu"], text=True).splitlines()[:16])) + "\n")
                output.flush()
                stop.wait(2)
    watcher = threading.Thread(target=monitor, daemon=True)
    watcher.start()
    try:
        labels = ["old-a", "new-a", "new-b", "old-b"]
        for index in range(args.rounds):
            for label in labels[index:] + labels[:index]:
                revision = label.split("-")[0]
                # The previous child (including its compiler worker) has exited.
                # Preserve the inode and overwrite its prefix before truncating.
                with binary.open("r+b") as stream:
                    stream.write(images[revision])
                    stream.truncate()
                    stream.flush()
                    os.fsync(stream.fileno())
                assert binary.stat().st_ino == inode
                digest = sha(binary)
                assert digest == protocol["binaries"][revision]["sha256"]
                for kind, directory in inputs.items():
                    output = execution / f"output-{kind}"
                    assert not output.exists()
                    relative = f"round-{index+1}/{label}/{kind}"
                    destination = args.output / relative
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    command = ["taskset", "-c", str(args.cpu), str(binary.resolve()), str(directory), str(output.resolve()), str(args.samples), "65536", args.mode]
                    start = time.time()
                    with destination.with_suffix(".log").open("w") as log:
                        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True)
                    output.rename(destination)
                    protocol["runs"].append(dict(round=index, label=label, kind=kind, directory=relative, command=command, inode=inode, sha256=digest, start_epoch=start, end_epoch=time.time()))
                    (args.output / "protocol.json").write_text(json.dumps(protocol, indent=2) + "\n")
                    print(f"round {index+1}/{args.rounds}: {label} {kind}", flush=True)
        result = summarize(args.output)
        (args.output / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps({k: {name: x for name, x in v.items() if name in ["old_ns", "new_ns", "change", "speedup", "qualified_gain"]} for k, v in result.items()}, indent=2))
    finally:
        stop.set()
        watcher.join()


if __name__ == "__main__":
    main()
