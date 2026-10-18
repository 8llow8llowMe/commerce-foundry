package com.commercefoundry.security.support.archfixture.compliant.adapter.in.web;

/** 매핑 메서드가 없는 인터페이스. 컨트롤러가 구현해도 R7 에 걸리지 않는다 — 막는 것은 매핑을 물려주는 상위 타입뿐이다. */
public interface Describable {

    String describe();
}
