#!/usr/bin/env python3
"""解析 JMeter 结果 CSV（默认列布局），算出 avg / P99 / QPS，并与简历上
优化前的基线对比。

JMeter CSV 默认列（fieldNames=true）:
  timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,
  success,failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,
  IdleTime,Connect

用法:
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

    print(f"=== 结果: {args.csv} ===")
    print(f"请求数      : {n}   (成功: {ok}, 库存应卖出: {args.stock})")
    print(f"avg (ms)    : {avg:8.2f}")
    print(f"P99  (ms)   : {p99:8.2f}")
    print(f"吞吐        : {qps:8.1f} req/s   (跨度 {span_s:.2f}s)")

    print("\n=== 对比优化前基线 ===")
    print(f"{'指标':<12}{'基线':>10}{'本次':>10}{'变化':>10}")
    print(f"{'avg (ms)':<12}{args.baseline_avg:>10.0f}{avg:>10.1f}{((avg-args.baseline_avg)/args.baseline_avg*100):>10.1f}%")
    print(f"{'P99 (ms)':<12}{args.baseline_p99:>10.0f}{p99:>10.1f}{((p99-args.baseline_p99)/args.baseline_p99*100):>10.1f}%")
    print(f"{'QPS':<12}{args.baseline_qps:>10.0f}{qps:>10.1f}{((qps-args.baseline_qps)/args.baseline_qps*100):>10.1f}%")

    print("\n对照简历发布数字: avg -64.8% ~176ms / P99 -31.9% ~545ms / QPS +50% ~1500")
    print(f"  本次实测: avg {avg:.0f}ms | P99 {p99:.0f}ms | QPS {qps:.0f}")


if __name__ == "__main__":
    main()