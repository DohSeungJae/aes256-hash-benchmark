package com.doh.ahbench.generator;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bench.generator")
public class GeneratorProperties {

    /** 전체 생성 건수. 계획서 6장: 1000만 건을 한 번만 생성해 seq를 붙인다. */
    private long totalRows = 10_000_000;

    /** 몇 건마다 CSV 파일을 나눠 저장할지. 계획서 6장: 100만 건씩 10개 파일. */
    private long chunkSize = 1_000_000;

    private int threadCount = 8;

    private String outputDir = "./data";

    private String emailDomain = "bench.example.com";

    /** 중간 실패 후 이어서 생성할 때 쓰는 시작 seq (기본 1부터). */
    private long startSeq = 1;

    public long getTotalRows() {
        return totalRows;
    }

    public void setTotalRows(long totalRows) {
        this.totalRows = totalRows;
    }

    public long getChunkSize() {
        return chunkSize;
    }

    public void setChunkSize(long chunkSize) {
        this.chunkSize = chunkSize;
    }

    public int getThreadCount() {
        return threadCount;
    }

    public void setThreadCount(int threadCount) {
        this.threadCount = threadCount;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public String getEmailDomain() {
        return emailDomain;
    }

    public void setEmailDomain(String emailDomain) {
        this.emailDomain = emailDomain;
    }

    public long getStartSeq() {
        return startSeq;
    }

    public void setStartSeq(long startSeq) {
        this.startSeq = startSeq;
    }
}
