package com.doh.ahbench.bench;

import com.doh.ahbench.common.ConfigId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 계획서 9장 "복호화: 복호화 1회 소요 시간 — 앱 내 타이머 로그" 그대로 구현.
 * {@code bench.timing.log-decrypt=true}일 때만 로그를 남긴다 (기본 false — 대규모 부하
 * 테스트 중 로그 폭주 방지, 계획서 5장 "SSD 여유 공간" 주의사항과도 연결).
 *
 * 로그 포맷은 grep/awk로 바로 집계할 수 있게 key=value로 고정한다:
 *   decrypt_timing config=C operation=login nanos=184213
 */
@Component
public class DecryptTimingLogger {

    private static final Logger log = LoggerFactory.getLogger("decrypt.timing");

    private final boolean enabled;

    public DecryptTimingLogger(@Value("${bench.timing.log-decrypt:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public void record(ConfigId config, String operation, long nanos) {
        if (enabled) {
            log.info("decrypt_timing config={} operation={} nanos={}", config.key(), operation, nanos);
        }
    }
}
