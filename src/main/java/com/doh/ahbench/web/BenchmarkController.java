package com.doh.ahbench.web;

import com.doh.ahbench.bench.BenchmarkHandlerRegistry;
import com.doh.ahbench.bench.BenchmarkOperations;
import com.doh.ahbench.bench.LoginResult;
import com.doh.ahbench.common.ConfigId;
import com.doh.ahbench.web.Dtos.LoginRequest;
import com.doh.ahbench.web.Dtos.LoginResponse;
import com.doh.ahbench.web.Dtos.SignupRequest;
import com.doh.ahbench.web.Dtos.SignupResponse;
import com.doh.ahbench.web.Dtos.WithdrawRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * k6 시나리오 S1~S5(계획서 8장)가 호출하는 엔드포인트.
 * {config} 경로 변수로 10가지 구성 중 하나를 고른다 — 예: /api/bench/c/login, /api/bench/e_hmac/signup
 *
 * 이렇게 하면 같은 서버·같은 프로세스 안에서 모든 구성을 동시에 올려두고, k6 시나리오가
 * BASE_URL + config 조합만 바꿔가며 그대로 재사용할 수 있다 (사용자 요청: "분리된 도메인/테이블로
 * 모든 것을 동시에 테스트").
 */
@RestController
@RequestMapping("/api/bench/{config}")
public class BenchmarkController {

    private final BenchmarkHandlerRegistry registry;

    public BenchmarkController(BenchmarkHandlerRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@PathVariable String config, @Valid @RequestBody LoginRequest req) {
        BenchmarkOperations ops = handlerFor(config);
        LoginResult result = ops.login(req.email(), req.password());
        LoginResponse body = new LoginResponse(result.success(), result.userId(), result.decryptedEmail(),
                result.decryptNanos());
        return result.success() ? ResponseEntity.ok(body) : ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@PathVariable String config, @Valid @RequestBody SignupRequest req) {
        BenchmarkOperations ops = handlerFor(config);
        long userId = ops.signup(req.email(), req.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(new SignupResponse(userId));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<Void> withdraw(@PathVariable String config, @Valid @RequestBody WithdrawRequest req) {
        BenchmarkOperations ops = handlerFor(config);
        ops.withdraw(req.email(), req.password());
        return ResponseEntity.noContent().build();
    }

    private BenchmarkOperations handlerFor(String config) {
        return registry.get(ConfigId.fromKey(config));
    }
}
