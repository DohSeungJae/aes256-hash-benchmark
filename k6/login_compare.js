// A vs F_HMAC 로그인 비교 (VU 1, 고정 반복 횟수).
//
// 사용:
//   k6 run --env CONFIG=a --env N=10000 --env PHASE=warmup                   k6/login_compare.js  # 1) 워밍업 (결과 버림)
//   k6 run --env CONFIG=a --env N=10000 --env PHASE=measure --env ITERATIONS=200 k6/login_compare.js  # 2) 측정
//   → 워밍업과 측정을 k6 프로세스 2개로 나눠, 측정 실행의 모든 통계(http_req_duration 등 기본 지표,
//     --out csv 원본 포함)에 워밍업 요청이 아예 들어가지 않게 한다. 서버는 계속 떠 있으므로 워밍업 효과는 유지된다.
//     k6/run-one.sh 가 이 두 단계를 자동으로 실행한다.
//
// 환경변수:
//   CONFIG      a | f_hmac                         (필수)
//   N           현재 적재 규모. k6/data/sample-<N>.csv 를 읽는다 (make-sample.sh 로 생성) (필수)
//   PHASE       warmup | measure (기본 measure)
//   ITERATIONS  측정 요청 수 (기본: 샘플 개수 - 5)
//   WARMUP      워밍업 요청 수 (기본 5). 워밍업은 샘플 앞쪽 5개(인덱스 0~4)를 돌려 쓴다
//               → 측정은 워밍업 횟수와 상관없이 항상 샘플 6번째(인덱스 5)부터 시작한다
//                 (A·F_HMAC의 워밍업 횟수가 달라도 같은 seq를 같은 순서로 측정)
//   BASE_URL    기본 http://localhost:8080
//   PASSWORD    load.sh 의 PW_PLAIN 과 같아야 함 (기본 test1234!)
//   TIMEOUT     요청 1건 타임아웃 (기본 60m — A는 규모가 크면 1건에 수십 분 걸린다)
//
// 결과 지표:
//   login_ms       응답시간 (이 값을 비교에 쓴다)
//   decrypt_ms     서버가 보고한 복호화 누적 시간 (응답의 decryptNanos)
//   login_success  로그인 성공 비율 (1.0 이어야 정상)
import http from 'k6/http';
import { check, fail } from 'k6';
import { SharedArray } from 'k6/data';
import { Trend, Rate } from 'k6/metrics';

const CONFIG = (__ENV.CONFIG || '').toLowerCase();
const N = __ENV.N;
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PASSWORD = __ENV.PASSWORD || 'test1234!';
const WARMUP = parseInt(__ENV.WARMUP || '5', 10);
const MEASURE_START = 5; // 측정 시작 인덱스 (고정). 샘플 0~4는 워밍업 전용.
const PHASE = (__ENV.PHASE || 'measure').toLowerCase();
if (PHASE !== 'warmup' && PHASE !== 'measure') {
    throw new Error(`PHASE는 warmup 또는 measure 이어야 합니다 (현재: "${PHASE}")`);
}

if (CONFIG !== 'a' && CONFIG !== 'f_hmac') {
    throw new Error(`CONFIG는 a 또는 f_hmac 이어야 합니다 (현재: "${CONFIG}")`);
}
if (!N) {
    throw new Error('N(적재 규모)을 지정하세요. 예: --env N=10000');
}

// seq,email 두 열짜리 작은 CSV라서 직접 파싱한다.
const samples = new SharedArray('samples', function () {
    return open(`./data/sample-${N}.csv`)
        .split(/\r?\n/)
        .slice(1)
        .filter((line) => line.trim() !== '')
        .map((line) => {
            const [seq, email] = line.split(',');
            return { seq: parseInt(seq, 10), email };
        });
});

const ITERATIONS = parseInt(__ENV.ITERATIONS || `${Math.max(samples.length - MEASURE_START, 1)}`, 10);
const COUNT = PHASE === 'warmup' ? WARMUP : ITERATIONS;

export const options = {
    scenarios: {
        login: {
            executor: 'per-vu-iterations',
            vus: 1,
            iterations: COUNT,
            maxDuration: __ENV.MAX_DURATION || '24h',
        },
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max', 'count'],
    thresholds: {
        login_success: ['rate==1'],
    },
};

const loginMs = new Trend('login_ms', true);
const decryptMs = new Trend('decrypt_ms', true);
const loginSuccess = new Rate('login_success');

export function setup() {
    console.log(`phase=${PHASE} config=${CONFIG} N=${N} sample=${samples.length} warmup=${WARMUP} iterations=${COUNT}`);
    if (samples.length === 0) {
        fail(`k6/data/sample-${N}.csv 가 비어 있습니다. ./k6/make-sample.sh ${N} 를 먼저 실행하세요.`);
    }
}

export default function () {
    // 샘플은 이미 무작위로 섞여 있으므로 순서대로 쓴다 → A와 F_HMAC이 같은 seq를 같은 순서로 조회.
    // 워밍업: 샘플 0~4를 돌려 씀 / 측정: 샘플 5부터
    const idx = PHASE === 'warmup' ? __ITER % MEASURE_START : MEASURE_START + __ITER;
    const s = samples[idx % samples.length];

    const res = http.post(
        `${BASE_URL}/api/bench/${CONFIG}/login`,
        JSON.stringify({ email: s.email, password: PASSWORD }),
        {
            headers: { 'Content-Type': 'application/json' },
            timeout: __ENV.TIMEOUT || '60m',
            // seq 태그: run-one.sh 가 --out csv 원본에서 요청별 기록(requests.csv)을 뽑을 때 쓴다
            tags: { name: `login_${CONFIG}`, phase: PHASE, seq: String(s.seq) },
        },
    );

    let body = null;
    try {
        body = JSON.parse(res.body);
    } catch (e) {
        // 타임아웃/에러 응답
    }
    const ok = res.status === 200 && body !== null && body.success === true;

    check(res, { 'login 200 + success': () => ok });
    if (!ok) {
        console.warn(`실패 seq=${s.seq} status=${res.status} body=${String(res.body).slice(0, 200)}`);
    }

    loginSuccess.add(ok);
    loginMs.add(res.timings.duration, { seq: String(s.seq) });
    if (body !== null && typeof body.decryptNanos === 'number') {
        decryptMs.add(body.decryptNanos / 1e6);
    }
}
