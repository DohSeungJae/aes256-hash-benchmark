package com.doh.ahbench.web;

import com.doh.ahbench.common.ConfigId;
import com.doh.ahbench.common.ConfigRegistry;
import com.doh.ahbench.common.ConfigSpec;
import com.doh.ahbench.crypto.HashServiceFactory;
import com.doh.ahbench.repo.BenchRowRepository;
import com.doh.ahbench.security.PasswordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 계획서 9장 "저장"(테이블·인덱스 크기), "DB 내부"(EXPLAIN) 지표를 수집하는 보조 API.
 * k6 부하 테스트 대상이 아니라, 결과 정리 단계(계획서 10장 5단계)에서 사람이/스크립트가
 * 호출해 수치를 뽑아내는 용도다.
 */
@RestController
public class AdminController {

    private final BenchRowRepository repo;
    private final HashServiceFactory hashServiceFactory;
    private final PasswordService passwordService;

    public AdminController(
            BenchRowRepository repo, HashServiceFactory hashServiceFactory, PasswordService passwordService) {
        this.repo = repo;
        this.hashServiceFactory = hashServiceFactory;
        this.passwordService = passwordService;
    }

    /**
     * 적재 스크립트가 쓰는 보조 엔드포인트: 모든 시드 행이 공유하는 더미 비밀번호의 bcrypt
     * 해시를 앱과 똑같은 cost factor로 뽑아온다 (scripts/load-data.sh 참고).
     */
    @GetMapping("/api/admin/bcrypt")
    public Map<String, String> bcrypt(@RequestParam String raw) {
        return Map.of("hash", passwordService.hash(raw));
    }

    @GetMapping("/api/admin/configs")
    public List<ConfigSpec> configs() {
        return ConfigRegistry.ALL;
    }

    @GetMapping("/api/admin/{config}/size")
    public Map<String, Object> size(@PathVariable String config) {
        ConfigSpec spec = ConfigRegistry.get(ConfigId.fromKey(config));
        long userBytes = repo.tableTotalSizeBytes(spec.userTable());
        long userRows = repo.countRows(spec.userTable());
        if (spec.usesMap()) {
            long mapBytes = repo.tableTotalSizeBytes(spec.mapTable());
            long mapRows = repo.countRows(spec.mapTable());
            return Map.of(
                    "config", config,
                    "userTable", Map.of("name", spec.userTable(), "bytes", userBytes, "rows", userRows),
                    "mapTable", Map.of("name", spec.mapTable(), "bytes", mapBytes, "rows", mapRows),
                    "totalBytes", userBytes + mapBytes);
        }
        return Map.of(
                "config", config,
                "userTable", Map.of("name", spec.userTable(), "bytes", userBytes, "rows", userRows),
                "totalBytes", userBytes);
    }

    /**
     * 실제 로그인 조회에 쓰는 쿼리의 실행 계획을 그대로 뽑는다 (계획서 7장: 구성 간 비교 시
     * EXPLAIN (ANALYZE, BUFFERS) 함께 저장). email 파라미터는 해시로 변환해서만 쓰인다.
     */
    @GetMapping("/api/admin/{config}/explain")
    public Map<String, Object> explain(@PathVariable String config, @RequestParam String email) {
        ConfigSpec spec = ConfigRegistry.get(ConfigId.fromKey(config));
        String hash = spec.hashAlgo() == com.doh.ahbench.crypto.HashAlgo.NONE
                ? null
                : hashServiceFactory.get(spec.hashAlgo()).hash(email);

        String sql = switch (spec.shape()) {
            case FULL_SCAN -> "SELECT id, email_aes256, password_hash FROM " + spec.userTable();
            case MAP_TABLE -> "SELECT id, email_hash, password_hash FROM " + spec.userTable()
                    + " WHERE email_hash = ?";
            case INLINE -> "SELECT id, email_hash, email_aes256, password_hash FROM " + spec.userTable()
                    + " WHERE email_hash = ?";
        };

        List<String> plan = (spec.shape() == com.doh.ahbench.common.ConfigShape.FULL_SCAN)
                ? repo.explainAnalyzeBuffers(sql)
                : repo.explainAnalyzeBuffers(sql, hash);

        return Map.of("config", config, "sql", sql, "plan", plan);
    }
}
