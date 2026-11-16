package com.doh.ahbench.common;

import com.doh.ahbench.crypto.HashAlgo;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.doh.ahbench.common.ConfigShape.*;
import static com.doh.ahbench.crypto.HashAlgo.*;

/**
 * 계획서 4장 "비교 구성" 표 + 11장 HMAC 변형을 코드로 옮긴 표.
 * 테이블 이름은 계획서 7장 "테이블 개요(예시 이름)"를 그대로 따른다.
 */
public final class ConfigRegistry {

    public static final List<ConfigSpec> ALL = List.of(
            new ConfigSpec(ConfigId.A, FULL_SCAN, "users_a", null, NONE,
                    "기준선: user 테이블에 AES256 암호문만 저장, 전체 조회 후 행마다 복호화·비교"),

            new ConfigSpec(ConfigId.B, MAP_TABLE, "users_b", "map_b", SHA256,
                    "user+map, 해시 인덱스 없음 (순차 탐색) — 인덱스 효과 측정용 기준"),

            new ConfigSpec(ConfigId.C, MAP_TABLE, "users_c", "map_c", SHA256,
                    "user+map, B-tree 인덱스 — 블로그가 제안한 구조"),

            new ConfigSpec(ConfigId.D, MAP_TABLE, "users_d", "map_d", SHA256,
                    "user+map, hash 인덱스 — H5: B-tree와 비교"),

            new ConfigSpec(ConfigId.E, INLINE, "users_e", null, SHA256,
                    "map 테이블 없이 user 테이블에 email_hash(B-tree)+email_aes256 — H6"),

            new ConfigSpec(ConfigId.F, INLINE, "users_f", null, SHA256,
                    "E 구조 + hash 인덱스 — E에서의 hash 인덱스 효과"),

            new ConfigSpec(ConfigId.C_HMAC, MAP_TABLE, "users_c_hmac", "map_c_hmac", HMAC_SHA256,
                    "C와 동일 구조, 조회 키를 HMAC-SHA256으로 교체 (계획서 11장 보안 권고)"),

            new ConfigSpec(ConfigId.D_HMAC, MAP_TABLE, "users_d_hmac", "map_d_hmac", HMAC_SHA256,
                    "D와 동일 구조, 조회 키를 HMAC-SHA256으로 교체"),

            new ConfigSpec(ConfigId.E_HMAC, INLINE, "users_e_hmac", null, HMAC_SHA256,
                    "E와 동일 구조, 조회 키를 HMAC-SHA256으로 교체"),

            new ConfigSpec(ConfigId.F_HMAC, INLINE, "users_f_hmac", null, HMAC_SHA256,
                    "F와 동일 구조, 조회 키를 HMAC-SHA256으로 교체")
    );

    private static final Map<ConfigId, ConfigSpec> BY_ID =
            ALL.stream().collect(Collectors.toMap(ConfigSpec::id, s -> s));

    public static ConfigSpec get(ConfigId id) {
        ConfigSpec spec = BY_ID.get(id);
        if (spec == null) {
            throw new IllegalArgumentException("등록되지 않은 구성: " + id);
        }
        return spec;
    }

    private ConfigRegistry() {
    }
}
