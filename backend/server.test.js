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

test('NYXORA Backend: Granular Host Permissions (URL input, Seek vs Play/Pause, Volume, Fullscreen, Chat) & Host-Only Config Authority', async () => {
  const server = createNyxoraConnectServer();
  const { httpServer } = server;
  await new Promise((r) => httpServer.listen(0, '127.0.0.1', r));
  const port = httpServer.address().port;

  const roomId = 'NYX-PERM-2826';
  const hostDeviceId = 'host-perm-1';
  const partDeviceId = 'part-perm-2';
  const roomKey = deriveRoomCodeHandshakeKey(roomId);

  const hostWs = await openRawWebSocket(port, roomId, hostDeviceId);
  const partWs = await openRawWebSocket(port, roomId, partDeviceId);

  try {
    // 1. Host creates room
    hostWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'JOIN_REQUEST',
      senderDeviceId: hostDeviceId,
      senderRole: 'HOST',
      sequenceNumber: 1,
      signingSecret: roomKey,
      payloadObj: {
        deviceName: 'Host Controller',
        hostRoomSnapshot: {
          roomId,
          roomName: 'Granular Permissions Room',
          accessKey: roomKey,
          hostDeviceId,
          authorityEpoch: 1,
          mode: 'WATCH_TOGETHER',
          isSyncActive: true,
          currentSyncedUrl: 'https://example.com/watch'
        }
      }
    }));
    assert.equal((await hostWs.nextMessage()).type, 'JOIN_ACCEPTED');
    assert.equal((await hostWs.nextMessage()).type, 'ROOM_STATE_SNAPSHOT');

    // 2. Participant joins room
    partWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'JOIN_REQUEST',
      senderDeviceId: partDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 1,
      signingSecret: roomKey,
      payloadObj: { deviceName: 'Participant Viewer' }
    }));
    assert.equal((await partWs.nextMessage()).type, 'JOIN_ACCEPTED');
    assert.equal((await partWs.nextMessage()).type, 'ROOM_STATE_SNAPSHOT');
    assert.equal((await hostWs.nextMessage()).type, 'ROOM_STATE_SNAPSHOT');

    // 3. Non-host participant cannot update room permissions (HOST_ONLY_ACTION)
    partWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'UPDATE_ROOM_CONFIG',
      senderDeviceId: partDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 2,
      signingSecret: roomKey,
      payloadObj: {
        permissions: { allowParticipantControls: false }
      }
    }));
    const hostOnlyDenied = await partWs.nextMessage();
    assert.equal(hostOnlyDenied.type, 'PERMISSION_DENIED');
    assert.equal(JSON.parse(hostOnlyDenied.payloadJson).code, 'HOST_ONLY_ACTION');

    // 4. Host disables ONLY Video Seek while keeping Play/Pause enabled
    hostWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'UPDATE_ROOM_CONFIG',
      senderDeviceId: hostDeviceId,
      senderRole: 'HOST',
      sequenceNumber: 2,
      signingSecret: roomKey,
      payloadObj: {
        permissions: {
          allowParticipantControls: true,
          allowParticipantVideoPlayPause: true,
          allowParticipantVideoSeek: false,
          allowParticipantVolumeControl: false,
          allowParticipantFullscreenControl: false,
          allowParticipantUrlInput: false,
          allowParticipantNavigationSync: true,
          allowParticipantChat: false,
          allowParticipantPlaybackControl: true
        }
      }
    }));
    const hostCfgSnap = await hostWs.nextMessage();
    const partCfgSnap = await partWs.nextMessage();
    assert.equal(hostCfgSnap.type, 'ROOM_STATE_SNAPSHOT');
    const updatedPerms = JSON.parse(partCfgSnap.payloadJson).permissions;
    assert.equal(updatedPerms.allowParticipantControls, true, 'Master switch must remain true when individual permissions are toggled');
    assert.equal(updatedPerms.allowParticipantVideoPlayPause, true);
    assert.equal(updatedPerms.allowParticipantVideoSeek, false);
    assert.equal(updatedPerms.allowParticipantChat, false);
    assert.equal(updatedPerms.allowParticipantUrlInput, false);

    // 5. Participant can Play/Pause video (controlActionType: PLAY_PAUSE)
    partWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'PLAYBACK_ACTION',
      senderDeviceId: partDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 3,
      signingSecret: roomKey,
      payloadObj: {
        pageUrl: 'https://example.com/watch',
        isPlaying: true,
        positionSeconds: 10.0,
        durationSeconds: 120.0,
        playbackRate: 1.0,
        controlActionType: 'PLAY_PAUSE'
      }
    }));
    const hostPlayReceived = await hostWs.nextMessage();
    const partPlayEcho = await partWs.nextMessage();
    assert.equal(hostPlayReceived.type, 'PLAYBACK_ACTION');
    assert.equal(partPlayEcho.type, 'PLAYBACK_ACTION');

    // 6. Participant cannot Seek video (controlActionType: SEEK -> VIDEO_SEEK_DISABLED)
    partWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'PLAYBACK_ACTION',
      senderDeviceId: partDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 4,
      signingSecret: roomKey,
      payloadObj: {
        pageUrl: 'https://example.com/watch',
        isPlaying: true,
        positionSeconds: 45.0,
        durationSeconds: 120.0,
        playbackRate: 1.0,
        controlActionType: 'SEEK'
      }
    }));
    const seekDenied = await partWs.nextMessage();
    assert.equal(seekDenied.type, 'PERMISSION_DENIED');
    assert.equal(JSON.parse(seekDenied.payloadJson).code, 'VIDEO_SEEK_DISABLED');

    // 7. Participant cannot control Volume or Fullscreen when disabled
    partWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'PLAYBACK_ACTION',
      senderDeviceId: partDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 5,
      signingSecret: roomKey,
      payloadObj: {
        pageUrl: 'https://example.com/watch',
        isPlaying: true,
        positionSeconds: 10.0,
        durationSeconds: 120.0,
        volumeLevel: 0.5,
        controlActionType: 'VOLUME'
      }
    }));
    const volDenied = await partWs.nextMessage();
    assert.equal(volDenied.type, 'PERMISSION_DENIED');
    assert.equal(JSON.parse(volDenied.payloadJson).code, 'VOLUME_CONTROL_DISABLED');

    partWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'PLAYBACK_ACTION',
      senderDeviceId: partDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 6,
      signingSecret: roomKey,
      payloadObj: {
        pageUrl: 'https://example.com/watch',
        isPlaying: true,
        positionSeconds: 10.0,
        durationSeconds: 120.0,
        isFullscreen: true,
        controlActionType: 'FULLSCREEN'
      }
    }));
    const fsDenied = await partWs.nextMessage();
    assert.equal(fsDenied.type, 'PERMISSION_DENIED');
    assert.equal(JSON.parse(fsDenied.payloadJson).code, 'FULLSCREEN_CONTROL_DISABLED');

    // 8. Participant cannot change URL via address bar input (isUrlInput: true -> URL_INPUT_DISABLED)
    partWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'NAVIGATE_URL',
      senderDeviceId: partDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 7,
      signingSecret: roomKey,
      payloadObj: {
        url: 'https://example.com/new-search',
        title: 'Search',
        isUrlInput: true
      }
    }));
    const urlInputDenied = await partWs.nextMessage();
    assert.equal(urlInputDenied.type, 'PERMISSION_DENIED');
    assert.equal(JSON.parse(urlInputDenied.payloadJson).code, 'URL_INPUT_DISABLED');

    // 9. Participant cannot send chat while allowParticipantChat is false, but room stays connected
    partWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'CHAT_MESSAGE',
      senderDeviceId: partDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 8,
      signingSecret: roomKey,
      payloadObj: {
        messageId: 'msg-blocked-chat',
        senderName: 'Participant Viewer',
        text: 'Can I chat?'
      }
    }));
    const chatDenied = await partWs.nextMessage();
    assert.equal(chatDenied.type, 'PERMISSION_DENIED');
    assert.equal(JSON.parse(chatDenied.payloadJson).code, 'CHAT_DISABLED');

    // Verify room is still connected and Host can still send chat
    hostWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'CHAT_MESSAGE',
      senderDeviceId: hostDeviceId,
      senderRole: 'HOST',
      sequenceNumber: 3,
      signingSecret: roomKey,
      payloadObj: {
        messageId: 'msg-host-announcement',
        senderName: 'Host Controller',
        text: 'Host announcement works while participant chat is muted'
      }
    }));
    assert.equal((await hostWs.nextMessage()).type, 'CHAT_MESSAGE');
    assert.equal((await partWs.nextMessage()).type, 'CHAT_MESSAGE');
  } finally {
    partWs.close();
    hostWs.close();
    await server.close();
  }
});

test('NYXORA Connect Backend: Live Mirror session state, WebRTC offer/answer/ICE signaling, and Host-routed MIRROR_REMOTE_CONTROL', async () => {
  const server = createNyxoraConnectServer();
  const { httpServer } = server;
  await new Promise((r) => httpServer.listen(0, '127.0.0.1', r));
  const port = httpServer.address().port;

  const roomId = 'NYX-M7R2-9K4W';
  const roomKey = deriveRoomCodeHandshakeKey(roomId);
  const hostDeviceId = 'device-mirror-host';
  const viewerDeviceId = 'device-mirror-viewer';

  const hostWs = await openRawWebSocket(port, roomId, hostDeviceId);
  const viewerWs = await openRawWebSocket(port, roomId, viewerDeviceId);

  try {
    hostWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'JOIN_REQUEST',
      senderDeviceId: hostDeviceId,
      senderRole: 'HOST',
      sequenceNumber: 1,
      signingSecret: roomKey,
      payloadObj: {
        deviceName: 'Mirror Host',
        hostRoomSnapshot: {
          roomId,
          roomName: 'Live Mirror Room',
          accessKey: roomKey,
          hostDeviceId,
          mode: 'BROWSE_TOGETHER',
          isSyncActive: true,
          currentSyncedUrl: 'https://example.com/stream',
          permissions: {
            allowParticipantControls: false,
            allowParticipantPlaybackControl: true,
            allowParticipantVideoPlayPause: true,
            allowParticipantVideoSeek: false
          }
        }
      }
    }));
    assert.equal((await hostWs.nextMessage()).type, 'JOIN_ACCEPTED');
    assert.equal((await hostWs.nextMessage()).type, 'ROOM_STATE_SNAPSHOT');

    viewerWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'JOIN_REQUEST',
      senderDeviceId: viewerDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 1,
      signingSecret: roomKey,
      payloadObj: {
        deviceName: 'Mirror Viewer'
      }
    }));
    assert.equal((await viewerWs.nextMessage()).type, 'JOIN_ACCEPTED');
    assert.equal((await viewerWs.nextMessage()).type, 'ROOM_STATE_SNAPSHOT');
    assert.equal((await hostWs.nextMessage()).type, 'ROOM_STATE_SNAPSHOT');

    // 1. Non-host cannot start Live Mirror
    viewerWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'MIRROR_SESSION_STATE',
      senderDeviceId: viewerDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 2,
      signingSecret: roomKey,
      payloadObj: {
        sessionState: 'MIRROR_LIVE',
        hostDeviceId,
        hostDeviceName: 'Mirror Host'
      }
    }));
    const mirrorDenied = await viewerWs.nextMessage();
    assert.equal(mirrorDenied.type, 'PERMISSION_DENIED');
    assert.equal(JSON.parse(mirrorDenied.payloadJson).code, 'HOST_ONLY_MIRROR');

    // 2. Host starts Live Mirror (MIRROR_LIVE)
    hostWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'MIRROR_SESSION_STATE',
      senderDeviceId: hostDeviceId,
      senderRole: 'HOST',
      sequenceNumber: 2,
      signingSecret: roomKey,
      payloadObj: {
        sessionState: 'MIRROR_LIVE',
        hostDeviceId,
        hostDeviceName: 'Mirror Host',
        isAudioShared: true,
        qualityProfile: 'BALANCED',
        activeStreamUrl: 'https://example.com/stream',
        startedAtMillis: Date.now()
      }
    }));
    const hostMirrorMsg = await hostWs.nextMessage();
    const viewerMirrorMsg = await viewerWs.nextMessage();
    assert.equal(hostMirrorMsg.type, 'MIRROR_SESSION_STATE');
    assert.equal(viewerMirrorMsg.type, 'MIRROR_SESSION_STATE');
    assert.equal(JSON.parse(viewerMirrorMsg.payloadJson).sessionState, 'MIRROR_LIVE');
    assert.equal((await hostWs.nextMessage()).type, 'ROOM_STATE_SNAPSHOT');
    assert.equal((await viewerWs.nextMessage()).type, 'ROOM_STATE_SNAPSHOT');

    // 3. Viewer notifies WEBRTC_VIEWER_READY -> routed to Host
    viewerWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'WEBRTC_VIEWER_READY',
      senderDeviceId: viewerDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 3,
      signingSecret: roomKey,
      payloadObj: {
        targetDeviceId: hostDeviceId,
        senderDeviceId: viewerDeviceId,
        qualityProfile: 'BALANCED'
      }
    }));
    const readyOnHost = await hostWs.nextMessage();
    assert.equal(readyOnHost.type, 'WEBRTC_VIEWER_READY');
    assert.equal(JSON.parse(readyOnHost.payloadJson).senderDeviceId, viewerDeviceId);

    // 4. Host sends WEBRTC_SIGNAL_OFFER -> routed to Viewer
    hostWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'WEBRTC_SIGNAL_OFFER',
      senderDeviceId: hostDeviceId,
      senderRole: 'HOST',
      sequenceNumber: 3,
      signingSecret: roomKey,
      payloadObj: {
        targetDeviceId: viewerDeviceId,
        senderDeviceId: hostDeviceId,
        sdpType: 'offer',
        sdpDescription: 'v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=NYXORA\r\n'
      }
    }));
    const offerOnViewer = await viewerWs.nextMessage();
    assert.equal(offerOnViewer.type, 'WEBRTC_SIGNAL_OFFER');
    assert.equal(JSON.parse(offerOnViewer.payloadJson).sdpType, 'offer');

    // 5. Viewer sends WEBRTC_SIGNAL_ANSWER and WEBRTC_SIGNAL_ICE_CANDIDATE -> routed to Host
    viewerWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'WEBRTC_SIGNAL_ANSWER',
      senderDeviceId: viewerDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 4,
      signingSecret: roomKey,
      payloadObj: {
        targetDeviceId: hostDeviceId,
        senderDeviceId: viewerDeviceId,
        sdpType: 'answer',
        sdpDescription: 'v=0\r\no=- 2 2 IN IP4 127.0.0.1\r\ns=NYXORA-VIEWER\r\n'
      }
    }));
    const answerOnHost = await hostWs.nextMessage();
    assert.equal(answerOnHost.type, 'WEBRTC_SIGNAL_ANSWER');

    viewerWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'WEBRTC_SIGNAL_ICE_CANDIDATE',
      senderDeviceId: viewerDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 5,
      signingSecret: roomKey,
      payloadObj: {
        targetDeviceId: hostDeviceId,
        senderDeviceId: viewerDeviceId,
        sdpMid: '0',
        sdpMLineIndex: 0,
        candidate: 'candidate:1 1 UDP 2122260223 192.168.1.10 54321 typ host'
      }
    }));
    const iceOnHost = await hostWs.nextMessage();
    assert.equal(iceOnHost.type, 'WEBRTC_SIGNAL_ICE_CANDIDATE');

    // 6. Viewer attempts MIRROR_REMOTE_CONTROL while allowParticipantControls is false -> rejected
    viewerWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'MIRROR_REMOTE_CONTROL',
      senderDeviceId: viewerDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 6,
      signingSecret: roomKey,
      payloadObj: {
        actionType: 'PLAY'
      }
    }));
    const remoteCtrlDenied = await viewerWs.nextMessage();
    assert.equal(remoteCtrlDenied.type, 'PERMISSION_DENIED');
    assert.equal(JSON.parse(remoteCtrlDenied.payloadJson).code, 'PARTICIPANT_CONTROLS_DISABLED');

    // 7. Host enables allowParticipantControls (with Play/Pause allowed, Seek disabled)
    hostWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'UPDATE_ROOM_CONFIG',
      senderDeviceId: hostDeviceId,
      senderRole: 'HOST',
      sequenceNumber: 4,
      signingSecret: roomKey,
      payloadObj: {
        permissions: {
          allowParticipantControls: true,
          allowParticipantPlaybackControl: true,
          allowParticipantVideoPlayPause: true,
          allowParticipantVideoSeek: false
        }
      }
    }));
    await hostWs.nextMessage();
    await viewerWs.nextMessage();

    // Viewer sends MIRROR_REMOTE_CONTROL PLAY -> routed to Host
    viewerWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'MIRROR_REMOTE_CONTROL',
      senderDeviceId: viewerDeviceId,
      senderRole: 'PARTICIPANT',
      sequenceNumber: 7,
      signingSecret: roomKey,
      payloadObj: {
        actionType: 'PLAY'
      }
    }));
    const remotePlayOnHost = await hostWs.nextMessage();
    assert.equal(remotePlayOnHost.type, 'MIRROR_REMOTE_CONTROL');
    assert.equal(JSON.parse(remotePlayOnHost.payloadJson).actionType, 'PLAY');
  } finally {
    viewerWs.close();
    hostWs.close();
    await server.close();
  }
});

test('GET /api/webrtc/ice-config enforces room HMAC auth and issues short-lived TURN credentials when configured', async () => {
  const prevTurnUrls = process.env.NYXORA_WEBRTC_TURN_URLS;
  const prevSharedSecret = process.env.NYXORA_WEBRTC_TURN_SHARED_SECRET;
  const prevStunUrls = process.env.NYXORA_WEBRTC_STUN_URLS;

  process.env.NYXORA_WEBRTC_STUN_URLS = 'stun:stun.l.google.com:19302, stun:stun1.l.google.com:19302';
  process.env.NYXORA_WEBRTC_TURN_URLS = 'turn:turn.nyxora.example.com:3478?transport=udp,turns:turn.nyxora.example.com:5349?transport=tcp';
  process.env.NYXORA_WEBRTC_TURN_SHARED_SECRET = 'test-coturn-rest-shared-secret';

  const server = createNyxoraConnectServer();
  const { httpServer } = server;
  await new Promise((r) => httpServer.listen(0, '127.0.0.1', r));
  const port = httpServer.address().port;
  const httpBaseUrl = `http://127.0.0.1:${port}`;

  const roomId = 'NYX-ICE9-8K2M';
  const roomKey = deriveRoomCodeHandshakeKey(roomId);
  const hostDeviceId = 'dev_ice_host';
  const hostWs = await openRawWebSocket(port, roomId, hostDeviceId);

  try {
    hostWs.sendEnvelope(createSignedEnvelope({
      roomId,
      type: 'JOIN_REQUEST',
      senderDeviceId: hostDeviceId,
      senderRole: 'HOST',
      sequenceNumber: 1,
      signingSecret: roomKey,
      payloadObj: {
        deviceName: 'ICE Host',
        hostRoomSnapshot: {
          roomId,
          roomName: 'ICE Config Room',
          accessKey: roomKey,
          hostDeviceId,
          mode: 'BROWSE_TOGETHER',
          isSyncActive: true,
          currentSyncedUrl: 'https://example.com'
        }
      }
    }));
    assert.equal((await hostWs.nextMessage()).type, 'JOIN_ACCEPTED');
    assert.equal((await hostWs.nextMessage()).type, 'ROOM_STATE_SNAPSHOT');

    // 1. Unauthorized request with invalid signature returns 401
    const unauthRes = await fetch(`${httpBaseUrl}/api/webrtc/ice-config`, {
      headers: {
        'X-Nyxora-Room': roomId,
        'X-Nyxora-Device': hostDeviceId,
        'X-Nyxora-Timestamp': String(Date.now()),
        'X-Nyxora-Signature': 'invalid-signature'
      }
    });
    assert.equal(unauthRes.status, 401);

    // 2. Authorized request with valid room HMAC returns short-lived TURN credentials
    const timestamp = String(Date.now());
    const signInput = `NYXORA_TURN_CRED_V1|${roomId}|${hostDeviceId}|${timestamp}`;
    const signature = crypto
      .createHmac('sha256', Buffer.from(roomKey, 'utf8'))
      .update(signInput, 'utf8')
      .digest('hex');

    const authRes = await fetch(`${httpBaseUrl}/api/webrtc/ice-config`, {
      headers: {
        'X-Nyxora-Room': roomId,
        'X-Nyxora-Device': hostDeviceId,
        'X-Nyxora-Timestamp': timestamp,
        'X-Nyxora-Signature': signature
      }
    });
    assert.equal(authRes.status, 200);
    const body = await authRes.json();
    assert.deepEqual(body.stunUrls, [
      'stun:stun.l.google.com:19302',
      'stun:stun1.l.google.com:19302'
    ]);
    assert.deepEqual(body.turnUrls, [
      'turn:turn.nyxora.example.com:3478?transport=udp',
      'turns:turn.nyxora.example.com:5349?transport=tcp'
    ]);
    assert.equal(body.turnConfigured, true);
    assert.ok(body.turnUsername.endsWith(`:${roomId}:${hostDeviceId}`));
    assert.ok(typeof body.turnCredential === 'string' && body.turnCredential.length > 10);
    assert.ok(body.ttlSeconds >= 60);
    assert.ok(body.expiresAtMillis > Date.now());
  } finally {
    hostWs.close();
    await server.close();
    if (prevTurnUrls === undefined) delete process.env.NYXORA_WEBRTC_TURN_URLS;
    else process.env.NYXORA_WEBRTC_TURN_URLS = prevTurnUrls;
    if (prevSharedSecret === undefined) delete process.env.NYXORA_WEBRTC_TURN_SHARED_SECRET;
    else process.env.NYXORA_WEBRTC_TURN_SHARED_SECRET = prevSharedSecret;
    if (prevStunUrls === undefined) delete process.env.NYXORA_WEBRTC_STUN_URLS;
    else process.env.NYXORA_WEBRTC_STUN_URLS = prevStunUrls;
  }
});
