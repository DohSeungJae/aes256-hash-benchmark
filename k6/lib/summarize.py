#!/usr/bin/env python3
"""k6 1회 실행 결과를 정리한다.

사용: summarize.py <cfg_dir> <run_id> <config> <N> <iterations> <warmup>

입력 (cfg_dir 안):
  raw.csv.gz     k6 --out csv 원본 (모든 지표)
  summary.json   k6 --summary-export
출력:
  requests.csv   요청 1건당 1줄: iter,phase,seq,status,duration_ms
  results/index.csv 에 1줄 추가 (전체 실행 이력)
"""
import csv
import gzip
import json
import os
import statistics
import sys

cfg_dir, run_id, config, n, iterations, warmup = sys.argv[1:7]
results_root = os.path.dirname(os.path.dirname(cfg_dir.rstrip('/')))  # results/<run>/<cfg> → results

# 1) 요청별 기록 (http_req_duration 한 줄 = 요청 1건)
rows = []
with gzip.open(os.path.join(cfg_dir, 'raw.csv.gz'), 'rt', newline='') as f:
    for r in csv.DictReader(f):
        if r['metric_name'] != 'http_req_duration':
            continue
        extra = r.get('extra_tags') or ''
        tags = dict(kv.split('=', 1) for kv in extra.split('&') if '=' in kv)
        rows.append({
            'iter': len(rows),
            'phase': tags.get('phase', r.get('phase', '')),
            'seq': tags.get('seq', r.get('seq', '')),
            'status': r.get('status', ''),
            'duration_ms': f"{float(r['metric_value']):.3f}",
            'timestamp': r['timestamp'],
        })
with open(os.path.join(cfg_dir, 'requests.csv'), 'w', newline='') as f:
    w = csv.DictWriter(f, fieldnames=['iter', 'phase', 'seq', 'status', 'duration_ms', 'timestamp'])
    w.writeheader()
    w.writerows(rows)

# 2) 요약 지표 (summary.json 기준)
with open(os.path.join(cfg_dir, 'summary.json')) as f:
    m = json.load(f).get('metrics', {})


def trend(name, key):
    v = m.get(name, {})
    v = v.get('values', v)  # 형식 차이 흡수
    x = v.get(key)
    return '' if x is None else f'{x:.3f}'


succ = m.get('login_success', {})
succ = succ.get('values', succ)
success_rate = succ.get('rate', succ.get('value', ''))

measured = [float(r['duration_ms']) for r in rows if r['phase'] == 'measure']
line = {
    'run_id': run_id,
    'config': config,
    'N': n,
    'iterations': iterations,
    'warmup': warmup,
    'measured_requests': len(measured),
    'success_rate': success_rate,
    'login_avg_ms': trend('login_ms', 'avg'),
    'login_med_ms': trend('login_ms', 'med'),
    'login_p95_ms': trend('login_ms', 'p(95)'),
    'login_p99_ms': trend('login_ms', 'p(99)'),
    'login_max_ms': trend('login_ms', 'max'),
    'decrypt_med_ms': trend('decrypt_ms', 'med'),
    'stdev_ms': f'{statistics.pstdev(measured):.3f}' if len(measured) > 1 else '',
    'result_dir': os.path.relpath(cfg_dir, os.path.dirname(results_root)),
}

index = os.path.join(results_root, 'index.csv')
new = not os.path.exists(index)
with open(index, 'a', newline='') as f:
    w = csv.DictWriter(f, fieldnames=list(line))
    if new:
        w.writeheader()
    w.writerow(line)

print(f"[summary] {config} N={n}: med={line['login_med_ms']}ms p95={line['login_p95_ms']}ms "
      f"success={success_rate} measured={len(measured)} → {index}")
