package com.doh.ahbench.common;

/**
 * 구성 A~F는 테이블 구조상 3가지 모양(shape)으로 묶인다. 같은 모양은 같은 조회·쓰기
 * 로직을 공유하고, 테이블 이름과 해시 전략만 다르다 (common/ConfigSpec 참고).
 */
public enum ConfigShape {
    /** 구성 A: 암호문만 저장. 전체 조회 후 앱에서 행마다 복호화·비교. */
    FULL_SCAN,

    /** 구성 B, C, D (+HMAC 변형): user 테이블은 해시만, map 테이블이 해시↔암호문 쌍을 보관. */
    MAP_TABLE,

    /** 구성 E, F (+HMAC 변형): map 테이블 없이 user 테이블 한 곳에 해시+암호문을 같이 저장. */
    INLINE
}
