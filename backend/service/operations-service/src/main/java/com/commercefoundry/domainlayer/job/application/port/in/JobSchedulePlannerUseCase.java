package com.commercefoundry.domainlayer.job.application.port.in;

/**
 * 정기 작업 등록 (진입점 — 스케줄러). 같은 서비스의 기능 Facade 가 자기 작업을 등록할 때는 이 UseCase 가 아니라 {@code JobRegistrationProcessor} 를 부른다.
 */
public interface JobSchedulePlannerUseCase {

    /**
     * 도래한 슬롯의 작업을 등록한다. 다른 인스턴스가 이미 돌고 있거나 스케줄이 없으면 0 이다.
     *
     * @return 이번에 새로 만든 작업 수
     */
    int plan();
}
