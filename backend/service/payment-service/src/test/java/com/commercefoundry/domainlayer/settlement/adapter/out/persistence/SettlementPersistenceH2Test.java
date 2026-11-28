package com.commercefoundry.domainlayer.settlement.adapter.out.persistence;

import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

/** H2(PostgreSQL 모드, Hibernate create-drop) — Docker 없는 로컬에서도 계약을 돌린다. */
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class SettlementPersistenceH2Test extends SettlementPersistenceContract {
}
