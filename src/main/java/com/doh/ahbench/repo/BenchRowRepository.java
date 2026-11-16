package com.doh.ahbench.repo;

import com.doh.ahbench.repo.Rows.FullScanRow;
import com.doh.ahbench.repo.Rows.InlineUserRow;
import com.doh.ahbench.repo.Rows.MapRow;
import com.doh.ahbench.repo.Rows.MapShapeUserRow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 구성 10가지가 모두 공유하는 데이터 접근 계층.
 *
 * JPA 엔티티를 구성별로 10개씩 만드는 대신 JdbcTemplate 기반으로 테이블 이름을 파라미터로
 * 받는다. 이유 두 가지:
 *  1) 계획서 7장이 지적하듯 Hibernate {@code @Index}는 hash 인덱스를 지원하지 않아
 *     어차피 Flyway로 DDL을 직접 관리해야 한다 — ORM 매핑의 이점이 적다.
 *  2) 이 프로젝트의 핵심 측정 대상은 "쿼리·인덱스·복호화 비용"이지 ORM 오버헤드가 아니다.
 *     10개 구성이 ORM 영속성 컨텍스트 차이로 다른 오버헤드를 갖게 되면 비교의 공정성이
 *     흔들린다 (계획서 9장 "통제 변수"). 모든 구성이 완전히 같은 방식으로 쿼리를 실행하게 한다.
 *
 * 테이블/컬럼 이름은 항상 ConfigRegistry에 고정된 상수에서만 오고, 사용자 입력이 SQL 식별자로
 * 들어가는 경로는 없다. 값(해시, 암호문, id 등)은 전부 PreparedStatement 파라미터로 바인딩한다.
 */
@Repository
public class BenchRowRepository {

    private final JdbcTemplate jdbcTemplate;
    private final JdbcTemplate streamingJdbcTemplate;

    public BenchRowRepository(
            JdbcTemplate jdbcTemplate,
            @Qualifier("streamingJdbcTemplate") JdbcTemplate streamingJdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.streamingJdbcTemplate = streamingJdbcTemplate;
    }

    // ── 구성 A: FULL_SCAN ────────────────────────────────────────────

    /**
     * 테이블 전체를 커서로 스트리밍하며 각 행을 consumer에 넘긴다. 복호화·비교는 호출자(핸들러)가 한다.
     * {@code @Transactional(readOnly = true)} + fetchSize 조합으로 PostgreSQL JDBC 드라이버가
     * 결과를 한 번에 메모리로 올리지 않고 서버 커서로 스트리밍하게 한다.
     */
    @Transactional(readOnly = true)
    public void streamFullScan(String userTable, Consumer<FullScanRow> consumer) {
        String sql = "SELECT id, email_aes256, password_hash FROM " + userTable;
        // RowCallbackHandler는 JdbcTemplate이 rs.next()를 호출한 뒤 행마다 불린다.
        // 여기서 rs.next()를 또 부르면 첫 행을 건너뛴다 (seq=1 로그인이 실패하던 원인).
        streamingJdbcTemplate.query(sql, (RowCallbackHandler) rs -> consumer.accept(new FullScanRow(
                rs.getLong("id"), rs.getString("email_aes256"), rs.getString("password_hash"))));
    }

    public long insertFullScanUser(String userTable, String emailAes256, String passwordHash) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO " + userTable + " (email_aes256, password_hash) VALUES (?, ?) RETURNING id",
                Long.class, emailAes256, passwordHash);
    }

    public void deleteFullScanUser(String userTable, long id) {
        jdbcTemplate.update("DELETE FROM " + userTable + " WHERE id = ?", id);
    }

    // ── 구성 B/C/D (+HMAC): MAP_TABLE ───────────────────────────────

    public Optional<MapShapeUserRow> findMapShapeUserByHash(String userTable, String emailHash) {
        String sql = "SELECT id, email_hash, password_hash FROM " + userTable + " WHERE email_hash = ?";
        return queryOne(sql, emailHash, (rs, rowNum) -> new MapShapeUserRow(
                rs.getLong("id"), rs.getString("email_hash"), rs.getString("password_hash")));
    }

    public Optional<MapRow> findMapRowByHash(String mapTable, String emailHash) {
        String sql = "SELECT id, email_hash, email_aes256 FROM " + mapTable + " WHERE email_hash = ?";
        return queryOne(sql, emailHash, (rs, rowNum) -> new MapRow(
                rs.getLong("id"), rs.getString("email_hash"), rs.getString("email_aes256")));
    }

    public boolean existsMapShapeUserByHash(String userTable, String emailHash) {
        String sql = "SELECT EXISTS(SELECT 1 FROM " + userTable + " WHERE email_hash = ?)";
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, emailHash));
    }

    private long insertMapShapeUser(String userTable, String emailHash, String passwordHash) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO " + userTable + " (email_hash, password_hash) VALUES (?, ?) RETURNING id",
                Long.class, emailHash, passwordHash);
    }

    private void insertMapRow(String mapTable, String emailHash, String emailAes256) {
        jdbcTemplate.update(
                "INSERT INTO " + mapTable + " (email_hash, email_aes256) VALUES (?, ?)",
                emailHash, emailAes256);
    }

    private void deleteMapShapeUser(String userTable, long id) {
        jdbcTemplate.update("DELETE FROM " + userTable + " WHERE id = ?", id);
    }

    private void deleteMapRowByHash(String mapTable, String emailHash) {
        jdbcTemplate.update("DELETE FROM " + mapTable + " WHERE email_hash = ?", emailHash);
    }

    /**
     * user+map 두 테이블에 각각 insert하는 것을 하나의 트랜잭션으로 묶는다 (계획서 7장).
     * {@code @Transactional}은 Spring이 관리하는 빈(이 리포지토리)에만 걸어야 실제로 동작한다 —
     * 핸들러(FullScanConfigHandler 등)는 직접 {@code new}로 만든 평범한 객체라 AOP 프록시를
     * 거치지 않으므로, 트랜잭션 경계는 반드시 여기(리포지토리 빈) 쪽에 있어야 한다.
     */
    @Transactional
    public long insertMapShapeRowPair(String userTable, String mapTable, String emailHash, String passwordHash,
            String emailAes256) {
        long userId = insertMapShapeUser(userTable, emailHash, passwordHash);
        insertMapRow(mapTable, emailHash, emailAes256);
        return userId;
    }

    /** 탈퇴 시 user·map 행을 함께 삭제 (계획서 11장 "삭제 정합성") — 같은 이유로 리포지토리에 둔다. */
    @Transactional
    public void deleteMapShapeRowPair(String userTable, String mapTable, long userId, String emailHash) {
        deleteMapShapeUser(userTable, userId);
        deleteMapRowByHash(mapTable, emailHash);
    }

    // ── 구성 E/F (+HMAC): INLINE ─────────────────────────────────────

    public Optional<InlineUserRow> findInlineUserByHash(String userTable, String emailHash) {
        String sql = "SELECT id, email_hash, email_aes256, password_hash FROM " + userTable
                + " WHERE email_hash = ?";
        return queryOne(sql, emailHash, (rs, rowNum) -> new InlineUserRow(
                rs.getLong("id"), rs.getString("email_hash"),
                rs.getString("email_aes256"), rs.getString("password_hash")));
    }

    public boolean existsInlineUserByHash(String userTable, String emailHash) {
        String sql = "SELECT EXISTS(SELECT 1 FROM " + userTable + " WHERE email_hash = ?)";
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class, emailHash));
    }

    public long insertInlineUser(String userTable, String emailHash, String emailAes256, String passwordHash) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO " + userTable + " (email_hash, email_aes256, password_hash) VALUES (?, ?, ?) "
                        + "RETURNING id",
                Long.class, emailHash, emailAes256, passwordHash);
    }

    public void deleteInlineUser(String userTable, long id) {
        jdbcTemplate.update("DELETE FROM " + userTable + " WHERE id = ?", id);
    }

    // ── 공통: 운영/측정 보조 (AdminController) ──────────────────────

    public long countRows(String table) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    public long tableTotalSizeBytes(String table) {
        Long size = jdbcTemplate.queryForObject(
                "SELECT pg_total_relation_size(?::regclass)", Long.class, table);
        return size == null ? 0 : size;
    }

    public List<String> explainAnalyzeBuffers(String sql, Object... args) {
        return jdbcTemplate.queryForList(
                "EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT) " + sql, String.class, args);
    }

    private <T> Optional<T> queryOne(String sql, String param, org.springframework.jdbc.core.RowMapper<T> mapper) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, mapper, param));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
