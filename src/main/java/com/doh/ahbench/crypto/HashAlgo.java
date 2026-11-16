package com.doh.ahbench.crypto;

/**
 * 조회 키(blind index)로 쓰는 해시 알고리즘.
 *
 * NONE       : 구성 A처럼 해시를 전혀 쓰지 않음
 * SHA256     : 블로그 원안. 솔트/키 없는 단순 SHA-256(Base64) — 사전 대입 공격에 취약 (계획서 11장)
 * HMAC_SHA256: 계획서가 권장하는 정정. 서버 비밀키를 쓰는 키드 해시라 사전 대입 공격에서 안전하면서도
 *              결정적이라 조회 키로 동일하게 쓸 수 있다.
 */
public enum HashAlgo {
    NONE,
    SHA256,
    HMAC_SHA256
}
