package com.commercefoundry.domainlayer.product.application.service.processor;

import com.commercefoundry.domainlayer.product.application.exception.PriceMigrationPreflightException;
import com.commercefoundry.domainlayer.product.application.exception.PriceMigrationPreflightException.Reason;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationPreflightModel.Report;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Criteria;
import com.commercefoundry.domainlayer.product.application.model.PriceMigrationReadModel.Lane;
import com.commercefoundry.domainlayer.product.application.port.out.PriceMigrationReadPort;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Bounds;
import com.commercefoundry.domainlayer.product.application.port.out.query.PriceMigrationReadQueryResult.Row;
import java.util.EnumMap;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * B1c-B 미연결 사전검증 실행기. 상한을 한 번 캡처하고 네 lane을 PRODUCT → SKU → PRODUCT_PRICE → SKU_PRICE 순서로
 * keyset 순회한 뒤 메모리 인덱스로 교차 판정한다. 루프 안 단건 조회는 없다.
 *
 * <p>한계: 상한과 각 페이지는 서로 다른 snapshot이다. 동시 편집 중에는 일시적 고아·drift·누락이 관측될 수 있고,
 * 상한 이후 삽입은 보지 않는다. 결과는 "그 실행에서 관측한 것"이며 이행 시점의 무결성을 보장하지 않는다.
 * 읽기 실패는 고정 사유 그대로 전파하고 재시도하지 않는다. 상한 초과·Port 계약 위반이면 부분 결과 없이 중단한다.
 * 인덱스는 서비스 힙을 같이 쓴다. 상한 근처에서는 대략 수백 MB가 될 수 있다.
 */
@Component
@RequiredArgsConstructor
public class PriceMigrationPreflightProcessor {

    static final int DEFAULT_PAGE_SIZE = 500;
    /** 네 lane 합계 스캔 행 상한. 메모리 인덱스 크기를 묶는다. */
    static final long MAX_SCANNED_ROWS = 2_000_000L;

    private final PriceMigrationReadPort priceMigrationReadPort;

    public Report run() {
        return run(DEFAULT_PAGE_SIZE, MAX_SCANNED_ROWS);
    }

    /** 테스트에서 페이지 경계·상한을 작게 두기 위한 진입이다. pageSize 범위 검사는 {@link Criteria}가 한다. */
    Report run(int pageSize, long maxScannedRows) {
        Bounds bounds = priceMigrationReadPort.captureBounds();
        if (bounds == null) {
            throw new PriceMigrationPreflightException(Reason.PORT_CONTRACT_VIOLATION);
        }
        var classifier = new PriceMigrationPreflightClassifier();
        var scanned = new EnumMap<Lane, Long>(Lane.class);
        long total = 0;
        for (Lane lane : Lane.values()) {
            Long upper = bounds.upper(lane);
            Long after = null;
            long laneCount = 0;
            while (upper != null) {
                List<Row> rows = priceMigrationReadPort.readPage(new Criteria(lane, upper, after, pageSize));
                if (rows == null || rows.size() > pageSize) {
                    throw new PriceMigrationPreflightException(Reason.PORT_CONTRACT_VIOLATION);
                }
                if (rows.isEmpty()) {
                    break;
                }
                total += rows.size();
                if (total > maxScannedRows) {
                    throw new PriceMigrationPreflightException(Reason.SCAN_LIMIT_EXCEEDED);
                }
                for (Row row : rows) {
                    requireKeysetOrder(lane, row, upper, after);
                    after = row.id();
                    classifier.accept(row);
                }
                laneCount += rows.size();
            }
            scanned.put(lane, laneCount);
        }
        return classifier.finish(scanned);
    }

    /** 다음 cursor가 항상 증가해야 순회가 끝난다. 계약을 어긴 페이지는 중복 집계·무한 순회 대신 중단한다. */
    private static void requireKeysetOrder(Lane lane, Row row, Long upper, Long after) {
        if (row == null || row.id() == null || PriceMigrationPreflightClassifier.laneOf(row) != lane
            || row.id() > upper || after != null && row.id() <= after) {
            throw new PriceMigrationPreflightException(Reason.PORT_CONTRACT_VIOLATION);
        }
    }
}
