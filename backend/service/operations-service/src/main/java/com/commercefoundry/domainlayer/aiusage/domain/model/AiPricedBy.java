package com.commercefoundry.domainlayer.aiusage.domain.model;

/** 비용을 어느 단가로 계산했는지. */
public enum AiPricedBy {

    /** 설정에 그 모델의 단가가 있다. */
    CONFIG,
    /** 설정에 없는 모델이라 설정 중 항목별 최고 단가로 계산했다 — 실제보다 크게 잡힌다. 설정에 단가를 더해야 한다. */
    MAX_FALLBACK
}
