// S4 혼합 시나리오 (계획서 8장). 읽기:쓰기 = 95:5 또는 70:30.
// 사용: k6 run --env CONFIG=c --env WRITE_RATIO=0.05 k6/s4_mixed.js
//      k6 run --env CONFIG=c --env WRITE_RATIO=0.30 k6/s4_mixed.js
import http from 'k6/http';
import { check } from 'k6';
import { randomEmail, benchUrl, randomNewEmail, PASSWORD } from './lib/config.js';

const WRITE_RATIO = Number(__ENV.WRITE_RATIO || 0.05);

export const options = {
    scenarios: {
        mixed: {
            executor: 'constant-vus',
            vus: Number(__ENV.VUS || 20),
            duration: __ENV.DURATION || '5m',
        },
    },
};

function doRead() {
    const email = randomEmail();
    const res = http.post(benchUrl('/login'), JSON.stringify({ email, password: PASSWORD }), {
        headers: { 'Content-Type': 'application/json' },
        tags: { op: 'read' },
    });
    check(res, { 'read 200': (r) => r.status === 200 });
}

function doWrite() {
    // 가입 후 탈퇴해서 데이터 규모를 유지한다 (S3와 동일한 접근).
    const email = randomNewEmail('mixed');
    const payload = JSON.stringify({ email, password: PASSWORD });
    const signupRes = http.post(benchUrl('/signup'), payload, {
        headers: { 'Content-Type': 'application/json' },
        tags: { op: 'write' },
    });
    check(signupRes, { 'write signup 201': (r) => r.status === 201 });

    const withdrawRes = http.post(benchUrl('/withdraw'), payload, {
        headers: { 'Content-Type': 'application/json' },
        tags: { op: 'write' },
    });
    check(withdrawRes, { 'write withdraw 204': (r) => r.status === 204 });
}

export default function () {
    if (Math.random() < WRITE_RATIO) {
        doWrite();
    } else {
        doRead();
    }
}
