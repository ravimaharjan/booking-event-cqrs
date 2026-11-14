#!/usr/bin/env python3
"""Samples CPU and memory of the stack's containers, the load generator, Docker's port proxy and the whole host while a load test runs.

  resource-sampler.py sample <out-file>                  runs until killed, one JSON line per sample
  resource-sampler.py summary <out-file> <t_from> <t_to> prints one summary line for samples in that time window

CPU of a container is in cores (1.0 = one fully busy core); docker reports it as a percentage of one core.
"""
import json
import os
import re
import subprocess
import sys
import time

CONTAINERS = ["app", "redis", "replica", "primary", "edge"]
PREFIX = "event-booking-cqrs-"


def host_cpu_ticks():
    with open("/proc/stat") as f:
        parts = [int(x) for x in f.readline().split()[1:]]
    idle = parts[3] + parts[4]
    return sum(parts), idle


def process_ticks(comm):
    """Total CPU ticks (user+system) used so far by all processes with this name, e.g. k6 or docker-proxy."""
    total = 0
    for pid in os.listdir("/proc"):
        if not pid.isdigit():
            continue
        try:
            with open(f"/proc/{pid}/comm") as f:
                if f.read().strip() != comm:
                    continue
            with open(f"/proc/{pid}/stat") as f:
                fields = f.read().rsplit(")", 1)[1].split()
            total += int(fields[11]) + int(fields[12])  # utime + stime
        except (OSError, IndexError):
            continue
    return total


def to_mib(text):
    m = re.match(r"([\d.]+)\s*([KMG]i?B)", text)
    value, unit = float(m.group(1)), m.group(2)
    return value * {"KiB": 1 / 1024, "MiB": 1, "GiB": 1024, "kB": 1 / 1024, "MB": 1, "GB": 1024}[unit]


def sample(out_path):
    names = [PREFIX + c + "-1" for c in CONTAINERS]
    prev_total, prev_idle = host_cpu_ticks()
    prev_time = time.time()
    prev_proc = {c: process_ticks(c) for c in ("k6", "docker-proxy")}
    tick = os.sysconf("SC_CLK_TCK")
    with open(out_path, "a") as out:
        while True:
            stats = subprocess.run(
                ["docker", "stats", "--no-stream", "--format", "{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}"] + names,
                capture_output=True, text=True).stdout
            total, idle = host_cpu_ticks()
            busy = 1 - (idle - prev_idle) / max(1, total - prev_total)
            prev_total, prev_idle = total, idle
            now = time.time()
            proc = {c: process_ticks(c) for c in prev_proc}
            # Cores used since the previous sample (ps %cpu would be a lifetime average and hide the load phase).
            cores = {c: max(0, proc[c] - prev_proc[c]) / tick / (now - prev_time) for c in proc}
            prev_proc, prev_time = proc, now
            row = {"t": now, "host_busy": busy, "k6_cores": cores["k6"], "proxy_cores": cores["docker-proxy"]}
            for line in stats.strip().splitlines():
                name, cpu, mem = line.split("|")
                key = name.removeprefix(PREFIX).removesuffix("-1")
                row[key] = {"cores": float(cpu.rstrip("%")) / 100, "mem_mib": to_mib(mem.split("/")[0].strip())}
            out.write(json.dumps(row) + "\n")
            out.flush()


def summary(path, t_from, t_to):
    rows = [json.loads(l) for l in open(path)]
    rows = [r for r in rows if t_from <= r["t"] <= t_to and "app" in r]
    if not rows:
        print("no samples")
        return

    def avg(f):
        return sum(f(r) for r in rows) / len(rows)

    def peak(f):
        return max(f(r) for r in rows)

    cells = [
        f"{avg(lambda r: r['app']['cores']):.1f}/{peak(lambda r: r['app']['cores']):.1f}",
        f"{peak(lambda r: r['app']['mem_mib']):.0f}",
        f"{avg(lambda r: r['redis']['cores']):.2f}/{peak(lambda r: r['redis']['cores']):.2f}",
        f"{peak(lambda r: r['redis']['mem_mib']):.0f}",
        f"{peak(lambda r: r['replica']['cores']):.2f}",
        f"{peak(lambda r: r['primary']['cores']):.2f}",
        f"{avg(lambda r: r['edge']['cores']):.1f}" if "edge" in rows[0] else "-",
        f"{avg(lambda r: r['k6_cores']):.1f}",
        f"{avg(lambda r: r['proxy_cores']):.1f}",
        f"{avg(lambda r: r['host_busy']) * 100:.0f}%",
    ]
    print(" ".join(f"{c:<12}" for c in cells) + f" n={len(rows)}")


if __name__ == "__main__":
    if sys.argv[1] == "sample":
        sample(sys.argv[2])
    else:
        summary(sys.argv[2], float(sys.argv[3]), float(sys.argv[4]))
