#!/usr/bin/env python3
"""Analyze a JMeter result CSV (default column layout) into avg / P99 / QPS
and compare against the pre-optimization baseline published on the resume.

Default JMeter CSV columns (fieldNames=true):
  timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,
  success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,
  IdleTime,Connect

Usage:
  python3 03-analyze.py result_seckill.csv
  python3 03-analyze.py result_seckill.csv --stock 200
      --baseline-avg 500 --baseline-p99 800 --baseline-qps 1000
"""
import argparse
import csv
import sys


def percentile(sorted_values, p):
    """Linear-interpolated percentile on pre-sorted data (JMeter-style)."""
    if not sorted_values:
        return 0.0
    k = (len(sorted_values) - 1) * p
    f = int(k)
    c = k - f
    if f + 1 < len(sorted_values):
        return sorted_values[f] + c * (sorted_values[f + 1] - sorted_values[f])
    return sorted_values[-1]


def main():
    parser = argparse.ArgumentParser(description="Compute avg/P99/QPS from a JMeter CSV.")
    parser.add_argument("csv", help="path to JMeter result CSV")
    parser.add_argument("--stock", type=int, default=200, help="voucher stock (expected successes)")
    parser.add_argument("--baseline-avg", type=float, default=500.0, help="pre-optimization avg ms")
    parser.add_argument("--baseline-p99", type=float, default=800.0, help="pre-optimization P99 ms")
    parser.add_argument("--baseline-qps", type=float, default=1000.0, help="pre-optimization throughput")
    args = parser.parse_args()

    elapsed, timestamps, ok = [], [], 0
    with open(args.csv, newline="", encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            try:
                elapsed.append(float(row["elapsed"]))
                timestamps.append(float(row["timeStamp"]))
                if row["success"].strip().lower() == "true":
                    ok += 1
            except (ValueError, KeyError):
                continue

    n = len(elapsed)
    if n == 0:
        print("No samples parsed from", args.csv)
        sys.exit(1)

    elapsed.sort()
    avg = sum(elapsed) / n
    p99 = percentile(elapsed, 0.99)

    span_s = (max(timestamps) - min(timestamps)) / 1000.0
    if span_s <= 0:  # fall back to avg-based estimate
        span_s = avg * n / 1000.0
    qps = n / span_s

    print(f"=== Result: {args.csv} ===")
    print(f"samples     : {n}   (success: {ok}, stock expected: {args.stock})")
    print(f"avg (ms)    : {avg:8.2f}")
    print(f"P99  (ms)   : {p99:8.2f}")
    print(f"throughput  : {qps:8.1f} req/s   (span {span_s:.2f}s)")

    print("\n=== vs pre-optimization baseline ===")
    print(f"{'metric':<12}{'baseline':>10}{'now':>10}{'delta':>10}")
    print(f"{'avg (ms)':<12}{args.baseline_avg:>10.0f}{avg:>10.1f}{((avg-args.baseline_avg)/args.baseline_avg*100):>10.1f}%")
    print(f"{'P99 (ms)':<12}{args.baseline_p99:>10.0f}{p99:>10.1f}{((p99-args.baseline_p99)/args.baseline_p99*100):>10.1f}%")
    print(f"{'QPS':<12}{args.baseline_qps:>10.0f}{qps:>10.1f}{((qps-args.baseline_qps)/args.baseline_qps*100):>10.1f}%")

    print("\nSanity check against published resume numbers:")
    print(f"  avg  -64.8%  -> expected ~176ms  (got {avg:.0f}ms)")
    print(f"  P99  -31.9%  -> expected ~545ms  (got {p99:.0f}ms)")
    print(f"  QPS  +50%    -> expected ~1500   (got {qps:.0f})")


if __name__ == "__main__":
    main()