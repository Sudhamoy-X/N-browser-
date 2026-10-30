'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const net = require('node:net');
const crypto = require('node:crypto');
const {
  createNyxoraConnectServer,
  deriveRoomCodeHandshakeKey,
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

test('NYXORA Connect Backend: Code-only participant join, invalid room codes, non-existent room rejection, reconnection, and URL sync', async () => {
  const server = createNyxoraConnectServer();
  const { httpServer } = server;
  await new Promise((r) => httpServer.listen(0, '127.0.0.1', r));
  const port = httpServer.address().port;

  const roomId = 'NYX-6M4P-8R2T';
  const roomCodeDerivedKey = deriveRoomCodeHandshakeKey(roomId);
  assert.match(roomCodeDerivedKey, /^[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}$/);

  const hostDeviceId = 'host-dev-100';
  const participantDeviceId = 'participant-dev-200';

  const hostWs = await openRawWebSocket(port, roomId, hostDeviceId);
  const participantWs = await openRawWebSocket(port, roomId, participantDeviceId);
  const badWs = await openRawWebSocket(port, 'NYX-9999-8888', 'bad-dev-300');

  try {
    // 1. Joining a non-existent room returns ROOM_NOT_FOUND
    const nonExistentJoin = createSignedEnvelope({
      roomId: 'NYX-9999-8888',
      type: 'JOIN_REQUEST',
      senderDeviceId: 'bad-dev-300',
      senderRole: 'PARTICIPANT',
      sequenceNumber: 1,
      signingSecret: deriveRoomCodeHandshakeKey('NYX-9999-8888'),
      payloadObj: { deviceName: 'Unknown Room Joiner' }
    });
    badWs.sendEnvelope(nonExistentJoin);
    const notFoundReject = await badWs.nextMessage();
    assert.equal(notFoundReject.type, 'JOIN_REJECTED');
    assert.equal(JSON.parse(notFoundReject.payloadJson).code, 'ROOM_NOT_FOUND');

    // 2. Joining with an invalid room code format returns INVALID_ROOM_ID
    const invalidFormatJoin = createSignedEnvelope({
      roomId: 'BAD-CODE',
      type: 'JOIN_REQUEST',
      senderDeviceId: 'bad-dev-300',
      senderRole: 'PARTICIPANT',
      sequenceNumber: 2,
      signingSecret: 'ANY-KEY',
      payloadObj: { deviceName: 'Invalid Code Joiner' }
    });
    badWs.sendEnvelope(invalidFormatJoin);
    const invalidReject = await badWs.nextMessage();
    assert.equal(invalidReject.type, 'JOIN_REJECTED');
    assert.equal(JSON.parse(invalidReject.payloadJson).code, 'INVALID_ROOM_ID');

    // 3. Host creates room using Room Code derived key
    const hostJoin = createSignedEnvelope({
      roomId,
      type: 'JOIN_REQUEST',
      senderDeviceId: hostDeviceId,
      senderRole: 'HOST',
      sequenceNumber: 1,
      signingSecret: roomCodeDerivedKey,
      payloadObj: {
        deviceName: 'Host Device',
        hostRoomSnapshot: {
          roomId,
          roomName: 'Code-Only Room',
          accessKey: roomCodeDerivedKey,
          hostDeviceId,
          mode: 'BROWSE_TOGETHER',
          isSyncActive: true,
          currentSyncedUrl: 'https://developer.android.com/'
        }
      }
    });
    hostWs.sendEnvelope(hostJoin);
    assert.equal((await hostWs.nextMessage()).type, 'JOIN_ACCEPTED');
    assert.equal((await hostWs.nextMessage()).type, 'ROOM_STATE_SNAPSHOT');

    // 4. Participant joins using ONLY the Room Code (no inviteToken or manual key entry)
    const participantJoin = createSignedEnvelope({
      roomId,
      type: 'JOIN_REQUEST',
      senderDeviceId: participantDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 1,
      signingSecret: deriveRoomCodeHandshakeKey(roomId),
      payloadObj: {
        deviceName: 'Participant Device'
      }
    });
    participantWs.sendEnvelope(participantJoin);

    const partAccepted = await participantWs.nextMessage();
    assert.equal(partAccepted.type, 'JOIN_ACCEPTED');
    const acceptedSnapshot = JSON.parse(partAccepted.payloadJson);
    assert.equal(acceptedSnapshot.members.length, 2);
    assert.equal(acceptedSnapshot.currentSyncedUrl, 'https://developer.android.com/');

    // Drain snapshots
    assert.equal((await participantWs.nextMessage()).type, 'ROOM_STATE_SNAPSHOT');
    assert.equal((await hostWs.nextMessage()).type, 'ROOM_STATE_SNAPSHOT');

    // 5. Reconnect participant on a new socket and verify duplicate session replacement (members count stays 2)
    const reconnectedWs = await openRawWebSocket(port, roomId, participantDeviceId);
    try {
      const reconnectJoin = createSignedEnvelope({
        roomId,
        type: 'JOIN_REQUEST',
        senderDeviceId: participantDeviceId,
        senderRole: 'PARTICIPANT',
        sequenceNumber: 2,
        signingSecret: deriveRoomCodeHandshakeKey(roomId),
        payloadObj: { deviceName: 'Participant Device' }
      });
      reconnectedWs.sendEnvelope(reconnectJoin);
      const reconnAccepted = await reconnectedWs.nextMessage();
      assert.equal(reconnAccepted.type, 'JOIN_ACCEPTED');
      assert.equal(JSON.parse(reconnAccepted.payloadJson).members.length, 2);
      assert.equal((await reconnectedWs.nextMessage()).type, 'ROOM_STATE_SNAPSHOT');
      assert.equal((await hostWs.nextMessage()).type, 'ROOM_STATE_SNAPSHOT');

      // 6. Participant broadcasts URL navigation back to Host
      const partNav = createSignedEnvelope({
        roomId,
        type: 'NAVIGATE_URL',
        senderDeviceId: participantDeviceId,
        senderRole: 'PARTICIPANT',
        sequenceNumber: 3,
        signingSecret: deriveRoomCodeHandshakeKey(roomId),
        payloadObj: {
          url: 'https://www.wikipedia.org/',
          title: 'Wikipedia'
        }
      });
      reconnectedWs.sendEnvelope(partNav);
      const hostReceivedNav = await hostWs.nextMessage();
      assert.equal(hostReceivedNav.type, 'NAVIGATE_URL');
      assert.equal(JSON.parse(hostReceivedNav.payloadJson).url, 'https://www.wikipedia.org/');
      const echoedPartNav = await reconnectedWs.nextMessage();
      assert.equal(echoedPartNav.type, 'NAVIGATE_URL');

      // 7. Unified mode: Host broadcasts PLAYBACK_ACTION in the same room and Participant receives it
      const hostPlayback = createSignedEnvelope({
        roomId,
        type: 'PLAYBACK_ACTION',
        senderDeviceId: hostDeviceId,
        senderRole: 'HOST',
        sequenceNumber: 2,
        signingSecret: roomCodeDerivedKey,
        payloadObj: {
          pageUrl: 'https://www.wikipedia.org/',
          isPlaying: true,
          positionSeconds: 42.5,
          durationSeconds: 180.0,
          playbackRate: 1.0,
          updatedAtEpochMs: Date.now()
        }
      });
      hostWs.sendEnvelope(hostPlayback);
      const partReceivedPlayback = await reconnectedWs.nextMessage();
      assert.equal(partReceivedPlayback.type, 'PLAYBACK_ACTION');
      assert.equal(JSON.parse(partReceivedPlayback.payloadJson).positionSeconds, 42.5);

      // 8. Reconnect when payload carries an inviteToken but frame is signed with room accessKey (fixes INVITE_UNAUTHORIZED bug)
      const tokenReconnectWs = await openRawWebSocket(port, roomId, participantDeviceId);
      try {
        const tokenId = 'INV55556666';
        const expiresAt = Date.now() + 600000;
        const tokenHmac = computeInviteTokenHmac(roomCodeDerivedKey, roomId, tokenId, expiresAt, false);
        const inviteToken = `${tokenId}.${expiresAt}.0.${tokenHmac}`;
        const mixedReconnectJoin = createSignedEnvelope({
          roomId,
          type: 'JOIN_REQUEST',
          senderDeviceId: participantDeviceId,
          senderRole: 'PARTICIPANT',
          sequenceNumber: 4,
          signingSecret: roomCodeDerivedKey,
          payloadObj: {
            deviceName: 'Participant Device',
            inviteToken
          }
        });
        tokenReconnectWs.sendEnvelope(mixedReconnectJoin);
        const mixedAccepted = await tokenReconnectWs.nextMessage();
        assert.equal(mixedAccepted.type, 'JOIN_ACCEPTED');
      } finally {
        tokenReconnectWs.close();
      }

      // 9. Temporary Host socket drop (e.g., Host switches apps to share Room Code) must NOT delete the room
      hostWs.close();
      await new Promise((r) => setTimeout(r, 80));
      assert.equal(server.rooms.has(roomId), true, 'Room must remain alive when Host socket temporarily drops');

      // New participant joins by Room Code while Host is temporarily disconnected
      const secondGuestId = 'guest-device-9999';
      const secondGuestWs = await openRawWebSocket(port, roomId, secondGuestId);
      try {
        const guestJoin = createSignedEnvelope({
          roomId,
          type: 'JOIN_REQUEST',
          senderDeviceId: secondGuestId,
          senderRole: 'PARTICIPANT',
          sequenceNumber: 1,
          signingSecret: deriveRoomCodeHandshakeKey(roomId),
          payloadObj: {
            deviceName: 'Second Guest Phone'
          }
        });
        secondGuestWs.sendEnvelope(guestJoin);
        const guestAccepted = await secondGuestWs.nextMessage();
        assert.equal(guestAccepted.type, 'JOIN_ACCEPTED');

        // Host reconnects and restores session
        const hostReconnectWs = await openRawWebSocket(port, roomId, hostDeviceId);
        try {
          const hostRejoin = createSignedEnvelope({
            roomId,
            type: 'JOIN_REQUEST',
            senderDeviceId: hostDeviceId,
            senderRole: 'HOST',
            sequenceNumber: 10,
            signingSecret: roomCodeDerivedKey,
            payloadObj: {
              deviceName: 'Host Device'
            }
          });
          hostReconnectWs.sendEnvelope(hostRejoin);
          const hostRejoinedMsg = await hostReconnectWs.nextMessage();
          assert.equal(hostRejoinedMsg.type, 'JOIN_ACCEPTED');
        } finally {
          hostReconnectWs.close();
        }
      } finally {
        secondGuestWs.close();
      }
    } finally {
      reconnectedWs.close();
    }
  } finally {
    badWs.close();
    participantWs.close();
    hostWs.close();
    await server.close();
  }
});

test('NYXORA Backend: real-time chat, secret redaction, member verification, and Allow Participant Controls switch', async () => {
  const server = createNyxoraConnectServer();
  const { httpServer } = server;
  await new Promise((r) => httpServer.listen(0, '127.0.0.1', r));
  const port = httpServer.address().port;

  const roomId = 'NYX-CHAT-7788';
  const hostDeviceId = 'host-chat-1';
  const partDeviceId = 'part-chat-2';
  const outsiderDeviceId = 'outsider-99';
  const roomKey = deriveRoomCodeHandshakeKey(roomId);

  const hostWs = await openRawWebSocket(port, roomId, hostDeviceId);
  const partWs = await openRawWebSocket(port, roomId, partDeviceId);
  const outsiderWs = await openRawWebSocket(port, roomId, outsiderDeviceId);

  try {
    // 1. Host creates room (shared controls enabled by default)
    hostWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'JOIN_REQUEST',
      senderDeviceId: hostDeviceId,
      senderRole: 'HOST',
      sequenceNumber: 1,
      signingSecret: roomKey,
      payloadObj: {
        deviceName: 'Host Alpha',
        hostRoomSnapshot: {
          roomId,
          roomName: 'Chat & Controls Room',
          accessKey: roomKey,
          hostDeviceId,
          authorityEpoch: 1,
          mode: 'BROWSE_TOGETHER',
          isSyncActive: true,
          currentSyncedUrl: 'https://example.com',
          currentSyncedTitle: 'Example'
        }
      }
    }));
    const hostAccepted = await hostWs.nextMessage();
    assert.equal(hostAccepted.type, 'JOIN_ACCEPTED');
    const initialSnap = JSON.parse(hostAccepted.payloadJson);
    assert.equal(initialSnap.permissions.allowParticipantControls, true);
    const hostInitialBroadcastSnap = await hostWs.nextMessage();
    assert.equal(hostInitialBroadcastSnap.type, 'ROOM_STATE_SNAPSHOT');

    // 2. Participant joins room
    partWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'JOIN_REQUEST',
      senderDeviceId: partDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 1,
      signingSecret: roomKey,
      payloadObj: { deviceName: 'Participant Beta' }
    }));
    const partAccepted = await partWs.nextMessage();
    assert.equal(partAccepted.type, 'JOIN_ACCEPTED');
    const partJoinSnap = await partWs.nextMessage();
    assert.equal(partJoinSnap.type, 'ROOM_STATE_SNAPSHOT');
    const hostJoinSnap = await hostWs.nextMessage();
    assert.equal(hostJoinSnap.type, 'ROOM_STATE_SNAPSHOT');

    // 3. Unauthenticated outsider attempting to send chat or control events is rejected with NOT_ROOM_MEMBER
    outsiderWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'CHAT_MESSAGE',
      senderDeviceId: outsiderDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 1,
      signingSecret: roomKey,
      payloadObj: {
        messageId: 'msg-outsider-1',
        senderName: 'Outsider',
        text: 'Unauthorized message'
      }
    }));
    const outsiderRejected = await outsiderWs.nextMessage();
    assert.equal(outsiderRejected.type, 'JOIN_REJECTED');
    assert.equal(JSON.parse(outsiderRejected.payloadJson).code, 'NOT_ROOM_MEMBER');

    // 4. Real-time chat with automatic secret redaction
    partWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'CHAT_MESSAGE',
      senderDeviceId: partDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 2,
      signingSecret: roomKey,
      payloadObj: {
        messageId: 'msg-part-1',
        senderName: 'Participant Beta',
        text: `Hello Host! My room key is ${roomKey} and token INV12345678.999999.0.abcdef1234567890abcdef`
      }
    }));

    const hostChatFrame = await hostWs.nextMessage();
    const partChatEcho = await partWs.nextMessage();
    assert.equal(hostChatFrame.type, 'CHAT_MESSAGE');
    assert.equal(partChatEcho.type, 'CHAT_MESSAGE');
    const chatPayload = JSON.parse(hostChatFrame.payloadJson);
    assert.equal(chatPayload.messageId, 'msg-part-1');
    assert.equal(chatPayload.senderName, 'Participant Beta');
    assert.equal(chatPayload.text.includes(roomKey), false, 'Room access key must never be exposed in chat messages');
    assert.equal(chatPayload.text.includes('INV12345678'), false, 'Invitation token must never be exposed in chat messages');
    assert.equal(chatPayload.text.includes('Hello Host!'), true);

    // 5. Host disables Allow Participant Controls -> Server immediately enforces it
    hostWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'UPDATE_ROOM_CONFIG',
      senderDeviceId: hostDeviceId,
      senderRole: 'HOST',
      sequenceNumber: 2,
      signingSecret: roomKey,
      payloadObj: {
        permissions: {
          allowParticipantControls: false,
          allowParticipantPlaybackControl: false,
          allowParticipantNavigationSync: false,
          syncUrlNavigation: true,
          syncBackForward: true,
          syncTabSelection: true,
          syncNewlyOpenedPages: true,
          maxParticipants: 8
        }
      }
    }));
    const hostPermSnap = await hostWs.nextMessage();
    const partPermSnap = await partWs.nextMessage();
    assert.equal(hostPermSnap.type, 'ROOM_STATE_SNAPSHOT');
    assert.equal(partPermSnap.type, 'ROOM_STATE_SNAPSHOT');
    assert.equal(JSON.parse(partPermSnap.payloadJson).permissions.allowParticipantControls, false);

    // 6. Participant attempts NAVIGATE_URL and PLAYBACK_ACTION while disabled -> Server rejects with PERMISSION_DENIED
    partWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'NAVIGATE_URL',
      senderDeviceId: partDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 3,
      signingSecret: roomKey,
      payloadObj: { url: 'https://blocked-by-host.example.com', title: 'Blocked' }
    }));
    const navDenied = await partWs.nextMessage();
    assert.equal(navDenied.type, 'PERMISSION_DENIED');
    assert.equal(JSON.parse(navDenied.payloadJson).code, 'PARTICIPANT_CONTROLS_DISABLED');

    partWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'PLAYBACK_ACTION',
      senderDeviceId: partDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 4,
      signingSecret: roomKey,
      payloadObj: {
        pageUrl: 'https://example.com/video.mp4',
        isPlaying: true,
        positionSeconds: 88.0,
        durationSeconds: 120.0,
        playbackRate: 1.0
      }
    }));
    const playbackDenied = await partWs.nextMessage();
    assert.equal(playbackDenied.type, 'PERMISSION_DENIED');
    assert.equal(JSON.parse(playbackDenied.payloadJson).code, 'PARTICIPANT_CONTROLS_DISABLED');

    // 7. Host re-enables Allow Participant Controls -> Participant controls restored without reconnecting
    hostWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'UPDATE_ROOM_CONFIG',
      senderDeviceId: hostDeviceId,
      senderRole: 'HOST',
      sequenceNumber: 3,
      signingSecret: roomKey,
      payloadObj: {
        permissions: {
          allowParticipantControls: true,
          allowParticipantPlaybackControl: true,
          allowParticipantNavigationSync: true
        }
      }
    }));
    await hostWs.nextMessage();
    const partReenabledSnap = await partWs.nextMessage();
    assert.equal(JSON.parse(partReenabledSnap.payloadJson).permissions.allowParticipantControls, true);

    partWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'NAVIGATE_URL',
      senderDeviceId: partDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 5,
      signingSecret: roomKey,
      payloadObj: { url: 'https://restored-controls.example.com', title: 'Restored' }
    }));
    const hostNavAfterRestore = await hostWs.nextMessage();
    const partNavAfterRestore = await partWs.nextMessage();
    assert.equal(hostNavAfterRestore.type, 'NAVIGATE_URL');
    assert.equal(JSON.parse(hostNavAfterRestore.payloadJson).url, 'https://restored-controls.example.com/');
    assert.equal(partNavAfterRestore.type, 'NAVIGATE_URL');
  } finally {
    outsiderWs.close();
    partWs.close();
    hostWs.close();
    await server.close();
  }
});




