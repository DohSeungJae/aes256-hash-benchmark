package com.doh.ahbench.common;

import com.doh.ahbench.crypto.HashAlgo;

/**
 * 구성 하나를 완전히 기술하는 값. 테이블 이름, 모양(shape), 해시 알고리즘만 다르면
 * 나머지 조회/가입/탈퇴 로직은 ConfigShape별로 공유된다 (bench 패키지의 Handler 참고).
 *
 * @param id       구성 식별자 (A~F, HMAC 변형)
 * @param shape    테이블 모양 (FULL_SCAN / MAP_TABLE / INLINE)
 * @param userTable 사용자 테이블 이름
 * @param mapTable  MAP_TABLE 모양일 때만 쓰는 해시↔암호문 매핑 테이블 이름. 그 외에는 null.
 * @param hashAlgo  조회 키 해시 알고리즘. FULL_SCAN(구성 A)은 NONE.
 * @param description 결과 문서/로그에 쓸 한 줄 설명
 */
public record ConfigSpec(
        ConfigId id,
        ConfigShape shape,
        String userTable,
        String mapTable,
        HashAlgo hashAlgo,
        String description
) {
    public boolean usesMap() {
        return shape == ConfigShape.MAP_TABLE;
    }
}
