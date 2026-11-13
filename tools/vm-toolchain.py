#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
"""Prepare reusable VM runners and run prepared Guest workloads."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
VM = ROOT / "host/compukter-vm"
TOOLS = ROOT / ".toolchain/vm-toolchain"
SUITES = {
    "collections": "generateCollectionBenchmarkArtifacts",
    "sequences": "generateSequenceBenchmarkArtifacts",
    "object-arrays": "generateObjectArrayBenchmarkArtifacts",
    "object-collections": "generateObjectCollectionBenchmarkArtifacts",
    "transient-allocations": "generateTransientAllocationBenchmarkArtifacts",
    "collection-reuse": "generateCollectionReuseBenchmarkArtifacts",
    "typed-execution": "generateExecutionBenchmarkArtifacts",
    "fleet-collections": "generateFleetCollectionArtifact",
}
RUNNERS = {
    "timing": ["guest-benchmark", "baseline-jit"],
    "memory": ["guest-benchmark", "baseline-jit", "program-sharing-benchmark"],
    "diagnostic": ["guest-benchmark", "baseline-jit", "jit-diagnostics"],
}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def git_identity(directory):
    def git(*args):
        return subprocess.check_output(["git", *args], cwd=directory, text=True).strip()
    return {"commit": git("rev-parse", "HEAD"), "dirty": bool(git("status", "--porcelain"))}


def runner_path(kind):
    extension = ".exe" if os.name == "nt" else ""
    return TOOLS / "bin" / (kind + extension)


def prepare(runners, suites):
    if not runners and not suites:
        raise ValueError("select at least one --runner or --suite")
    for kind in dict.fromkeys(runners):
        target = ROOT / ".toolchain/build/cargo" / ("vm-toolchain-" + kind)
        env = dict(os.environ, CARGO_TARGET_DIR=str(target))
        subprocess.run(
            ["cargo", "build", "--release", "--locked", "--bin", "guest-benchmark",
             "--features", ",".join(RUNNERS[kind])], cwd=VM, env=env, check=True,
        )
        built = target / "release" / ("guest-benchmark.exe" if os.name == "nt" else "guest-benchmark")
        destination = runner_path(kind)
        destination.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=destination.parent, delete=False) as temporary:
            staging = Path(temporary.name)
        try:
            shutil.copy2(built, staging)
            os.replace(staging, destination)
        finally:
            staging.unlink(missing_ok=True)
        info = {"runner": kind, "features": RUNNERS[kind], "sha256": sha256(destination),
                "vm": git_identity(VM)}
        destination.with_suffix(destination.suffix + ".json").write_text(json.dumps(info, indent=2) + "\n")
        print(f"Prepared {kind}: {destination}", flush=True)
    if suites:
        tasks = [":compiler-k2:" + SUITES[name] for name in dict.fromkeys(suites)]
        subprocess.run([str(ROOT / "gradlew-sandbox-dev-parallel-summary"), *tasks], cwd=ROOT, check=True)
        for name in dict.fromkeys(suites):
            path = suite_path(name)
            workload_hashes(path)
            print(f"Prepared {name}: {path}", flush=True)


def suite_path(name):
    return ROOT / "modules/common/compiler-k2/build/generated/benchmarks" / name


def workload_hashes(directory):
    directory = directory.resolve(strict=True)
    manifest = directory / "manifest.tsv"
    if not manifest.is_file():
        raise ValueError(f"missing workload manifest: {manifest}")
    files = sorted(p for p in directory.iterdir() if p.is_file() and p.suffix in (".cpkt", ".kt", ".tsv"))
    if not any(p.suffix == ".cpkt" for p in files):
        raise ValueError(f"no compiled workloads in {directory}")
    return {p.name: sha256(p) for p in files}


def checked_runner(kind):
    path = runner_path(kind)
    descriptor = path.with_suffix(path.suffix + ".json")
    if not path.is_file() or not descriptor.is_file():
        raise ValueError(f"runner {kind} is not prepared")
    info = json.loads(descriptor.read_text())
    if info.get("runner") != kind or info.get("sha256") != sha256(path):
        raise ValueError(f"runner {kind} does not match its preparation identity")
    return path, info


def result_directory(path):
    path = path.expanduser().resolve()
    if path.is_relative_to(ROOT.resolve()) or any(
        (parent / ".git").is_file() or (parent / ".git/HEAD").is_file()
        for parent in (path, *path.parents)
    ):
        raise ValueError("measurement results must be outside the source checkout")
    path.mkdir(parents=True, exist_ok=False)
    return path


def run(args):
    runner, info = checked_runner(args.runner)
    inputs = (args.inputs or suite_path(args.suite)).resolve()
    hashes = workload_hashes(inputs)
    if args.command in ("run", "diagnose"):
        expected = "timing" if args.command == "run" else "diagnostic"
        if args.runner != expected:
            raise ValueError(f"{args.command} requires the {expected} runner")
        if args.samples < 3 or args.samples % 2 == 0:
            raise ValueError("samples must be an odd number of at least 3")
        if args.heap < 32 or args.heap % 16:
            raise ValueError("heap must be at least 32 bytes and a multiple of 16")
        output = result_directory(args.output)
        command = [str(runner), str(inputs), str(output), str(args.samples), str(args.heap), args.mode]
        if args.filter:
            command.append(args.filter)
    else:
        if args.runner == "diagnostic":
            raise ValueError("fleet measurements require the timing or memory runner")
        if args.population < 1 or args.programs < 1 or args.workers < 1 or args.batches < 1:
            raise ValueError("population, programs, workers and batches must be positive")
        if args.heap < 32 or args.heap % 16:
            raise ValueError("heap must be at least 32 bytes and a multiple of 16")
        output = result_directory(args.output)
        command = [str(runner), "jit-mixed-fleet", str(inputs), str(output), str(args.population),
                   str(args.programs), str(args.workers), str(args.heap), args.mode, args.order,
                   str(args.batches), args.phase]
    identity = {"runner": info, "inputs": hashes, "input_directory": str(inputs), "command": command,
                "source": git_identity(ROOT)}
    (output / "toolchain-identity.json").write_text(json.dumps(identity, indent=2) + "\n")
    subprocess.run(command, check=True)
    print(f"Results: {output}", flush=True)


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="command", required=True)
    prep = sub.add_parser("prepare", help="build selected runners and Guest workloads")
    prep.add_argument("--runner", action="append", choices=RUNNERS, default=[])
    prep.add_argument("--suite", action="append", choices=SUITES, default=[])
    for name in ("run", "diagnose", "fleet"):
        command = sub.add_parser(name, help="run an already prepared native runner")
        command.add_argument("--runner", choices=RUNNERS, default="diagnostic" if name == "diagnose" else "timing")
        inputs = command.add_mutually_exclusive_group(required=True)
        inputs.add_argument("--suite", choices=SUITES)
        inputs.add_argument("--inputs", type=Path)
        command.add_argument("--output", type=Path, required=True)
        command.add_argument("--heap", type=int, default=65536)
        if name in ("run", "diagnose"):
            command.add_argument("--samples", type=int, default=5)
            command.add_argument("--mode", choices=("traced", "untraced", "jit-traced", "jit-untraced"), default="jit-untraced")
            command.add_argument("--filter")
        else:
            command.add_argument("--population", type=int, default=2000)
            command.add_argument("--programs", type=int, default=1)
            command.add_argument("--workers", type=int, default=1)
            command.add_argument("--batches", type=int, default=20)
            command.add_argument("--mode", choices=("off", "on"), default="on")
            command.add_argument("--order", choices=("forward", "reverse"), default="forward")
            command.add_argument("--phase", choices=("warm", "cold"), default="warm")
    return p


def main():
    args = parser().parse_args()
    try:
        if args.command == "prepare":
            prepare(args.runner, args.suite)
        else:
            run(args)
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"vm-toolchain: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
