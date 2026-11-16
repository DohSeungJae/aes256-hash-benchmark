// S2 회원가입 (계획서 8장). H4: 쓰기 비용 측정.
// 주의: 이 시나리오는 실행할수록 데이터가 계속 늘어난다 — 긴 측정 구간 동안 돌리면
// "회원가입 TPS"가 목적이지 규모 자체를 바꾸는 게 목적이 아니므로, 측정 후 해당 구성
// 테이블을 다시 적재(load-data.sh)해서 규모를 원복하는 것을 권장한다.
import http from 'k6/http';
import { check } from 'k6';
import { benchUrl, randomNewEmail, PASSWORD } from './lib/config.js';

export const options = {
    scenarios: {
        signup: {
            executor: 'constant-vus',
            vus: Number(__ENV.VUS || 10),
            duration: __ENV.DURATION || '5m',
        },
    },
};

export default function () {
    const email = randomNewEmail('signup');
    const payload = JSON.stringify({ email, password: PASSWORD });
    const res = http.post(benchUrl('/signup'), payload, { headers: { 'Content-Type': 'application/json' } });
    check(res, { 'status is 201': (r) => r.status === 201 });
}
