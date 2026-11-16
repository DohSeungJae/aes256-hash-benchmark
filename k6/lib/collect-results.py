#!/usr/bin/env python3
"""results/<run>/<config>/ 폴더들을 읽어 측정 결과를 한 CSV로 정리한다.

사용: python3 k6/lib/collect-results.py [results_dir] [out_csv]
      (기본: results → results/collected.csv)

- 기준은 각 폴더의 k6.log (k6 콘솔 요약). 폴더가 지워진 실행은 들어가지 않는다.
- k6.log 는 시간을 반올림해 찍으므로(예: 13m0s) 같은 실행의 summary.json 에서 정밀값을 가져오고,
  두 값이 반올림 범위 안에서 일치하는지 검사한다. 어긋나면 경고만 출력한다.
- 워밍업 횟수·시작/종료 시각은 meta.txt 에서 읽는다.
"""
import csv
import json
import os
import re
import sys

root = sys.argv[1] if len(sys.argv) > 1 else 'results'
out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(root, 'collected.csv')

UNIT = {'µs': 0.001, 'us': 0.001, 'ms': 1, 's': 1000, 'm': 60000, 'h': 3600000}


def dur_ms(t):
    """k6 표기 '13m0s', '9.38s', '211.9µs', '1h2m3s' → ms"""
    total = 0.0
    for num, unit in re.findall(r'([\d.]+)(µs|us|ms|h|m|s)', t):
        total += float(num) * UNIT[unit]
    return total


def trend_line(log, name):
    m = re.search(rf'^\s+{name}\.+: (.+)$', log, re.M)
    if not m:
        return None
    vals = dict(re.findall(r'(avg|min|med|p\(90\)|p\(95\)|p\(99\)|max|count)=(\S+)', m.group(1)))
    return {k: (int(v) if k == 'count' else dur_ms(v)) for k, v in vals.items()}


def summary_vals(path, name):
    try:
        mm = json.load(open(path))['metrics'].get(name, {})
    except (OSError, ValueError):
        return {}
    return mm.get('values', mm)


rows = []
for run in sorted(os.listdir(root)):
    rdir = os.path.join(root, run)
    if not os.path.isdir(rdir):
        continue
    nm = re.search(r'_N(\d+)$', run)
    for cfg in ('a', 'f_hmac'):
        d = os.path.join(rdir, cfg)
        logp = os.path.join(d, 'k6.log')
        if not os.path.isfile(logp):
            continue
        log = open(logp, encoding='utf-8', errors='replace').read()
        lt = trend_line(log, 'login_ms')
        if not lt:
            print(f'[skip] {logp}: login_ms 요약 없음 (중단된 실행?)', file=sys.stderr)
            continue
        dt = trend_line(log, 'decrypt_ms') or {}
        sm = re.search(r'^\s+login_success\.+: ([\d.]+)% (\d+) out of (\d+)', log, re.M)
        meta = {}
        mp = os.path.join(d, 'meta.txt')
        if os.path.isfile(mp):
            for line in open(mp, encoding='utf-8'):
                k, _, v = line.partition(':')
                meta[k.strip()] = v.strip()
        warm = re.match(r'(\d+)', meta.get('warmup', ''))

        sj = summary_vals(os.path.join(d, 'summary.json'), 'login_ms')
        sd = summary_vals(os.path.join(d, 'summary.json'), 'decrypt_ms')
        precise = {}
        for k in ('avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'):
            v = sj.get(k)
            if v is not None:
                # k6.log 는 유효숫자 4자리 또는 초 단위로 반올림 → 1% 또는 1초 이내면 같은 값
                if abs(v - lt[k]) > max(0.01 * v, 1000 if v >= 60000 else 0.01 * v + 0.01):
                    print(f'[warn] {d} {k}: k6.log={lt[k]:.3f} summary.json={v:.3f}', file=sys.stderr)
                precise[k] = v
            else:
                precise[k] = lt[k]

        rows.append({
            'run_id': run,
            'config': cfg,
            'N': nm.group(1) if nm else '',
            'warmup': warm.group(1) if warm else '',
            'measured_requests': lt.get('count', ''),
            'success_rate': f'{float(sm.group(1)) / 100:g}' if sm else '',
            'login_avg_ms': f"{precise['avg']:.3f}",
            'login_min_ms': f"{precise['min']:.3f}",
            'login_med_ms': f"{precise['med']:.3f}",
            'login_p90_ms': f"{precise['p(90)']:.3f}",
            'login_p95_ms': f"{precise['p(95)']:.3f}",
            'login_p99_ms': f"{precise['p(99)']:.3f}",
            'login_max_ms': f"{precise['max']:.3f}",
            'decrypt_med_ms': f"{sd.get('med', dt.get('med', 0)):.3f}" if (sd or dt) else '',
            'start': meta.get('start', ''),
            'end': meta.get('end', ''),
            'k6_exit': meta.get('k6_exit', '').split()[0] if meta.get('k6_exit') else '',
        })

with open(out, 'w', newline='') as f:
    w = csv.DictWriter(f, fieldnames=list(rows[0]))
    w.writeheader()
    w.writerows(rows)
print(f'[collect] {len(rows)}건 → {out}')

# 규모별 요약 (같은 규모의 측정 여러 회를 평균) → summary.csv
LABEL = {10000: '1만', 100000: '10만', 1000000: '100만', 10000000: '1000만'}


def mean(xs):
    return sum(xs) / len(xs) if xs else None


def fmt(v, nd=3):
    return '' if v is None else f'{v:.{nd}f}'


def human(ms):
    if ms is None:
        return ''
    if ms < 1000:
        return f'{ms:.1f}ms'
    if ms < 60000:
        return f'{ms / 1000:.2f}초'
    if ms < 600000:  # 10분 미만은 초로 (예: 82.9초)
        return f'{ms / 1000:.1f}초'
    return f'{ms / 1000:,.1f}초 ({ms / 60000:.1f}분)'  # 10분 이상: 초 + 괄호 안에 분


summary = []
prev_a = None
for n in sorted({int(r['N']) for r in rows if r['N']}):
    a = [r for r in rows if r['N'] and int(r['N']) == n and r['config'] == 'a']
    f = [r for r in rows if r['N'] and int(r['N']) == n and r['config'] == 'f_hmac']
    col = lambda rs, k: mean([float(r[k]) for r in rs])
    a_avg, f_avg = col(a, 'login_avg_ms'), col(f, 'login_avg_ms')
    summary.append({
        'N': n,
        'scale': LABEL.get(n, f'{n:,}'),
        'runs_a': len(a),
        'runs_f_hmac': len(f),
        'a_requests_per_run': a[0]['measured_requests'] if a else '',
        'f_requests_per_run': f[0]['measured_requests'] if f else '',
        'a_avg_ms': fmt(a_avg),
        'a_med_ms': fmt(col(a, 'login_med_ms')),
        'a_p95_ms': fmt(col(a, 'login_p95_ms')),
        'a_max_ms': fmt(max(float(r['login_max_ms']) for r in a) if a else None),
        'a_growth_vs_prev': fmt(a_avg / prev_a if (a_avg and prev_a) else None, 2),
        'f_avg_ms': fmt(f_avg),
        'f_med_ms': fmt(col(f, 'login_med_ms')),
        'f_p95_ms': fmt(col(f, 'login_p95_ms')),
        'f_max_ms': fmt(max(float(r['login_max_ms']) for r in f) if f else None),
        'ratio_a_over_f': fmt(a_avg / f_avg if (a_avg and f_avg) else None, 0),
        'a_avg': human(a_avg),
        'f_avg': human(f_avg),
        'success_rate_min': min(float(r['success_rate']) for r in a + f if r['success_rate'] != '') if (a or f) else '',
    })
    prev_a = a_avg

sum_out = os.path.join(os.path.dirname(out) or '.', 'summary.csv')
with open(sum_out, 'w', newline='', encoding='utf-8-sig') as f:  # BOM: Excel에서 한글이 깨지지 않게
    w = csv.DictWriter(f, fieldnames=list(summary[0]))
    w.writeheader()
    w.writerows(summary)
print(f'[collect] 규모별 요약 {len(summary)}줄 → {sum_out}')
for s in summary:
    print(f"  {s['scale']:>6}: A {s['a_avg']:>8} | F_HMAC {s['f_avg']:>7} | {int(float(s['ratio_a_over_f'] or 0)):,}배  ({s['runs_a']}회/{s['runs_f_hmac']}회)")


# 사람이 보기 쉬운 표 → summary.md (마크다운 미리보기로도, 메모장/터미널로도 줄이 맞게)
import unicodedata


def width(t):
    return sum(2 if unicodedata.east_asian_width(ch) in 'WF' else 1 for ch in str(t))


def table(head, body, align):
    cols = list(zip(*([head] + body)))
    ws = [max(width(c) for c in col) for col in cols]

    def cell(t, w, al):
        pad = w - width(t)
        if al == 'r':
            return ' ' * pad + str(t)
        if al == 'c':
            return ' ' * (pad // 2) + str(t) + ' ' * (pad - pad // 2)
        return str(t) + ' ' * pad

    line = lambda r: '| ' + ' | '.join(cell(t, w, al) for t, w, al in zip(r, ws, align)) + ' |'
    sep = '|' + '|'.join((':' + '-' * w + ':') if al == 'c' else ('-' * (w + 1) + ':') if al == 'r' else ('-' * (w + 2)) for w, al in zip(ws, align)) + '|'
    return '\n'.join([line(head), sep] + [line(r) for r in body])


def hm(v):
    return human(float(v)) if v not in ('', None) else '–'


quick = [[s['scale'], hm(s['a_avg_ms']), hm(s['f_avg_ms']), f"{int(float(s['ratio_a_over_f'])):,}배"] for s in summary]
detail = [[s['scale'], hm(s['a_avg_ms']), hm(s['a_med_ms']), hm(s['a_p95_ms']), hm(s['a_max_ms']),
           (f"×{float(s['a_growth_vs_prev']):.1f}" if s['a_growth_vs_prev'] else '–'),
           hm(s['f_avg_ms']), hm(s['f_med_ms']), hm(s['f_p95_ms']), f"{s['a_requests_per_run']} / {s['f_requests_per_run']}"]
          for s in summary]
runs_tbl = [[r['run_id'], r['config'], LABEL.get(int(r['N']), r['N']), f"{r['warmup']}회", r['measured_requests'],
             hm(r['login_avg_ms']), hm(r['login_med_ms']), hm(r['login_p95_ms']), hm(r['login_max_ms']),
             f"{float(r['success_rate']):.0%}" if r['success_rate'] else ''] for r in rows]

md = f"""# AES 전체 스캔(A) vs HMAC 해시 인덱스(F_HMAC) 로그인 성능

규모마다 측정 3회의 평균입니다. 원본: 각 결과 폴더의 `k6.log` / `summary.json` / `meta.txt`
(`python3 k6/lib/collect-results.py` 로 다시 만들 수 있음)

## 한눈에 보기

{table(['규모', 'A (AES 전체 스캔)', 'F_HMAC (해시 인덱스)', 'A가 느린 배수'], quick, 'crrr')}

## 상세

{table(['규모', 'A 평균', 'A 중앙값', 'A p95', 'A 최대', 'A 증가 배율', 'F 평균', 'F 중앙값', 'F p95', '요청 수 (A / F)'], detail, 'crrrrcrrrc')}

- **A 증가 배율**: 바로 아래 규모보다 A 평균이 몇 배 늘었는지. 데이터가 10배 늘 때 A도 거의 10배씩 늘었다.
- **F_HMAC**: 규모와 상관없이 거의 일정하다. 대부분이 bcrypt 비밀번호 검증 시간이다.
- **요청 수**: 측정 1회당 요청 수. 워밍업은 별도 k6 실행으로 돌려 통계에 들어가지 않는다.
- 모든 측정에서 로그인 성공률 100%.

## 측정 조건

| 항목 | 값 |
|------|----|
| 부하 | k6, VU 1, 고정 반복 횟수 |
| 워밍업 | A: 1만 5회 · 10만 3회 · 100만 2회 · 1000만 1회 / F_HMAC: 5회 |
| PostgreSQL | 16, 메모리 5GB, CPU 3, shared_buffers 2GB |
| 앱 서버 | Spring Boot, 메모리 2GB, CPU 3, 힙 1GB |
| 로그인 | 존재하는 이메일 + 올바른 비밀번호, A·F_HMAC이 같은 seq를 같은 순서로 조회 |

## 전체 측정 기록

{table(['실행', '구성', '규모', '워밍업', '요청 수', '평균', '중앙값', 'p95', '최대', '성공률'], runs_tbl, 'lcccrrrrrr')}
"""
md_out = os.path.join(os.path.dirname(os.path.abspath(root)), 'summary.md')  # 저장소 루트 (results 의 상위)
with open(md_out, 'w', encoding='utf-8') as f:
    f.write(md)
print(f'[collect] 보기 쉬운 표 → {md_out}')
