package com.commercefoundry.domainlayer.legaldraftgeneration.domain.model;

import java.util.List;

/** 모델이 고를 수 있는 값은 템플릿에 포함된 절의 순서뿐이다. */
public record LegalDraftPlan(List<String> sectionOrder) {}
