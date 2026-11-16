package com.doh.ahbench.common;

/**
 * 계획서 4장 "비교 구성"의 6가지 구성 + 11장 보안 권고(HMAC-SHA256)를 적용한 4가지 변형.
 *
 * A, B, C, D, E, F : 계획서 원안 (해시는 평범한 SHA-256)
 * C_HMAC, D_HMAC, E_HMAC, F_HMAC : C/D/E/F와 테이블 구조는 동일하지만
 *   조회 키를 HMAC-SHA256(서버 비밀키 사용)으로 바꾼 변형. 솔트 없는 SHA-256의
 *   사전 대입 공격 취약점을 보완한 구조가 성능상 손해가 거의 없는지 측정하기 위함.
 *
 * A/B에는 HMAC 변형을 따로 두지 않았다: A는 해시를 전혀 쓰지 않는 기준선이고,
 * B는 "인덱스 없음"이 측정 목적이라 해시 알고리즘 종류가 결과에 영향을 주지 않기 때문이다.
 */
public enum ConfigId {
    A,
    B,
    C,
    D,
    E,
    F,
    C_HMAC,
    D_HMAC,
    E_HMAC,
    F_HMAC;

    /** 경로 변수(path variable) 등 외부 입력과 매칭할 때 쓰는 소문자 키. 예: "c_hmac" */
    public String key() {
        return name().toLowerCase();
    }

    public static ConfigId fromKey(String key) {
        for (ConfigId id : values()) {
            if (id.key().equals(key.toLowerCase())) {
                return id;
            }
        }
        throw new IllegalArgumentException("알 수 없는 구성: " + key);
    }
}
