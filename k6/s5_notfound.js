// S5 존재하지 않는 이메일 조회 (계획서 8장). 구성 A는 실패해도 전체 스캔을 다 돈다 —
// 실패 경로 비용이 성공 경로보다 비싸거나 같은 유일한 시나리오.
import http from 'k6/http';
import { check } from 'k6';
import { benchUrl, PASSWORD } from './lib/config.js';

export const options = {
    scenarios: {
        notfound: {
            executor: 'constant-vus',
            vus: Number(__ENV.VUS || 10),
            duration: __ENV.DURATION || '3m',
        },
    },
};

export default function () {
    // 데이터셋에 절대 존재하지 않는 도메인 — new.bench.example.com은 생성기가 쓰지 않는다.
    const email = `does-not-exist-${__VU}-${__ITER}@never.bench.example.com`;
    const res = http.post(benchUrl('/login'), JSON.stringify({ email, password: PASSWORD }), {
        headers: { 'Content-Type': 'application/json' },
    });
    check(res, {
        'status is 401 (not found)': (r) => r.status === 401,
        'success is false': (r) => {
            try {
                return JSON.parse(r.body).success === false;
            } catch (e) {
                return false;
            }
        },
    });
}
