import { parseBusinessInfo } from './business-info';

/** 서버 전용. 브라우저는 게이트웨이를 직접 부르지 않는다. */
export const gatewayUrl = process.env.GATEWAY_URL ?? 'http://localhost:8000';

/**
 * 서버 전용. 푸터 사업자 정보(BUSINESS_*). 정적으로 미리 그리는 페이지(not-found 등)는 빌드 시점 값이 들어가므로
 * 배포 환경은 빌드 · 실행 모두에 같은 값을 준다.
 */
export const businessInfo = parseBusinessInfo(process.env);
