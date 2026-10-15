import { joinUrl } from './url';

export type HealthStatus = 'UP' | 'DOWN';

export type HealthReport = {
  app: HealthStatus;
  gateway: HealthStatus;
};

/** 게이트웨이 actuator 헬스체크. 어떤 실패든 DOWN 이다. */
export async function checkGateway(
  gatewayUrl: string,
  fetchImpl: typeof fetch = fetch,
  timeoutMs = 3000,
): Promise<HealthStatus> {
  try {
    const response = await fetchImpl(joinUrl(gatewayUrl, '/actuator/health'), {
      cache: 'no-store',
      signal: AbortSignal.timeout(timeoutMs),
    });
    if (!response.ok) return 'DOWN';
    const body = (await response.json()) as { status?: unknown };
    return body.status === 'UP' ? 'UP' : 'DOWN';
  } catch {
    return 'DOWN';
  }
}

export async function healthResponse(
  gatewayUrl: string,
  fetchImpl: typeof fetch = fetch,
): Promise<Response> {
  const gateway = await checkGateway(gatewayUrl, fetchImpl);
  const report: HealthReport = { app: 'UP', gateway };
  return Response.json(report, {
    status: gateway === 'UP' ? 200 : 503,
    headers: { 'cache-control': 'no-store' },
  });
}
