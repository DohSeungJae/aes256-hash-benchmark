package com.doh.ahbench.repo;

/** BenchRowRepository가 주고받는 단순 행(row) 값 객체들. */
public final class Rows {

    /** 구성 A: 전체 스캔 대상 행 (id, 암호문, 비밀번호 해시) */
    public record FullScanRow(long id, String emailAes256, String passwordHash) {
    }

    /** MAP_TABLE 모양의 user 테이블 행 (해시, 비밀번호 해시만 가짐) */
    public record MapShapeUserRow(long id, String emailHash, String passwordHash) {
    }

    /** MAP_TABLE 모양의 map 테이블 행 (해시 ↔ 암호문 쌍) */
    public record MapRow(long id, String emailHash, String emailAes256) {
    }

    /** INLINE 모양의 user 테이블 행 (해시+암호문을 한 행에 같이 가짐) */
    public record InlineUserRow(long id, String emailHash, String emailAes256, String passwordHash) {
    }

    private Rows() {
    }
}
