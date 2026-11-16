package com.doh.ahbench.crypto;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** HashAlgo로 적절한 HashService 구현을 찾아주는 레지스트리. */
@Component
public class HashServiceFactory {

    private final Map<HashAlgo, HashService> services = new EnumMap<>(HashAlgo.class);

    public HashServiceFactory(List<HashService> implementations) {
        for (HashService impl : implementations) {
            services.put(impl.algo(), impl);
        }
    }

    /** NONE에 대해서는 서비스가 없다 (구성 A는 해시를 쓰지 않음) — 호출하면 예외. */
    public HashService get(HashAlgo algo) {
        HashService service = services.get(algo);
        if (service == null) {
            throw new IllegalArgumentException("HashService가 없는 알고리즘: " + algo);
        }
        return service;
    }
}
