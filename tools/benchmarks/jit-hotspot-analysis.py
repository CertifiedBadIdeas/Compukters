#!/usr/bin/env python3
# Copyright 2026 The Compukters Developers
# SPDX-License-Identifier: Apache-2.0
"""Recompute phased self-sample distributions and isolated JIT diagnostics."""
import argparse
from collections import Counter
import csv
import hashlib
import json
from pathlib import Path
import re
import statistics

HEADER = re.compile(r'^\s*(\d+)/(\d+)\s+(\d+)\.(\d+):\s*$')
SELF = re.compile(r'^\s*(\d+)/(\d+)\s+(\d+)\.(\d+):\s+([0-9a-f]+)\s+(.+?)\s+\((.*)\)\s*$')
FRAME = re.compile(r'^\s+([0-9a-f]+)\s+(.+)$')
WORK = ['hot_'+x for x in ['fixed_units','dynamic_units','maintenance_units','blocks',
                         'executed_instructions','retired_instructions','requests','responses']]


def rows(path):
    with path.open() as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def timestamp(seconds, fraction):
    assert len(fraction) == 9
    return int(seconds)*1_000_000_000+int(fraction)


def executable_ranges(path):
    result=[]
    for line in path.read_text().splitlines():
        parts=line.split(maxsplit=5)
        if 'x' in parts[1] and (len(parts)==5 or parts[5].startswith('[anon')):
            begin,end=parts[0].split('-')
            result.append((int(begin,16),int(end,16)))
    return result


def group(symbols, address, mappings):
    if any(a <= address < b for a,b in mappings):
        return 'generated-code'
    text='\n'.join(symbols)
    # Mutually exclusive sampled-IP scopes, not cumulative caller percentages.
    if '::gc::' in text or any(x in text for x in ['::start_collection','::sweep_block','::enqueue_gray','::dequeue_gray']):
        return 'gc'
    if '::heap::' in text and any(x in text for x in ['>::reserve','>::commit','>::zero_reserved_payload',
                                                     '>::free_block','>::find_suitable','>::insert_free',
                                                     '>::remove_free','>::ensure_backing','>::write_reserved']):
        return 'guest-allocation'
    if '::heap::' in text:
        return 'guest-heap'
    if '::frame::' in text or any(x in text for x in ['::read_register','::write_register','::write_frame_value']):
        return 'frame-access'
    if 'jit::lower::checked_heap' in text or 'prepared_execution::execute_heap_opcode' in text:
        return 'typed-heap-operation'
    if '::jit::' in text:
        return 'jit-entry-exit'
    if any(x in text for x in ['::dispatch_target','::reference_type','::resolve_call','::prepare_call']):
        return 'call-type-dispatch'
    if '::prepared::' in text or '::instruction_for_execution' in text:
        return 'prepared-dispatch'
    if '::machine::' in text:
        return 'interpreter-other'
    if any(x in text.lower() for x in ['malloc','calloc','realloc','__rust_alloc','::alloc::','::dealloc','__libc_free']):
        return 'native-allocation'
    if symbols and all('[unknown]' in x for x in symbols):
        return 'unresolved'
    return 'other'


def profile(root, run):
    directory=root/run['directory']
    actual=json.loads((directory/'run.json').read_text())
    assert actual==run and run['status']==0
    controls=[x for x in run['controls'] if x['command'] in ['enable','disable']]
    assert [x['command'] for x in controls]==['enable','disable','enable','disable']
    bounds=[x['monotonic_ns'] for x in controls]
    assert bounds==sorted(bounds) and len(set(bounds))==4
    report=(directory/'report.txt').read_text()
    lost=re.search(r'Total Lost Samples:\s*(\d+)',report)
    assert lost and int(lost[1])==0,(directory,'lost samples')
    inline={};current=None
    for line in (directory/'leaf-inline.txt').read_text().splitlines():
        header=HEADER.match(line)
        if header:
            current=(int(header[1]),int(header[2]),timestamp(header[3],header[4]))
            assert current not in inline
            inline[current]=[]
        elif line.strip():
            frame=FRAME.match(line)
            assert frame and current is not None,(directory,line)
            inline[current].append((int(frame[1],16),frame[2]))
    counters={p:Counter() for p in ['cold-main','cold-compiler','warm-main','warm-compiler']}
    physical={p:Counter() for p in counters}
    scopes={p:Counter() for p in counters}
    ranges={p:executable_ranges(directory/(p+'-maps.txt')) for p in ['cold','warm']}
    outside=0;missing_inline=0
    for line in (directory/'self.txt').read_text().splitlines():
        if not line.strip() or line.startswith('#'):
            continue
        sample=SELF.match(line)
        assert sample,(directory,line)
        pid,tid=int(sample[1]),int(sample[2]);ns=timestamp(sample[3],sample[4])
        assert pid==run['pid']
        phase='cold' if bounds[0] <= ns <= bounds[1] else 'warm' if bounds[2] <= ns <= bounds[3] else None
        if phase is None:
            outside+=1
            continue
        key=phase+('-main' if tid==pid else '-compiler')
        ip=int(sample[5],16)
        frames=inline.get((pid,tid,ns),[])
        symbols=[s for address,s in frames if address==ip]
        if not symbols:
            missing_inline+=1
            symbols=[sample[6]]
        counters[key][group(symbols,ip,ranges[phase])]+=1
        physical[key][sample[6]]+=1
        scopes[key][' -> '.join(symbols)]+=1
    assert sum(counters['warm-main'].values()) >= 1000,(directory,'insufficient warm samples')
    summary,=rows(directory/'output/measurements.tsv')
    samples=rows(directory/'output/samples.tsv')
    assert summary['id']==run['case'] and len(samples)==run['samples']
    assert statistics.median(int(s['hot_cpu_ns']) for s in samples)==int(summary['hot_cpu_median_ns'])
    before,after=rows(directory/'output/jit.tsv')
    tier_changes={field:[before[field],after[field]] for field in
                  ['compiled','compile_ns','code_bytes','mapped_bytes_upper_bound','state_bytes']
                  if before[field]!=after[field]}
    assert int(after['pending'])==int(after['failures'])==0 and after['hot_telemetry']=='false'
    assert int(after['hits'])==int(after['native_attempts'])==0
    assert (int(after['compiled'])>0)==(run['mode']=='on')
    return dict(case=run['case'],mode=run['mode'],round=run['round'],
                counts={p:dict(c) for p,c in counters.items()},
                physical={p:dict(c.most_common()) for p,c in physical.items()},
                scopes={p:dict(c.most_common()) for p,c in scopes.items()},
                outside_ack_window=outside,missing_inline=missing_inline,
                hot_cpu_ns=int(summary['hot_cpu_median_ns']),
                work={k:int(summary[k]) for k in WORK},jit=after,tier_changes=tier_changes)


def calls(path):
    header,*lines=path.read_text().splitlines()
    assert header=='block\tinstruction\ttarget\tcount\tcallee'
    return {tuple(fields[:3]):dict(count=int(fields[3]),callee=fields[4])
            for line in lines if not line.startswith('op\t') and (fields:=line.split('\t',4))}


def diagnostic(root, run):
    directory=root/run['directory'];case=run['case']
    summary,=rows(directory/'measurements.tsv');n=int(summary['samples']);assert n==3
    before,after=rows(directory/'jit.tsv')
    assert before['hot_telemetry']==after['hot_telemetry']=='true'
    for field in ['compiled','compile_ns','code_bytes','mapped_bytes_upper_bound','state_bytes']:
        assert before[field]==after[field]
    warm=calls(directory/(case+'-calls-warm.tsv'));final=calls(directory/(case+'-calls.tsv'))
    differences=[]
    for key,value in final.items():
        assert key[0]!=str(2**64-1),'call histogram overflow'
        delta=value['count']-warm.get(key,dict(count=0))['count']
        assert delta>=0 and delta%n==0
        if delta:differences.append(dict(site=list(key),count=delta,per_session=delta//n,callee=value['callee']))
    old=[int(x) for x in (directory/(case+'-exits-warm.tsv')).read_text().split()]
    log=(root/(run['directory']+'.log')).read_text()
    match=re.search(r'JIT exits .*?\[branch,end,lifecycle,suffix,failure,budget\] \[([^]]+)\]',log)
    assert match
    new=[int(x) for x in match[1].split(',')]
    exits=[b-a for a,b in zip(old,new)];assert len(exits)==6 and all(x>=0 for x in exits)
    hits=int(after['hits'])-int(before['hits']);attempts=int(after['native_attempts'])-int(before['native_attempts'])
    assert sum(exits)==hits
    return dict(case=case,samples=n,calls=sorted(differences,key=lambda x:x['count'],reverse=True),
                calls_per_session=sum(x['per_session'] for x in differences),
                native_attempts_per_session=attempts/n,hits_per_session=hits/n,
                exits=dict(zip(['branch','end','lifecycle','suffix','failure','budget'],exits)),
                work={k:int(summary[k]) for k in WORK})


def analyze(root):
    for name,digest in json.loads((root/'inputs.json').read_text()).items():
        assert hashlib.sha256((root/name).read_bytes()).hexdigest()==digest,name
    protocol=json.loads((root/'profile-protocol.json').read_text())
    observations=[profile(root,run) for run in protocol['runs']]
    assert len(observations)==36 and protocol['rounds']==3
    identities={(r['case'],r['mode'],r['round']) for r in observations};assert len(identities)==36
    diagnostics=[diagnostic(root,run) for run in protocol['diagnostics']];assert len(diagnostics)==6
    result={}
    for _,case,*_ in protocol['cases']:
        selected=[r for r in observations if r['case']==case]
        assert len(selected)==6 and all(r['work']==selected[0]['work'] for r in selected)
        diag,=[r for r in diagnostics if r['case']==case];assert diag['work']==selected[0]['work']
        modes={}
        for mode in ['off','on']:
            candidates=[r for r in selected if r['mode']==mode]
            assert {r['round'] for r in candidates}=={0,1,2}
            # Retain every fork, explicitly exclude evolving tiers from warm distributions.
            group=[r for r in candidates if not r['tier_changes']]
            assert len(group)>=2,(case,mode,'fewer than two stable forks')
            buckets=sorted({k for r in group for k in r['counts']['warm-main']})
            fractions={k:[r['counts']['warm-main'].get(k,0)/sum(r['counts']['warm-main'].values()) for r in group] for k in buckets}
            modes[mode]=dict(stable_rounds=[r['round'] for r in group],
                             excluded_rounds=[r['round'] for r in candidates if r['tier_changes']],
                             samples=[sum(r['counts']['warm-main'].values()) for r in group],
                             fractions={k:dict(median=statistics.median(v),min=min(v),max=max(v)) for k,v in fractions.items()},
                             hot_cpu_ns=statistics.median(r['hot_cpu_ns'] for r in group),
                             physical=dict(sum((Counter(r['physical']['warm-main']) for r in group),Counter()).most_common()))
        result[case]=dict(modes=modes,diagnostic=diag)
    return dict(cases=result,observations=observations)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,required=True)
    parser.add_argument('--check',action='store_true')
    args=parser.parse_args();result=analyze(args.root)
    if args.check:
        assert result==json.loads((args.root/'profile-summary.json').read_text())
        print('PASS: phased samples, no loss, all work, explicit tier classification and exact diagnostic deltas')
    else:
        (args.root/'profile-summary.json').write_text(json.dumps(result,indent=2)+'\n')
        print('Wrote profile-summary.json')
