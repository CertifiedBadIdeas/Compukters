#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# Licensed under the Apache License, Version 2.0.
"""ABBA control for the same JIT revision with optional shared hot telemetry."""
import argparse
import csv
import fcntl
import hashlib
import json
from pathlib import Path
import statistics
import subprocess


def rows(path):
    return list(csv.DictReader(path.open(), delimiter='\t'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def summarize(root):
    protocol = json.loads((root/'protocol.json').read_text())
    assert protocol['complete'] and len(protocol['runs']) == 36
    values = []
    checks = None
    commands = {}
    for run in protocol['runs']:
        assert commands.setdefault(run['workers'], run['command']) == run['command']
        assert run['inode'] == protocol['inode'] and run['sha256'] == protocol['binaries'][run['label'].split('-')[0]]['sha256']
        directory = root/run['directory']
        current = rows(directory/'checks.tsv')
        if checks is None:
            checks = current
        assert checks == current
        batches = rows(directory/'batches.tsv')
        assert len(batches) == 20 and all(int(b['retired']) == 8192000 for b in batches)
        telemetry = rows(directory/'jit.tsv')
        before, = [s for s in telemetry if s['phase'] == 'before']
        after, = [s for s in telemetry if s['phase'] == 'after']
        keys = ['compiled','regions','pending','failures','code_bytes','mapped_bytes','state_bytes','compile_ns']
        assert all(before[k] == after[k] for k in keys)
        assert int(after['pending']) == int(after['failures']) == 0
        assert int(after['compiled']) > 0
        if run['label'].startswith('hot'):
            assert after['hot_telemetry'] == 'true' and int(after['native_attempts']) > int(before['native_attempts'])
        else:
            assert after['hot_telemetry'] == 'false' and int(after['hits']) == int(after['native_attempts']) == 0
        values.append(dict(run,cpu_ns=statistics.median(int(b['cpu_ns']) for b in batches),
            wall_ns=statistics.median(int(b['wall_ns']) for b in batches),
            coverage={k:after[k] for k in ['compiled','regions','code_bytes','mapped_bytes','state_bytes']}))
    result = []
    for workers in [1,4,8]:
        selected = [v for v in values if v['workers'] == workers]
        rounds = []
        for rnd in range(3):
            group = {s['label']:s for s in selected if s['round'] == rnd}
            assert set(group) == {'hot-a','hot-b','plain-a','plain-b'}
            same_coverage = all(s['coverage'] == group['hot-a']['coverage'] for s in group.values())
            aa = max(abs(group['hot-a']['cpu_ns']/group['hot-b']['cpu_ns']-1),abs(group['plain-a']['cpu_ns']/group['plain-b']['cpu_ns']-1))
            hot = statistics.median(group[k]['cpu_ns'] for k in ['hot-a','hot-b'])
            plain = statistics.median(group[k]['cpu_ns'] for k in ['plain-a','plain-b'])
            rounds.append(dict(cpu_change=plain/hot-1,aa=aa,same_coverage=same_coverage))
        hot = statistics.median(v['cpu_ns'] for v in selected if v['label'].startswith('hot'))
        plain = statistics.median(v['cpu_ns'] for v in selected if v['label'].startswith('plain'))
        result.append(dict(workers=workers,hot_cpu_ns=hot,plain_cpu_ns=plain,cpu_change=plain/hot-1,
            qualified=all(r['same_coverage'] and r['aa'] < .1 and r['cpu_change'] < -r['aa'] for r in rounds),rounds=rounds))
    return dict(comparisons=result,observations=values)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ['hot','plain','inputs']:
        p.add_argument('--'+name,type=Path)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--lock',type=Path,action='append',default=[])
    p.add_argument('--check',action='store_true')
    args = p.parse_args()
    if args.check:
        assert summarize(args.output) == json.loads((args.output/'summary.json').read_text())
        print('PASS: ABBA, fixed inode/argv, exact state, stable coverage, optional counters and AA qualification')
        return
    args.output.mkdir(parents=True,exist_ok=False)
    held = []
    for path in args.lock:
        stream = path.open('rb');fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB);held.append(stream)
    binaries = {'hot':args.hot.resolve(),'plain':args.plain.resolve()}
    runner = args.output/'runner'
    runner.write_bytes(binaries['hot'].read_bytes());runner.chmod(0o755)
    protocol = dict(complete=False,inode=runner.stat().st_ino,locks=[str(p.resolve()) for p in args.lock],inputs={p.name:sha(p) for p in sorted(args.inputs.iterdir()) if p.suffix in ['.cpkt','.kt','.tsv']},binaries={k:dict(path=str(v),sha256=sha(v)) for k,v in binaries.items()},runs=[])
    output = args.output/'output'
    for rnd in range(3):
        labels = ['hot-a','plain-a','plain-b','hot-b']
        for label in labels[rnd:]+labels[:rnd]:
            binary = binaries[label.split('-')[0]]
            with runner.open('r+b') as stream:
                stream.write(binary.read_bytes());stream.truncate()
            assert runner.stat().st_ino == protocol['inode']
            for workers in [1,4,8]:
                command = [str(runner.resolve()),'jit-mixed-fleet',str(args.inputs.resolve()),str(output.resolve()),'2000','1',str(workers),'65536','on','forward','20']
                relative = f'round-{rnd+1}/{label}/w{workers}'
                destination = args.output/relative;destination.parent.mkdir(parents=True,exist_ok=True)
                with destination.with_suffix('.log').open('w') as log:
                    subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=90)
                output.rename(destination)
                protocol['runs'].append(dict(round=rnd,label=label,workers=workers,command=command,directory=relative,inode=runner.stat().st_ino,sha256=sha(runner)))
                (args.output/'protocol.json').write_text(json.dumps(protocol,indent=2)+'\n')
                print(relative,flush=True)
    protocol['complete'] = True
    (args.output/'protocol.json').write_text(json.dumps(protocol,indent=2)+'\n')
    (args.output/'summary.json').write_text(json.dumps(summarize(args.output),indent=2)+'\n')


if __name__ == '__main__':
    main()
