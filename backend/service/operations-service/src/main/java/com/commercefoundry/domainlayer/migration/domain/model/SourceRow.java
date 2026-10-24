package com.commercefoundry.domainlayer.migration.domain.model;

import java.util.Map;

/**
 * 원본 내보내기 파일의 데이터 행 하나 — 머리글(컬럼명) → 셀 문자열 그대로. 값은 개인정보일 수 있어 {@link #toString()} 이 셀을 내지 않는다.
 *
 * @param rowNumber 데이터 행 번호(머리글 다음 행이 1)
 */
public record SourceRow(int rowNumber, Map<String, String> cells) {

    public SourceRow {
        cells = Map.copyOf(cells);
    }

    /** 셀 원문. 컬럼이 없거나 그 행에 칸이 모자라면 null 이다. 컬럼명이 null 이면(설정하지 않은 선택 컬럼) null 이다. */
    public String cell(String column) {
        return column == null ? null : cells.get(column);
    }

    @Override
    public String toString() {
        return "SourceRow[rowNumber=" + rowNumber + ", columns=" + cells.size() + "]";
    }
}
