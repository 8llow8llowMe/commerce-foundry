package com.commercefoundry.domainlayer.member.domain.model;

/** 비밀번호 등 회원 정보가 바뀌면 버전이 달라져 이전 링크를 거절한다. 토큰 원문과 이메일은 저장하지 않는다. */
public record AdminPasswordResetGrant(long memberId, long memberVersion) { }
