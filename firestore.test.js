const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";

const OWNER_UID = "owner_admin_1";
const ALICE_UID = "alice_member_1";
const BOB_UID = "bob_member_2";

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

async function seedOwnerAdmin() {
  const ts = new Date();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(OWNER_UID).set({
      userId: OWNER_UID,
      createdAt: ts,
    });
  });
}

function ownerCtx() {
  return testEnv.authenticatedContext(OWNER_UID, {
    email: "admin@example.com",
    email_verified: true,
  });
}

function spoofAdminCtx() {
  return testEnv.authenticatedContext("spoof_uid", {
    email: "admin@example.com",
    email_verified: true,
  });
}

function aliceCtx() {
  return testEnv.authenticatedContext(ALICE_UID, {
    email: "alice@example.com",
    email_verified: true,
  });
}

function bobCtx() {
  return testEnv.authenticatedContext(BOB_UID, {
    email: "bob@example.com",
    email_verified: true,
  });
}

test("1. Unauthenticated caller cannot read accounts", async () => {
  const db = testEnv.unauthenticatedContext().firestore();
  await assertFails(db.collection("accounts").doc(ALICE_UID).get());
});

test("2. Member can self-register only as pending member, not owner or approved", async () => {
  const db = aliceCtx().firestore();
  const ts = new Date();
  await assertFails(
    db.collection("accounts").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice@example.com",
      displayName: "Alice",
      accountRole: "owner",
      accessStatus: "approved",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    db.collection("accounts").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice@example.com",
      displayName: "Alice",
      accountRole: "member",
      accessStatus: "pending",
      deviceModel: "Pixel 9 (Android 16)",
      cityRegion: "Dhaka",
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

test("3. Member cannot escalate accessStatus on own account or write /admins", async () => {
  const ts = new Date();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("accounts").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice@example.com",
      displayName: "Alice",
      accountRole: "member",
      accessStatus: "pending",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const db = aliceCtx().firestore();
  await assertFails(
    db.collection("accounts").doc(ALICE_UID).update({
      accessStatus: "approved",
      updatedAt: new Date(),
    })
  );
  await assertFails(
    db.collection("admins").doc(ALICE_UID).set({
      userId: ALICE_UID,
      createdAt: ts,
    })
  );
});

test("4. PII Blanket Test: Non-owner cannot read another user's account", async () => {
  const ts = new Date();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("accounts").doc(BOB_UID).set({
      userId: BOB_UID,
      email: "bob@example.com",
      displayName: "Bob",
      accountRole: "member",
      accessStatus: "approved",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const db = aliceCtx().firestore();
  await assertFails(db.collection("accounts").doc(BOB_UID).get());
});

test("5. Admin Spoofing Test: User not in /admins/{uid} cannot list accounts", async () => {
  const db = spoofAdminCtx().firestore();
  await assertFails(db.collection("accounts").get());
});

test("6. Ghost Field Shadow Update Test: Extra fields rejected", async () => {
  const db = aliceCtx().firestore();
  const ts = new Date();
  await assertFails(
    db.collection("accounts").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice@example.com",
      displayName: "Alice",
      accountRole: "member",
      accessStatus: "pending",
      isVerified: true,
      createdAt: ts,
      updatedAt: ts,
    })
  );
});

test("7. Registered Owner Admin can list accounts, approve members, create invitations, and write immutable audit logs", async () => {
  await seedOwnerAdmin();
  const ts = new Date();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("accounts").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice@example.com",
      displayName: "Alice",
      accountRole: "member",
      accessStatus: "pending",
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const db = ownerCtx().firestore();
  await assertSucceeds(db.collection("admins").doc(OWNER_UID).get());
  await assertSucceeds(db.collection("accounts").get());
  await assertSucceeds(
    db.collection("accounts").doc(ALICE_UID).update({
      accessStatus: "approved",
      updatedAt: new Date(),
    })
  );

  await assertSucceeds(
    db.collection("invitations").doc("inv_alice").set({
      invitedEmail: "alice@example.com",
      invitedByUid: OWNER_UID,
      note: "Close Family",
      status: "invited",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  await assertSucceeds(
    db.collection("audit_logs").doc("log_1").set({
      actorUid: OWNER_UID,
      action: "USER_APPROVED",
      targetSummary: "Approved Alice into private circle",
      createdAt: ts,
    })
  );

  // Audit log immutability
  await assertFails(
    db.collection("audit_logs").doc("log_1").update({
      action: "TAMPERED",
    })
  );
  await assertFails(db.collection("audit_logs").doc("log_1").delete());
});

test("8. Feature Requests: Member can query own requests ONLY with requesterUid filter", async () => {
  const ts = new Date();
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("feature_requests").doc("req_alice").set({
      requesterUid: ALICE_UID,
      requesterName: "Alice",
      title: "Sylheti Voice Shortcut",
      description: "Add quick phrase shortcut for Sylheti dialect greetings.",
      recommendedSkill: "Regional Dialect Pack",
      status: "submitted",
      requiresPlayUpdate: false,
      createdAt: ts,
      updatedAt: ts,
    });
  });

  const db = aliceCtx().firestore();
  await assertFails(db.collection("feature_requests").get());
  await assertSucceeds(
    db.collection("feature_requests").where("requesterUid", "==", ALICE_UID).get()
  );
});

test("9. MasterBootstrap: First authenticated user can claim write-once master_owner; takeover and delete are blocked", async () => {
  const ts = new Date();
  const ownerDb = ownerCtx().firestore();

  // Cannot claim on behalf of another UID
  await assertFails(
    ownerDb.collection("system_bootstrap").doc("master_owner").set({
      ownerUid: ALICE_UID,
      createdAt: ts,
    })
  );

  // Valid write-once self-claim succeeds
  await assertSucceeds(
    ownerDb.collection("system_bootstrap").doc("master_owner").set({
      ownerUid: OWNER_UID,
      createdAt: ts,
    })
  );

  // Subsequent overwrite or delete by another user or even owner fails (immutable)
  const aliceDb = aliceCtx().firestore();
  await assertFails(
    aliceDb.collection("system_bootstrap").doc("master_owner").set({
      ownerUid: ALICE_UID,
      createdAt: ts,
    })
  );
  await assertFails(
    ownerDb.collection("system_bootstrap").doc("master_owner").delete()
  );
});

test("10. CustomerRemoteConfig: Claimed Master Owner can publish live config; Customers can read but NEVER write", async () => {
  const ts = new Date();
  const ownerDb = ownerCtx().firestore();
  await assertSucceeds(
    ownerDb.collection("system_bootstrap").doc("master_owner").set({
      ownerUid: OWNER_UID,
      createdAt: ts,
    })
  );

  const validConfig = {
    configVersion: 2,
    announcementBanner: "নতুন ফিচার যুক্ত হয়েছে",
    forcedThemePalette: "CYBER_CYAN",
    defaultAutoVoice: true,
    customSystemPrompt: "Reply concisely in Bangla",
    enabledFeatureFlags: "ALL_DISPLAY_VOICE,MAPS_NAVIGATION,PHOTO_VISION",
    lastAppliedFixSummary: "Applied voice optimization",
    updatedByUid: OWNER_UID,
    createdAt: ts,
    updatedAt: ts,
  };

  // Non-owner Alice cannot publish CustomerRemoteConfig
  const aliceDb = aliceCtx().firestore();
  await assertFails(
    aliceDb.collection("customer_configs").doc("global").set({
      ...validConfig,
      updatedByUid: ALICE_UID,
    })
  );

  // Claimed Master Owner publishes live config successfully
  await assertSucceeds(
    ownerDb.collection("customer_configs").doc("global").set(validConfig)
  );

  // Authenticated Customer Alice can read live config in real time
  await assertSucceeds(
    aliceDb.collection("customer_configs").doc("global").get()
  );

  // Unauthenticated caller cannot read config
  await assertFails(
    testEnv.unauthenticatedContext().firestore().collection("customer_configs").doc("global").get()
  );
});

test("11. GestureTransferSession: Sender creates grabbed transfer, paired Receiver marks received, unauthenticated/tampered writes fail", async () => {
  const ts = new Date();
  const aliceDb = aliceCtx().firestore();
  const bobDb = bobCtx().firestore();

  await assertFails(
    testEnv.unauthenticatedContext().firestore().collection("gesture_transfers").doc("tr_1").get()
  );

  // Spoofed senderUid fails
  await assertFails(
    aliceDb.collection("gesture_transfers").doc("tr_1").set({
      senderUid: BOB_UID,
      pairingCode: "SHUVO24",
      fileName: "report.pdf",
      mimeType: "application/pdf",
      payloadBase64: "SGVsbG8=",
      fileSizeBytes: 5,
      status: "grabbed",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Valid fist-grab upload succeeds
  await assertSucceeds(
    aliceDb.collection("gesture_transfers").doc("tr_1").set({
      senderUid: ALICE_UID,
      pairingCode: "SHUVO24",
      fileName: "report.pdf",
      mimeType: "application/pdf",
      payloadBase64: "SGVsbG8=",
      fileSizeBytes: 5,
      status: "grabbed",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Paired receiver queries active grabbed session by pairingCode + status
  await assertSucceeds(
    bobDb
      .collection("gesture_transfers")
      .where("pairingCode", "==", "SHUVO24")
      .where("status", "==", "grabbed")
      .get()
  );

  // Receiver cannot tamper with payloadBase64
  await assertFails(
    bobDb.collection("gesture_transfers").doc("tr_1").update({
      payloadBase64: "VGFtcGVyZWQ=",
      status: "received",
      updatedAt: new Date(),
    })
  );

  // Receiver open-palm drop marks status = 'received'
  await assertSucceeds(
    bobDb.collection("gesture_transfers").doc("tr_1").update({
      status: "received",
      updatedAt: new Date(),
    })
  );
});

test("12. BirthdayWishEntry: Strict per-user isolation under /users/{userId}/birthdays/{birthdayId}", async () => {
  const ts = new Date();
  const aliceDb = aliceCtx().firestore();
  const bobDb = bobCtx().firestore();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("birthdays").doc("bday_rahim").set({
      userId: ALICE_UID,
      personName: "রহিম",
      birthDay: 4,
      birthMonth: 11,
      phoneOrContact: "+8801711000000",
      wishMessage: "শুভ জন্মদিন রহিম! অনেক শুভকামনা।",
      lastWishedYear: 0,
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Owner can list and update lastWishedYear
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("birthdays").get()
  );
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("birthdays").doc("bday_rahim").update({
      lastWishedYear: 2026,
      updatedAt: new Date(),
    })
  );

  // Cross-user Bob cannot read or modify Alice's birthdays
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("birthdays").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("birthdays").doc("bday_rahim").delete()
  );
});

test("13. SubscriptionTier & Global Pricing: Members cannot self-upgrade to paid; Owner can toggle subscriptionTier and pricing fields", async () => {
  await seedOwnerAdmin();
  const ts = new Date();
  const ownerDb = ownerCtx().firestore();
  const aliceDb = aliceCtx().firestore();

  await assertSucceeds(
    ownerDb.collection("system_bootstrap").doc("master_owner").set({
      ownerUid: OWNER_UID,
      createdAt: ts,
    })
  );

  // Member cannot self-register with subscriptionTier = 'paid'
  await assertFails(
    aliceDb.collection("accounts").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice@example.com",
      displayName: "Alice",
      accountRole: "member",
      accessStatus: "pending",
      subscriptionTier: "paid",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Member can self-register with subscriptionTier = 'free'
  await assertSucceeds(
    aliceDb.collection("accounts").doc(ALICE_UID).set({
      userId: ALICE_UID,
      email: "alice@example.com",
      displayName: "Alice",
      accountRole: "member",
      accessStatus: "pending",
      subscriptionTier: "free",
      createdAt: ts,
      updatedAt: ts,
    })
  );

  // Member cannot escalate own subscriptionTier to 'paid'
  await assertFails(
    aliceDb.collection("accounts").doc(ALICE_UID).update({
      subscriptionTier: "paid",
      updatedAt: new Date(),
    })
  );

  // Owner can approve and toggle Alice's subscriptionTier to 'paid'
  await assertSucceeds(
    ownerDb.collection("accounts").doc(ALICE_UID).update({
      accessStatus: "approved",
      subscriptionTier: "paid",
      updatedAt: new Date(),
    })
  );

  // Owner can publish pricing & limit config
  await assertSucceeds(
    ownerDb.collection("customer_configs").doc("global").set({
      configVersion: 3,
      announcementBanner: "লাইভ প্রাইসিং সিঙ্ক",
      forcedThemePalette: "CYBER_CYAN",
      defaultAutoVoice: true,
      customSystemPrompt: "Reply concisely",
      enabledFeatureFlags: "ALL_DISPLAY_VOICE,OFFICE_SUITE",
      lastAppliedFixSummary: "Updated pricing",
      monthlyPaidFeeBdt: 349,
      freeDailyLimit: 30,
      globalPaidModeEnabled: true,
      updatedByUid: OWNER_UID,
      createdAt: ts,
      updatedAt: ts,
    })
  );
});


