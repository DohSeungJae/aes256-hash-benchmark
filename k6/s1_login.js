// S1 로그인 (계획서 8장). H1/H2/H3 핵심 가설을 검증하는 시나리오.
// 사용: k6 run --env CONFIG=c --env BASE_URL=http://<서버>:8080 k6/s1_login.js
import http from 'k6/http';
import { check } from 'k6';
import { randomEmail, benchUrl, PASSWORD } from './lib/config.js';

export const options = {
    scenarios: {
        ramping: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                { duration: '30s', target: 1 },
                { duration: '2m', target: 1 },   // warm-up (계획서: 2~3분)
                { duration: '30s', target: 5 },
                { duration: '2m', target: 5 },
                { duration: '30s', target: 10 },
                { duration: '2m', target: 10 },
                { duration: '30s', target: 20 },
                { duration: '2m', target: 20 },
                { duration: '30s', target: 50 },
                { duration: '2m', target: 50 },  // 포화 지점 탐색 (1 -> 5 -> 10 -> 20 -> 50)
                { duration: '30s', target: 0 },
            ],
        },
    },
    thresholds: {
        http_req_duration: ['p(95)<5000'], // 절대 기준이 아니라 폭주 조기 감지용
    },
};

export default function () {
    const email = randomEmail();
    const payload = JSON.stringify({ email, password: PASSWORD });
    const res = http.post(benchUrl('/login'), payload, { headers: { 'Content-Type': 'application/json' } });
    check(res, {
        'status is 200': (r) => r.status === 200,
        'success true': (r) => {
            try {
                return JSON.parse(r.body).success === true;
            } catch (e) {
                return false;
            }
        },
    });
}
