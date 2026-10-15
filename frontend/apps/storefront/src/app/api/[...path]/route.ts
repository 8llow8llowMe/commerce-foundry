import { createProxyHandler } from '@cf/api-client/server';
import { authCookies } from '@/lib/auth-cookies';
import { gatewayUrl } from '@/lib/env';

const handler = createProxyHandler({ gatewayUrl, cookies: authCookies });

export const dynamic = 'force-dynamic';
export { handler as DELETE, handler as GET, handler as PATCH, handler as POST, handler as PUT };
