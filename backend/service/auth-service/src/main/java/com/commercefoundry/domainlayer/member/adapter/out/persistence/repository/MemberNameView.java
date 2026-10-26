package com.commercefoundry.domainlayer.member.adapter.out.persistence.repository;

/** 회원 이름 벌크 조회 투영. 아이디 · 이름만 읽는다 — 비밀번호 해시를 함께 읽지 않는다. */
public interface MemberNameView {

    long getId();

    String getName();
}
