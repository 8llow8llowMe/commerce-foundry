package com.commercefoundry.domainlayer.settlement.adapter.out.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * 키 목록을 {@value #SIZE} 개씩 나눠 IN 절 한 문장씩 읽는다 — 하루 거래가 늘어도 바인드 파라미터 상한(PostgreSQL 32767)을 넘지 않게. 조각마다 한 번이라 건별 조회(N+1)가 아니다.
 */
final class InClauseChunks {

    static final int SIZE = 500;

    private InClauseChunks() {
    }

    static <K, V> List<V> query(Collection<K> keys, Function<List<K>, List<V>> query) {
        List<K> list = List.copyOf(keys);
        List<V> result = new ArrayList<>();
        for (int from = 0; from < list.size(); from += SIZE) {
            result.addAll(query.apply(list.subList(from, Math.min(list.size(), from + SIZE))));
        }
        return result;
    }
}
