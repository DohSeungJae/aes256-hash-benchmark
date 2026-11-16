package com.doh.ahbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 암호화된 회원정보 조회 성능 검증 계획서의 구현체.
 *
 * 하나의 서버 안에 구성 A~F(+HMAC 변형)를 모두 별도의 테이블/도메인으로 올려
 * 같은 프로세스·같은 JVM·같은 커넥션 풀 조건에서 동시에 측정할 수 있게 한다.
 *
 * 실행 모드:
 *  - 기본(default) 프로필: 벤치마크 대상 REST API 서버
 *  - generate 프로필: CSV 데이터 생성기 (CommandLineRunner), 서버는 띄우지 않음
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AhBenchApplication {
    public static void main(String[] args) {
        SpringApplication.run(AhBenchApplication.class, args);
    }
}
