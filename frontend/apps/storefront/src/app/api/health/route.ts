import { healthResponse } from '@cf/api-client/server';
import { gatewayUrl } from '@/lib/env';

export const dynamic = 'force-dynamic';

export function GET(): Promise<Response> {
  return healthResponse(gatewayUrl);
}
