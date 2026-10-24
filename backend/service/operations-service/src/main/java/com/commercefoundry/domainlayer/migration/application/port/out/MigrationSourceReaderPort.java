package com.commercefoundry.domainlayer.migration.application.port.out;

import com.commercefoundry.domainlayer.migration.domain.model.SourceRow;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.List;

/** 외부 쇼핑몰 내보내기 파일을 원본 행으로 읽는다 ({@code migration-guide.md} §2). 값은 문자열 그대로 — 정규화는 domain 이 한다. */
public interface MigrationSourceReaderPort {

    /**
     * 첫 행을 머리글로 읽고, 나머지를 데이터 행(번호 1부터)으로 돌려준다. 빈 행은 건너뛴다. UTF-8 BOM 은 버린다.
     *
     * @param charset 원본 인코딩 — 카페24 내보내기는 CP949 일 수 있다(D3)
     * @param requiredColumns 머리글에 반드시 있어야 할 컬럼
     * @throws com.commercefoundry.domainlayer.migration.application.exception.MigrationException {@code SOURCE_UNREADABLE} · {@code SOURCE_COLUMN_MISSING}
     */
    List<SourceRow> read(InputStream input, Charset charset, Collection<String> requiredColumns);
}
