// 모든 k6 시나리오가 공유하는 설정 로더.
// 환경변수(계획서 8장 "부하 테스트 시나리오"에서 말하는 파라미터들):
//   BASE_URL   서버 주소 (기본 http://localhost:8080)
//   CONFIG     비교 구성 키: a,b,c,d,e,f,c_hmac,d_hmac,e_hmac,f_hmac
//   EMAILS_CSV 생성기가 만든 CSV 경로 (seq 무작위 추출용, 기본 ./data/users_000.csv)
//   PASSWORD   모든 시드 계정이 공유하는 더미 비밀번호 (load-data.sh의 DUMMY_PASSWORD와 일치해야 함)
import { SharedArray } from 'k6/data';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const CONFIG = __ENV.CONFIG || 'c';
export const PASSWORD = __ENV.PASSWORD || 'BenchPassword123!';
const EMAILS_CSV = __ENV.EMAILS_CSV || './data/users_000.csv';

// SharedArray는 VU들 사이에서 메모리를 공유해, CSV를 VU마다 다시 읽지 않게 해준다.
// (계획서 8장: "이메일은 seq 순서대로 규칙적으로 만들고, 테스트에서는 seq를 무작위로 뽑아
//  캐시 편향을 피한다")
export const emails = new SharedArray('emails', function () {
    const content = open(EMAILS_CSV);
    const parsed = papaparse.parse(content, { header: true, skipEmptyLines: true });
    return parsed.data.map((row) => row.email);
});

export function randomEmail() {
    return emails[Math.floor(Math.random() * emails.length)];
}

export function benchUrl(path) {
    return `${BASE_URL}/api/bench/${CONFIG}${path}`;
}

export function randomNewEmail(prefix) {
    return `${prefix}-${__VU}-${__ITER}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@new.bench.example.com`;
}
