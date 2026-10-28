'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');
const crypto = require('node:crypto');
const {
  createNyxoraConnectServer,
  computeInviteTokenHmac,
  computeEventHmac,
  redactSecretsForLog,
  decodeWebSocketFrames
} = require('./server');

function encodeMaskedClientFrame(text) {
  const payload = Buffer.from(text, 'utf8');
  const mask = crypto.randomBytes(4);
  let header;
  if (payload.length <= 125) {
    header = Buffer.alloc(2);
    header[0] = 0x81;
    header[1] = 0x80 | payload.length;
  } else {
    header = Buffer.alloc(4);
    header[0] = 0x81;
    header[1] = 0x80 | 126;
    header.writeUInt16BE(payload.length, 2);
  }
  const masked = Buffer.alloc(payload.length);
  for (let i = 0; i < payload.length; i++) {
    masked[i] = payload[i] ^ mask[i % 4];
  }
  return Buffer.concat([header, mask, masked]);
}

function openRawWebSocket(port, roomId, deviceId) {
  return new Promise((resolve, reject) => {
    const key = crypto.randomBytes(16).toString('base64');
    const socket = net.createConnection({ host: '127.0.0.1', port }, () => {
      const handshake = [
        'GET /nyxora HTTP/1.1',
        `Host: 127.0.0.1:${port}`,
        'Upgrade: websocket',
        'Connection: Upgrade',
        `Sec-WebSocket-Key: ${key}`,
        'Sec-WebSocket-Version: 13',
        `X-Nyxora-Room: ${roomId}`,
        `X-Nyxora-Device: ${deviceId}`,
        '\r\n'
      ].join('\r\n');
      socket.write(handshake);
    });

    let upgraded = false;
    let buffer = Buffer.alloc(0);
    const messages = [];
    const waiters = [];

    function processFrames() {
      const decoded = decodeWebSocketFrames(buffer);
      buffer = decoded.remaining;
      for (const f of decoded.frames) {
        if (f.opcode === 0x1) {
          const parsed = JSON.parse(f.payload.toString('utf8'));
          if (waiters.length > 0) {
            const w = waiters.shift();
            w(parsed);
          } else {
            messages.push(parsed);
          }
        }
      }
    }

    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk]);
      if (!upgraded) {
        const headerEnd = buffer.indexOf('\r\n\r\n');
        if (headerEnd === -1) return;
        const headerStr = buffer.slice(0, headerEnd).toString('utf8');
        if (!headerStr.startsWith('HTTP/1.1 101')) {
          reject(new Error(`Unexpected handshake response: ${headerStr}`));
          socket.destroy();
          return;
        }
        upgraded = true;
        buffer = buffer.slice(headerEnd + 4);
        processFrames();
        resolve({
          socket,
          sendEnvelope(envelopeObj) {
            socket.write(encodeMaskedClientFrame(JSON.stringify(envelopeObj)));
          },
          nextMessage(timeoutMs = 3000) {
            if (messages.length > 0) {
              return Promise.resolve(messages.shift());
            }
            return new Promise((resMsg, rejMsg) => {
              const timer = setTimeout(() => rejMsg(new Error('Timed out waiting for WS frame')), timeoutMs);
              waiters.push((msg) => {
                clearTimeout(timer);
                resMsg(msg);
              });
            });
          },
          close() {
            socket.destroy();
          }
        });
        return;
      }
      processFrames();
    });

    socket.on('error', reject);
  });
}

function createSignedEnvelope({ roomId, type, senderDeviceId, senderRole, authorityEpoch = 1, sequenceNumber = 1, payloadObj, signingSecret }) {
  const eventId = crypto.randomUUID();
  const timestampMillis = Date.now();
  const payloadJson = JSON.stringify(payloadObj);
  const hmacSignature = computeEventHmac(
    signingSecret,
    roomId,
    eventId,
    type,
    senderDeviceId,
    authorityEpoch,
    sequenceNumber,
    timestampMillis,
    payloadJson
  );
  return {
    eventId,
    roomId,
    type,
    senderDeviceId,
    senderRole,
    authorityEpoch,
    sequenceNumber,
    timestampMillis,
    payloadJson,
    hmacSignature
  };
}

test('NYXORA Connect Backend: HTTP healthz, assetlinks.json, and HTTPS /connect invitation page', async () => {
  const server = createNyxoraConnectServer();
  const { httpServer } = server;
  await new Promise((r) => httpServer.listen(0, '127.0.0.1', r));
  const port = httpServer.address().port;

  try {
    const healthRes = await fetch(`http://127.0.0.1:${port}/healthz`);
    assert.equal(healthRes.status, 200);
    const healthJson = await healthRes.json();
    assert.equal(healthJson.status, 'ok');

    const assetRes = await fetch(`http://127.0.0.1:${port}/.well-known/assetlinks.json`);
    assert.equal(assetRes.status, 200);
    const assetJson = await assetRes.json();
    assert.equal(assetJson[0].target.package_name, 'com.aistudio.nyxorabrowser.vqxkpl');

    const roomId = 'NYX-4B7M-9Q2W';
    const accessKey = 'ABCD-EFGH-JKLM-NPQR';
    const tokenId = 'INV23456789';
    const futureExp = Date.now() + 600000;
    const tokenHmac = computeInviteTokenHmac(accessKey, roomId, tokenId, futureExp, false);
    const validInv = `${tokenId}.${futureExp}.0.${tokenHmac}`;

    const inviteRes = await fetch(`http://127.0.0.1:${port}/connect?room=${roomId}&inv=${encodeURIComponent(validInv)}&name=Study+Session`);
    assert.equal(inviteRes.status, 200);
    const html = await inviteRes.text();
    assert.ok(html.includes(roomId));
    assert.ok(html.includes('Open in NYXORA Browser'));
    assert.ok(html.includes('intent://connect?'));

    // Expired invitation returns 410
    const pastExp = Date.now() - 10000;
    const expiredHmac = computeInviteTokenHmac(accessKey, roomId, tokenId, pastExp, false);
    const expiredInv = `${tokenId}.${pastExp}.0.${expiredHmac}`;
    const expiredRes = await fetch(`http://127.0.0.1:${port}/connect?room=${roomId}&inv=${encodeURIComponent(expiredInv)}`);
    assert.equal(expiredRes.status, 410);
  } finally {
    await server.close();
  }
});

test('NYXORA Connect Backend: Multi-device WebSocket room creation, token join, URL sync, and host closure', async () => {
  const server = createNyxoraConnectServer();
  const { httpServer } = server;
  await new Promise((r) => httpServer.listen(0, '127.0.0.1', r));
  const port = httpServer.address().port;

  const roomId = 'NYX-8K3P-5T9X';
  const accessKey = 'WXYZ-2345-6789-ABCD';
  const hostDeviceId = 'device-host-1';
  const guestDeviceId = 'device-guest-2';

  const hostWs = await openRawWebSocket(port, roomId, hostDeviceId);
  const guestWs = await openRawWebSocket(port, roomId, guestDeviceId);

  try {
    // 1. Host creates & joins room over WebSocket
    const hostJoinEnv = createSignedEnvelope({
      roomId,
      type: 'JOIN_REQUEST',
      senderDeviceId: hostDeviceId,
      senderRole: 'HOST',
      sequenceNumber: 1,
      signingSecret: accessKey,
      payloadObj: {
        deviceName: 'Pixel 9 Host',
        hostRoomSnapshot: {
          roomId,
          roomName: 'Cross-Device Sync Room',
          accessKey,
          hostDeviceId,
          mode: 'BROWSE_TOGETHER',
          isSyncActive: true,
          currentSyncedUrl: 'https://example.com/'
        }
      }
    });
    hostWs.sendEnvelope(hostJoinEnv);

    const hostAccepted = await hostWs.nextMessage();
    assert.equal(hostAccepted.type, 'JOIN_ACCEPTED');
    const hostSnap = await hostWs.nextMessage();
    assert.equal(hostSnap.type, 'ROOM_STATE_SNAPSHOT');

    // 2. Guest joins using a signed time-bound invitation token (without raw accessKey)
    const tokenId = 'INV98765432';
    const expiresAt = Date.now() + 600000;
    const tokenHmac = computeInviteTokenHmac(accessKey, roomId, tokenId, expiresAt, true);
    const inviteToken = `${tokenId}.${expiresAt}.1.${tokenHmac}`;

    const guestJoinEnv = createSignedEnvelope({
      roomId,
      type: 'JOIN_REQUEST',
      senderDeviceId: guestDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 1,
      signingSecret: tokenHmac,
      payloadObj: {
        deviceName: 'Galaxy S24 Guest',
        inviteToken
      }
    });
    guestWs.sendEnvelope(guestJoinEnv);

    const guestAccepted = await guestWs.nextMessage();
    assert.equal(guestAccepted.type, 'JOIN_ACCEPTED');
    const guestAcceptedPayload = JSON.parse(guestAccepted.payloadJson);
    assert.equal(guestAcceptedPayload.members.length, 2);

    // Drain ROOM_STATE_SNAPSHOT broadcast on both sockets
    const guestSnap = await guestWs.nextMessage();
    assert.equal(guestSnap.type, 'ROOM_STATE_SNAPSHOT');
    const hostMemberUpdate = await hostWs.nextMessage();
    assert.equal(hostMemberUpdate.type, 'ROOM_STATE_SNAPSHOT');

    // 3. Host navigates to a URL with a sensitive query parameter; Guest receives sanitized URL
    const navEnv = createSignedEnvelope({
      roomId,
      type: 'NAVIGATE_URL',
      senderDeviceId: hostDeviceId,
      senderRole: 'HOST',
      sequenceNumber: 2,
      signingSecret: accessKey,
      payloadObj: {
        url: 'https://kotlinlang.org/docs/home.html?access_token=secret_leak&section= overview',
        title: 'Kotlin Docs'
      }
    });
    hostWs.sendEnvelope(navEnv);

    const guestNav = await guestWs.nextMessage();
    assert.equal(guestNav.type, 'NAVIGATE_URL');
    const guestNavPayload = JSON.parse(guestNav.payloadJson);
    assert.ok(!guestNavPayload.url.includes('access_token'));
    assert.ok(guestNavPayload.url.includes('kotlinlang.org/docs/home.html'));

    // 4. Verify secret redaction helper never leaks tokens or keys
    const redacted = redactSecretsForLog(`JOIN url=https://nyxora.app/connect?room=${roomId}&inv=${inviteToken} key=${accessKey}`);
    assert.ok(!redacted.includes(accessKey));
    assert.ok(!redacted.includes(tokenHmac));
    assert.ok(redacted.includes('[REDACTED]'));
  } finally {
    hostWs.close();
    guestWs.close();
    await server.close();
  }
});
