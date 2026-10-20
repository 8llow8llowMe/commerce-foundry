import { createProxyHandler } from '@cf/api-client/server';
import { bffOptions } from '@/lib/bff-options';

const handler = createProxyHandler(bffOptions);

export const dynamic = 'force-dynamic';
export { handler as DELETE, handler as GET, handler as PATCH, handler as POST, handler as PUT };
