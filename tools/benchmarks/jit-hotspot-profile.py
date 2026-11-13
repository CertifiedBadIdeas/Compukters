#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
"""Gate perf sampling around first/warmed calls; never mix diagnostic CPU with timings."""
import argparse
import csv
import fcntl
import hashlib
import json
import os
from pathlib import Path
import select
import subprocess
import time

# Repetitions provide several CPU seconds in the sampled warm window.
CASES = [('transient-allocations', 'for-list-256-300', 16384, 101, 101),
         ('transient-allocations', 'fold-prebuilt-256-300', 16384, 101, 101),
         ('collection-reuse', 'map-not-null-1024-100-fresh', 262144, 31, 31),
         ('collection-reuse', 'map-not-null-1024-100-reuse-objects', 262144, 31, 31),
         ('typed-execution', 'pid-vec3-f64-20000', 65536, 1001, 4001),
         ('mixed', 'collections-00', 65536, 151, 151)]
PERF_ENV = dict(os.environ, DEBUGINFOD_URLS='')


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def rows(path):
    with path.open() as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def profile(root, case, mode, rnd):
    suite, name, heap, off, on = case
    repetitions = on if mode == 'on' else off
    directory = root / f'profiles/round-{rnd}/{name}/{mode}'
    directory.mkdir(parents=True)
    phases = directory / 'phases'
    phases.mkdir()
    control_read, control_write = os.pipe()
    ack_read, ack_write = os.pipe()
    env = dict(PERF_ENV, COMPUKTERS_BENCHMARK_PHASES=str(phases))
    child = ['taskset', '-c', '6', str(root / 'timing'), str(root / 'inputs' / suite),
             str(directory / 'output'), str(repetitions), str(heap),
             'jit-untraced' if mode == 'on' else 'untraced', name]
    argv = ['taskset', '-c', '8', 'perf', 'record', '-N', '-B', '-o', str(directory / 'perf.data'),
            '-e', 'task-clock:u', '-F', '999', '--clockid=mono', '--call-graph', 'dwarf,8192',
            '--user-callchains', '-m', '64',
            '--delay=-1', f'--control=fd:{control_read},{ack_write}', '--'] + child
    events = []
    start = time.time()
    with (directory / 'record.log').open('w') as log:
        process = subprocess.Popen(argv, env=env, stdout=log, stderr=subprocess.STDOUT,
                                   pass_fds=(control_read, ack_write))
        os.close(control_read)
        os.close(ack_write)

        def wait_phase(phase):
            path = phases / (phase + '.ready')
            deadline = time.monotonic() + 120
            while not path.exists() or not path.read_text().strip():
                assert process.poll() is None, (directory, log.name)
                assert time.monotonic() < deadline, ('phase timeout', phase)
                time.sleep(.002)
            return int(path.read_text())

        def control(command):
            # perf can acknowledge its initial disabled state before the first command.
            while select.select([ack_read], [], [], 0)[0]:
                initial = os.read(ack_read, 256)
                assert initial, 'perf acknowledgment pipe closed'
                events.append(dict(command='unsolicited-ack', bytes=initial.hex(), monotonic_ns=time.monotonic_ns()))
            os.write(control_write, (command + '\n').encode())
            assert select.select([ack_read], [], [], 10)[0], ('perf acknowledgment timeout', command)
            reply = os.read(ack_read, 256)
            acknowledgments = reply.replace(b'\0', b'').splitlines()
            assert acknowledgments and all(value == b'ack' for value in acknowledgments), repr(reply)
            events.append(dict(command=command, acknowledgment=reply.hex(), monotonic_ns=time.monotonic_ns()))

        try:
            pid = wait_phase('cold')
            control('enable')
            (phases / 'cold.go').touch()
            assert wait_phase('cold-done') == pid
            control('disable')
            (directory / 'cold-maps.txt').write_text(Path(f'/proc/{pid}/maps').read_text())
            (phases / 'cold-done.go').touch()
            assert wait_phase('warm') == pid
            (directory / 'warm-maps.txt').write_text(Path(f'/proc/{pid}/maps').read_text())
            control('enable')
            (phases / 'warm.go').touch()
            assert wait_phase('done') == pid
            control('disable')
            (phases / 'done.go').touch()
            status = process.wait(timeout=30)
            assert status == 0, directory
        finally:
            os.close(control_write)
            os.close(ack_read)
            if process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
    record = dict(case=name, mode=mode, round=rnd, pid=pid, samples=repetitions,
                  directory=str(directory.relative_to(root)), argv=argv, controls=events,
                  status=status, seconds=time.time()-start)
    save(directory / 'run.json', record)
    controls = [event for event in events if event['command'] in ['enable', 'disable']]
    warm_time = ','.join(f'{controls[i]["monotonic_ns"]/1e9:.9f}' for i in [2,3])
    exports = [('self.txt', ['script','-G','--ns','--show-lost-events','-F','pid,tid,time,ip,sym,dso']),
               ('leaf-inline.txt', ['script','--inline','--max-stack','1','--ns','-F','pid,tid,time,ip,sym,dso']),
               ('report.txt', ['report','--stdio','--no-children','-g','none','--percent-limit','0.3','--time',warm_time]),
               ('header.txt', ['report','--header-only','--stdio'])]
    for filename, command in exports:
        with (directory / filename).open('w') as output, (directory / (filename+'.stderr')).open('w') as errors:
            subprocess.run(['perf'] + command + ['-i',str(directory / 'perf.data')],
                           stdout=output, stderr=errors, env=PERF_ENV, check=True)
    return record


def diagnostics(root, case):
    suite, name, heap, _, _ = case
    directory = root / 'diagnostics' / name
    directory.parent.mkdir(exist_ok=True)
    argv = ['taskset','-c','6',str(root / 'diagnostic'),str(root / 'inputs' / suite),
            str(directory),'3',str(heap),'jit-untraced',name]
    with directory.with_suffix('.log').open('w') as log:
        subprocess.run(argv,stdout=log,stderr=subprocess.STDOUT,check=True)
    return dict(case=name,argv=argv,directory=str(directory.relative_to(root)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,required=True)
    parser.add_argument('--stand',type=Path,required=True)
    parser.add_argument('--pilot',action='store_true')
    args = parser.parse_args()
    root = args.root.resolve()
    assert not (root / 'profile-protocol.json').exists(), 'Never overwrite existing samples'
    locks = []
    for rel in ['.toolchain/gradle-build.lock','.git/modules/.toolchain/gradle-build.lock']:
        stream = (args.stand / rel).open('rb')
        fcntl.flock(stream,fcntl.LOCK_EX|fcntl.LOCK_NB)
        locks.append(stream)
    for name, item in json.loads((root/'binaries.json').read_text()).items():
        assert sha(root / name) == item['sha256']
    for rel, digest in json.loads((root/'inputs.json').read_text()).items():
        assert sha(root / rel) == digest
    protocol = dict(cases=CASES,frequency=999,stack_bytes=8192,rounds=1 if args.pilot else 3,runs=[],diagnostics=[])
    save(root/'profile-protocol.json',protocol)
    try:
        for rnd in range(protocol['rounds']):
            for i, case in enumerate(CASES[:1] if args.pilot else CASES[::1 if rnd%2==0 else -1]):
                for mode in ['off','on'][::1 if (i+rnd)%2==0 else -1]:
                    protocol['runs'].append(profile(root,case,mode,rnd))
                    save(root/'profile-protocol.json',protocol)
                    print(f'profile round {rnd+1}: {case[1]} {mode}',flush=True)
        if not args.pilot:
            for case in CASES:
                protocol['diagnostics'].append(diagnostics(root,case))
                save(root/'profile-protocol.json',protocol)
                print('diagnostic:',case[1],flush=True)
    finally:
        for stream in reversed(locks):
            stream.close()


if __name__ == '__main__':
    main()
