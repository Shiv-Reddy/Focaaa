import { base64Of, deviceIdOf, fail, idOf, requireDevice, requireMemberDevice, requirePair, type Data, type Env } from './common.ts';

type MemberRole = 'owner' | 'joiner';
type DraftFrame =
  | { type: 'edit'; sessionId: string; sequence: number; epoch: number; payloadBase64: string }
  | { type: 'ack'; sessionId: string; sequence: number }
  | { type: 'start'; sessionId: string; sequence: 0 }
  | { type: 'resume'; sessionId: string; sequence: number }
  | { type: 'stop'; sessionId: string; sequence: number };

const SESSION_ID = /^[A-Za-z0-9_-]{8,80}$/;
const MAX_FRAME_BYTES = 32_768;
const MAX_EVENTS_PER_SECOND = 30;

/** The edge checks membership; the Durable Object never receives Firebase tokens. */
export async function authorizeLiveDraft(
  env: Pick<Env, 'DB'>, uid: string, deviceId: string, pairId: string
): Promise<MemberRole> {
  await requireDevice(env.DB, uid, deviceId);
  const pair = await requirePair(env.DB, uid, pairId, true);
  requireMemberDevice(pair, uid, deviceId);
  return pair.owner_uid === uid ? 'owner' : 'joiner';
}

export function validateLiveDraftFrame(raw: string): DraftFrame {
  if (new TextEncoder().encode(raw).length > MAX_FRAME_BYTES) {
    fail(413, 'invalid-argument', 'The live draft frame is too large.');
  }
  let value: Data;
  try { value = JSON.parse(raw) as Data; }
  catch { fail(400, 'invalid-argument', 'The live draft frame is invalid.'); }
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      !['edit', 'ack', 'start', 'resume', 'stop'].includes(String(value.type)) ||
      typeof value.sessionId !== 'string' || !SESSION_ID.test(value.sessionId) ||
      !Number.isSafeInteger(value.sequence) || (value.sequence as number) < 0) {
    fail(400, 'invalid-argument', 'The live draft frame is invalid.');
  }
  const fields = Object.keys(value).sort().join(',');
  if (value.type === 'edit') {
    if (fields !== 'epoch,payloadBase64,sequence,sessionId,type' ||
        !Number.isSafeInteger(value.epoch) || (value.epoch as number) < 0 ||
        (value.sequence as number) === 0) {
      fail(400, 'invalid-argument', 'The live draft frame is invalid.');
    }
    base64Of(value, 'payloadBase64', 24_000);
  } else if (fields !== 'sequence,sessionId,type' || (value.type === 'start' && value.sequence !== 0)) {
    fail(400, 'invalid-argument', 'The live draft frame is invalid.');
  }
  return value as DraftFrame;
}

type SocketAttachment = { uid: string; deviceId: string; pairId: string; role: MemberRole; second: number; count: number };

/** One hibernating object per pair; content is already encrypted on the phones. */
export class LiveDraftRoom {
  private readonly state: DurableObjectState;
  private readonly env: Env;
  constructor(state: DurableObjectState, env: Env) {
    this.state = state;
    this.env = env;
  }

  async fetch(request: Request): Promise<Response> {
    if (new URL(request.url).pathname === '/revoke' && request.method === 'POST') {
      for (const socket of this.state.getWebSockets()) socket.close(1008, 'Pair ended');
      return new Response('closed');
    }
    if (new URL(request.url).pathname !== '/connect' || request.headers.get('upgrade')?.toLowerCase() !== 'websocket') {
      return new Response('Not found', { status: 404 });
    }
    const uid = request.headers.get('x-verified-uid') ?? '';
    const deviceId = request.headers.get('x-verified-device') ?? '';
    const pairId = request.headers.get('x-verified-pair') ?? '';
    const role = request.headers.get('x-verified-role');
    if (role !== 'owner' && role !== 'joiner') return new Response('Forbidden', { status: 403 });
    try {
      if (await authorizeLiveDraft(this.env, uid, deviceId, pairId) !== role) {
        return new Response('Forbidden', { status: 403 });
      }
    } catch { return new Response('Forbidden', { status: 403 }); }

    // A reconnect replaces an old socket for the same device, avoiding duplicate delivery.
    for (const old of this.state.getWebSockets()) {
      const attachment = old.deserializeAttachment() as SocketAttachment | null;
      if (attachment?.role === role) old.close(1000, 'Reconnected');
    }
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    server.serializeAttachment({ uid, deviceId, pairId, role, second: 0, count: 0 } satisfies SocketAttachment);
    this.state.acceptWebSocket(server);
    server.send(JSON.stringify({ type: 'ready', peerOnline: this.hasPeer(role) }));
    this.notifyPeer(role, JSON.stringify({ type: 'peer-online' }));
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(socket: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const attachment = socket.deserializeAttachment() as SocketAttachment | null;
    if (!attachment || typeof message !== 'string') { socket.close(1008, 'Invalid frame'); return; }
    try {
      const second = Math.floor(Date.now() / 1_000);
      const count = attachment.second === second ? attachment.count + 1 : 1;
      if (count > MAX_EVENTS_PER_SECOND) fail(429, 'resource-exhausted', 'Too many live draft frames.');
      attachment.second = second;
      attachment.count = count;
      socket.serializeAttachment(attachment);
      await authorizeLiveDraft(this.env, attachment.uid, attachment.deviceId, attachment.pairId);
      const frame = validateLiveDraftFrame(message);
      this.notifyPeer(attachment.role, JSON.stringify(frame));
    } catch { socket.close(1008, 'Live draft access ended'); }
  }

  webSocketClose(socket: WebSocket): void {
    const attachment = socket.deserializeAttachment() as SocketAttachment | null;
    if (attachment && !this.hasSameRoleSocket(attachment.role, socket)) {
      this.notifyPeer(attachment.role, JSON.stringify({ type: 'peer-offline' }));
    }
  }

  webSocketError(socket: WebSocket): void {
    const attachment = socket.deserializeAttachment() as SocketAttachment | null;
    if (attachment && !this.hasSameRoleSocket(attachment.role, socket)) {
      this.notifyPeer(attachment.role, JSON.stringify({ type: 'peer-offline' }));
    }
  }

  private hasSameRoleSocket(role: MemberRole, except: WebSocket): boolean {
    return this.state.getWebSockets().some(socket => socket !== except &&
      (socket.deserializeAttachment() as SocketAttachment | null)?.role === role);
  }

  private hasPeer(role: MemberRole): boolean {
    return this.state.getWebSockets().some(socket =>
      (socket.deserializeAttachment() as SocketAttachment | null)?.role !== role);
  }

  private notifyPeer(role: MemberRole, frame: string): void {
    for (const socket of this.state.getWebSockets()) {
      const peer = socket.deserializeAttachment() as SocketAttachment | null;
      if (peer && peer.role !== role) {
        try { socket.send(frame); } catch { /* The client will reconnect. */ }
      }
    }
  }
}

export async function closeLiveDraftRoom(env: Env, pairId: string): Promise<void> {
  if (!env.LIVE_DRAFT) return;
  const stub = env.LIVE_DRAFT.get(env.LIVE_DRAFT.idFromName(pairId));
  await stub.fetch(new Request('https://live-draft.internal/revoke', { method: 'POST' }));
}

export async function connectLiveDraft(request: Request, env: Env, uid: string): Promise<Response> {
  if (request.headers.get('upgrade')?.toLowerCase() !== 'websocket') {
    fail(426, 'failed-precondition', 'A WebSocket upgrade is required.');
  }
  if (!env.LIVE_DRAFT) fail(503, 'unavailable', 'Live draft routing is unavailable.');
  const url = new URL(request.url);
  const data = {
    deviceId: url.searchParams.get('deviceId'),
    conversationId: url.searchParams.get('conversationId')
  };
  const deviceId = deviceIdOf(data);
  const pairId = idOf(data, 'conversationId');
  const role = await authorizeLiveDraft(env, uid, deviceId, pairId);
  const stub = env.LIVE_DRAFT.get(env.LIVE_DRAFT.idFromName(pairId));
  return stub.fetch(new Request('https://live-draft.internal/connect', {
    headers: {
      Upgrade: 'websocket',
      'x-verified-uid': uid,
      'x-verified-device': deviceId,
      'x-verified-pair': pairId,
      'x-verified-role': role
    }
  }));
}
