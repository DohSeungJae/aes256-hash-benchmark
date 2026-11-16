#!/usr/bin/env python3
"""측정 결과 그래프 (README용 PNG).

사용: python3 k6/lib/plot-results.py [results_dir] [out_dir]
      (기본: results → docs/images)
입력: results/collected.csv (k6/lib/collect-results.py 가 만든 측정별 결과)
출력:
  login_time_by_scale.png  규모별 평균 로그인 시간 (A vs F_HMAC, 로그-로그)
  slowdown_ratio.png       규모별 A ÷ F_HMAC 배율
필요: matplotlib (pip install matplotlib). 한글 글꼴은 맑은 고딕(Windows) → Noto/Nanum → 기본 순으로 찾는다.
"""
import csv
import os
import statistics as st
import sys

import matplotlib

matplotlib.use('Agg')
import matplotlib.pyplot as plt  # noqa: E402
from matplotlib import font_manager  # noqa: E402
from matplotlib.ticker import FixedLocator, NullLocator  # noqa: E402

root = sys.argv[1] if len(sys.argv) > 1 else 'results'
out_dir = sys.argv[2] if len(sys.argv) > 2 else os.path.join('docs', 'images')
os.makedirs(out_dir, exist_ok=True)

# ---- 한글 글꼴 ----
for path in ('/mnt/c/Windows/Fonts/malgun.ttf', '/mnt/c/Windows/Fonts/malgunbd.ttf',
             '/usr/share/fonts/truetype/nanum/NanumGothic.ttf'):
    if os.path.isfile(path):
        font_manager.fontManager.addfont(path)
names = {f.name for f in font_manager.fontManager.ttflist}
for fam in ('Malgun Gothic', 'NanumGothic', 'Noto Sans CJK KR', 'WenQuanYi Zen Hei'):
    if fam in names:
        plt.rcParams['font.family'] = fam
        break
plt.rcParams['axes.unicode_minus'] = False

# ---- 데이터 ----
rows = list(csv.DictReader(open(os.path.join(root, 'collected.csv'), encoding='utf-8')))
LABEL = {10000: '1만', 100000: '10만', 1000000: '100만', 10000000: '1000만'}
ns = sorted({int(r['N']) for r in rows})


def runs(cfg, n):
    return [float(r['login_avg_ms']) for r in rows if r['config'] == cfg and int(r['N']) == n]


a_mean = [st.mean(runs('a', n)) for n in ns]
f_mean = [st.mean(runs('f_hmac', n)) for n in ns]


def human(ms):
    if ms < 1000:
        return f'{ms:.1f}ms'
    if ms < 60000:
        return f'{ms / 1000:.2f}초'
    if ms < 600000:
        return f'{ms / 1000:.1f}초'
    return f'{ms / 1000:,.1f}초\n({ms / 60000:.1f}분)'


# ---- 스타일 ----
C_A = '#C8553D'   # A: AES 전체 스캔
C_F = '#2A7F8E'   # F_HMAC: 해시 인덱스
INK = '#2B2D33'
MUTED = '#6B6F78'
GRID = '#E4E6EA'
plt.rcParams.update({
    'figure.dpi': 110, 'savefig.dpi': 200,
    'axes.edgecolor': GRID, 'axes.labelcolor': MUTED, 'xtick.color': MUTED, 'ytick.color': MUTED,
    'axes.spines.top': False, 'axes.spines.right': False, 'font.size': 11,
})

# ===== 1) 규모별 평균 로그인 시간 (로그-로그) =====
fig, ax = plt.subplots(figsize=(9, 5.6))
ax.set_xscale('log')
ax.set_yscale('log')

for cfg, mean, color, name in (('a', a_mean, C_A, 'A · AES-256 복호화 전체 스캔'),
                               ('f_hmac', f_mean, C_F, 'F_HMAC · HMAC-SHA256 해시 인덱스')):
    ax.plot(ns, mean, '-o', color=color, lw=2.6, ms=8, zorder=3, label=name)
    for n in ns:  # 각 측정(3회)을 작은 점으로
        ax.scatter([n] * len(runs(cfg, n)), runs(cfg, n), s=22, color=color, alpha=0.35, zorder=2, lw=0)

for n, v in zip(ns, a_mean):
    ax.annotate(human(v), (n, v), textcoords='offset points', xytext=(0, 12), ha='center', va='bottom',
                fontsize=10, color=C_A, fontweight='bold')
for n, v in zip(ns, f_mean):
    ax.annotate(human(v), (n, v), textcoords='offset points', xytext=(0, -14), ha='center', va='top',
                fontsize=10, color=C_F, fontweight='bold')

yt = [10, 100, 1000, 10000, 60000, 600000, 3600000]
ax.yaxis.set_major_locator(FixedLocator(yt))
ax.set_yticklabels(['10ms', '100ms', '1초', '10초', '1분', '10분', '1시간'])
ax.yaxis.set_minor_locator(NullLocator())
ax.set_ylim(15, 4_000_000)
ax.xaxis.set_major_locator(FixedLocator(ns))
ax.set_xticklabels([f'{LABEL.get(n, n)}건' for n in ns])
ax.xaxis.set_minor_locator(NullLocator())
ax.set_xlim(ns[0] / 2.2, ns[-1] * 2.2)
ax.grid(True, axis='y', color=GRID, lw=1)
ax.set_xlabel('회원 수 (로그 눈금)')
ax.set_ylabel('평균 로그인 응답시간 (로그 눈금)')
ax.set_title('규모별 평균 로그인 시간: A는 데이터에 비례, F_HMAC은 일정', loc='left', color=INK, fontsize=14,
             fontweight='bold', pad=14)
ax.legend(loc='upper left', frameon=False, fontsize=10.5)
fig.text(0.01, 0.01, '규모별 3회 측정 평균 (작은 점 = 각 측정) · k6 VU 1 · 워밍업 제외 · 로그인 성공률 100%',
         fontsize=9, color=MUTED)
fig.tight_layout(rect=(0, 0.03, 1, 1))
p1 = os.path.join(out_dir, 'login_time_by_scale.png')
fig.savefig(p1, facecolor='white')
plt.close(fig)

# ===== 2) A ÷ F_HMAC 배율 =====
ratio = [a / f for a, f in zip(a_mean, f_mean)]
fig, ax = plt.subplots(figsize=(9, 4.8))
xs = range(len(ns))
bars = ax.bar(xs, ratio, width=0.58, color=C_A, zorder=3)
ax.set_yscale('log')
ax.set_ylim(1, max(ratio) * 6)
ax.yaxis.set_major_locator(FixedLocator([1, 10, 100, 1000, 10000]))
ax.set_yticklabels(['1배', '10배', '100배', '1,000배', '10,000배'])
ax.yaxis.set_minor_locator(NullLocator())
ax.set_xticks(list(xs))
ax.set_xticklabels([f'{LABEL.get(n, n)}건' for n in ns])
ax.grid(True, axis='y', color=GRID, lw=1, zorder=0)
for b, r, a, f in zip(bars, ratio, a_mean, f_mean):
    ax.annotate(f'{r:,.0f}배', (b.get_x() + b.get_width() / 2, r), textcoords='offset points', xytext=(0, 6),
                ha='center', va='bottom', fontsize=13, fontweight='bold', color=INK)
ax.set_ylabel('A 평균 ÷ F_HMAC 평균 (로그 눈금)')
ax.set_title('A(전체 스캔)가 F_HMAC(해시 인덱스)보다 몇 배 느린가', loc='left', color=INK, fontsize=14,
             fontweight='bold', pad=14)
fig.text(0.01, 0.01, '규모별 3회 측정 평균의 비율', fontsize=9, color=MUTED)
fig.tight_layout(rect=(0, 0.03, 1, 1))
p2 = os.path.join(out_dir, 'slowdown_ratio.png')
fig.savefig(p2, facecolor='white')
plt.close(fig)

print(f'[plot] {p1}\n[plot] {p2}  (font: {plt.rcParams["font.family"]})')
