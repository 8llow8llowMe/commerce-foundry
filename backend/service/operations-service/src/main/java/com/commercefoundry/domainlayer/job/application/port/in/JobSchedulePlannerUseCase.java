package com.commercefoundry.domainlayer.job.application.port.in;

/**
 * 정기 작업 등록 (진입점 — 스케줄러).
 */
public interface JobSchedulePlannerUseCase {

    /**
     * 도래한 슬롯의 작업을 등록한다. 다른 인스턴스가 이미 돌고 있거나 스케줄이 없으면 0 이다.
     *
     * @return 이번에 새로 만든 작업 수
     */
    int plan();
}
