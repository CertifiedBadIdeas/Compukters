#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# Licensed under the Apache License, Version 2.0.
"""Cold JIT queue-progress comparison; native sessions, not Minecraft capacity."""
import argparse
import csv
import fcntl
import hashlib
import importlib.util
import json
import statistics
import subprocess
import time
from pathlib import Path


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + '\n')


def rows(path):
    with path.open() as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


COLD = [(32, w, order) for w in [1, 4, 8] for order in ['forward', 'reverse']] + [(1, 8, 'forward'), (8, 8, 'forward')]
WARM = [(32, w, 'forward') for w in [1, 4, 8]] + [(32, 8, 'reverse'), (1, 8, 'forward'), (8, 8, 'forward')]
MEMORY = [('cold', 32, 8, 'forward'), ('warm', 32, 8, 'forward'), ('warm', 1, 8, 'forward'), ('warm', 8, 8, 'forward')]


def schedule():
    runs = [dict(kind='timing',phase='cold',round=0,programs=32,workers=8,order=order,revision='candidate',position=0,mode='off') for order in ['forward','reverse']]
    for rnd in range(3):
        for phase, configs in [('cold', COLD), ('warm', WARM)]:
            for n, w, order in configs:
                sequence = ['baseline', 'candidate', 'candidate', 'baseline'] if rnd % 2 == 0 else ['candidate', 'baseline', 'baseline', 'candidate']
                for pos, revision in enumerate(sequence):
                    runs.append(dict(kind='timing', phase=phase, round=rnd, programs=n, workers=w, order=order, revision=revision, position=pos))
        for n, w, order in COLD:
            for pos, revision in enumerate(['baseline', 'candidate'] if rnd % 2 == 0 else ['candidate', 'baseline']):
                runs.append(dict(kind='diagnostic', phase='cold', round=rnd, programs=n, workers=w, order=order, revision=revision, position=pos))
    for phase, n, w, order in MEMORY:
        for revision in ['baseline', 'candidate']:
            runs.append(dict(kind='memory', phase=phase, round=0, programs=n, workers=w, order=order, revision=revision, position=0))
    for run in runs:
        run.update(population=2000)
        run.setdefault('mode','on')
        run['directory'] = '{kind}/{phase}/r{round}/p{programs}-w{workers}-{order}-{revision}-{mode}-{position}'.format(**run)
    return runs


def load_observer():
    spec = importlib.util.spec_from_file_location('resource_budget', Path(__file__).with_name('jit-resource-budget.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.observe


def analyze(root):
    protocol = json.loads((root/'protocol.json').read_text())
    assert protocol['complete'] and [{k:r[k] for k in schedule()[0]} for r in protocol['runs']] == schedule()
    observe, states, observations = load_observer(), {}, []
    for run in protocol['runs']:
        directory = root/run['directory']
        assert json.loads((directory/'process-completed.json').read_text())['returncode'] == 0
        o = observe(directory, run, states)
        resource, = rows(directory/'resources.tsv')
        if run['revision']=='candidate':
            assert 'queue_permits' in resource
        assert int(resource.get('queue_permits',0)) == 0
        assert int(resource['pending']) == 0 and int(resource['modules']) == o['modules'] <= 2048
        assert int(resource['reserved_bytes']) == o['mapped_bytes'] <= 16*1024*1024
        o['batch_median_cpu_ns'] = o['cpu_ns']
        if run['phase']=='cold':
            o['cpu_ns'] = sum(int(r['cpu_ns']) for r in rows(directory/'batches.tsv'))
        o['total_wall_ns'] = sum(int(r['wall_ns']) for r in rows(directory/'batches.tsv'))
        telemetry = rows(directory/'jit.tsv')
        o['coverage'] = [sum(int(r['compiled'])>0 for r in telemetry if r['phase']==f'batch-{batch}') for batch in range(20)]
        o['first_all_native_batch'] = next((i for i,native in enumerate(o['coverage']) if native==run['programs']),None)
        final = [r for r in telemetry if r['phase']=='drained']
        if run['phase']=='warm':
            epochs={r['id']:r['admission_epoch'] for r in telemetry if r['phase']=='before'}
            assert epochs=={r['id']:r['admission_epoch'] for r in telemetry if r['phase']=='after'}
        assert all(int(r['compiled']) <= 64 and int(r['reserved_bytes']) == int(r['mapped_bytes']) <= 512*1024 for r in final)
        if run['kind']=='diagnostic':
            admission = rows(directory/'admission.tsv')
            assert {r['id'] for r in admission} == {r['id'] for r in final}
            assert all(int(r['submitted']) == int(r['started']) == int(r['published']) + next(int(s['failures']) for s in final if s['id']==r['id']) for r in admission)
            if run['revision']=='candidate':
                assert all('channel_full' in r and 'queue_deferred' in r for r in admission)
            assert all(int(r.get('channel_full',0)) == 0 for r in admission)
            assert sum(int(r['published']) for r in admission) == o['modules']
            assert all(int(r['requests']) == sum(int(r[k]) for k in ['denied_global','denied_program','unsupported','queue_full','submitted']) + int(r.get('queue_deferred',0)) for r in admission)
            assert all(r['compiler_cpu_supported']=='true' for r in admission)
            o['admission'] = admission
            o['compiler_cpu_ns'] = sum(int(r['compile_cpu_ns']) for r in admission)
        observations.append(o)
    matrix=[]
    for phase, configs in [('cold', COLD), ('warm', WARM)]:
        for n,w,order in configs:
            rounds=[]
            for rnd in range(3):
                selected=[o for o in observations if o['kind']=='timing' and o['mode']=='on' and o['phase']==phase and (o['programs'],o['workers'],o['order'],o['round'])==(n,w,order,rnd)]
                pair={r:[o['cpu_ns'] for o in selected if o['revision']==r] for r in ['baseline','candidate']}
                a,b=(statistics.median(pair[r]) for r in ['baseline','candidate'])
                variation=max(max(v)/min(v)-1 for v in pair.values())
                rounds.append(dict(baseline_ns=a,candidate_ns=b,change=b/a-1,variation=variation,resolved=abs(b/a-1)>variation and variation<.1))
            a=statistics.median(r['baseline_ns'] for r in rounds)
            b=statistics.median(r['candidate_ns'] for r in rounds)
            matrix.append(dict(phase=phase,programs=n,workers=w,order=order,baseline_ns=a,candidate_ns=b,change=b/a-1,qualified=all(r['resolved'] and (r['change']>0)==(b>a) for r in rounds),rounds=rounds))
    return dict(processes=len(observations),matrix=matrix,observations=observations)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--binaries',type=Path,help='directory with baseline/candidate timing/memory/diagnostic binaries')
    parser.add_argument('--inputs',type=Path)
    parser.add_argument('--lock',type=Path,action='append',default=[])
    parser.add_argument('--resume',action='store_true')
    parser.add_argument('--check',action='store_true')
    args=parser.parse_args()
    root=args.output.resolve()
    if args.check:
        assert analyze(root)==json.loads((root/'summary.json').read_text())
        print('PASS: schedule, successful processes, checkpoints, work, resources, diagnostic accounting and controls')
        return
    if not args.resume:root.mkdir(parents=True,exist_ok=False)
    held=[]
    for path in args.lock:
        stream=path.open('a+')
        fcntl.flock(stream,fcntl.LOCK_EX)
        held.append(stream)
    binaries={f'{revision}-{kind}':args.binaries.resolve()/f'{revision}-{kind}' for revision in ['baseline','candidate'] for kind in ['timing','memory','diagnostic']}
    inputs=args.inputs.resolve()
    identity=dict(binaries={k:dict(path=str(p),sha256=sha(p)) for k,p in binaries.items()},inputs={p.name:sha(p) for p in sorted(inputs.iterdir()) if p.is_file()},locks=[str(p.resolve()) for p in args.lock])
    if args.resume:
        protocol=json.loads((root/'protocol.json').read_text())
        assert all(protocol[k]==v for k,v in identity.items())
    else:
        protocol=dict(identity,complete=False,runs=[],controller_sha256=sha(Path(__file__)),observer_sha256=sha(Path(__file__).with_name('jit-resource-budget.py')))
        save(root/'protocol.json',protocol)
    states={}
    observe=load_observer()
    for index,run in enumerate(schedule()):
        directory=root/run['directory']
        if index<len(protocol['runs']):
            saved=protocol['runs'][index]
            assert all(saved[k]==v for k,v in run.items())
            observe(directory,saved,states)
            continue
        binary=binaries[f"{run['revision']}-{run['kind']}"]
        args_child=[str(binary),'jit-mixed-fleet',str(inputs),str(directory),'2000',str(run['programs']),str(run['workers']),'65536',run['mode'],run['order'],'20',run['phase']]
        marker=directory/'process-completed.json'
        if marker.exists():
            assert json.loads(marker.read_text())['returncode']==0
            run['recovered']=True
        else:
            assert not directory.exists(), f'Incomplete child retained at {directory}; inspect before recovery'
            directory.mkdir(parents=True)
            start=time.monotonic()
            with (directory/'process.log').open('w') as stream:
                process=subprocess.run(args_child,stdout=stream,stderr=subprocess.STDOUT)
            save(marker,dict(args=args_child,returncode=process.returncode,elapsed_s=time.monotonic()-start))
            assert process.returncode==0, directory
        observe(directory,run,states)
        protocol['runs'].append(run)
        save(root/'protocol.json',protocol)
        print(f"{index+1}/{len(schedule())}: {run['directory']}",flush=True)
    protocol['complete']=True
    save(root/'protocol.json',protocol)
    save(root/'summary.json',analyze(root))


if __name__=='__main__':main()
