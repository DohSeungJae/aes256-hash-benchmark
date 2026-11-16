package com.doh.ahbench.crypto;

/** 평문을 조회 키(blind index)용 해시로 변환하는 전략. */
public interface HashService {
    String hash(String plaintext);

    HashAlgo algo();
}
