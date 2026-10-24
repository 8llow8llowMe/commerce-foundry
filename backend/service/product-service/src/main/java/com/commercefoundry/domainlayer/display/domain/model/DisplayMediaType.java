package com.commercefoundry.domainlayer.display.domain.model;

/**
 * 미디어 종류 — 화면이 렌더 분기({@code <img>} · {@code <video>})에만 쓰는 제어 코드라 응답에 문자열로 내린다 ({@code coding-conventions.md} §11 예외).
 */
public enum DisplayMediaType {

    IMAGE,

    /** 영상. 재생 전 · 자동 재생이 막힌 환경에서 보일 포스터 이미지가 필요하다. */
    VIDEO
}
