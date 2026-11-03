import { authenticate } from './auth.ts';
import { RelayError, fail, type Data, type Env } from './common.ts';
import { sendHint } from './fcm.ts';
import {
  registerDevice, createPairingInvite, joinPairingInvite, fetchPairingRequest,
  publishPairingWelcome, fetchPairingWelcome, confirmPairing, revokePairing
} from './pairing.ts';
import { sendEnvelope, fetchPendingEnvelopes, acknowledgeEnvelopes, markRead, fetchMessageStatuses, revokeDevice, purgeExpired } from './messages.ts';
import { beginMediaUpload, uploadMediaChunk, completeMediaUpload, fetchMediaManifest, fetchMediaChunk, cancelMediaUpload } from './media.ts';
import { closeLiveDraftRoom, connectLiveDraft, setLiveDraftConsent, LiveDraftRoom } from './live-draft.ts';
export { LiveDraftRoom };

type Authenticator = (request: Request, env: Env) => Promise<string>;
type HintSender = (env: Env, token: string, pairId: string) => Promise<void>;
const maxRequestBytes = 220_000;

async function readJson(request: Request): Promise<Data> {
  const reader = request.body?.getReader();
  if (!reader) fail(400, 'invalid-argument', 'The request body is missing.');
  let size = 0;
  const parts: Uint8Array[] = [];
  while (true) {
    const item = await reader.read();
    if (item.done) break;
    size += item.value.byteLength;
    if (size > maxRequestBytes) {
      await reader.cancel();
      fail(413, 'invalid-argument', 'The request is too large.');
    }
    parts.push(item.value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const part of parts) { bytes.set(part, offset); offset += part.length; }
  try {
    const value: unknown = JSON.parse(new TextDecoder().decode(bytes));
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid body');
    return value as Data;
  } catch {
    fail(400, 'invalid-argument', 'The request body must be a JSON object.');
  }
}

function json(value: unknown, status = 200): Response {
  return Response.json(value, { status, headers: { 'cache-control': 'no-store' } });
}

/** Testable request handler. Production always uses verified Firebase Auth and App Check. */
export function createRelayHandler(dependencies: { authenticate: Authenticator; sendHint: HintSender }) {
  return async (request: Request, env: Env, _context: ExecutionContext): Promise<Response> => {
    try {
      const url = new URL(request.url);
      if (url.pathname === '/health' && request.method === 'GET') return json({ service: 'focaaa-relay', status: 'ok' });
      if (url.pathname === '/v1/liveDraft' && request.method === 'GET') {
        if (!env.DB || !env.FIREBASE_PROJECT_ID) fail(503, 'unavailable', 'Relay configuration is incomplete.');
        const uid = await dependencies.authenticate(request, env);
        return await connectLiveDraft(request, env, uid);
      }
      if (request.method !== 'POST' || !url.pathname.startsWith('/v1/')) {
        fail(404, 'not-found', 'Unknown relay operation.');
      }
      if (!env.DB || !env.FIREBASE_PROJECT_ID) fail(503, 'unavailable', 'Relay configuration is incomplete.');
      const uid = await dependencies.authenticate(request, env);
      const data = await readJson(request);
      const now = Date.now();
      const name = url.pathname.slice('/v1/'.length);
      let result: unknown;
      switch (name) {
        case 'registerDevice': result = await registerDevice(env, uid, data, now); break;
        case 'createPairingInvite': result = await createPairingInvite(env, uid, data, now); break;
        case 'joinPairingInvite': result = await joinPairingInvite(env, uid, data, now); break;
        case 'fetchPairingRequest': result = await fetchPairingRequest(env, uid, data, now); break;
        case 'publishPairingWelcome': result = await publishPairingWelcome(env, uid, data, now); break;
        case 'fetchPairingWelcome': result = await fetchPairingWelcome(env, uid, data, now); break;
        case 'confirmPairing': result = await confirmPairing(env, uid, data, now); break;
        case 'setLiveDraftConsent': {
          result = await setLiveDraftConsent(env, uid, data, now);
          if (!(result as { enabled: boolean }).enabled) {
            try { await closeLiveDraftRoom(env, data.conversationId as string); }
            catch { /* Every subsequent frame rechecks the withdrawn consent. */ }
          }
          break;
        }
        case 'revokePairing': {
          result = await revokePairing(env, uid, data, now);
          const pairId = data.conversationId;
          if (typeof pairId === 'string') {
            try { await closeLiveDraftRoom(env, pairId); }
            catch { /* Pair revocation is durable; sockets recheck membership on every frame. */ }
          }
          break;
        }
        case 'sendEnvelope': result = await sendEnvelope(env, uid, data, now, dependencies.sendHint); break;
        case 'fetchPendingEnvelopes': result = await fetchPendingEnvelopes(env, uid, data, now); break;
        case 'acknowledgeEnvelopes': result = await acknowledgeEnvelopes(env, uid, data, now); break;
        case 'markRead': result = await markRead(env, uid, data, now); break;
        case 'fetchMessageStatuses': result = await fetchMessageStatuses(env, uid, data, now); break;
        case 'revokeDevice': result = await revokeDevice(env, uid, data, now); break;
        case 'beginMediaUpload': result = await beginMediaUpload(env, uid, data, now); break;
        case 'uploadMediaChunk': result = await uploadMediaChunk(env, uid, data, now); break;
        case 'completeMediaUpload': result = await completeMediaUpload(env, uid, data, now); break;
        case 'fetchMediaManifest': result = await fetchMediaManifest(env, uid, data, now); break;
        case 'fetchMediaChunk': result = await fetchMediaChunk(env, uid, data, now); break;
        case 'cancelMediaUpload': result = await cancelMediaUpload(env, uid, data, now); break;
        default: fail(404, 'not-found', 'Unknown relay operation.');
      }
      return json({ result });
    } catch (error) {
      if (error instanceof RelayError) return json({ error: { code: error.code, message: error.message } }, error.status);
      console.error('relay_request_failed', error instanceof Error ? error.name : 'unknown');
      return json({ error: { code: 'internal', message: 'The relay could not complete the request.' } }, 500);
    }
  };
}

const productionHandler = createRelayHandler({ authenticate, sendHint });

export default {
  fetch: productionHandler,
  scheduled(_controller: ScheduledController, env: Env, context: ExecutionContext) {
    context.waitUntil(purgeExpired(env, Date.now()));
  }
} satisfies ExportedHandler<Env>;
