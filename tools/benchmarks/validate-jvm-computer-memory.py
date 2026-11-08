#!/usr/bin/env python3
"""Recompute paired retained-memory summaries from unmodified per-process evidence."""
import argparse
import json
from pathlib import Path
import re
import statistics

from jvm_memory_metrics import histogram_footprint


def other(path):
    match = re.search(r'Other \(reserved=(\d+)KB, committed=(\d+)KB\)', path.read_text())
    return int(match[2]) * 1024 if match else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence', type=Path)
    args = parser.parse_args()
    root = args.evidence
    meta = json.loads((root / 'provenance.json').read_text())
    variants = [name for name, _ in meta['variants']]
    assert len(variants) == 2
    data = {}
    for round_number in range(1, meta['rounds'] + 1):
        for count in meta['populations']:
            pair = {}
            for variant in variants:
                directory = root / f'round-{round_number}' / str(count) / variant
                rows = json.loads((directory / 'rows.json').read_text())
                assert len(rows) == 9 and len({r['phase'] for r in rows}) == 9
                phases = {row['phase']: row for row in rows}
                assert all(phases[p]['available'] == count for p in ['admitted', 'prompt', 'observed', 'resumed'])
                assert all(phases[p]['available'] == 0 for p in ['baseline', 'hosts', 'closed', 'dropped', 'store_closed'])
                assert all(phases[p]['guest_heap_quota'] == count * 262144 for p in ['prompt', 'observed', 'resumed'])
                assert phases['observed']['instructions'] == phases['prompt']['instructions']
                assert phases['resumed']['instructions'] > phases['prompt']['instructions']
                baseline_other = other(directory / 'baseline-nmt.txt')
                for phase, row in phases.items():
                    row.update(histogram_footprint((directory / f'{phase}-histogram.txt').read_text()))
                    row['Other'] = other(directory / f'{phase}-nmt.txt')
                    for metric in ['Rss', 'Pss', 'java_object_bytes', 'Other']:
                        row[metric + '_delta'] = row[metric] - (baseline_other if metric == 'Other' else phases['baseline'][metric])
                    data.setdefault((count, variant, phase), []).append(row)
                pair[variant] = phases
            for phase in pair[variants[0]]:
                for field in ['hosts', 'available', 'guest_heap_used', 'guest_heap_quota', 'mutable_quota',
                              'filesystem_logical_bytes', 'filesystem_nodes', 'fixed_units', 'instructions']:
                    assert pair[variants[0]][phase][field] == pair[variants[1]][phase][field], (round_number, count, phase, field)
    summary = []
    for (count, variant, phase), rows in sorted(data.items()):
        assert len(rows) == meta['rounds']
        metrics = {metric: statistics.median(row[metric] for row in rows)
                   for metric in ['Rss_delta', 'Pss_delta', 'java_object_bytes_delta', 'Other_delta']}
        summary.append(dict(population=count, variant=variant, phase=phase, **metrics))
    (root / 'summary.json').write_text(json.dumps(summary, indent=2))
    print(f"Validated {meta['rounds'] * len(meta['populations']) * 2} fresh JVMs and identical logical work in every phase")
    for count in meta['populations']:
        for phase in ['prompt', 'observed', 'store_closed']:
            for row in summary:
                if row['population'] == count and row['phase'] == phase:
                    print(count, phase, row['variant'], {k: round(v / 2**20, 3) for k, v in row.items() if k.endswith('_delta')})


if __name__ == '__main__':
    main()
