#!/usr/bin/env python3
"""Resource budgets of the launcher on a TV: memory, idle CPU, APK size. Changes nothing on the TV, presses no keys.

  TV_SERIAL=192.168.1.132:5555 python3 tools/bench/budget.py [--apk path] [--pss-max 80] [--cpu-max 1.0] [--samples 6]

Put Home on screen first and leave the remote alone while it samples (about 20 s). Exit code 1 when a budget is
exceeded. The numbers are the same ones research/performance.md reports: PSS from `dumpsys meminfo`, CPU as the share
of one core from /proc/<pid>/stat over a few seconds. PSS is read twice: as it is, and after the launcher's own
garbage collection settles (a second reading 5 s later), because bitmaps free their native memory only on a GC.
"""
import argparse
import os
import re
import subprocess
import sys
import time

PKG = "com.gathin.tvhome"
APK_MAX = 1048576
HZ = 100        # clock ticks per second on Android


def pss_kb(meminfo):
    """TOTAL PSS in kB from `dumpsys meminfo <pkg>`, None when absent."""
    m = re.search(r"TOTAL PSS:\s+(\d+)", meminfo) or re.search(r"^\s*TOTAL\s+(\d+)\s", meminfo, re.M)
    return int(m.group(1)) if m else None


def cpu_ticks(stat):
    """utime + stime of a /proc/<pid>/stat line (the command name may hold spaces and brackets)."""
    rest = stat[stat.rindex(")") + 2:].split()
    return int(rest[11]) + int(rest[12])


def cpu_percent(stat1, stat2, seconds):
    """Share of one core used between two stat readings, in percent."""
    return 100.0 * (cpu_ticks(stat2) - cpu_ticks(stat1)) / HZ / seconds


def check(pss_mb, cpu, apk_bytes, pss_max, cpu_max, apk_max=APK_MAX):
    """The list of exceeded budgets as readable lines; empty when all hold."""
    bad = []
    if pss_mb is not None and pss_mb > pss_max:
        bad.append("memory %.0f MB over %.0f MB" % (pss_mb, pss_max))
    if cpu is not None and cpu > cpu_max:
        bad.append("idle CPU %.1f%% over %.1f%%" % (cpu, cpu_max))
    if apk_bytes is not None and apk_bytes >= apk_max:
        bad.append("APK %d B is not under %d B" % (apk_bytes, apk_max))
    return bad


def adb(serial, *args):
    return subprocess.run(["adb", "-s", serial, *args], stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=60).stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", default="launcher/app/build/outputs/apk/release/app-release.apk")
    ap.add_argument("--pss-max", type=float, default=80.0, help="MB; 80 is the agreed idle budget, 50 the goal")
    ap.add_argument("--cpu-max", type=float, default=1.0)
    ap.add_argument("--samples", type=int, default=6)
    ap.add_argument("--seconds", type=float, default=3.0)
    a = ap.parse_args()
    serial = os.environ.get("TV_SERIAL")
    if not serial:
        sys.exit("set TV_SERIAL, e.g. 192.168.1.132:5555")
    pid = adb(serial, "shell", "pidof", PKG).strip().split()
    if not pid:
        sys.exit("the launcher is not running")
    pid = pid[0]
    first = pss_kb(adb(serial, "shell", "dumpsys", "meminfo", PKG))
    cpus = []
    for _ in range(a.samples):
        s1 = adb(serial, "shell", "cat", "/proc/%s/stat" % pid)
        time.sleep(a.seconds)
        s2 = adb(serial, "shell", "cat", "/proc/%s/stat" % pid)
        cpus.append(cpu_percent(s1, s2, a.seconds))
    settled = pss_kb(adb(serial, "shell", "dumpsys", "meminfo", PKG))
    cpu = sum(cpus) / len(cpus)
    apk = os.path.getsize(a.apk) if os.path.exists(a.apk) else None
    mb = lambda kb: None if kb is None else kb / 1024.0
    print("PSS now      %s MB" % ("?" if first is None else "%.0f" % mb(first)))
    print("PSS settled  %s MB (budget %.0f)" % ("?" if settled is None else "%.0f" % mb(settled), a.pss_max))
    print("idle CPU     %.2f%% of one core, %d samples of %.0f s (budget %.1f%%)" % (cpu, a.samples, a.seconds, a.cpu_max))
    print("APK          %s B (under %d)" % (apk, APK_MAX))
    bad = check(mb(settled), cpu, apk, a.pss_max, a.cpu_max)
    for line in bad:
        print("OVER BUDGET:", line)
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
