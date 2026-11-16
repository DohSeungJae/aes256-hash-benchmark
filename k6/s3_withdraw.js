// S3 회원탈퇴 (계획서 8장: "가입 후 탈퇴"). H4: 쓰기 비용 측정.
// 매 반복마다 새 계정을 가입시키고 곧바로 탈퇴시켜, 데이터 규모를 그대로 유지한 채
// user+map(또는 inline) 삭제 트랜잭션 비용만 측정한다.
import http from 'k6/http';
import { check } from 'k6';
import { benchUrl, randomNewEmail, PASSWORD } from './lib/config.js';

export const options = {
    scenarios: {
        withdraw: {
            executor: 'constant-vus',
            vus: Number(__ENV.VUS || 10),
            duration: __ENV.DURATION || '5m',
        },
    },
};

export default function () {
    const email = randomNewEmail('withdraw');
    const payload = JSON.stringify({ email, password: PASSWORD });

    const signupRes = http.post(benchUrl('/signup'), payload, { headers: { 'Content-Type': 'application/json' } });
    check(signupRes, { 'signup 201': (r) => r.status === 201 });

    const withdrawRes = http.post(benchUrl('/withdraw'), payload, { headers: { 'Content-Type': 'application/json' } });
    check(withdrawRes, { 'withdraw 204': (r) => r.status === 204 });
}
