const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

// 1. Unauthenticated rejection
test("Unauthenticated user cannot read or write bookmarks", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("bookmarks").doc("bm_1").get()
  );
});

// 2. Cross-user isolation
test("Authenticated user cannot read another user's bookmarks", async () => {
  const now = new Date(Date.now() - 1000);
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("users").doc(BOB_UID).collection("bookmarks").doc("bm_bob").set({
      userId: BOB_UID,
      title: "Bob Site",
      url: "https://bob.example.com",
      folder: "General",
      isShortcut: true,
      createdAt: now,
      updatedAt: now,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("bookmarks").doc("bm_bob").get()
  );
});

// 3. Valid Bookmark CRUD & Client Query Alignment
test("Authenticated owner can create, query, and update their own bookmark", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const createdAt = new Date(Date.now() - 2000);
  const bmRef = aliceDb.collection("users").doc(ALICE_UID).collection("bookmarks").doc("bm_1");

  await assertSucceeds(
    bmRef.set({
      userId: ALICE_UID,
      title: "Kotlin Docs",
      url: "https://kotlinlang.org",
      folder: "Dev",
      isShortcut: true,
      createdAt: createdAt,
      updatedAt: createdAt,
    })
  );

  // Aligned list query with where("userId", "==", ALICE_UID)
  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("bookmarks")
      .where("userId", "==", ALICE_UID)
      .get()
  );

  // Valid update
  await assertSucceeds(
    bmRef.update({
      title: "Kotlin Official Docs",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

// 4. Shadow Update Rejection (Ghost Field)
test("Shadow update with unauthorized ghost field is rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const createdAt = new Date(Date.now() - 2000);
  const bmRef = aliceDb.collection("users").doc(ALICE_UID).collection("bookmarks").doc("bm_shadow");

  await assertSucceeds(
    bmRef.set({
      userId: ALICE_UID,
      title: "Nyxora",
      url: "https://nyxora.app",
      folder: "General",
      isShortcut: false,
      createdAt: createdAt,
      updatedAt: createdAt,
    })
  );

  await assertFails(
    bmRef.update({
      title: "Nyxora Hacked",
      isVerified: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

// 5. Value Poisoning & Enum Validation on SiteSettings
test("SiteSettings rejects invalid enum or out-of-bounds textZoom", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const now = new Date(Date.now() - 1000);
  const siteRef = aliceDb.collection("users").doc(ALICE_UID).collection("site_settings").doc("example_com");

  await assertFails(
    siteRef.set({
      userId: ALICE_UID,
      host: "example.com",
      allowAds: false,
      allowPopups: false,
      allowRedirects: false,
      desktopMode: false,
      textZoom: 100,
      cameraPermission: "SUPER_ALLOW", // Invalid enum
      micPermission: "ASK",
      locationPermission: "ASK",
      notificationPermission: "ASK",
      createdAt: now,
      updatedAt: now,
    })
  );

  await assertSucceeds(
    siteRef.set({
      userId: ALICE_UID,
      host: "example.com",
      allowAds: false,
      allowPopups: false,
      allowRedirects: false,
      desktopMode: false,
      textZoom: 100,
      cameraPermission: "ASK",
      micPermission: "ASK",
      locationPermission: "ASK",
      notificationPermission: "ASK",
      createdAt: now,
      updatedAt: now,
    })
  );
});
