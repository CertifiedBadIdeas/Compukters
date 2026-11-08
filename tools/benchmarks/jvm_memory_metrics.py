"""Class-histogram accounting shared by the JVM and physical-fleet probes."""
import re

# HotSpot reports dedicated dead-space fillers as objects. They are not application data.
FILLERS = {'[Ljdk.internal.vm.FillerElement;', 'jdk.internal.vm.FillerObject', 'jdk.vm.internal.FillerObject'}


def histogram_footprint(text):
    total = re.search(r'^Total\s+(\d+)\s+(\d+)', text, re.M)
    assert total, 'Missing complete histogram total'
    entries = [match.groups() for match in re.finditer(r'^\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)', text, re.M)]
    assert sum(int(size) for _, size, _ in entries) == int(total[2])
    assert sum(int(count) for count, _, _ in entries) == int(total[1])
    filler_bytes = sum(int(size) for _, size, name in entries if name in FILLERS)
    filler_objects = sum(int(count) for count, _, name in entries if name in FILLERS)
    return dict(java_histogram_bytes=int(total[2]), java_histogram_objects=int(total[1]),
                java_filler_bytes=filler_bytes, java_object_bytes=int(total[2]) - filler_bytes,
                java_object_count=int(total[1]) - filler_objects,
                mod_class_bytes=sum(int(size) for _, size, name in entries if name.startswith('ru.lazyhat.compukters.')))
