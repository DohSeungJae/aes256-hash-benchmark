package com.doh.ahbench.bench;

import com.doh.ahbench.common.ConfigId;
import com.doh.ahbench.common.ConfigRegistry;
import com.doh.ahbench.common.ConfigSpec;
import com.doh.ahbench.crypto.Aes256CipherService;
import com.doh.ahbench.crypto.HashServiceFactory;
import com.doh.ahbench.repo.BenchRowRepository;
import com.doh.ahbench.security.PasswordService;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * ConfigRegistry.ALL(10개 구성)을 순회하며 ConfigShape에 맞는 핸들러를 하나씩 만들어
 * 등록한다. 컨트롤러는 이 레지스트리에서 config 경로 변수로 핸들러를 찾아 쓴다.
 */
@Component
public class BenchmarkHandlerRegistry {

    private final Map<ConfigId, BenchmarkOperations> handlers = new EnumMap<>(ConfigId.class);

    public BenchmarkHandlerRegistry(
            BenchRowRepository repo,
            Aes256CipherService cipher,
            HashServiceFactory hashServiceFactory,
            PasswordService passwordService,
            DecryptTimingLogger timingLogger) {

        for (ConfigSpec spec : ConfigRegistry.ALL) {
            BenchmarkOperations handler = switch (spec.shape()) {
                case FULL_SCAN -> new FullScanConfigHandler(
                        spec.id(), spec.userTable(), repo, cipher, passwordService, timingLogger);

                case MAP_TABLE -> new MapTableConfigHandler(
                        spec.id(), spec.userTable(), spec.mapTable(), repo, cipher,
                        hashServiceFactory.get(spec.hashAlgo()), passwordService, timingLogger);

                case INLINE -> new InlineConfigHandler(
                        spec.id(), spec.userTable(), repo, cipher,
                        hashServiceFactory.get(spec.hashAlgo()), passwordService, timingLogger);
            };
            handlers.put(spec.id(), handler);
        }
    }

    public BenchmarkOperations get(ConfigId id) {
        BenchmarkOperations handler = handlers.get(id);
        if (handler == null) {
            throw new IllegalArgumentException("핸들러가 없는 구성: " + id);
        }
        return handler;
    }
}
