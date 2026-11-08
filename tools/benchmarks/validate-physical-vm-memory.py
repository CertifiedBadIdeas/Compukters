#!/usr/bin/env python3
"""Validate settled actor counts and summarize physical GameTest memory snapshots."""
import argparse
import json
import re
from pathlib import Path

from jvm_memory_metrics import histogram_footprint


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence', type=Path)
    parser.add_argument('--prefix', default='physical-stable')
    args = parser.parse_args()
    rows = []
    for population in (100, 500, 1000):
        directory = args.evidence / f'{args.prefix}-{population}'
        current = []
        for phase in ('warmup', 'baseline', 'prompt', 'observed', 'dropped'):
            meta = dict(line.split('=', 1) for line in (directory / f'{phase}-metadata.txt').read_text().splitlines())
            assert int(meta['population']) == population
            assert int(meta['actors']) == (population if phase in ('prompt', 'observed') else 0)
            histogram = (directory / f'{phase}-histogram.txt').read_text()
            footprint = histogram_footprint(histogram)
            nmt = (directory / f'{phase}-nmt.txt').read_text()
            match = re.search(r'Other \(reserved=\d+KB, committed=(\d+)KB\)', nmt)
            smaps = (directory / f'{phase}-smaps.txt').read_text()
            resident = {key: int(value) * 1024 for key, value in re.findall(r'^(Rss|Pss):\s+(\d+) kB', smaps, re.M)}
            current.append(dict(population=population, phase=phase, actors=int(meta['actors']),
                                **footprint, **resident, Other=int(match[1]) * 1024 if match else 0))
        baseline = next(row for row in current if row['phase'] == 'baseline')
        for row in current:
            for metric in ('java_object_bytes', 'java_filler_bytes', 'mod_class_bytes', 'Rss', 'Pss', 'Other'):
                row[metric + '_delta'] = row[metric] - baseline[metric]
        rows.extend(current)
    (args.evidence / 'physical-summary.json').write_text(json.dumps(rows, indent=2))
    print('Validated all settled physical populations and complete actor release; GC filler excluded from object totals')
    for row in rows:
        print(row['population'], row['phase'], {k: round(v / 2**20, 3) for k, v in row.items() if k.endswith('_delta')})


if __name__ == '__main__':
    main()
