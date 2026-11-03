import { deviceIdOf, fail, idOf, requireDevice, requireMemberDevice, requirePair, type Env } from './common.ts';

type Role = 'owner' | 'joiner';
type Attachment = { uid: string; deviceId: string; pairId: string; role: Role; second: number; count: number; protocol: 'typing-v1' };

export async function authorizeTyping(env: Pick<Env, 'DB'>, uid: string, deviceId: string, pairId: string): Promise<Role> {
  await requireDevice(env.DB, uid, deviceId);
  const pair = await requirePair(env.DB, uid, pairId, true);
  requireMemberDevice(pair, uid, deviceId);
  return pair.owner_uid === uid ? 'owner' : 'joiner';
}

/** No text, edits, or history may cross this route. */
export function validateTypingFrame(raw: string): { type: 'typing'; active: boolean } {
  if (new TextEncoder().encode(raw).length > 32) fail(400, 'invalid-argument', 'Invalid typing status.');
  let value: unknown;
  try { value = JSON.parse(raw); } catch { fail(400, 'invalid-argument', 'Invalid typing status.'); }
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      Object.keys(value).sort().join(',') !== 'active,type' ||
      (value as Record<string, unknown>).type !== 'typing' ||
      typeof (value as Record<string, unknown>).active !== 'boolean') {
    fail(400, 'invalid-argument', 'Invalid typing status.');
  }
  return value as { type: 'typing'; active: boolean };
}

/** Historical class name keeps the existing Durable Object binding intact. */
export class LiveDraftRoom {
  private readonly state: DurableObjectState;
  private readonly env: Env;
  constructor(state: DurableObjectState, env: Env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === '/revoke' && request.method === 'POST') {
      for (const socket of this.state.getWebSockets()) socket.close(1008, 'Pair ended');
      return new Response('closed');
    }
    if (url.pathname !== '/connect' || request.headers.get('upgrade')?.toLowerCase() !== 'websocket') {
      return new Response('Not found', { status: 404 });
    }
    const uid = request.headers.get('x-verified-uid') ?? '';
    const deviceId = request.headers.get('x-verified-device') ?? '';
    const pairId = request.headers.get('x-verified-pair') ?? '';
    const role = request.headers.get('x-verified-role');
    if (role !== 'owner' && role !== 'joiner') return new Response('Forbidden', { status: 403 });
    try {
      if (await authorizeTyping(this.env, uid, deviceId, pairId) !== role) return new Response('Forbidden', { status: 403 });
    } catch { return new Response('Forbidden', { status: 403 }); }
    for (const old of this.state.getWebSockets()) {
      const attachment = old.deserializeAttachment() as Attachment | null;
      if (!attachment || attachment.protocol !== 'typing-v1' || attachment.role === role) old.close(1000, 'Reconnected');
    }
    const pair = new WebSocketPair();
    const server = pair[1];
    server.serializeAttachment({ uid, deviceId, pairId, role, second: 0, count: 0, protocol: 'typing-v1' } satisfies Attachment);
    this.state.acceptWebSocket(server);
    server.send(JSON.stringify({ type: 'ready' }));
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  async webSocketMessage(socket: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const attachment = socket.deserializeAttachment() as Attachment | null;
    if (!attachment || attachment.protocol !== 'typing-v1' || typeof message !== 'string') {
      socket.close(1008, 'Invalid typing status'); return;
    }
    try {
      const second = Math.floor(Date.now() / 1000);
      attachment.count = attachment.second === second ? attachment.count + 1 : 1;
      attachment.second = second;
      if (attachment.count > 6) fail(429, 'resource-exhausted', 'Too many typing updates.');
      socket.serializeAttachment(attachment);
      await authorizeTyping(this.env, attachment.uid, attachment.deviceId, attachment.pairId);
      const frame = validateTypingFrame(message);
      this.notifyPeer(attachment.role, JSON.stringify(frame));
    } catch { socket.close(1008, 'Typing access ended'); }
  }

  webSocketClose(socket: WebSocket): void { this.offline(socket); }
  webSocketError(socket: WebSocket): void { this.offline(socket); }

  private offline(socket: WebSocket): void {
    const attachment = socket.deserializeAttachment() as Attachment | null;
    if (attachment?.protocol === 'typing-v1' && !this.state.getWebSockets().some(other =>
      other !== socket && (other.deserializeAttachment() as Attachment | null)?.protocol === 'typing-v1' &&
      (other.deserializeAttachment() as Attachment | null)?.role === attachment.role)) {
      this.notifyPeer(attachment.role, JSON.stringify({ type: 'typing', active: false }));
    }
  }

  private notifyPeer(role: Role, frame: string): void {
    for (const peer of this.state.getWebSockets()) {
      const attachment = peer.deserializeAttachment() as Attachment | null;
      if (attachment?.protocol === 'typing-v1' && attachment.role !== role) {
        try { peer.send(frame); } catch { /* The phone will reconnect. */ }
      }
    }
  }
}

export async function closeTypingRoom(env: Env, pairId: string): Promise<void> {
  if (!env.LIVE_DRAFT) return;
  const stub = env.LIVE_DRAFT.get(env.LIVE_DRAFT.idFromName(pairId));
  await stub.fetch(new Request('https://typing.internal/revoke', { method: 'POST' }));
}

export async function connectTyping(request: Request, env: Env, uid: string): Promise<Response> {
  if (request.headers.get('upgrade')?.toLowerCase() !== 'websocket') {
    fail(426, 'failed-precondition', 'A WebSocket upgrade is required.');
  }
  if (!env.LIVE_DRAFT) fail(503, 'unavailable', 'Typing status is unavailable.');
  const url = new URL(request.url);
  const deviceId = deviceIdOf({ deviceId: url.searchParams.get('deviceId') });
  const pairId = idOf({ conversationId: url.searchParams.get('conversationId') }, 'conversationId');
  const role = await authorizeTyping(env, uid, deviceId, pairId);
  const stub = env.LIVE_DRAFT.get(env.LIVE_DRAFT.idFromName(pairId));
  return stub.fetch(new Request('https://typing.internal/connect', {
    headers: { Upgrade: 'websocket', 'x-verified-uid': uid, 'x-verified-device': deviceId,
      'x-verified-pair': pairId, 'x-verified-role': role }
  }));
}
