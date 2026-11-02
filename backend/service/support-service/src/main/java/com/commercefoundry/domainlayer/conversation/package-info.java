/**
 * 상담 대화 컨텍스트 (#470). 대화 · 메시지 · 전이 이력 · 비회원 세션이 들어온다 — 엔티티는 #470-B1, REST 는 #470-B2, WebSocket 은 #470-C.
 *
 * <p>설계 정본은 이슈 #470 "설계 확정 (2026-11-02)" 댓글이다. 자원은 {@code conversations} 하나가 정본이다(문의 · 1회성 질문을 따로 두지 않는다).
 */
package com.commercefoundry.domainlayer.conversation;
