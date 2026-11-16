package com.doh.ahbench.repo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

@Configuration
public class JdbcConfig {

    /**
     * 구성 A의 전체 스캔 전용 JdbcTemplate. fetchSize를 지정해 PostgreSQL 드라이버가
     * (readOnly 트랜잭션 안에서) 서버 커서로 결과를 스트리밍하게 한다 — 1000만 건을
     * 한 번에 애플리케이션 메모리로 올리지 않기 위함 (계획서 4장 "A는 큰 규모에서 ... 타임아웃
     * 지점만 기록" — 그 타임아웃이 쿼리 자체가 아니라 OOM 때문에 나지 않도록 하는 장치).
     */
    @Bean
    public JdbcTemplate streamingJdbcTemplate(
            DataSource dataSource,
            @Value("${bench.fullscan.fetch-size:1000}") int fetchSize) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setFetchSize(fetchSize);
        return template;
    }
}
