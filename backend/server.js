'use strict';

const http = require('http');
const crypto = require('crypto');
const { URL } = require('url');

const WS_MAGIC_GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11';
const ROOM_ID_REGEX = /^NYX-[A-Z2-9]{4}-[A-Z2-9]{4}$/;
const TOKEN_ID_REGEX = /^[A-Z2-9]{8,16}$/;
const HEX_64_REGEX = /^[0-9a-fA-F]{64}$/;
const DEFAULT_ROOM_TTL_MS = 2 * 60 * 60 * 1000; // 2 hours
const ANDROID_PACKAGE_NAME = 'com.aistudio.nyxorabrowser.vqxkpl';

const SENSITIVE_QUERY_KEYS = new Set([
  'access_token', 'id_token', 'refresh_token', 'token', 'auth', 'auth_token',
  'password', 'passwd', 'pwd', 'secret', 'client_secret', 'session',
  'session_id', 'sessionid', 'jsessionid', 'phpsessid', 'sid', 'code',
  'api_key', 'apikey', 'key', 'jwt', 'bearer', 'csrf', 'csrf_token',
  'xsrf_token', 'signature', 'sig'
]);

function redactSecretsForLog(message) {
  if (!message) return '';
  return String(message)
    .replace(/(https?:\/\/)([^/\s:@]+:[^/\s@]+)@/gi, '$1[REDACTED]@')
    .replace(/(inv|key|token|access_key|access_token|id_token|refresh_token|password|passwd|pwd|secret|client_secret|session_id|api_key|apikey|bearer)=([^&\s"']+)/gi, '$1=[REDACTED]')
    .replace(/"(accessKey|inviteToken|hmacSignature|handshakeSecret|password|secret|token)"\s*:\s*"([^"]+)"/gi, '"$1":"[REDACTED]"')
    .replace(/\bINV[A-Z0-9]{5,20}\.\d+\.[01]\.[0-9a-fA-F]{8,128}\b/gi, '[REDACTED_TOKEN]')
    .replace(/Bearer\s+[A-Za-z0-9\-_.=]+/gi, 'Bearer [REDACTED]')
    .replace(/\b[0-9a-fA-F]{64}\b/g, '[REDACTED_HMAC]')
    .replace(/\b[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}\b/g, '[REDACTED_KEY]');
}

function sanitizeChatMessageForSync(rawMessage, roomAccessKey = '') {
  if (typeof rawMessage !== 'string') return null;
  const normalized = rawMessage.replace(/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/g, '').replace(/\t/g, ' ').trim();
  if (!normalized) return null;
  let cleaned = redactSecretsForLog(normalized);
  const trimmedKey = String(roomAccessKey || '').trim();
  if (trimmedKey.length >= 8) {
    const escapedKey = trimmedKey.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    cleaned = cleaned.replace(new RegExp(escapedKey, 'gi'), '[REDACTED_KEY]');
  }
  const bounded = cleaned.slice(0, 500).trim();
  return bounded || null;
}

function normalizeRoomPermissions(rawPerms, fallbackPerms) {
  const base = fallbackPerms || {
    maxParticipants: 5,
    isRoomLocked: false,
    syncUrlNavigation: true,
    syncBackForward: true,
    syncTabSelection: false,
    syncNewlyOpenedPages: true,
    allowParticipantPlaybackControl: true,
    allowParticipantNavigationSync: true,
    allowParticipantControls: true
  };
  if (!rawPerms || typeof rawPerms !== 'object') {
    return { ...base };
  }
  const merged = { ...base, ...rawPerms };
  const hasExplicitMaster = Object.prototype.hasOwnProperty.call(rawPerms, 'allowParticipantControls');
  const masterAllowed = hasExplicitMaster ? Boolean(rawPerms.allowParticipantControls) : Boolean(merged.allowParticipantControls !== false);
  const pbAllowed = hasExplicitMaster && !Object.prototype.hasOwnProperty.call(rawPerms, 'allowParticipantPlaybackControl')
    ? masterAllowed
    : (Boolean(merged.allowParticipantPlaybackControl !== false) && (!hasExplicitMaster || masterAllowed));
  const navAllowed = hasExplicitMaster && !Object.prototype.hasOwnProperty.call(rawPerms, 'allowParticipantNavigationSync')
    ? masterAllowed
    : (Boolean(merged.allowParticipantNavigationSync !== false) && (!hasExplicitMaster || masterAllowed));
  const effectiveControls = masterAllowed && pbAllowed && navAllowed;
  return {
    maxParticipants: Math.min(10, Math.max(2, Number(merged.maxParticipants || 5))),
    isRoomLocked: Boolean(merged.isRoomLocked),
    syncUrlNavigation: merged.syncUrlNavigation !== false,
    syncBackForward: merged.syncBackForward !== false,
    syncTabSelection: Boolean(merged.syncTabSelection),
    syncNewlyOpenedPages: merged.syncNewlyOpenedPages !== false,
    allowParticipantPlaybackControl: pbAllowed,
    allowParticipantNavigationSync: navAllowed,
    allowParticipantControls: effectiveControls
  };
}

function computeInviteTokenHmac(accessKey, roomId, tokenId, expiresAtMillis, singleUse) {
  const cleanKey = (accessKey || '').trim().toUpperCase() || 'DEFAULT-UNAUTH-KEY';
  const canonical = `NYXORA_INVITE_V1|${roomId.trim().toUpperCase()}|${tokenId.trim().toUpperCase()}|${expiresAtMillis}|${singleUse ? 1 : 0}`;
  return crypto.createHmac('sha256', Buffer.from(cleanKey, 'utf8'))
    .update(canonical, 'utf8')
    .digest('hex');
}

function computeEventHmac(accessKey, roomId, eventId, type, senderDeviceId, authorityEpoch, sequenceNumber, timestampMillis, payloadJson) {
  const cleanKey = (accessKey || '').trim().toUpperCase() || 'DEFAULT-UNAUTH-KEY';
  const canonical = `${roomId.trim().toUpperCase()}|${eventId}|${type}|${senderDeviceId}|${authorityEpoch}|${sequenceNumber}|${timestampMillis}|${payloadJson}`;
  return crypto.createHmac('sha256', Buffer.from(cleanKey, 'utf8'))
    .update(canonical, 'utf8')
    .digest('hex');
}

const CROCKFORD_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

function deriveRoomCodeHandshakeKey(rawRoomId) {
  const normalized = String(rawRoomId || '').trim().toUpperCase();
  if (!ROOM_ID_REGEX.test(normalized)) return '';
  const digest = crypto.createHmac('sha256', Buffer.from('NYXORA_ROOM_CODE_HANDSHAKE_V1', 'utf8'))
    .update(normalized, 'utf8')
    .digest();
  let chars = '';
  for (let i = 0; i < 16; i++) {
    const idx = (digest[i] & 0xff) % CROCKFORD_ALPHABET.length;
    chars += CROCKFORD_ALPHABET[idx];
  }
  return `${chars.slice(0, 4)}-${chars.slice(4, 8)}-${chars.slice(8, 12)}-${chars.slice(12, 16)}`;
}

function timingSafeHexEqual(hexA, hexB) {
  const a = String(hexA || '').trim().toLowerCase();
  const b = String(hexB || '').trim().toLowerCase();
  if (a.length === 0 || a.length !== b.length) return false;
  return crypto.timingSafeEqual(Buffer.from(a, 'utf8'), Buffer.from(b, 'utf8'));
}

function sanitizeUrlForSync(rawUrl) {
  if (!rawUrl || typeof rawUrl !== 'string') return null;
  const trimmed = rawUrl.trim();
  if (!trimmed || trimmed.length > 2048) return null;
  if (trimmed.toLowerCase() === 'nyxora://home') return 'nyxora://home';
  try {
    const parsed = new URL(trimmed);
    const protocol = parsed.protocol.toLowerCase();
    if (protocol !== 'http:' && protocol !== 'https:') return null;
    parsed.username = '';
    parsed.password = '';
    const keysToDelete = [];
    for (const key of parsed.searchParams.keys()) {
      if (SENSITIVE_QUERY_KEYS.has(key.toLowerCase())) {
        keysToDelete.push(key);
      }
    }
    for (const k of keysToDelete) {
      parsed.searchParams.delete(k);
    }
    if (parsed.hash) {
      const lowerHash = parsed.hash.toLowerCase();
      for (const sKey of SENSITIVE_QUERY_KEYS) {
        if (lowerHash.includes(`${sKey}=`)) {
          parsed.hash = '';
          break;
        }
      }
    }
    return parsed.toString();
  } catch (_) {
    return null;
  }
}

function parseInviteToken(invToken, nowMillis = Date.now()) {
  const trimmed = String(invToken || '').trim();
  if (!trimmed) return { valid: false, code: 'INVITE_INVALID', message: 'Missing invitation token' };
  const parts = trimmed.split('.');
  if (parts.length !== 4) {
    return { valid: false, code: 'INVITE_INVALID', message: 'Invalid room invitation link or token format' };
  }
  const tokenId = parts[0].trim().toUpperCase();
  const expiresAtMillis = Number(parts[1].trim());
  const singleUse = parts[2].trim() === '1';
  const tokenHmac = parts[3].trim().toLowerCase();

  if (!TOKEN_ID_REGEX.test(tokenId) || !Number.isFinite(expiresAtMillis) || expiresAtMillis <= 0 || !HEX_64_REGEX.test(tokenHmac)) {
    return { valid: false, code: 'INVITE_INVALID', message: 'Invalid room invitation token structure' };
  }
  if (nowMillis > expiresAtMillis) {
    return { valid: false, code: 'INVITE_EXPIRED', message: 'This room invitation has expired' };
  }
  return {
    valid: true,
    tokenId,
    expiresAtMillis,
    singleUse,
    tokenHmac
  };
}

function encodeWebSocketFrame(opcode, payloadBuffer) {
  const length = payloadBuffer.length;
  let header;
  if (length <= 125) {
    header = Buffer.alloc(2);
    header[0] = 0x80 | (opcode & 0x0f);
    header[1] = length;
  } else if (length <= 65535) {
    header = Buffer.alloc(4);
    header[0] = 0x80 | (opcode & 0x0f);
    header[1] = 126;
    header.writeUInt16BE(length, 2);
  } else {
    header = Buffer.alloc(10);
    header[0] = 0x80 | (opcode & 0x0f);
    header[1] = 127;
    header.writeBigUInt64BE(BigInt(length), 2);
  }
  return Buffer.concat([header, payloadBuffer]);
}

function decodeWebSocketFrames(buffer) {
  const frames = [];
  let offset = 0;

  while (offset + 2 <= buffer.length) {
    const firstByte = buffer[offset];
    const secondByte = buffer[offset + 1];
    const opcode = firstByte & 0x0f;
    const isMasked = (secondByte & 0x80) !== 0;
    let payloadLen = secondByte & 0x7f;
    let headerLen = 2;

    if (payloadLen === 126) {
      if (offset + 4 > buffer.length) break;
      payloadLen = buffer.readUInt16BE(offset + 2);
      headerLen = 4;
    } else if (payloadLen === 127) {
      if (offset + 10 > buffer.length) break;
      const bigLen = buffer.readBigUInt64BE(offset + 2);
      if (bigLen > BigInt(65536)) {
        throw new Error('Frame payload too large');
      }
      payloadLen = Number(bigLen);
      headerLen = 10;
    }

    const maskLen = isMasked ? 4 : 0;
    const totalFrameLen = headerLen + maskLen + payloadLen;
    if (offset + totalFrameLen > buffer.length) break;

    const payloadStart = offset + headerLen + maskLen;
    const payload = Buffer.alloc(payloadLen);
    buffer.copy(payload, 0, payloadStart, payloadStart + payloadLen);

    if (isMasked) {
      const maskOffset = offset + headerLen;
      for (let i = 0; i < payloadLen; i++) {
        payload[i] ^= buffer[maskOffset + (i % 4)];
      }
    }

    frames.push({ opcode, payload });
    offset += totalFrameLen;
  }

  return {
    frames,
    remaining: buffer.slice(offset)
  };
}

function escapeHtml(str) {
  return String(str || '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function renderInvitationHtml({ roomId, roomName, invToken, wssEndpoint, isValid, errorTitle, errorDetail }) {
  const safeRoomId = escapeHtml(roomId || 'Unknown');
  const safeRoomName = escapeHtml(roomName || `Room ${safeRoomId}`);
  const queryParams = new URLSearchParams();
  if (roomId) queryParams.set('room', roomId);
  if (invToken) queryParams.set('inv', invToken);
  if (roomName) queryParams.set('name', roomName);
  if (wssEndpoint) queryParams.set('wss', wssEndpoint);
  const queryString = queryParams.toString();

  const customSchemeUrl = `nyxora://connect?${queryString}`;
  const androidIntentUrl = `intent://connect?${queryString}#Intent;scheme=nyxora;package=${ANDROID_PACKAGE_NAME};end`;

  if (!isValid) {
    return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>NYXORA Connect — Invitation Unavailable</title>
  <style>
    body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0B0F17; color: #F1F5F9; display: flex; align-items: center; justify-content: center; min-height: 100vh; padding: 20px; box-sizing: border-box; }
    .card { max-width: 440px; width: 100%; background: #131B2E; border: 1px solid #26324D; border-radius: 14px; padding: 24px; }
    .badge { display: inline-block; background: rgba(239, 68, 68, 0.16); color: #F87171; font-size: 12px; font-weight: 600; padding: 4px 10px; border-radius: 999px; margin-bottom: 12px; }
    h1 { font-size: 20px; margin: 0 0 8px; }
    p { font-size: 14px; color: #94A3B8; line-height: 1.5; margin: 0 0 16px; }
    .mono { font-family: monospace; background: #0B0F17; padding: 10px 12px; border-radius: 8px; font-size: 13px; color: #CBD5E1; }
  </style>
</head>
<body>
  <div class="card">
    <span class="badge">${escapeHtml(errorTitle || 'Invitation Invalid')}</span>
    <h1>Unable to Join NYXORA Room</h1>
    <p>${escapeHtml(errorDetail || 'This invitation link is invalid, expired, or has been revoked by the room host.')}</p>
    <div class="mono">Room ID: ${safeRoomId}</div>
  </div>
</body>
</html>`;
  }

  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Join ${safeRoomName} on NYXORA Browser</title>
  <style>
    body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0B0F17; color: #F1F5F9; display: flex; align-items: center; justify-content: center; min-height: 100vh; padding: 20px; box-sizing: border-box; }
    .card { max-width: 440px; width: 100%; background: #131B2E; border: 1px solid #26324D; border-radius: 14px; padding: 24px; }
    .badge { display: inline-block; background: rgba(41, 121, 255, 0.16); color: #5C9DFF; font-size: 12px; font-weight: 600; padding: 4px 10px; border-radius: 999px; margin-bottom: 12px; }
    h1 { font-size: 20px; margin: 0 0 6px; }
    p { font-size: 14px; color: #94A3B8; line-height: 1.5; margin: 0 0 16px; }
    .room-box { font-family: monospace; background: #0B0F17; border: 1px solid #1E293B; padding: 12px; border-radius: 8px; font-size: 14px; color: #E2E8F0; margin-bottom: 18px; }
    .btn { display: block; width: 100%; text-align: center; background: #2979FF; color: #FFFFFF; text-decoration: none; font-weight: 600; font-size: 14px; padding: 12px 16px; border-radius: 8px; box-sizing: border-box; margin-bottom: 10px; }
    .btn-secondary { display: block; width: 100%; text-align: center; background: transparent; color: #94A3B8; border: 1px solid #334155; text-decoration: none; font-weight: 500; font-size: 13px; padding: 10px 16px; border-radius: 8px; box-sizing: border-box; }
    .hint { font-size: 12px; color: #64748B; margin-top: 16px; line-height: 1.4; }
  </style>
</head>
<body>
  <div class="card">
    <span class="badge">NYXORA Connect • Browse &amp; Watch Together</span>
    <h1>${safeRoomName}</h1>
    <p>You have been invited to join a real-time synchronized session in NYXORA Browser.</p>
    <div class="room-box">Room ID: <strong>${safeRoomId}</strong></div>
    <a id="open-intent" class="btn" href="${escapeHtml(androidIntentUrl)}">Open in NYXORA Browser</a>
    <a id="open-scheme" class="btn-secondary" href="${escapeHtml(customSchemeUrl)}">Launch via Deep Link</a>
    <p class="hint">If NYXORA Browser is installed on your Android device, tap <strong>Open in NYXORA Browser</strong> to join room <strong>${safeRoomId}</strong> automatically. If not installed, install the NYXORA Browser APK first or paste this link inside NYXORA Connect.</p>
  </div>
  <script>
    (function() {
      var ua = navigator.userAgent || '';
      if (/Android/i.test(ua)) {
        setTimeout(function() {
          window.location.href = ${JSON.stringify(customSchemeUrl)};
        }, 150);
      }
    })();
  </script>
</body>
</html>`;
}

function createNyxoraConnectServer(options = {}) {
  const rooms = new Map();
  const clients = new Set();
  const deduplicator = new Map();
  const sha256CertFingerprint = options.sha256CertFingerprint ||
    process.env.NYXORA_ANDROID_SHA256_CERT ||
    'FA:C6:17:45:DC:09:03:78:6F:B9:ED:E6:2A:96:2B:39:9F:73:48:F0:BB:6F:89:9B:83:32:66:75:91:03:3B:9C';

  function markEventIfNew(eventId) {
    if (!eventId) return false;
    if (deduplicator.has(eventId)) return false;
    deduplicator.set(eventId, Date.now());
    if (deduplicator.size > 2048) {
      const oldestKey = deduplicator.keys().next().value;
      deduplicator.delete(oldestKey);
    }
    return true;
  }

  function createServerSignedEnvelope(roomState, type, payloadObj, signingSecretOverride) {
    roomState.lastSequenceNumber = (roomState.lastSequenceNumber || 1) + 1;
    const seq = roomState.lastSequenceNumber;
    const eventId = crypto.randomUUID();
    const now = Date.now();
    const payloadJson = JSON.stringify(payloadObj || {});
    const secret = signingSecretOverride || roomState.accessKey;
    const hmacSignature = computeEventHmac(
      secret,
      roomState.roomId,
      eventId,
      type,
      roomState.hostDeviceId,
      roomState.authorityEpoch || 1,
      seq,
      now,
      payloadJson
    );
    return JSON.stringify({
      eventId,
      roomId: roomState.roomId,
      type,
      senderDeviceId: roomState.hostDeviceId,
      senderRole: 'HOST',
      authorityEpoch: roomState.authorityEpoch || 1,
      sequenceNumber: seq,
      timestampMillis: now,
      payloadJson,
      hmacSignature
    });
  }

  function toSnapshotObject(roomState, includeAccessKey = false) {
    const obj = {
      roomId: roomState.roomId,
      roomName: roomState.roomName,
      endpointUrl: roomState.endpointUrl,
      hostDeviceId: roomState.hostDeviceId,
      authorityEpoch: roomState.authorityEpoch || 1,
      mode: roomState.mode || 'BROWSE_TOGETHER',
      isSyncActive: roomState.isSyncActive !== false,
      permissions: normalizeRoomPermissions(roomState.permissions),
      members: roomState.members || [],
      currentSyncedUrl: roomState.currentSyncedUrl || '',
      currentSyncedTitle: roomState.currentSyncedTitle || '',
      chatMessages: Array.isArray(roomState.chatMessages) ? roomState.chatMessages.slice(-100) : [],
      createdAtMillis: roomState.createdAtMillis || Date.now(),
      expiresAtMillis: roomState.expiresAtMillis || (Date.now() + DEFAULT_ROOM_TTL_MS),
      lastSequenceNumber: roomState.lastSequenceNumber || 1
    };
    if (includeAccessKey) {
      obj.accessKey = roomState.accessKey;
    }
    if (roomState.playbackState) {
      obj.playbackState = roomState.playbackState;
    }
    return obj;
  }

  function broadcastToRoom(roomId, textFrame, excludeClient = null) {
    for (const client of clients) {
      if (client.roomId === roomId && client !== excludeClient) {
        client.sendText(textFrame);
      }
    }
  }

  function broadcastRoomSnapshot(roomCtx) {
    const snapshotFrame = createServerSignedEnvelope(
      roomCtx.state,
      'ROOM_STATE_SNAPSHOT',
      toSnapshotObject(roomCtx.state, false)
    );
    broadcastToRoom(roomCtx.state.roomId, snapshotFrame, null);
  }

  function sendRejectFrame(client, roomId, code, message) {
    const now = Date.now();
    const payloadJson = JSON.stringify({ code, message });
    const envelope = JSON.stringify({
      eventId: crypto.randomUUID(),
      roomId: roomId || 'NYX-0000-0000',
      type: 'JOIN_REJECTED',
      senderDeviceId: 'server',
      senderRole: 'HOST',
      authorityEpoch: 1,
      sequenceNumber: 0,
      timestampMillis: now,
      payloadJson,
      hmacSignature: 'server'
    });
    client.sendText(envelope);
  }

  function handleClientLeave(client, explicitLeave = false) {
    const roomId = client.roomId;
    const deviceId = client.deviceId;
    if (!roomId || !deviceId) return;
    client.roomId = null;
    client.deviceId = null;

    const roomCtx = rooms.get(roomId);
    if (!roomCtx) return;

    if (explicitLeave) {
      const remaining = (roomCtx.state.members || []).filter((m) => m.deviceId !== deviceId);
      if (remaining.length === 0 || roomCtx.state.hostDeviceId === deviceId) {
        const closedFrame = createServerSignedEnvelope(
          roomCtx.state,
          'ROOM_CLOSED',
          { reason: 'Room closed because host left the room' }
        );
        broadcastToRoom(roomId, closedFrame, client);
        rooms.delete(roomId);
        return;
      }

      roomCtx.state.members = remaining;
      broadcastRoomSnapshot(roomCtx);
      return;
    }

    // Temporary socket disconnect (e.g., network switch or sharing Room Code in another app):
    // Keep the room alive until expiration and mark the member as RECONNECTING unless already reconnected.
    let hasOtherActiveSocket = false;
    for (const other of clients) {
      if (other !== client && other.roomId === roomId && other.deviceId === deviceId) {
        hasOtherActiveSocket = true;
        break;
      }
    }
    if (!hasOtherActiveSocket) {
      roomCtx.state.members = (roomCtx.state.members || []).map((m) =>
        m.deviceId === deviceId ? { ...m, status: 'RECONNECTING' } : m
      );
      broadcastRoomSnapshot(roomCtx);
    }
  }

  function handleIncomingFrame(client, rawText) {
    if (!rawText || rawText.length > 8192) return;
    let envelope;
    try {
      envelope = JSON.parse(rawText);
    } catch (_) {
      return;
    }
    if (!envelope || !envelope.eventId || !envelope.type || !envelope.roomId) return;
    if (!markEventIfNew(envelope.eventId)) return;

    const normalizedRoomId = String(envelope.roomId).trim().toUpperCase();
    if (!ROOM_ID_REGEX.test(normalizedRoomId)) {
      sendRejectFrame(client, normalizedRoomId, 'INVALID_ROOM_ID', 'Invalid Room ID format');
      return;
    }

    let payloadObj = {};
    try {
      payloadObj = JSON.parse(envelope.payloadJson || '{}');
    } catch (_) {
      payloadObj = {};
    }

    let roomCtx = rooms.get(normalizedRoomId);
    const now = Date.now();

    // Allow Host to bootstrap/register a room on the relay via a signed JOIN_REQUEST containing hostRoomSnapshot
    if (!roomCtx && envelope.type === 'JOIN_REQUEST' && envelope.senderRole === 'HOST' && payloadObj.hostRoomSnapshot) {
      const snap = payloadObj.hostRoomSnapshot;
      const derivedKey = deriveRoomCodeHandshakeKey(normalizedRoomId);
      const candidateKey = String(snap.accessKey || '').trim().toUpperCase() || derivedKey;
      const expectedHmac = computeEventHmac(
        candidateKey,
        normalizedRoomId,
        envelope.eventId,
        envelope.type,
        envelope.senderDeviceId,
        envelope.authorityEpoch || 1,
        envelope.sequenceNumber || 1,
        envelope.timestampMillis || now,
        envelope.payloadJson
      );
      const expectedDerivedHmac = derivedKey
        ? computeEventHmac(
            derivedKey,
            normalizedRoomId,
            envelope.eventId,
            envelope.type,
            envelope.senderDeviceId,
            envelope.authorityEpoch || 1,
            envelope.sequenceNumber || 1,
            envelope.timestampMillis || now,
            envelope.payloadJson
          )
        : '';
      if (candidateKey &&
          (timingSafeHexEqual(expectedHmac, envelope.hmacSignature) ||
           (expectedDerivedHmac && timingSafeHexEqual(expectedDerivedHmac, envelope.hmacSignature)))) {
        roomCtx = {
          state: {
            roomId: normalizedRoomId,
            roomName: String(snap.roomName || 'NYXORA Room').slice(0, 48),
            accessKey: candidateKey,
            endpointUrl: String(snap.endpointUrl || ''),
            hostDeviceId: envelope.senderDeviceId,
            authorityEpoch: Number(snap.authorityEpoch || 1),
            mode: snap.mode || 'BROWSE_TOGETHER',
            isSyncActive: snap.isSyncActive !== false,
            permissions: normalizeRoomPermissions(snap.permissions),
            members: Array.isArray(snap.members) ? snap.members : [],
            currentSyncedUrl: String(snap.currentSyncedUrl || ''),
            currentSyncedTitle: String(snap.currentSyncedTitle || ''),
            playbackState: snap.playbackState || null,
            chatMessages: Array.isArray(snap.chatMessages) ? snap.chatMessages.slice(-100) : [],
            createdAtMillis: Number(snap.createdAtMillis || now),
            expiresAtMillis: Number(snap.expiresAtMillis || (now + DEFAULT_ROOM_TTL_MS)),
            lastSequenceNumber: Number(snap.lastSequenceNumber || 1)
          },
          revokedTokenIds: new Set(),
          consumedSingleUseTokens: new Map(),
          kickedDeviceIds: new Set()
        };
        rooms.set(normalizedRoomId, roomCtx);
      }
    }

    if (!roomCtx) {
      sendRejectFrame(client, normalizedRoomId, 'ROOM_NOT_FOUND', 'Room does not exist or has closed');
      return;
    }

    if (now > roomCtx.state.expiresAtMillis) {
      rooms.delete(normalizedRoomId);
      sendRejectFrame(client, normalizedRoomId, 'ROOM_EXPIRED', 'This room has expired');
      return;
    }

    if (roomCtx.kickedDeviceIds.has(envelope.senderDeviceId)) {
      sendRejectFrame(client, normalizedRoomId, 'PARTICIPANT_REMOVED', 'You were removed from this room by the host');
      return;
    }

    if (envelope.type === 'JOIN_REQUEST') {
      const inviteToken = String(payloadObj.inviteToken || '').trim();
      let authorized = false;
      let handshakeSecret = roomCtx.state.accessKey;

      const expectedSessionHmac = computeEventHmac(
        roomCtx.state.accessKey,
        normalizedRoomId,
        envelope.eventId,
        envelope.type,
        envelope.senderDeviceId,
        envelope.authorityEpoch || 1,
        envelope.sequenceNumber || 1,
        envelope.timestampMillis || now,
        envelope.payloadJson
      );
      const derivedCodeKey = deriveRoomCodeHandshakeKey(normalizedRoomId);
      const expectedCodeHmac = derivedCodeKey
        ? computeEventHmac(
            derivedCodeKey,
            normalizedRoomId,
            envelope.eventId,
            envelope.type,
            envelope.senderDeviceId,
            envelope.authorityEpoch || 1,
            envelope.sequenceNumber || 1,
            envelope.timestampMillis || now,
            envelope.payloadJson
          )
        : '';

      if (inviteToken) {
        const parsedToken = parseInviteToken(inviteToken, now);
        if (!parsedToken.valid) {
          sendRejectFrame(client, normalizedRoomId, parsedToken.code, parsedToken.message);
          return;
        }
        if (roomCtx.revokedTokenIds.has(parsedToken.tokenId)) {
          sendRejectFrame(client, normalizedRoomId, 'INVITE_REVOKED', 'This room invitation has been revoked by the host');
          return;
        }
        if (parsedToken.singleUse) {
          const existingOwner = roomCtx.consumedSingleUseTokens.get(parsedToken.tokenId);
          if (existingOwner && existingOwner !== envelope.senderDeviceId) {
            sendRejectFrame(client, normalizedRoomId, 'INVITE_ALREADY_USED', 'This single-use room invitation has already been used');
            return;
          }
        }
        const expectedTokenHmac = computeInviteTokenHmac(
          roomCtx.state.accessKey,
          normalizedRoomId,
          parsedToken.tokenId,
          parsedToken.expiresAtMillis,
          parsedToken.singleUse
        );
        const expectedDerivedTokenHmac = derivedCodeKey
          ? computeInviteTokenHmac(
              derivedCodeKey,
              normalizedRoomId,
              parsedToken.tokenId,
              parsedToken.expiresAtMillis,
              parsedToken.singleUse
            )
          : '';
        const tokenValidForRoom =
          timingSafeHexEqual(expectedTokenHmac, parsedToken.tokenHmac) ||
          (expectedDerivedTokenHmac && timingSafeHexEqual(expectedDerivedTokenHmac, parsedToken.tokenHmac));

        const expectedFrameHmac = computeEventHmac(
          parsedToken.tokenHmac,
          normalizedRoomId,
          envelope.eventId,
          envelope.type,
          envelope.senderDeviceId,
          envelope.authorityEpoch || 1,
          envelope.sequenceNumber || 1,
          envelope.timestampMillis || now,
          envelope.payloadJson
        );
        const signedWithTokenHmac = timingSafeHexEqual(expectedFrameHmac, envelope.hmacSignature);
        const signedWithSessionKey = timingSafeHexEqual(expectedSessionHmac, envelope.hmacSignature);
        const signedWithDerivedCodeKey = Boolean(expectedCodeHmac && timingSafeHexEqual(expectedCodeHmac, envelope.hmacSignature));

        if (tokenValidForRoom && (signedWithTokenHmac || signedWithSessionKey || signedWithDerivedCodeKey)) {
          authorized = true;
          if (signedWithTokenHmac) {
            handshakeSecret = parsedToken.tokenHmac;
          } else if (signedWithSessionKey) {
            handshakeSecret = roomCtx.state.accessKey;
          } else {
            handshakeSecret = derivedCodeKey;
          }
          if (parsedToken.singleUse) {
            roomCtx.consumedSingleUseTokens.set(parsedToken.tokenId, envelope.senderDeviceId);
          }
        } else {
          sendRejectFrame(client, normalizedRoomId, 'INVITE_UNAUTHORIZED', 'Invalid room invitation signature or credentials');
          return;
        }
      } else {
        if (timingSafeHexEqual(expectedSessionHmac, envelope.hmacSignature)) {
          authorized = true;
          handshakeSecret = roomCtx.state.accessKey;
        } else if (expectedCodeHmac && timingSafeHexEqual(expectedCodeHmac, envelope.hmacSignature)) {
          authorized = true;
          handshakeSecret = derivedCodeKey;
        }
      }

      if (!authorized) {
        sendRejectFrame(client, normalizedRoomId, 'UNAUTHORIZED_KEY', 'Invalid Room Access Key or signature');
        return;
      }

      const isAlreadyMember = roomCtx.state.members.some((m) => m.deviceId === envelope.senderDeviceId);
      const isHost = roomCtx.state.hostDeviceId === envelope.senderDeviceId;
      if (!isAlreadyMember && !isHost) {
        if (roomCtx.state.permissions.isRoomLocked) {
          sendRejectFrame(client, normalizedRoomId, 'ROOM_LOCKED', 'Room is locked by the host');
          return;
        }
        if (roomCtx.state.members.length >= (roomCtx.state.permissions.maxParticipants || 5)) {
          sendRejectFrame(client, normalizedRoomId, 'ROOM_FULL', 'Room has reached its participant limit');
          return;
        }
      }

      // Close any prior duplicate connection for the same deviceId
      for (const existingClient of clients) {
        if (existingClient !== client && existingClient.roomId === normalizedRoomId && existingClient.deviceId === envelope.senderDeviceId) {
          existingClient.roomId = null;
          existingClient.deviceId = null;
          existingClient.close();
        }
      }

      client.roomId = normalizedRoomId;
      client.deviceId = envelope.senderDeviceId;

      const deviceName = String(payloadObj.deviceName || 'Android Device').trim().slice(0, 36) || 'Android Device';
      const memberEntry = {
        deviceId: envelope.senderDeviceId,
        deviceName,
        role: isHost ? 'HOST' : 'PARTICIPANT',
        status: 'SYNCED',
        joinedAtMillis: now,
        lastHeartbeatMillis: now,
        isFollowingPaused: false
      };

      const filteredMembers = roomCtx.state.members.filter((m) => m.deviceId !== envelope.senderDeviceId);
      roomCtx.state.members = [...filteredMembers, memberEntry];

      const joinAcceptedFrame = createServerSignedEnvelope(
        roomCtx.state,
        'JOIN_ACCEPTED',
        toSnapshotObject(roomCtx.state, true),
        handshakeSecret
      );
      client.sendText(joinAcceptedFrame);
      broadcastRoomSnapshot(roomCtx);
      return;
    }

    // Post-join frames must be signed with the room's session accessKey or derived room code key
    const expectedHmac = computeEventHmac(
      roomCtx.state.accessKey,
      normalizedRoomId,
      envelope.eventId,
      envelope.type,
      envelope.senderDeviceId,
      envelope.authorityEpoch || 1,
      envelope.sequenceNumber || 1,
      envelope.timestampMillis || now,
      envelope.payloadJson
    );
    const postJoinDerivedKey = deriveRoomCodeHandshakeKey(normalizedRoomId);
    const expectedPostJoinCodeHmac = postJoinDerivedKey
      ? computeEventHmac(
          postJoinDerivedKey,
          normalizedRoomId,
          envelope.eventId,
          envelope.type,
          envelope.senderDeviceId,
          envelope.authorityEpoch || 1,
          envelope.sequenceNumber || 1,
          envelope.timestampMillis || now,
          envelope.payloadJson
        )
      : '';
    if (!timingSafeHexEqual(expectedHmac, envelope.hmacSignature) &&
        !(expectedPostJoinCodeHmac && timingSafeHexEqual(expectedPostJoinCodeHmac, envelope.hmacSignature))) {
      sendRejectFrame(client, normalizedRoomId, 'UNAUTHORIZED_KEY', 'Invalid Room Access Key or signature');
      return;
    }

    // Strictly verify that the socket and senderDeviceId belong to an authenticated member of this room
    const authenticatedMember = (roomCtx.state.members || []).find((m) => m.deviceId === envelope.senderDeviceId);
    if (client.roomId !== normalizedRoomId || client.deviceId !== envelope.senderDeviceId || !authenticatedMember) {
      sendRejectFrame(client, normalizedRoomId, 'NOT_ROOM_MEMBER', 'Only authenticated room members can send chat or use shared controls');
      return;
    }

    const isHostSender = roomCtx.state.hostDeviceId === envelope.senderDeviceId;
    const perms = normalizeRoomPermissions(roomCtx.state.permissions);
    roomCtx.state.permissions = perms;

    switch (envelope.type) {
      case 'HEARTBEAT_PING': {
        roomCtx.state.members = roomCtx.state.members.map((m) =>
          m.deviceId === envelope.senderDeviceId ? { ...m, lastHeartbeatMillis: now, status: m.isFollowingPaused ? 'PAUSED_FOLLOWING' : 'SYNCED' } : m
        );
        const pongFrame = createServerSignedEnvelope(roomCtx.state, 'HEARTBEAT_PONG', { serverTimeMillis: now });
        client.sendText(pongFrame);
        break;
      }
      case 'LEAVE_ROOM': {
        handleClientLeave(client, true);
        break;
      }
      case 'ROOM_CLOSED': {
        if (isHostSender) {
          const closedFrame = createServerSignedEnvelope(roomCtx.state, 'ROOM_CLOSED', { reason: 'Host closed the room' });
          broadcastToRoom(normalizedRoomId, closedFrame, null);
          rooms.delete(normalizedRoomId);
        }
        break;
      }
      case 'NAVIGATE_URL':
      case 'SELECT_TAB_NAVIGATION':
      case 'NEW_PAGE_OPENED': {
        if (!isHostSender && (!perms.allowParticipantControls || !perms.allowParticipantNavigationSync)) {
          const deniedFrame = createServerSignedEnvelope(roomCtx.state, 'PERMISSION_DENIED', {
            code: 'PARTICIPANT_CONTROLS_DISABLED',
            message: 'Shared controls are disabled by the host — only the host can control shared navigation and playback.'
          });
          client.sendText(deniedFrame);
          break;
        }
        if (!roomCtx.state.isSyncActive) break;
        const safeUrl = sanitizeUrlForSync(payloadObj.url);
        if (!safeUrl) break;
        const safeTitle = String(payloadObj.title || '').trim().slice(0, 120);
        roomCtx.state.currentSyncedUrl = safeUrl;
        roomCtx.state.currentSyncedTitle = safeTitle;
        const navFrame = createServerSignedEnvelope(roomCtx.state, envelope.type, {
          url: safeUrl,
          title: safeTitle,
          initiatedBy: envelope.senderDeviceId
        });
        broadcastToRoom(normalizedRoomId, navFrame, null);
        break;
      }
      case 'NAVIGATE_BACK':
      case 'NAVIGATE_FORWARD': {
        if (!isHostSender && (!perms.allowParticipantControls || !perms.allowParticipantNavigationSync)) {
          const deniedFrame = createServerSignedEnvelope(roomCtx.state, 'PERMISSION_DENIED', {
            code: 'PARTICIPANT_CONTROLS_DISABLED',
            message: 'Shared controls are disabled by the host — only the host can control shared navigation and playback.'
          });
          client.sendText(deniedFrame);
          break;
        }
        if (!roomCtx.state.isSyncActive) break;
        if (!perms.syncBackForward) break;
        const bfFrame = createServerSignedEnvelope(roomCtx.state, envelope.type, {
          direction: envelope.type === 'NAVIGATE_BACK' ? 'BACK' : 'FORWARD',
          initiatedBy: envelope.senderDeviceId
        });
        broadcastToRoom(normalizedRoomId, bfFrame, null);
        break;
      }
      case 'PLAYBACK_ACTION':
      case 'PLAYBACK_BEACON': {
        if (!isHostSender && (!perms.allowParticipantControls || !perms.allowParticipantPlaybackControl || envelope.type === 'PLAYBACK_BEACON')) {
          if (envelope.type === 'PLAYBACK_ACTION') {
            const deniedFrame = createServerSignedEnvelope(roomCtx.state, 'PERMISSION_DENIED', {
              code: 'PARTICIPANT_CONTROLS_DISABLED',
              message: 'Shared controls are disabled by the host — only the host can control shared navigation and playback.'
            });
            client.sendText(deniedFrame);
          }
          break;
        }
        if (!roomCtx.state.isSyncActive) break;
        const safePageUrl = sanitizeUrlForSync(payloadObj.pageUrl || roomCtx.state.currentSyncedUrl);
        if (!safePageUrl) break;
        const pbState = {
          pageUrl: safePageUrl,
          isPlaying: Boolean(payloadObj.isPlaying),
          positionSeconds: Math.max(0, Number(payloadObj.positionSeconds || 0)),
          durationSeconds: Math.max(0, Number(payloadObj.durationSeconds || 0)),
          playbackRate: Math.min(2.0, Math.max(0.25, Number(payloadObj.playbackRate || 1.0))),
          updatedAtMillis: now,
          updatedByDeviceId: envelope.senderDeviceId,
          sequenceNumber: (roomCtx.state.lastSequenceNumber || 1) + 1
        };
        roomCtx.state.currentSyncedUrl = safePageUrl;
        roomCtx.state.playbackState = pbState;
        const pbFrame = createServerSignedEnvelope(roomCtx.state, envelope.type, pbState);
        broadcastToRoom(normalizedRoomId, pbFrame, null);
        break;
      }
      case 'CHAT_MESSAGE': {
        const sanitizedText = sanitizeChatMessageForSync(payloadObj.text, roomCtx.state.accessKey);
        if (!sanitizedText) break;
        const messageId = String(payloadObj.messageId || '').trim().slice(0, 64) || crypto.randomUUID();
        const senderName = String(authenticatedMember.deviceName || payloadObj.senderName || (isHostSender ? 'Host' : 'Participant')).trim().slice(0, 36) || 'Participant';
        const chatMsg = {
          messageId,
          roomId: normalizedRoomId,
          senderDeviceId: envelope.senderDeviceId,
          senderName,
          senderRole: isHostSender ? 'HOST' : 'PARTICIPANT',
          text: sanitizedText,
          timestampMillis: now
        };
        const existingHistory = Array.isArray(roomCtx.state.chatMessages) ? roomCtx.state.chatMessages : [];
        roomCtx.state.chatMessages = [...existingHistory.filter((m) => m.messageId !== messageId), chatMsg].slice(-100);
        const chatFrame = createServerSignedEnvelope(roomCtx.state, 'CHAT_MESSAGE', chatMsg);
        broadcastToRoom(normalizedRoomId, chatFrame, null);
        break;
      }
      case 'UPDATE_ROOM_CONFIG': {
        if (!isHostSender) break;
        if (payloadObj.roomName) roomCtx.state.roomName = String(payloadObj.roomName).slice(0, 48);
        if (payloadObj.mode) roomCtx.state.mode = payloadObj.mode;
        if (typeof payloadObj.isSyncActive === 'boolean') roomCtx.state.isSyncActive = payloadObj.isSyncActive;
        if (payloadObj.permissions) {
          roomCtx.state.permissions = normalizeRoomPermissions(payloadObj.permissions, roomCtx.state.permissions);
        }
        broadcastRoomSnapshot(roomCtx);
        break;
      }
      case 'PARTICIPANT_FOLLOW_STATE': {
        const paused = Boolean(payloadObj.isFollowingPaused);
        roomCtx.state.members = roomCtx.state.members.map((m) =>
          m.deviceId === envelope.senderDeviceId ? { ...m, isFollowingPaused: paused, status: paused ? 'PAUSED_FOLLOWING' : 'SYNCED' } : m
        );
        broadcastRoomSnapshot(roomCtx);
        break;
      }
      case 'REQUEST_RESYNC': {
        const snapFrame = createServerSignedEnvelope(
          roomCtx.state,
          'ROOM_STATE_SNAPSHOT',
          toSnapshotObject(roomCtx.state, false)
        );
        client.sendText(snapFrame);
        break;
      }
      default:
        break;
    }
  }

  const httpServer = http.createServer((req, res) => {
    const reqUrl = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    const pathname = reqUrl.pathname;

    if (req.method === 'GET' && pathname === '/healthz') {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify({
        status: 'ok',
        service: 'nyxora-connect-relay',
        activeRooms: rooms.size,
        connectedClients: clients.size
      }));
      return;
    }

    if (req.method === 'GET' && pathname === '/.well-known/assetlinks.json') {
      const assetLinks = [
        {
          relation: ['delegate_permission/common.handle_all_urls'],
          target: {
            namespace: 'android_app',
            package_name: ANDROID_PACKAGE_NAME,
            sha256_cert_fingerprints: [sha256CertFingerprint]
          }
        }
      ];
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      res.end(JSON.stringify(assetLinks, null, 2));
      return;
    }

    if (req.method === 'GET' && (pathname === '/connect' || pathname.startsWith('/connect/') || pathname === '/invite' || pathname.startsWith('/invite/'))) {
      const roomParam = String(reqUrl.searchParams.get('room') || '').trim().toUpperCase();
      const invParam = String(reqUrl.searchParams.get('inv') || '').trim();
      const nameParam = String(reqUrl.searchParams.get('name') || '').trim();
      const wssParam = String(reqUrl.searchParams.get('wss') || '').trim();

      if (!ROOM_ID_REGEX.test(roomParam)) {
        res.writeHead(400, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(renderInvitationHtml({
          roomId: roomParam,
          isValid: false,
          errorTitle: 'Invalid Room ID',
          errorDetail: 'The room identifier in this invitation link is missing or malformed.'
        }));
        return;
      }

      const tokenCheck = parseInviteToken(invParam, Date.now());
      if (!tokenCheck.valid) {
        res.writeHead(410, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(renderInvitationHtml({
          roomId: roomParam,
          isValid: false,
          errorTitle: tokenCheck.code === 'INVITE_EXPIRED' ? 'Invitation Expired' : 'Invalid Invitation Token',
          errorDetail: tokenCheck.message
        }));
        return;
      }

      const activeRoom = rooms.get(roomParam);
      if (activeRoom) {
        if (activeRoom.revokedTokenIds.has(tokenCheck.tokenId)) {
          res.writeHead(410, { 'Content-Type': 'text/html; charset=utf-8' });
          res.end(renderInvitationHtml({
            roomId: roomParam,
            isValid: false,
            errorTitle: 'Invitation Revoked',
            errorDetail: 'This room invitation has been revoked by the host.'
          }));
          return;
        }
        const expectedHmac = computeInviteTokenHmac(
          activeRoom.state.accessKey,
          roomParam,
          tokenCheck.tokenId,
          tokenCheck.expiresAtMillis,
          tokenCheck.singleUse
        );
        if (!timingSafeHexEqual(expectedHmac, tokenCheck.tokenHmac)) {
          res.writeHead(403, { 'Content-Type': 'text/html; charset=utf-8' });
          res.end(renderInvitationHtml({
            roomId: roomParam,
            isValid: false,
            errorTitle: 'Unauthorized Invitation',
            errorDetail: 'The cryptographic signature on this invitation token does not match the room.'
          }));
          return;
        }
      }

      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(renderInvitationHtml({
        roomId: roomParam,
        roomName: nameParam || (activeRoom && activeRoom.state.roomName) || `Room ${roomParam}`,
        invToken: invParam,
        wssEndpoint: wssParam,
        isValid: true
      }));
      return;
    }

    res.writeHead(404, { 'Content-Type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ error: 'Not Found' }));
  });

  httpServer.on('upgrade', (req, socket) => {
    const reqUrl = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
    if (reqUrl.pathname !== '/nyxora') {
      socket.write('HTTP/1.1 404 Not Found\r\n\r\n');
      socket.destroy();
      return;
    }

    const wsKey = req.headers['sec-websocket-key'];
    if (!wsKey) {
      socket.write('HTTP/1.1 400 Bad Request\r\n\r\n');
      socket.destroy();
      return;
    }

    const acceptKey = crypto.createHash('sha1')
      .update(wsKey.trim() + WS_MAGIC_GUID, 'utf8')
      .digest('base64');

    const responseHeaders = [
      'HTTP/1.1 101 Switching Protocols',
      'Upgrade: websocket',
      'Connection: Upgrade',
      `Sec-WebSocket-Accept: ${acceptKey}`,
      '\r\n'
    ].join('\r\n');

    socket.write(responseHeaders);
    socket.setNoDelay(true);

    let recvBuffer = Buffer.alloc(0);
    const client = {
      socket,
      roomId: null,
      deviceId: null,
      sendText(text) {
        if (socket.destroyed) return;
        const frame = encodeWebSocketFrame(0x1, Buffer.from(text, 'utf8'));
        socket.write(frame);
      },
      close() {
        if (!socket.destroyed) {
          try {
            socket.write(encodeWebSocketFrame(0x8, Buffer.alloc(0)));
          } catch (_) {}
          socket.destroy();
        }
      }
    };
    clients.add(client);

    socket.on('data', (chunk) => {
      recvBuffer = Buffer.concat([recvBuffer, chunk]);
      let decoded;
      try {
        decoded = decodeWebSocketFrames(recvBuffer);
      } catch (_) {
        client.close();
        return;
      }
      recvBuffer = decoded.remaining;
      for (const frame of decoded.frames) {
        if (frame.opcode === 0x1) {
          handleIncomingFrame(client, frame.payload.toString('utf8'));
        } else if (frame.opcode === 0x8) {
          handleClientLeave(client);
          client.close();
        } else if (frame.opcode === 0x9) {
          if (!socket.destroyed) {
            socket.write(encodeWebSocketFrame(0xA, frame.payload));
          }
        }
      }
    });

    socket.on('close', () => {
      clients.delete(client);
      handleClientLeave(client);
    });

    socket.on('error', () => {
      clients.delete(client);
      handleClientLeave(client);
    });
  });

  return {
    httpServer,
    rooms,
    clients,
    close() {
      for (const c of clients) {
        try {
          c.socket.destroy();
        } catch (_) {}
      }
      clients.clear();
      if (typeof httpServer.closeAllConnections === 'function') {
        httpServer.closeAllConnections();
      }
      return new Promise((resolve) => {
        httpServer.close(() => resolve());
      });
    }
  };
}

if (require.main === module) {
  const port = Number(process.env.PORT || 8080);
  const { httpServer } = createNyxoraConnectServer();
  httpServer.listen(port, '0.0.0.0', () => {
    console.log(redactSecretsForLog(`[NYXORA Connect Backend] Listening on port ${port} (WS: /nyxora, Invite: /connect, AssetLinks: /.well-known/assetlinks.json)`));
  });
}

module.exports = {
  createNyxoraConnectServer,
  deriveRoomCodeHandshakeKey,
  computeInviteTokenHmac,
  computeEventHmac,
  parseInviteToken,
  redactSecretsForLog,
  encodeWebSocketFrame,
  decodeWebSocketFrames
};
