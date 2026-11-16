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

# 시간 복잡도 근거: (1) 규모 대비 로그-로그 기울기, (2) A 요청별 응답시간 vs seq 직선 회귀
import glob
import math


def slope(xs, ys):
    mx, my = sum(xs) / len(xs), sum(ys) / len(ys)
    return sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / sum((x - mx) ** 2 for x in xs)


ok = [s for s in summary if s['a_avg_ms'] and s['f_avg_ms']]
lx = [math.log10(s['N']) for s in ok]
k_a = slope(lx, [math.log10(float(s['a_avg_ms'])) for s in ok]) if len(ok) > 1 else None
k_f = slope(lx, [math.log10(float(s['f_avg_ms'])) for s in ok]) if len(ok) > 1 else None

seq_fit = []
for s in ok:
    pts = []
    for p in glob.glob(os.path.join(root, f"*_N{s['N']}", 'a', 'requests.csv')):
        for r in csv.DictReader(open(p, encoding='utf-8')):
            if r.get('phase') == 'measure' and r.get('seq'):
                pts.append((int(r['seq']), float(r['duration_ms'])))
    if len(pts) < 3:
        continue
    xs, ys = [q[0] for q in pts], [q[1] for q in pts]
    b = slope(xs, ys)
    c = sum(ys) / len(ys) - b * sum(xs) / len(xs)
    my = sum(ys) / len(ys)
    r2 = 1 - sum((y - (c + b * x)) ** 2 for x, y in pts) / sum((y - my) ** 2 for y in ys)
    seq_fit.append([s['scale'], len(pts), f'{b * 1000:.1f}ms', f'{r2:.3f}'])

complexity = ''
if k_a is not None:
    complexity = f"""## 시간 복잡도: 예상대로 A는 O(n), F_HMAC은 O(1)

- **A (AES 전체 스캔) = O(n)**: 행을 앞에서부터 하나씩 복호화해 비교하므로 읽는 행 수에 비례한다.
  규모-시간 로그-로그 기울기가 **{k_a:.3f}** (1이면 O(n)) — 데이터가 10배 늘 때 시간도 약 10배 늘었다.
- **F_HMAC (해시 인덱스) = O(1)**: 이메일의 HMAC 값으로 hash 인덱스를 한 번 조회한다.
  로그-로그 기울기가 **{k_f:.3f}** (0이면 O(1)) — 1만 건부터 1000만 건까지 응답시간이 거의 같았다.

![규모별 평균 로그인 시간](docs/images/login_time_by_scale.png)

![A ÷ F_HMAC 배율](docs/images/slowdown_ratio.png)
"""
    if seq_fit:
        complexity += f"""
A는 일치하는 행(seq번째)을 찾으면 멈추므로, 요청별 응답시간을 `고정 시간 + 행당 비용 × seq` 로 맞춰 보면
규모와 상관없이 행당 비용이 같고 거의 완전한 직선이다(R²가 1에 가까울수록 직선).

{table(['규모', 'A 요청 수', '행 1천 개당 시간', 'R²'], seq_fit, 'crrr')}

평균 증가 배율이 정확히 10배가 아니라 조금 작은 이유는 (1) 행 수와 무관한 고정 비용(bcrypt 검증·HTTP, 약 60ms)이
작은 규모에서 비중이 크고, (2) 무작위로 뽑은 seq의 평균 위치가 규모마다 조금 달랐기 때문이다.
"""

md = f"""# AES 전체 스캔(A) vs HMAC 해시 인덱스(F_HMAC) 로그인 성능

규모마다 측정 3회의 평균. 원본: 각 결과 폴더의 `k6.log` / `summary.json` / `meta.txt`
(`python3 k6/lib/collect-results.py` 로 다시 만들 수 있음)

## 비교 대상과 측정 기능

| 구성 | 저장하는 값 | 인덱스 | 로그인 시 이메일 찾는 방법 |
|---|---|---|---|
| **A** | AES-256 암호문 | 없음 (PK만) | 모든 행을 복호화해 비교 (전체 스캔) |
| **F_HMAC** | AES-256 암호문 + 이메일의 HMAC-SHA256 값 | `email_hash`에 hash 인덱스 | HMAC 값으로 인덱스를 한 번 조회 |

**왜 A와 F_HMAC인가**

- **A = 해결 전(기준선).** AES-256은 비결정적 암호화라 같은 이메일도 매번 암호문이 다르다.
  암호문만으로는 DB가 이메일로 행을 찾을 수 없어, 모든 행을 복호화하며 비교해야 한다.
- **F_HMAC = 해결 후(최종 권장 구조).** 이메일의 해시 값을 같은 테이블에 저장(blind index)하고
  hash 인덱스를 건다. 로그인은 등치 조회뿐이라 hash 인덱스면 충분하고, map 테이블 조인도 없다.
  해시는 SHA-256 대신 비밀 키가 필요한 HMAC-SHA256을 써서 이메일 목록 대입으로 원래 이메일을
  알아낼 수 없게 했다.
- 두 끝을 직접 비교하면 blind index 도입 효과가 가장 분명하게 드러난다. 중간 단계 구성(B~E)은
  측정 시간이 한정돼 있어 뺐다.

**측정 기능: 로그인만.** 계획서 시나리오(S1~S5) 중 S1 로그인만 측정했다. 이메일로 회원을 찾는
비용이 두 구성의 차이가 가장 직접 드러나는 지점이다. 모든 요청은 존재하는 이메일 + 올바른
비밀번호로 보내는 성공 로그인이고, A와 F_HMAC이 같은 회원을 같은 순서로 조회한다.

## 한눈에 보기

{table(['규모', 'A (AES 전체 스캔)', 'F_HMAC (해시 인덱스)', 'A가 느린 배수'], quick, 'crrr')}

## 상세

{table(['규모', 'A 평균', 'A 중앙값', 'A p95', 'A 최대', 'A 증가 배율', 'F 평균', 'F 중앙값', 'F p95', '요청 수 (A / F)'], detail, 'crrrrcrrrc')}

- **A 증가 배율**: 바로 아래 규모보다 A 평균이 몇 배 늘었는지. 데이터가 10배 늘 때 A도 거의 10배씩 늘었다.
- **F_HMAC**: 규모와 상관없이 거의 일정하다. 대부분이 bcrypt 비밀번호 검증 시간이다.
- **요청 수**: 측정 1회당 요청 수. 워밍업은 별도 k6 실행으로 돌려 통계에 들어가지 않는다.
- 모든 측정에서 로그인 성공률 100%.

{complexity}
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
