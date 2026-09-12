#!/usr/bin/env python3
"""Sample memory once a second while a benchmark runs.

Stops ONLY the benchmark app (matched by its executable path) when available
memory falls below a floor, so a runaway parse yields a number instead of a
killed session. Reads /proc directly; the bash version forked per process and
could not keep a one-second cadence.

usage: memwatch.py <logfile> [floor_mb]
"""
import os
import signal
import sys
import time

APP = os.environ.get('LSBENCH_APP', '/home/johan/dev/logsidian-perf/static/out/Logseq-linux-x64/Logseq')
log_path = sys.argv[1]
floor = int(sys.argv[2]) if len(sys.argv) > 2 else 4096


def avail_mb():
    with open('/proc/meminfo') as f:
        for line in f:
            if line.startswith('MemAvailable:'):
                return int(line.split()[1]) // 1024
    return -1


def app_procs():
    for pid in os.listdir('/proc'):
        if not pid.isdigit():
            continue
        try:
            if os.readlink(f'/proc/{pid}/exe') != APP:
                continue
            with open(f'/proc/{pid}/status') as f:
                rss = next((int(l.split()[1]) // 1024 for l in f if l.startswith('VmRSS:')), 0)
            with open(f'/proc/{pid}/cmdline', 'rb') as f:
                args = f.read().split(b'\0')
            kind = next((a[7:].decode() for a in args if a.startswith(b'--type=')), 'main')
            yield int(pid), rss, kind
        except OSError:
            continue


HZ = os.sysconf('SC_CLK_TCK')
prev_ticks = {}


def thread_cpu(procs, names=('DedicatedWorker', 'Logseq')):
    """CPU % since the last sample of the renderer's db-worker thread
    ('DedicatedWorker') and main thread ('Logseq'). Busy-but-silent workers
    show up here even when the worker's own heartbeat cannot report."""
    pct = {n: 0.0 for n in names}
    now = time.monotonic()
    for pid, _, kind in procs:
        if kind != 'renderer':
            continue
        try:
            tids = os.listdir(f'/proc/{pid}/task')
        except OSError:
            continue
        for tid in tids:
            try:
                with open(f'/proc/{pid}/task/{tid}/comm') as f:
                    name = f.read().strip()
                if name not in pct:
                    continue
                with open(f'/proc/{pid}/task/{tid}/stat') as f:
                    rest = f.read().rsplit(')', 1)[1].split()
                ticks = int(rest[11]) + int(rest[12])  # utime + stime
            except (OSError, IndexError, ValueError):
                continue
            last = prev_ticks.get((pid, tid))
            prev_ticks[(pid, tid)] = (ticks, now)
            if last and now > last[1]:
                pct[name] += 100.0 * (ticks - last[0]) / HZ / (now - last[1])
    return pct


with open(log_path, 'w', buffering=1) as log:
    log.write('time avail_mb logseq_rss_mb max_proc_mb max_type nprocs worker_cpu_pct ui_cpu_pct\n')
    while True:
        procs = list(app_procs())
        avail = avail_mb()
        total = sum(r for _, r, _ in procs)
        top = max(procs, key=lambda p: p[1], default=(0, 0, '-'))
        cpu = thread_cpu(procs)
        log.write(f'{time.strftime("%H:%M:%S")} {avail} {total} {top[1]} {top[2]} {len(procs)} '
                  f'{cpu["DedicatedWorker"]:.0f} {cpu["Logseq"]:.0f}\n')
        if procs and avail < floor:
            log.write(f'{time.strftime("%H:%M:%S")} FLOOR HIT: avail {avail} MB < {floor} MB, stopping benchmark app\n')
            for pid, _, _ in procs:
                try:
                    os.kill(pid, signal.SIGTERM)
                except OSError:
                    pass
        time.sleep(1)
