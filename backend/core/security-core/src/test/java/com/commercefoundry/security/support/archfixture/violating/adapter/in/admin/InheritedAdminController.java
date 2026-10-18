package com.commercefoundry.security.support.archfixture.violating.adapter.in.admin;

import org.springframework.web.bind.annotation.RestController;

/** 위반 예시 — 상위 클래스에서 permitAll() 어드민 핸들러를 물려받는다. 직접 선언된 핸들러가 없어 R2 가 보지 못하므로 R7 이 막는다. */
@RestController
public class InheritedAdminController extends AbstractAdminController {
}
