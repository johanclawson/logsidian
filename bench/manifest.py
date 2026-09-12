#!/usr/bin/env python3
"""Byte-level manifest of a graph directory, and a diff of two manifests.

The release gate for anything that touches file writes: open a graph COPY with
the candidate build, and the files the user owns must come out byte-for-byte
unchanged unless the scenario changed them on purpose. Logseq's own backups
(logseq/bak/) are listed separately: a new backup means the app overwrote or
refused something, which the gate reports even when the main files match.

usage: manifest.py snap <graphDir> <out.json>
       manifest.py diff <before.json> <after.json>   (exit 1 on any change)

Refuses to touch ~/OneDrive (the real graph): only copies are measured.
"""
import hashlib
import json
import os
import sys

SKIP_DIRS = {'.git', '.recycle'}


def refuse_real_graph(path):
    real = os.path.realpath(path)
    if real.startswith(os.path.realpath(os.path.expanduser('~/OneDrive'))):
        sys.exit(f'refusing {path}: the real graph is never measured, use a copy')


def snap(root):
    refuse_real_graph(root)
    files, bak = {}, {}
    for d, dirs, names in os.walk(root):
        dirs[:] = [x for x in dirs if x not in SKIP_DIRS]
        for n in names:
            p = os.path.join(d, n)
            rel = os.path.relpath(p, root)
            with open(p, 'rb') as f:
                h = hashlib.sha256(f.read()).hexdigest()
            (bak if rel.startswith(os.path.join('logseq', 'bak') + os.sep) else files)[rel] = h
    return {'root': os.path.realpath(root), 'files': files, 'bak': bak}


def diff(a, b):
    fa, fb = a['files'], b['files']
    changed = sorted(k for k in fa.keys() & fb.keys() if fa[k] != fb[k])
    added = sorted(fb.keys() - fa.keys())
    removed = sorted(fa.keys() - fb.keys())
    new_bak = sorted(b['bak'].keys() - a['bak'].keys())
    return {'changed': changed, 'added': added, 'removed': removed, 'new_bak': new_bak}


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    if sys.argv[1] == 'snap' and len(sys.argv) == 4:
        m = snap(sys.argv[2])
        with open(sys.argv[3], 'w') as f:
            json.dump(m, f)
        print(f"{len(m['files'])} files, {len(m['bak'])} backups -> {sys.argv[3]}")
    elif sys.argv[1] == 'diff' and len(sys.argv) == 4:
        with open(sys.argv[2]) as f:
            a = json.load(f)
        with open(sys.argv[3]) as f:
            b = json.load(f)
        d = diff(a, b)
        for k, v in d.items():
            print(f'{k}: {len(v)}' + ''.join(f'\n  {x}' for x in v[:20]) + ('\n  ...' if len(v) > 20 else ''))
        sys.exit(1 if any(d.values()) else 0)
    else:
        sys.exit(__doc__)


if __name__ == '__main__':
    main()
