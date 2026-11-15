// Register PWA Service Worker (only on HTTP/HTTPS)
if ('serviceWorker' in navigator && window.location.protocol.startsWith('http')) {
  navigator.serviceWorker.register('./sw.js').catch(() => {});
}

// Pure-JS SHA-256 (works over HTTPS and local HTTP alike without exposing plaintext secrets)
function sha256Hex(ascii) {
  function rightRotate(value, amount) {
    return (value >>> amount) | (value << (32 - amount));
  }
  const maxWord = Math.pow(2, 32);
  let result = '';
  const words = [];
  const utf8 = unescape(encodeURIComponent(ascii));
  const asciiBitLength = utf8.length * 8;
  const hash = [];
  const k = [];
  let primeCounter = 0;
  const isComposite = {};
  for (let candidate = 2; primeCounter < 64; candidate++) {
    if (!isComposite[candidate]) {
      for (let i = 0; i < 313; i += candidate) isComposite[i] = candidate;
      hash[primeCounter] = (Math.pow(candidate, 0.5) * maxWord) | 0;
      k[primeCounter++] = (Math.pow(candidate, 1 / 3) * maxWord) | 0;
    }
  }
  let msg = utf8 + '\x80';
  while ((msg.length % 64) - 56) msg += '\x00';
  for (let i = 0; i < msg.length; i++) {
    const j = msg.charCodeAt(i);
    words[i >> 2] |= j << (((3 - i) % 4) * 8);
  }
  words[words.length] = (asciiBitLength / maxWord) | 0;
  words[words.length] = asciiBitLength | 0;

  for (let j = 0; j < words.length;) {
    const w = words.slice(j, (j += 16));
    const oldHash = hash.slice(0);
    for (let i = 0; i < 64; i++) {
      const w15 = w[i - 15], w2 = w[i - 2];
      const a = hash[0], e = hash[4];
      const temp1 =
        hash[7] +
        (rightRotate(e, 6) ^ rightRotate(e, 11) ^ rightRotate(e, 25)) +
        ((e & hash[5]) ^ (~e & hash[6])) +
        k[i] +
        (w[i] = i < 16
          ? w[i]
          : (w[i - 16] +
              (rightRotate(w15, 7) ^ rightRotate(w15, 18) ^ (w15 >>> 3)) +
              w[i - 7] +
              (rightRotate(w2, 17) ^ rightRotate(w2, 19) ^ (w2 >>> 10))) | 0);
      const temp2 =
        (rightRotate(a, 2) ^ rightRotate(a, 13) ^ rightRotate(a, 22)) +
        ((a & hash[1]) ^ (a & hash[2]) ^ (hash[1] & hash[2]));
      hash[7] = hash[6]; hash[6] = hash[5]; hash[5] = hash[4];
      hash[4] = (hash[3] + temp1) | 0;
      hash[3] = hash[2]; hash[2] = hash[1]; hash[1] = hash[0];
      hash[0] = (temp1 + temp2) | 0;
    }
    for (let i = 0; i < 8; i++) hash[i] = (hash[i] + oldHash[i]) | 0;
  }
  for (let i = 0; i < 8; i++) {
    for (let j = 3; j + 1; j--) {
      const b = (hash[i] >> (j * 8)) & 255;
      result += (b < 16 ? '0' : '') + b.toString(16);
    }
  }
  return result;
}

const MASTER_SALT = 'hs_master_v1:';
const DEFAULT_PASS_HASH = '0871fb5a426289e29058e9bb21ed510a5619793a00dc8431898ac08ad16cf158';
const AUTHORIZED_EMAIL_HASHES = [
  '2beaa7b8e478a610cea627b683e367aa512814ee9a29d7a803aa193d19ace903',
  '54e81fdb47761ae088a02a9521cc714b6adea0a44ab554ccbe6776cd7b47508a',
  'd62e40d387ca93cc2530601b100eb181c47291f7226a1de73e54c3eedc9e510f',
  '7ac9a28960122df4faa55e00141fa8931489b54fab07e1c5baa772f359cc39c'
];

if (localStorage.getItem('hs_master_password')) {
  const legacyPass = localStorage.getItem('hs_master_password');
  localStorage.setItem('hs_master_pass_hash', sha256Hex(MASTER_SALT + legacyPass));
  localStorage.removeItem('hs_master_password');
}

function getMasterPasswordHash() {
  return localStorage.getItem('hs_master_pass_hash') || DEFAULT_PASS_HASH;
}

// State
const state = {
  activeTab: 'panel-voice',
  autoMode: true,
  isListening: false,
  isSpeaking: false,
  isProcessing: false,
  micPermissionDenied: false,
  voiceProfile: localStorage.getItem('hs_voice_profile') || 'FEMALE',
  femalePhotoIndex: Number(localStorage.getItem('hs_female_photo_idx') || '0') || 0,
  malePhotoIndex: Number(localStorage.getItem('hs_male_photo_idx') || '0') || 0,
  facingMode: 'environment',
  stream: null,
  apiKey: localStorage.getItem('hs_api_key') || '',
  globalPaid: JSON.parse(localStorage.getItem('hs_global_paid') ?? 'true'),
  isPaid: JSON.parse(localStorage.getItem('hs_is_paid') ?? 'false'),
  paidUntil: Number(localStorage.getItem('hs_paid_until') || '0'),
  monthlyFee: Number(localStorage.getItem('hs_monthly_fee') || '299'),
  freeLimit: Number(localStorage.getItem('hs_free_limit') || '25'),
  usageCount: Number(localStorage.getItem('hs_usage_count') || '0'),
  usageDate: localStorage.getItem('hs_usage_date') || '',
  isCloudOwner: false,
  cloudUser: null,
  cloudDb: null,
  cloudFsMod: null,
  currentAvatarItem: null,
  customAvatarDataUrl: localStorage.getItem('hs_custom_avatar_dataurl') || '',
  useCustomAvatar: JSON.parse(localStorage.getItem('hs_use_custom_avatar') ?? 'false'),
  micEngineMode: localStorage.getItem('hs_mic_engine') || 'AUTO',
  speechFailStreak: 0,
  cloudBirthdays: null,
  birthdaysUnsubscribe: null
};

// Elements
const autoModeBtn = document.getElementById('autoModeBtn');
const manualModeBtn = document.getElementById('manualModeBtn');
const manualInputBar = document.getElementById('manualInputBar');
const voiceStatus = document.getElementById('voiceStatus');
const chatBox = document.getElementById('chatBox');
const textCommandInput = document.getElementById('textCommandInput');
const sendTextBtn = document.getElementById('sendTextBtn');
const micTapBtn = document.getElementById('micTapBtn');
const planBadgeBtn = document.getElementById('planBadgeBtn');
const planModal = document.getElementById('planModal');
const closePlanModalBtn = document.getElementById('closePlanModalBtn');
const cloudAuthBtn = document.getElementById('cloudAuthBtn');
const remoteBannerBar = document.getElementById('remoteBannerBar');

// Navigation
document.querySelectorAll('.nav-btn').forEach((btn) => {
  btn.addEventListener('click', () => {
    const targetId = btn.dataset.target;
    state.activeTab = targetId;
    if (targetId !== 'panel-voice' && state.isListening) {
      stopSpeechRecognition();
    }
    if (targetId !== 'panel-camera' && state.stream) {
      state.stream.getTracks().forEach((t) => t.stop());
      state.stream = null;
      if (videoEl) videoEl.srcObject = null;
    }
    document.querySelectorAll('.panel').forEach((p) => p.classList.remove('active'));
    document.querySelectorAll('.nav-btn').forEach((b) => {
      b.classList.remove('btn-primary');
      b.classList.add('btn-outline');
    });
    document.getElementById(targetId).classList.add('active');
    btn.classList.remove('btn-outline');
    btn.classList.add('btn-primary');

    if (targetId === 'panel-voice' && state.autoMode && !state.micPermissionDenied && !state.isSpeaking && !state.isProcessing) {
      setTimeout(startSpeechRecognition, 350);
    }
  });
});

// Avatar Catalog with Per-Photo Mouth Coordinates for Real Lip-Sync
const AVATAR_CATALOG = {
  FEMALE: [
    { src: 'assets/avatars/avatar_female_chloe.webp', name: 'Chloe Android', imgAspect: 0.667, mouthX: 0.49, mouthY: 0.388, mouthW: 0.135 },
    { src: 'assets/avatars/avatar_female_chloe_close.jpg', name: 'Chloe Close-up', imgAspect: 415 / 739, mouthX: 0.54, mouthY: 0.475, mouthW: 0.22 },
    { src: 'assets/avatars/avatar_female_chloe_seated.jpg', name: 'Chloe Seated', imgAspect: 415 / 739, mouthX: 0.39, mouthY: 0.228, mouthW: 0.12 }
  ],
  MALE: [
    { src: 'assets/avatars/avatar_male_connor_rk800.jpg', name: 'Connor RK800', imgAspect: 1.0, mouthX: 0.50, mouthY: 0.455, mouthW: 0.13 },
    { src: 'assets/avatars/avatar_male_connor.jpg', name: 'Connor Android', imgAspect: 637 / 849, mouthX: 0.59, mouthY: 0.485, mouthW: 0.16 },
    { src: 'assets/avatars/avatar_male_connor_smile.jpg', name: 'Connor Smile', imgAspect: 345 / 460, mouthX: 0.58, mouthY: 0.705, mouthW: 0.26 },
    { src: 'assets/avatars/avatar_male_connor_profile.jpg', name: 'Connor Profile', imgAspect: 437 / 702, mouthX: 0.47, mouthY: 0.232, mouthW: 0.085 }
  ],
  DEEP: [
    { src: 'assets/avatars/avatar_male_connor_rk800.jpg', name: 'Connor RK800 (Deep)', imgAspect: 1.0, mouthX: 0.50, mouthY: 0.455, mouthW: 0.13 },
    { src: 'assets/avatars/avatar_male_connor.jpg', name: 'Connor Android (Deep)', imgAspect: 637 / 849, mouthX: 0.59, mouthY: 0.485, mouthW: 0.16 },
    { src: 'assets/avatars/avatar_male_connor_profile.jpg', name: 'Connor Profile (Deep)', imgAspect: 437 / 702, mouthX: 0.47, mouthY: 0.232, mouthW: 0.085 }
  ]
};

let lipSyncRafId = null;

function layoutJawLipSyncPatch() {
  const wrapper = document.getElementById('avatarPhotoWrapper');
  const cavity = document.getElementById('avatarMouthCavity');
  const jawLayer = document.getElementById('avatarJawLayer');
  const item = state.currentAvatarItem;
  if (!wrapper || !cavity || !jawLayer || !item) return;

  const boxW = wrapper.clientWidth || 1;
  const boxH = wrapper.clientHeight || 1;
  const boxAspect = boxW / boxH;

  let drawnW, drawnH, offsetX;
  const offsetY = 0; // matches object-position: center top
  if (boxAspect > item.imgAspect) {
    drawnW = boxW;
    drawnH = boxW / item.imgAspect;
    offsetX = 0;
  } else {
    drawnH = boxH;
    drawnW = boxH * item.imgAspect;
    offsetX = (boxW - drawnW) * 0.5;
  }

  const px = offsetX + item.mouthX * drawnW;
  const py = offsetY + item.mouthY * drawnH;
  const mw = Math.max(12, item.mouthW * drawnW);

  const rx = mw * 0.72;
  const ry = mw * 0.52;
  const jawCenterY = py + ry * 0.92;
  jawLayer.style.clipPath = `ellipse(${rx.toFixed(1)}px ${ry.toFixed(1)}px at ${px.toFixed(1)}px ${jawCenterY.toFixed(1)}px)`;

  const cavW = mw * 0.78;
  cavity.style.left = `${(px - cavW * 0.5).toFixed(1)}px`;
  cavity.style.top = `${(py - 1.5).toFixed(1)}px`;
  cavity.style.width = `${cavW.toFixed(1)}px`;
  cavity._maxDrop = Math.min(12, Math.max(3, mw * 0.16));
}

function startLipSyncLoop() {
  if (lipSyncRafId) return;
  const cavity = document.getElementById('avatarMouthCavity');
  const jawLayer = document.getElementById('avatarJawLayer');
  if (!cavity || !jawLayer) return;

  layoutJawLipSyncPatch();
  cavity.style.display = 'block';
  jawLayer.style.display = 'block';

  const tick = (now) => {
    if (!state.isSpeaking) {
      stopLipSyncLoop();
      return;
    }
    const maxDrop = cavity._maxDrop || 5;
    const waveA = 0.5 + 0.5 * Math.sin(now * 0.022);
    const waveB = 0.5 + 0.5 * Math.sin(now * 0.014 + 1.3);
    const open = Math.max(0, Math.min(1, waveA * 0.65 + waveB * 0.35));
    const dropPx = open * maxDrop;

    jawLayer.style.transform = `translateY(${dropPx.toFixed(2)}px)`;
    cavity.style.height = `${Math.max(2, dropPx * 1.2 + 2).toFixed(2)}px`;
    cavity.style.opacity = open > 0.08 ? String(Math.min(0.95, open * 1.1)) : '0';

    lipSyncRafId = requestAnimationFrame(tick);
  };
  lipSyncRafId = requestAnimationFrame(tick);
}

function stopLipSyncLoop() {
  if (lipSyncRafId) {
    cancelAnimationFrame(lipSyncRafId);
    lipSyncRafId = null;
  }
  const cavity = document.getElementById('avatarMouthCavity');
  const jawLayer = document.getElementById('avatarJawLayer');
  if (jawLayer) {
    jawLayer.style.display = 'none';
    jawLayer.style.transform = 'translateY(0px)';
  }
  if (cavity) {
    cavity.style.display = 'none';
  }
}

window.addEventListener('resize', () => {
  if (state.isSpeaking) layoutJawLipSyncPatch();
});

function updateAvatarUI() {
  const avatarImg = document.getElementById('avatarPhoto');
  const jawImg = document.getElementById('avatarJawImg');
  const personaBadge = document.getElementById('personaModelName');
  const templeLed = document.getElementById('templeLedRing');
  const stageContainer = document.getElementById('avatarStageContainer');
  const speechWave = document.getElementById('speechWave');
  const cyclePhotoBtn = document.getElementById('cyclePhotoBtn');
  const quickSwitchVoiceBtn = document.getElementById('quickSwitchVoiceBtn');
  const resetCustomBtn = document.getElementById('resetCustomAvatarBtn');
  const toggleMicBtn = document.getElementById('toggleMicEngineBtn');
  if (!avatarImg || !personaBadge) return;

  const prof = state.voiceProfile || 'FEMALE';
  const list = AVATAR_CATALOG[prof] || AVATAR_CATALOG.FEMALE;
  const idx = prof === 'FEMALE' ? (state.femalePhotoIndex % list.length) : (state.malePhotoIndex % list.length);
  const hasCustom = Boolean(state.customAvatarDataUrl);
  const usingCustom = hasCustom && Boolean(state.useCustomAvatar);
  const currentItem = usingCustom
    ? {
        src: state.customAvatarDataUrl,
        name: 'Custom Portrait',
        imgAspect: 0.75,
        mouthX: 0.50,
        mouthY: 0.475,
        mouthW: 0.16
      }
    : (list[idx] || list[0]);
  state.currentAvatarItem = currentItem;

  const isSameSrc = usingCustom
    ? avatarImg.src === currentItem.src
    : avatarImg.src.endsWith(currentItem.src);

  if (!isSameSrc) {
    avatarImg.style.opacity = '0.45';
    setTimeout(() => {
      avatarImg.src = currentItem.src;
      avatarImg.alt = currentItem.name;
      avatarImg.style.opacity = '1';
      if (jawImg) jawImg.src = currentItem.src;
      if (state.isSpeaking) layoutJawLipSyncPatch();
    }, 80);
  } else if (jawImg && jawImg.src !== avatarImg.src) {
    jawImg.src = currentItem.src;
  }

  if (state.isSpeaking) {
    personaBadge.textContent = 'কথা বলছে...';
  } else if (state.isListening) {
    personaBadge.textContent = 'শুনছে...';
  } else if (usingCustom) {
    personaBadge.textContent = 'কাস্টম পোর্ট্রেট';
  } else if (prof === 'FEMALE') {
    personaBadge.textContent = 'Chloe Android (নারী)';
  } else if (prof === 'DEEP') {
    personaBadge.textContent = 'Connor RK800 (ডিপ)';
  } else {
    personaBadge.textContent = 'Connor RK800 (পুরুষ)';
  }

  if (cyclePhotoBtn) {
    const totalCount = hasCustom ? list.length + 1 : list.length;
    const displayNum = usingCustom ? totalCount : idx + 1;
    cyclePhotoBtn.textContent = usingCustom ? `কাস্টম 📷 ${displayNum}/${totalCount}` : `ছবি 📷 ${displayNum}/${totalCount}`;
  }
  if (resetCustomBtn) {
    resetCustomBtn.classList.toggle('hidden', !hasCustom);
  }
  if (toggleMicBtn) {
    toggleMicBtn.textContent =
      state.micEngineMode === 'VAD'
        ? '🎙️ VAD'
        : state.micEngineMode === 'SPEECH'
        ? '🎙️ Speech'
        : '🎙️ অটো';
  }
  if (quickSwitchVoiceBtn) {
    quickSwitchVoiceBtn.textContent =
      prof === 'FEMALE' ? 'পুরুষ কণ্ঠ ⇄' : prof === 'MALE' ? 'ডিপ কণ্ঠ ⇄' : 'নারী কণ্ঠ ⇄';
  }

  if (stageContainer) {
    stageContainer.classList.toggle('speaking', state.isSpeaking);
    stageContainer.classList.toggle('listening', !state.isSpeaking && state.isListening);
    stageContainer.classList.toggle('compact', chatBox && chatBox.children.length > 0);
  }

  if (speechWave) {
    speechWave.classList.toggle('hidden', !state.isSpeaking);
  }

  if (templeLed) {
    templeLed.classList.toggle('speaking', state.isSpeaking);
    templeLed.classList.toggle('listening', !state.isSpeaking && state.isListening);
  }

  if (state.isSpeaking) {
    startLipSyncLoop();
  } else {
    stopLipSyncLoop();
  }
}

// Plan & Usage Helpers
function isUserPaidActive() {
  if (!state.globalPaid) return true;
  if (!state.isPaid) return false;
  if (state.paidUntil > 0 && Date.now() > state.paidUntil) {
    state.isPaid = false;
    localStorage.setItem('hs_is_paid', 'false');
    return false;
  }
  return true;
}

function updatePlanUI() {
  const paid = isUserPaidActive();
  const today = new Date().toISOString().slice(0, 10);
  const effectiveUsage = state.usageDate === today ? state.usageCount : 0;
  planBadgeBtn.textContent = paid ? 'PAID' : 'FREE';
  planBadgeBtn.style.color = paid ? 'var(--primary)' : 'var(--secondary)';
  document.getElementById('toggleGlobalPaidBtn').textContent =
    state.globalPaid ? 'গ্লোবাল পেইড: চালু' : 'গ্লোবাল ফ্রি: চালু';
  document.getElementById('tableFreeLimit').textContent = `${state.freeLimit} বার/দিন`;
  document.getElementById('tablePaidFee').textContent = `৳${state.monthlyFee}/মাস`;
  document.getElementById('modalPlanSummary').textContent = paid
    ? 'আপনার বর্তমান প্ল্যান: PAID (সম্পূর্ণ আনলিমিটেড)'
    : `আপনার বর্তমান প্ল্যান: FREE (আজকের ব্যবহার: ${effectiveUsage}/${state.freeLimit})`;
}
updatePlanUI();

function checkAndIncrementUsage() {
  if (isUserPaidActive()) return true;
  const today = new Date().toISOString().slice(0, 10);
  if (state.usageDate !== today) {
    state.usageDate = today;
    state.usageCount = 0;
  }
  if (state.usageCount >= state.freeLimit) {
    const msg = 'আপনার আজকের ফ্রি লিমিট শেষ হয়েছে। আনলিমিটেড ব্যবহারের জন্য Paid প্ল্যানে আপগ্রেড করুন।';
    addChatMessage(msg, 'ai');
    speakText(msg);
    updatePlanUI();
    return false;
  }
  state.usageCount += 1;
  localStorage.setItem('hs_usage_date', state.usageDate);
  localStorage.setItem('hs_usage_count', String(state.usageCount));
  updatePlanUI();
  return true;
}

planBadgeBtn.addEventListener('click', () => {
  updatePlanUI();
  planModal.classList.add('open');
});
closePlanModalBtn.addEventListener('click', () => planModal.classList.remove('open'));

// Text-to-Speech (Sentence chunked + Chrome keep-alive + watchdog + post-speech echo cooldown)
let cachedVoices = [];
let ttsKeepAliveTimer = null;
let ttsSafetyTimer = null;
let postSpeechRestartTimer = null;
let ttsSessionId = 0;

function refreshTtsVoices() {
  if ('speechSynthesis' in window) {
    cachedVoices = window.speechSynthesis.getVoices() || [];
  }
}
refreshTtsVoices();
if ('speechSynthesis' in window && 'onvoiceschanged' in window.speechSynthesis) {
  window.speechSynthesis.onvoiceschanged = refreshTtsVoices;
}

function clearTtsTimers() {
  if (ttsKeepAliveTimer) {
    clearInterval(ttsKeepAliveTimer);
    ttsKeepAliveTimer = null;
  }
  if (ttsSafetyTimer) {
    clearTimeout(ttsSafetyTimer);
    ttsSafetyTimer = null;
  }
  if (postSpeechRestartTimer) {
    clearTimeout(postSpeechRestartTimer);
    postSpeechRestartTimer = null;
  }
  if (typeof vadRestartTimer !== 'undefined' && vadRestartTimer) {
    clearTimeout(vadRestartTimer);
    vadRestartTimer = null;
  }
}

function stopSpeaking() {
  ttsSessionId += 1;
  clearTtsTimers();
  try {
    if ('speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }
  } catch (_) {}
  if (state.isSpeaking) {
    state.isSpeaking = false;
    updateAvatarUI();
  }
}

function splitSpeechChunks(rawText, maxLen = 170) {
  const clean = (rawText || '')
    .replace(/[*_`#]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  if (!clean) return [];
  if (clean.length <= maxLen) return [clean];

  const sentences = clean.split(/(?<=[।.!?])\s+|\n+/);
  const chunks = [];
  let buf = '';

  for (const s of sentences) {
    const part = s.trim();
    if (!part) continue;
    if ((buf + ' ' + part).trim().length <= maxLen) {
      buf = (buf ? buf + ' ' : '') + part;
    } else {
      if (buf) {
        chunks.push(buf);
        buf = '';
      }
      if (part.length <= maxLen) {
        buf = part;
      } else {
        const words = part.split(/\s+/);
        for (const w of words) {
          if ((buf + ' ' + w).trim().length <= maxLen) {
            buf = (buf ? buf + ' ' : '') + w;
          } else {
            if (buf) chunks.push(buf);
            buf = w.slice(0, maxLen);
          }
        }
      }
    }
  }
  if (buf) chunks.push(buf);
  return chunks;
}

function pickVoiceForUtterance(hasBangla) {
  refreshTtsVoices();
  if (!cachedVoices.length) return null;
  const langVoices = cachedVoices.filter((v) => v.lang.toLowerCase().startsWith(hasBangla ? 'bn' : 'en'));
  const candidateVoices = langVoices.length > 0 ? langVoices : cachedVoices;

  if (state.voiceProfile === 'FEMALE') {
    return (
      candidateVoices.find((v) => {
        const n = v.name.toLowerCase();
        return (
          n.includes('female') || n.includes('woman') || n.includes('bnf') ||
          n.includes('tanisha') || n.includes('kalpana') || n.includes('heera') ||
          n.includes('zira') || n.includes('samantha') || n.includes('kavya')
        );
      }) || candidateVoices[0]
    );
  }
  return (
    candidateVoices.find((v) => {
      const n = v.name.toLowerCase();
      return (
        (n.includes('male') && !n.includes('female')) ||
        (n.includes('man') && !n.includes('woman')) ||
        n.includes('bnm') || n.includes('pradeep') || n.includes('bashkar') ||
        n.includes('hemant') || n.includes('prabhat') || n.includes('david') ||
        n.includes('alex') || n.includes('rishi')
      );
    }) || (candidateVoices.length >= 2 ? candidateVoices[candidateVoices.length - 1] : candidateVoices[0])
  );
}

function speakText(text) {
  if (!('speechSynthesis' in window) || !text) return;
  const chunks = splitSpeechChunks(text, 170);
  if (!chunks.length) return;

  stopSpeaking();
  const mySession = ++ttsSessionId;
  state.isSpeaking = true;
  updateAvatarUI();

  // Keep-alive prevents Chromium from silently pausing SpeechSynthesis mid-utterance
  ttsKeepAliveTimer = setInterval(() => {
    if (ttsSessionId !== mySession) return;
    try {
      if (window.speechSynthesis.speaking && !window.speechSynthesis.paused) {
        window.speechSynthesis.pause();
        window.speechSynthesis.resume();
      }
    } catch (_) {}
  }, 8000);

  const finishAll = () => {
    if (ttsSessionId !== mySession) return;
    clearTtsTimers();
    state.isSpeaking = false;
    updateAvatarUI();
    if (state.autoMode && state.activeTab === 'panel-voice' && !state.micPermissionDenied && !state.isProcessing) {
      postSpeechRestartTimer = setTimeout(() => {
        if (state.autoMode && state.activeTab === 'panel-voice' && !state.isSpeaking && !state.isProcessing && !state.micPermissionDenied) {
          startSpeechRecognition();
        }
      }, 550);
    }
  };

  let idx = 0;
  const speakNextChunk = () => {
    if (ttsSessionId !== mySession) return;
    if (idx >= chunks.length) {
      finishAll();
      return;
    }
    const chunkText = chunks[idx++];
    const utter = new SpeechSynthesisUtterance(chunkText);
    const hasBangla = /[\u0980-\u09FF]/.test(chunkText);
    utter.lang = hasBangla ? 'bn-BD' : 'en-US';
    const voice = pickVoiceForUtterance(hasBangla);
    if (voice) utter.voice = voice;

    if (state.voiceProfile === 'FEMALE') {
      utter.pitch = 1.24;
      utter.rate = 1.02;
    } else if (state.voiceProfile === 'MALE') {
      utter.pitch = 0.72;
      utter.rate = 0.94;
    } else {
      utter.pitch = 0.55;
      utter.rate = 0.86;
    }

    let settled = false;
    const safetyMs = Math.min(28000, Math.max(4500, chunkText.length * 160));
    if (ttsSafetyTimer) clearTimeout(ttsSafetyTimer);
    ttsSafetyTimer = setTimeout(() => {
      if (settled || ttsSessionId !== mySession) return;
      settled = true;
      try { window.speechSynthesis.cancel(); } catch (_) {}
      speakNextChunk();
    }, safetyMs);

    utter.onend = () => {
      if (settled || ttsSessionId !== mySession) return;
      settled = true;
      if (ttsSafetyTimer) {
        clearTimeout(ttsSafetyTimer);
        ttsSafetyTimer = null;
      }
      speakNextChunk();
    };
    utter.onerror = () => {
      if (settled || ttsSessionId !== mySession) return;
      settled = true;
      if (ttsSafetyTimer) {
        clearTimeout(ttsSafetyTimer);
        ttsSafetyTimer = null;
      }
      finishAll();
    };

    try {
      window.speechSynthesis.speak(utter);
    } catch (_) {
      finishAll();
    }
  };

  speakNextChunk();
}

// Shared Gemini REST Caller (Gemini 3.8 Flash primary with graceful fallback to 2.5 Flash & 2.5 Flash Lite)
async function callGeminiFlash(parts) {
  const models = ['gemini-3.8-flash', 'gemini-2.5-flash', 'gemini-2.5-flash-lite'];
  let lastErr = 'Unknown API error';

  for (const model of models) {
    const res = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent?key=${encodeURIComponent(state.apiKey)}`,
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ contents: [{ parts }] })
      }
    );
    const data = await res.json().catch(() => ({}));
    if (res.ok) {
      return { ok: true, text: data?.candidates?.[0]?.content?.parts?.[0]?.text || '' };
    }
    lastErr = data?.error?.message || `HTTP ${res.status}`;
    if (res.status !== 404 && !/not found|overloaded|quota/i.test(lastErr)) {
      break;
    }
  }
  return { ok: false, error: lastErr };
}

// Diary Auto Logging
function getDiaryLogs() {
  try {
    return JSON.parse(localStorage.getItem('hs_diary_logs') || '[]');
  } catch {
    return [];
  }
}

function appendDiaryLog(role, text) {
  const toggle = document.getElementById('diaryAutoLogToggle');
  if (toggle && !toggle.checked) return;
  const clean = (text || '').trim();
  if (!clean) return;
  const logs = getDiaryLogs();
  const timeStr = new Date().toLocaleTimeString('bn-BD', { hour: '2-digit', minute: '2-digit' });
  logs.unshift({
    id: Date.now(),
    time: timeStr,
    role,
    text: clean.slice(0, 300)
  });
  localStorage.setItem('hs_diary_logs', JSON.stringify(logs.slice(0, 60)));
  if (typeof renderDiaryLogs === 'function') renderDiaryLogs();
}

function addChatMessage(text, who) {
  chatBox.classList.remove('empty-chat');
  const div = document.createElement('div');
  div.className = `msg ${who}`;
  div.textContent = text;
  chatBox.appendChild(div);
  chatBox.scrollTop = chatBox.scrollHeight;
  updateAvatarUI();
}

// Cross-Browser Voice Capture: SpeechRecognition + MediaRecorder Web Audio VAD Fallback
const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
let recognition = null;
let vadRecorder = null;
let vadStream = null;
let vadAudioCtx = null;
let vadRafId = null;
let vadRestartTimer = null;

function cleanupVadResources() {
  if (vadRafId) {
    cancelAnimationFrame(vadRafId);
    vadRafId = null;
  }
  if (vadRestartTimer) {
    clearTimeout(vadRestartTimer);
    vadRestartTimer = null;
  }
  try {
    if (vadRecorder && vadRecorder.state !== 'inactive') {
      vadRecorder.onstop = null;
      vadRecorder.stop();
    }
  } catch (_) {}
  vadRecorder = null;
  if (vadStream) {
    try { vadStream.getTracks().forEach((t) => t.stop()); } catch (_) {}
    vadStream = null;
  }
  if (vadAudioCtx) {
    try { vadAudioCtx.close(); } catch (_) {}
    vadAudioCtx = null;
  }
}

async function startVadAudioCapture() {
  if (state.activeTab !== 'panel-voice' || state.micPermissionDenied || state.isListening || state.isSpeaking || state.isProcessing) return;
  if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
    voiceStatus.textContent = 'এই ব্রাউজারে মাইক্রোফোন সাপোর্ট নেই — লিখে পাঠান।';
    return;
  }

  cleanupVadResources();
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true }
    });
    if (state.activeTab !== 'panel-voice' || state.isSpeaking || state.isProcessing) {
      stream.getTracks().forEach((t) => t.stop());
      return;
    }
    vadStream = stream;

    const mimeCandidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus'];
    const pickedMime = mimeCandidates.find((m) => MediaRecorder.isTypeSupported?.(m)) || '';
    const recorder = pickedMime ? new MediaRecorder(stream, { mimeType: pickedMime }) : new MediaRecorder(stream);
    vadRecorder = recorder;

    const chunks = [];
    let cancelled = false;
    let hasSpeech = false;
    let speechStartedAt = 0;
    let lastSpeechAt = 0;
    let maxPeakRms = 0;
    let speechFrames = 0;
    let elapsedSinceStart = 0;
    const captureStartedAt = performance.now();

    const AudioCtx = window.AudioContext || window.webkitAudioContext;
    const audioCtx = AudioCtx ? new AudioCtx() : null;
    vadAudioCtx = audioCtx;
    let analyser = null;
    let sampleBuf = null;
    if (audioCtx) {
      const srcNode = audioCtx.createMediaStreamSource(stream);
      analyser = audioCtx.createAnalyser();
      analyser.fftSize = 1024;
      srcNode.connect(analyser);
      sampleBuf = new Float32Array(analyser.fftSize);
    }

    recorder.ondataavailable = (ev) => {
      if (ev.data && ev.data.size > 0) chunks.push(ev.data);
    };

    recorder.onstop = async () => {
      state.isListening = false;
      updateAvatarUI();
      cleanupVadResources();
      if (cancelled) return;

      const blob = new Blob(chunks, { type: recorder.mimeType || pickedMime || 'audio/webm' });
      if (maxPeakRms < 0.002 && elapsedSinceStart >= 2200) {
        voiceStatus.textContent = 'মাইকে অডিও সিগন্যাল আসছে না — ব্রাউজার/ডিভাইস Mic Allow করুন বা লিখে পাঠান।';
      }
      if (!hasSpeech || maxPeakRms < 0.005 || blob.size < 1200) {
        if (state.autoMode && state.activeTab === 'panel-voice' && !state.isSpeaking && !state.isProcessing && !state.micPermissionDenied) {
          vadRestartTimer = setTimeout(startSpeechRecognition, 450);
        }
        return;
      }
      await handleUserAudioCommand(blob, blob.type || 'audio/webm');
    };

    state.isListening = true;
    voiceStatus.textContent = 'শুনছি (VAD অডিও)... কথা বলুন';
    updateAvatarUI();
    recorder.start(180);

    const monitorVad = () => {
      if (!state.isListening || recorder.state !== 'recording') return;
      const now = performance.now();
      const elapsed = now - captureStartedAt;
      elapsedSinceStart = elapsed;

      let rms = 0;
      if (analyser && sampleBuf) {
        analyser.getFloatTimeDomainData(sampleBuf);
        let sum = 0;
        for (let i = 0; i < sampleBuf.length; i++) sum += sampleBuf[i] * sampleBuf[i];
        rms = Math.sqrt(sum / sampleBuf.length);
      }

      if (rms > maxPeakRms) maxPeakRms = rms;
      if (rms > 0.013 && elapsed > 180) {
        speechFrames += 1;
        lastSpeechAt = now;
        if (!hasSpeech && speechFrames >= 2) {
          hasSpeech = true;
          speechStartedAt = now;
        }
      }

      const silentAfterSpeech = hasSpeech && (now - lastSpeechAt >= 1050) && (now - speechStartedAt >= 480);
      const maxUttExceeded = hasSpeech && (now - speechStartedAt >= 12000);
      const noSpeechTimeout = !hasSpeech && elapsed >= 8000;

      if (silentAfterSpeech || maxUttExceeded || noSpeechTimeout) {
        if (noSpeechTimeout) cancelled = false; // triggers silent auto-restart cleanly
        try { recorder.stop(); } catch (_) {}
        return;
      }
      vadRafId = requestAnimationFrame(monitorVad);
    };
    vadRafId = requestAnimationFrame(monitorVad);
  } catch (err) {
    state.isListening = false;
    cleanupVadResources();
    updateAvatarUI();
    if (err?.name === 'NotAllowedError' || err?.name === 'PermissionDeniedError') {
      state.micPermissionDenied = true;
      voiceStatus.textContent = 'মাইক্রোফোন পারমিশন ব্লক করা আছে — অনুগ্রহ করে Allow দিন।';
    } else {
      voiceStatus.textContent = 'মাইক্রোফোন চালু করা যায়নি — লিখে পাঠান।';
    }
  }
}

function shouldUseVadEngine() {
  if (state.micEngineMode === 'VAD') return true;
  if (state.micEngineMode === 'SPEECH') return !SpeechRecognition;
  return !SpeechRecognition || state.speechFailStreak >= 2;
}

function startSpeechRecognition() {
  if (state.activeTab !== 'panel-voice') return;
  if (state.micPermissionDenied) {
    voiceStatus.textContent = 'মাইক্রোফোন পারমিশন ব্লক আছে — ব্রাউজার থেকে Allow করে রিলোড করুন বা লিখে পাঠান।';
    return;
  }
  if (state.isListening || state.isSpeaking || state.isProcessing) return;

  if (shouldUseVadEngine()) {
    startVadAudioCapture();
    return;
  }

  try {
    recognition = new SpeechRecognition();
    recognition.lang = 'bn-BD';
    recognition.interimResults = false;
    recognition.continuous = false;

    recognition.onstart = () => {
      state.isListening = true;
      voiceStatus.textContent = 'শুনছি... কথা বলুন';
      updateAvatarUI();
    };
    recognition.onresult = (e) => {
      state.speechFailStreak = 0;
      state.isListening = false;
      try { recognition.stop(); } catch (_) {}
      updateAvatarUI();
      const transcript = e.results[0][0].transcript.trim();
      if (transcript) handleUserCommand(transcript);
    };
    recognition.onerror = (e) => {
      state.isListening = false;
      updateAvatarUI();
      const errCode = e?.error || '';
      if (errCode === 'not-allowed' || errCode === 'service-not-allowed') {
        state.micPermissionDenied = true;
        voiceStatus.textContent = 'মাইক্রোফোন পারমিশন ব্লক করা আছে — অনুগ্রহ করে Allow দিন।';
        return;
      }
      if (errCode === 'network' || errCode === 'audio-capture' || errCode === 'language-not-supported') {
        state.speechFailStreak += 1;
        if (state.speechFailStreak >= 2 && navigator.mediaDevices?.getUserMedia) {
          startVadAudioCapture();
          return;
        }
      }
      if (state.autoMode && state.activeTab === 'panel-voice' && !state.isSpeaking && !state.isProcessing && !state.micPermissionDenied) {
        setTimeout(startSpeechRecognition, 850);
      }
    };
    recognition.onend = () => {
      state.isListening = false;
      updateAvatarUI();
      if (state.autoMode && state.activeTab === 'panel-voice' && !state.isSpeaking && !state.isProcessing && !state.micPermissionDenied) {
        setTimeout(startSpeechRecognition, 500);
      }
    };
    recognition.start();
  } catch (_) {
    state.speechFailStreak += 1;
    startVadAudioCapture();
  }
}

function stopSpeechRecognition() {
  state.isListening = false;
  cleanupVadResources();
  try { recognition?.stop(); } catch (_) {}
  updateAvatarUI();
}

function switchToAutoMode(announce = true) {
  state.autoMode = true;
  state.micPermissionDenied = false;
  autoModeBtn.className = 'btn-primary';
  autoModeBtn.textContent = '✓ AUTO';
  manualModeBtn.className = 'btn-outline';
  manualModeBtn.textContent = 'MANUAL';
  manualInputBar.classList.add('hidden');
  voiceStatus.textContent = 'অটো মোড চালু — কথা বলুন';
  updateAvatarUI();
  if (announce) speakText('এখন অটো মোড চালু হয়েছে, আপনি কথা বলুন।');
}

function switchToManualMode(announce = true) {
  state.autoMode = false;
  manualModeBtn.className = 'btn-secondary';
  manualModeBtn.textContent = '✓ MANUAL';
  autoModeBtn.className = 'btn-outline';
  autoModeBtn.textContent = 'AUTO';
  manualInputBar.classList.remove('hidden');
  stopSpeechRecognition();
  voiceStatus.textContent = 'ম্যানুয়াল মোড চালু — লিখে বা মাইকে ট্যাপ করে বলুন';
  updateAvatarUI();
  if (announce) speakText('এখন ম্যানুয়াল মোড চালু হয়েছে।');
}

// Mode Switching
autoModeBtn.addEventListener('click', () => {
  if (state.autoMode) return;
  switchToAutoMode(true);
});

manualModeBtn.addEventListener('click', () => {
  if (!state.autoMode) return;
  switchToManualMode(true);
});

function detectRequestedVoiceProfile(rawCmd) {
  const normCmd = rawCmd.trim().toLowerCase()
    .replace(/ভয়েস/g, 'ভয়েস')
    .replace(/কন্ঠ/g, 'কণ্ঠ')
    .replace(/মেয়ে/g, 'মেয়ে');
  if (!normCmd) return null;
  if (
    normCmd.includes('ইমেইল') ||
    normCmd.includes('মেইল') ||
    normCmd.includes('email') ||
    normCmd.includes('gmail') ||
    normCmd.includes('মেসেজ') ||
    normCmd.includes('message') ||
    normCmd.includes('পাঠা') ||
    /\bsend\b/.test(normCmd)
  ) {
    return null;
  }

  const words = normCmd.split(/\s+/).filter(Boolean);
  const hasVoiceCue =
    normCmd.includes('ভয়েস') ||
    normCmd.includes('কণ্ঠ') ||
    normCmd.includes('গলা') ||
    normCmd.includes('সাউন্ড') ||
    /\b(voice|sound|tone|tts|speak)\b/.test(normCmd);

  if (!hasVoiceCue && words.length > 3) return null;

  const isDeep =
    /\bdeep\b/.test(normCmd) ||
    normCmd.includes('ডিপ') ||
    normCmd.includes('গম্ভীর') ||
    (normCmd.includes('ভারী') && hasVoiceCue) ||
    normCmd.includes('ব্যাস কণ্ঠ');
  if (isDeep) return 'DEEP';

  const isFemale =
    /\bfemale\b/.test(normCmd) ||
    normCmd.includes('ফিমেল') ||
    normCmd.includes('ফিমেইল') ||
    (/(নারী|মহিলা|মেয়ে|লেডি)/.test(normCmd) && hasVoiceCue) ||
    normCmd === 'female' || normCmd === 'নারী' || normCmd === 'মেয়ে';
  if (isFemale) return 'FEMALE';

  const isMale =
    /\bmale\b/.test(normCmd) ||
    (/(পুরুষ|ছেলে)/.test(normCmd) && hasVoiceCue) ||
    (normCmd.includes('মেল') && hasVoiceCue) ||
    normCmd === 'male' || normCmd === 'পুরুষ';
  if (isMale) return 'MALE';

  return null;
}

function stripVoiceSwitchPhraseWeb(rawCmd) {
  let cleaned = (rawCmd || '').trim();
  const patterns = [
    /(পুরুষ|ছেলে|নারী|মহিলা|মেয়ে|মেয়ে|লেডি|ফিমেল|ফিমেইল|ডিপ|গম্ভীর|ভারী|ব্যাস|male|female|deep)\s*(ভয়েস|ভয়েস|কণ্ঠ|কন্ঠ|গলা|সাউন্ড|voice|tone|sound)\s*(করো|কর|দাও|দিন|দাও তো|বদলাও|পরিবর্তন করো|সেট করো|এ|তে|দিয়ে|দিয়ে)?/gi,
    /(ভয়েস|ভয়েস|কণ্ঠ|কন্ঠ)\s*(পুরুষ|নারী|মেয়ে|ডিপ|male|female|deep)\s*(করো|কর|দাও|বদলাও)?/gi,
    /^(এবং|আর|তারপর|দয়া করে|দয়া করে|please|and|then)\s+/gi
  ];
  for (const p of patterns) {
    cleaned = cleaned.replace(p, ' ');
  }
  return cleaned.replace(/^[,.\-।!?\s]+|[,.\-।!?\s]+$/g, '').replace(/\s+/g, ' ').trim();
}

function executeWebLocalCommand(rawCmd) {
  const lower = (rawCmd || '').trim().toLowerCase();
  if (!lower) return null;

  // Offline wake words, greetings & assistant identity (zero API key required)
  const cleanWake = lower.replace(/[,.\-।!?\s]+/g, ' ').trim();
  if (/^(hey\s*shuvo|shuvo|হে\s*শুভ|শুভ|hello\s*shuvo|হ্যালো\s*শুভ|শুনছো|শুনছেন|hey\s*shuvo\s*শুনছো)$/.test(cleanWake)) {
    return 'হ্যাঁ, আমি শুনতে পাচ্ছি বস। আপনি বলুন বস।';
  }
  if (/^(তুমি\s*কে|তোমার\s*নাম\s*কি|আপনার\s*নাম\s*কি|who\s*are\s*you|what\s*is\s*your\s*name)$/.test(cleanWake)) {
    return 'আমি Hey Shuvo, আপনার ডিজিটাল অ্যাসিস্ট্যান্ট।';
  }
  if (/^(হ্যালো|হাই|hello|hi|assalamu\s*alaikum|সালাম|আসসালামু\s*আলাইকুম)$/.test(cleanWake)) {
    return 'হ্যালো বস! আমি Hey Shuvo—বলুন, আপনার জন্য কী সাহায্য করতে পারি?';
  }

  // Mode commands
  if (/অটো\s*মোড|auto\s*mode/.test(lower)) {
    switchToAutoMode(false);
    return 'এখন অটো মোড চালু হয়েছে, আপনি কথা বলুন।';
  }
  if (/ম্যানুয়াল\s*মোড|ম্যানুয়াল\s*মোড|manual\s*mode/.test(lower)) {
    switchToManualMode(false);
    return 'এখন ম্যানুয়াল মোড চালু হয়েছে।';
  }

  // Theme switch
  if (lower.includes('থিম') || lower.includes('theme')) {
    const themeMap = [
      { key: 'emerald', match: /সবুজ|emerald|green/ },
      { key: 'gold', match: /সোনালী|গোল্ড|gold|amber|yellow/ },
      { key: 'violet', match: /বেগুনি|ভায়োলেট|violet|purple/ },
      { key: 'cyan', match: /নীল|সায়ান|সায়ান|cyan|blue/ }
    ];
    const hit = themeMap.find((t) => t.match.test(lower));
    if (hit) {
      applyWebTheme(hit.key);
      return `থিম পরিবর্তন করে ${hit.key} করা হয়েছে।`;
    }
  }

  // Quick navigation shortcuts
  if (/ক্যামেরা\s*(খোল|চালু|ওপেন)|open\s*camera/.test(lower)) {
    document.querySelector('.nav-btn[data-target="panel-camera"]')?.click();
    startCamera();
    return 'ক্যামেরা চালু করা হয়েছে।';
  }
  if (/ট্যালি\s*(খাতা)?\s*(খোল|দেখা|যাও)|open\s*tally/.test(lower)) {
    document.querySelector('.nav-btn[data-target="panel-hub"]')?.click();
    document.querySelector('.hub-sub-btn[data-hub="hub-tally"]')?.click();
    return 'ট্যালি খাতা খোলা হয়েছে।';
  }
  if (/বার্থডে|জন্মদিন/.test(lower) && /খোল|দেখা|যাও/.test(lower)) {
    document.querySelector('.nav-btn[data-target="panel-hub"]')?.click();
    document.querySelector('.hub-sub-btn[data-hub="hub-birthday"]')?.click();
    return 'বার্থডে রিমাইন্ডার খোলা হয়েছে।';
  }
  if (/ডায়েরি|ডায়েরি/.test(lower) && /খোল|দেখা|যাও/.test(lower)) {
    document.querySelector('.nav-btn[data-target="panel-hub"]')?.click();
    document.querySelector('.hub-sub-btn[data-hub="hub-diary"]')?.click();
    return 'ভয়েস ডায়েরি খোলা হয়েছে।';
  }
  if (/জরুরি\s*(হেল্পলাইন|সেবা|নম্বর)/.test(lower)) {
    document.querySelector('.nav-btn[data-target="panel-hub"]')?.click();
    document.querySelector('.hub-sub-btn[data-hub="hub-emergency"]')?.click();
    return 'বাংলাদেশের জরুরি হেল্পলাইন ৯৯৯, ৩৩৩, ১৬২৬৩ ও ১০৯ তালিকা খোলা হয়েছে।';
  }

  // Voice Tally entry: supports both Bangla numerals (৫০০) and ASCII numerals (500)
  const normalizedCmd = rawCmd.replace(/[০-৯]/g, (d) => String('০১২৩৪৫৬৭৮৯'.indexOf(d)));
  const tallyNumMatch = normalizedCmd.match(/(\d+(?:\.\d+)?)/);
  if (tallyNumMatch && /(ট্যালি|হিসাব|বাকি|খরচ|আয়|আয়)/.test(lower) && /(লেখ|লিখ|যোগ|রাখ|সেভ|add|save)/.test(lower)) {
    const amount = Number(tallyNumMatch[1]);
    if (amount > 0) {
      const type = /খরচ|expense/.test(lower) ? 'EXPENSE' : /আয়|আয়|income/.test(lower) ? 'INCOME' : 'DUE';
      const customer = rawCmd
        .replace(/ট্যালি\s*খাতায়|ট্যালি\s*খাতায়|ট্যালি|খাতায়|খাতায়|হিসাব|টাকা|লেখো|লিখো|লেখ|লিখ|যোগ\s*করো|সেভ\s*করো|বাকি|খরচ|আয়|আয়|নামে/gi, ' ')
        .replace(tallyNumMatch[1], ' ')
        .replace(/[০-৯]+(?:\.[০-৯]+)?/g, ' ')
        .replace(/\s+/g, ' ')
        .trim()
        .replace(/(?:-এর|ের)$/, '') || 'কাস্টমার';
      const entries = getTallyEntries();
      entries.unshift({
        id: Date.now(),
        customer,
        amount,
        type,
        date: new Date().toLocaleDateString('bn-BD')
      });
      localStorage.setItem('hs_tally_entries', JSON.stringify(entries.slice(0, 100)));
      renderTallyEntries();
      const label = type === 'INCOME' ? 'আয়' : type === 'EXPENSE' ? 'খরচ' : 'বাকি';
      return `${customer}-এর ৳${amount} (${label}) ট্যালি খাতায় সেভ করা হয়েছে।`;
    }
  }

  // Time & Date offline queries
  if (/কয়টা\s*বাজে|কয়টা\s*বাজে|এখন\s*সময়|what\s*time/.test(lower)) {
    const t = new Date().toLocaleTimeString('bn-BD', { hour: '2-digit', minute: '2-digit' });
    return `এখন সময় ${t}।`;
  }
  if (/আজ\s*কি\s*তারিখ|আজকের\s*তারিখ|what\s*date|today'?s?\s*date|কি\s*বার/.test(lower)) {
    const d = new Date().toLocaleDateString('bn-BD', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' });
    return `আজ ${d}।`;
  }

  // Open YouTube / Google search
  if (/ইউটিউব\s*(খোল|ওপেন)|open\s*youtube/.test(lower) && lower.split(/\s+/).length <= 3) {
    window.open('https://www.youtube.com', '_blank');
    return 'ইউটিউব খোলা হচ্ছে।';
  }
  const ytSearch = rawCmd.match(/(.+?)\s*(?:ইউটিউবে|ইউটিউব|youtube)\s*(?:চালাও|সার্চ|দেখাও|search|play)/i);
  if (ytSearch && ytSearch[1].trim()) {
    const q = ytSearch[1].trim();
    window.open(`https://www.youtube.com/results?search_query=${encodeURIComponent(q)}`, '_blank');
    return `"${q}" ইউটিউবে খোঁজা হচ্ছে।`;
  }
  const ggSearch = rawCmd.match(/(.+?)\s*(?:গুগলে|গুগল|google)\s*(?:সার্চ|খোঁজো|দেখাও|search)/i);
  if (ggSearch && ggSearch[1].trim()) {
    const q = ggSearch[1].trim();
    window.open(`https://www.google.com/search?q=${encodeURIComponent(q)}`, '_blank');
    return `"${q}" গুগলে খোঁজা হচ্ছে।`;
  }

  return null;
}

async function handleUserAudioCommand(audioBlob, mimeType) {
  if (state.isProcessing) return;
  if (!state.apiKey) {
    const r = 'VAD অডিও বুঝতে মাস্টার ট্যাব থেকে আপনার Gemini API Key সেভ করুন।';
    voiceStatus.textContent = r;
    addChatMessage(r, 'ai');
    speakText(r);
    return;
  }
  if (!checkAndIncrementUsage()) return;

  state.isProcessing = true;
  voiceStatus.textContent = 'ভয়েস অডিও বিশ্লেষণ হচ্ছে...';
  try {
    const buf = await audioBlob.arrayBuffer();
    const bytes = new Uint8Array(buf);
    let binary = '';
    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
    const base64 = btoa(binary);

    const prompt =
      'Listen carefully to the user audio (Bangla, Banglish, or English). If it is only silence or background noise, reply strictly with SILENT. Otherwise reply strictly in two lines:\nTRANSCRIPT: <exact words user spoke>\nREPLY: <natural, concise plain-text reply in the matching language as Hey Shuvo for speaking aloud with zero markdown>';
    const result = await callGeminiFlash([
      { inlineData: { mimeType: mimeType.split(';')[0] || 'audio/webm', data: base64 } },
      { text: prompt }
    ]);
    if (!result.ok) {
      const errMsg = `Gemini API ত্রুটি: ${result.error}`;
      voiceStatus.textContent = errMsg;
      addChatMessage(errMsg, 'ai');
      if (state.autoMode && state.activeTab === 'panel-voice' && !state.micPermissionDenied) {
        setTimeout(startSpeechRecognition, 900);
      }
      return;
    }
    const raw = (result.text || '').trim();
    if (!raw || /^silent$/i.test(raw)) {
      voiceStatus.textContent = state.autoMode ? 'অটো ভয়েস প্রস্তুত — কথা বলুন...' : 'ম্যানুয়াল মোড সক্রিয়';
      if (state.autoMode && state.activeTab === 'panel-voice' && !state.micPermissionDenied) {
        setTimeout(startSpeechRecognition, 500);
      }
      return;
    }

    const tMatch = raw.match(/TRANSCRIPT:\s*(.+)/i);
    const rMatch = raw.match(/REPLY:\s*([\s\S]+)/i);
    const spokenTranscript = (tMatch ? tMatch[1] : '').trim();
    let finalReply = (rMatch ? rMatch[1] : raw).trim();

    addChatMessage(spokenTranscript ? `🎤 "${spokenTranscript}"` : '🎤 [ভয়েস বার্তা]', 'user');
    appendDiaryLog('আপনি', spokenTranscript || '🎤 [ভয়েস বার্তা]');

    if (spokenTranscript) {
      let effectiveTranscript = spokenTranscript;
      const requestedProfile = detectRequestedVoiceProfile(spokenTranscript);
      if (requestedProfile) {
        state.voiceProfile = requestedProfile;
        localStorage.setItem('hs_voice_profile', requestedProfile);
        updateAvatarUI();
        const remainder = stripVoiceSwitchPhraseWeb(spokenTranscript);
        if (!remainder || remainder.length < 3) {
          finalReply =
            requestedProfile === 'DEEP'
              ? 'জি বস, এখন থেকে আমি গম্ভীর ও ভারী কণ্ঠে কথা বলছি।'
              : requestedProfile === 'MALE'
              ? 'জি বস, এখন থেকে আমি স্পষ্ট পুরুষ কণ্ঠে কথা বলছি।'
              : 'জি বস, এখন থেকে আমি স্পষ্ট নারী কণ্ঠে কথা বলছি।';
          voiceStatus.textContent = state.autoMode ? 'অটো ভয়েস প্রস্তুত — কথা বলুন...' : 'ম্যানুয়াল মোড সক্রিয়';
          addChatMessage(finalReply, 'ai');
          appendDiaryLog('Hey Shuvo', finalReply);
          speakText(finalReply);
          return;
        }
        effectiveTranscript = remainder;
      }

      const localReply = executeWebLocalCommand(effectiveTranscript);
      if (localReply) {
        finalReply = localReply;
      }
    }

    voiceStatus.textContent = state.autoMode ? 'অটো ভয়েস প্রস্তুত — কথা বলুন...' : 'ম্যানুয়াল মোড সক্রিয়';
    addChatMessage(finalReply, 'ai');
    appendDiaryLog('Hey Shuvo', finalReply);
    speakText(finalReply);
  } catch (_) {
    const r = 'ভয়েস বিশ্লেষণে নেটওয়ার্ক সমস্যা হয়েছে, আবার চেষ্টা করুন।';
    voiceStatus.textContent = r;
    addChatMessage(r, 'ai');
    speakText(r);
  } finally {
    state.isProcessing = false;
  }
}

// Command Handling
async function handleUserCommand(cmd) {
  if (state.isProcessing) return;
  addChatMessage(cmd, 'user');
  appendDiaryLog('আপনি', cmd);

  let effectiveCmd = cmd;
  const requestedProfile = detectRequestedVoiceProfile(cmd);
  if (requestedProfile) {
    state.voiceProfile = requestedProfile;
    localStorage.setItem('hs_voice_profile', requestedProfile);
    updateAvatarUI();
    const remainder = stripVoiceSwitchPhraseWeb(cmd);
    if (!remainder || remainder.length < 3) {
      const reply =
        requestedProfile === 'DEEP'
          ? 'জি বস, এখন থেকে আমি গম্ভীর ও ভারী কণ্ঠে কথা বলছি।'
          : requestedProfile === 'MALE'
          ? 'জি বস, এখন থেকে আমি স্পষ্ট পুরুষ কণ্ঠে কথা বলছি।'
          : 'জি বস, এখন থেকে আমি স্পষ্ট নারী কণ্ঠে কথা বলছি।';
      addChatMessage(reply, 'ai');
      speakText(reply);
      return;
    }
    effectiveCmd = remainder;
  }

  const localReply = executeWebLocalCommand(effectiveCmd);
  if (localReply) {
    addChatMessage(localReply, 'ai');
    appendDiaryLog('Hey Shuvo', localReply);
    speakText(localReply);
    return;
  }

  if (!state.apiKey) {
    const r = 'দয়া করে মাস্টার ট্যাব থেকে আপনার Gemini API Key সেভ করুন।';
    addChatMessage(r, 'ai');
    speakText(r);
    return;
  }

  if (!checkAndIncrementUsage()) return;

  state.isProcessing = true;
  if (state.isListening) stopSpeechRecognition();
  voiceStatus.textContent = 'উত্তর তৈরি হচ্ছে...';
  try {
    const result = await callGeminiFlash([
      { text: `You are Hey Shuvo, a warm, natural, knowledgeable Bangla/English AI companion. Reply concisely and naturally for speaking aloud without markdown symbols: ${effectiveCmd}` }
    ]);
    if (!result.ok) {
      const errMsg = `Gemini API ত্রুটি: ${result.error}`;
      voiceStatus.textContent = errMsg;
      addChatMessage(errMsg, 'ai');
      if (state.autoMode && state.activeTab === 'panel-voice' && !state.micPermissionDenied) {
        setTimeout(startSpeechRecognition, 900);
      }
      return;
    }
    const reply = result.text || 'দুঃখিত, উত্তর পাওয়া যায়নি।';
    voiceStatus.textContent = state.autoMode ? 'অটো ভয়েস প্রস্তুত — কথা বলুন...' : 'ম্যানুয়াল মোড সক্রিয়';
    addChatMessage(reply, 'ai');
    appendDiaryLog('Hey Shuvo', reply);
    speakText(reply);
  } catch (err) {
    const r = 'নেটওয়ার্ক সমস্যা হয়েছে, আবার চেষ্টা করুন।';
    voiceStatus.textContent = r;
    addChatMessage(r, 'ai');
    speakText(r);
  } finally {
    state.isProcessing = false;
  }
}

sendTextBtn.addEventListener('click', () => {
  const val = textCommandInput.value.trim();
  if (!val) return;
  textCommandInput.value = '';
  handleUserCommand(val);
});
textCommandInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') sendTextBtn.click();
});
micTapBtn.addEventListener('click', () => {
  state.micPermissionDenied = false;
  startSpeechRecognition();
});

// Camera Vision
const videoEl = document.getElementById('cameraVideo');
const captureCanvas = document.getElementById('captureCanvas');
const visionResult = document.getElementById('visionResult');

async function startCamera() {
  try {
    state.stream?.getTracks().forEach((t) => t.stop());
    state.stream = await navigator.mediaDevices.getUserMedia({
      video: { facingMode: state.facingMode },
      audio: false
    });
    videoEl.srcObject = state.stream;
  } catch (e) {
    visionResult.textContent = 'ক্যামেরা চালু করা যায়নি—ব্রাউজার পারমিশন দিন।';
  }
}

document.getElementById('startCamBtn').addEventListener('click', startCamera);
document.getElementById('switchCamBtn').addEventListener('click', () => {
  state.facingMode = state.facingMode === 'environment' ? 'user' : 'environment';
  startCamera();
});

document.getElementById('captureAnalyzeBtn').addEventListener('click', async () => {
  if (!state.stream || !videoEl.videoWidth || !videoEl.videoHeight) {
    visionResult.textContent = 'প্রথমে "ক্যামেরা চালু করুন" বোতামে চাপ দিয়ে লাইভ ক্যামেরা চালু করুন।';
    return;
  }
  if (!state.apiKey) {
    visionResult.textContent = 'দয়া করে মাস্টার ট্যাব থেকে Gemini API Key সেভ করুন।';
    return;
  }
  if (!checkAndIncrementUsage()) return;

  captureCanvas.width = videoEl.videoWidth;
  captureCanvas.height = videoEl.videoHeight;
  captureCanvas.getContext('2d').drawImage(videoEl, 0, 0);
  const base64 = captureCanvas.toDataURL('image/jpeg', 0.8).split(',')[1];
  const q = document.getElementById('visionQueryInput').value.trim() ||
    'Identify people, objects, environment, text, and gently observe possible mood/emotion without claiming certainty. Reply naturally in Bangla.';

  visionResult.textContent = 'ছবি বিশ্লেষণ করা হচ্ছে...';
  try {
    const result = await callGeminiFlash([
      { inlineData: { mimeType: 'image/jpeg', data: base64 } },
      { text: q }
    ]);
    if (!result.ok) {
      visionResult.textContent = `Gemini Vision ত্রুটি: ${result.error}`;
      return;
    }
    const reply = result.text || 'বিশ্লেষণ করা যায়নি।';
    visionResult.textContent = reply;
    speakText(reply);
  } catch (_) {
    visionResult.textContent = 'নেটওয়ার্ক ত্রুটি হয়েছে।';
  }
});

// --- Smart Hub Features (Theme, Tally Khata, Birthdays, Writer, Diary) ---
// 1. Theme Palette
const savedTheme = localStorage.getItem('hs_web_theme') || 'cyan';
function applyWebTheme(theme) {
  if (theme === 'cyan') {
    document.documentElement.removeAttribute('data-theme');
  } else {
    document.documentElement.setAttribute('data-theme', theme);
  }
  localStorage.setItem('hs_web_theme', theme);
  document.querySelectorAll('.hub-theme-btn').forEach((b) => {
    b.classList.toggle('active', b.dataset.theme === theme);
  });
}
applyWebTheme(savedTheme);
document.querySelectorAll('.hub-theme-btn').forEach((btn) => {
  btn.addEventListener('click', () => applyWebTheme(btn.dataset.theme));
});

// Hub sub-tab switching
document.querySelectorAll('.hub-sub-btn').forEach((btn) => {
  btn.addEventListener('click', () => {
    const target = btn.dataset.hub;
    document.querySelectorAll('.hub-section').forEach((s) => s.classList.remove('active'));
    document.querySelectorAll('.hub-sub-btn').forEach((b) => {
      b.classList.remove('btn-primary');
      b.classList.add('btn-outline');
    });
    document.getElementById(target)?.classList.add('active');
    btn.classList.remove('btn-outline');
    btn.classList.add('btn-primary');
  });
});

// 2. Tally Khata
function getTallyEntries() {
  try {
    return JSON.parse(localStorage.getItem('hs_tally_entries') || '[]');
  } catch {
    return [];
  }
}

function renderTallyEntries() {
  const listEl = document.getElementById('tallyList');
  const summaryEl = document.getElementById('tallySummaryBar');
  if (!listEl || !summaryEl) return;
  const entries = getTallyEntries();
  let inc = 0, exp = 0, due = 0;
  entries.forEach((e) => {
    if (e.type === 'INCOME') inc += Number(e.amount) || 0;
    else if (e.type === 'EXPENSE') exp += Number(e.amount) || 0;
    else due += Number(e.amount) || 0;
  });
  summaryEl.textContent = `আয়: ৳${inc} | খরচ: ৳${exp} | বাকি: ৳${due}`;
  listEl.innerHTML = entries.length === 0
    ? '<div style="font-size:12px;color:var(--muted);">এখনো কোনো হিসাব যোগ করা হয়নি।</div>'
    : '';
  entries.forEach((e) => {
    const row = document.createElement('div');
    row.className = 'hub-item';
    const typeLabel = e.type === 'INCOME' ? 'আয়' : e.type === 'EXPENSE' ? 'খরচ' : 'বাকি';
    const info = document.createElement('div');
    info.innerHTML = `<strong></strong><br><small style="color:var(--muted);"></small>`;
    info.querySelector('strong').textContent = `${e.customer} — ৳${e.amount}`;
    info.querySelector('small').textContent = `${typeLabel} • ${e.date || ''}`;
    const delBtn = document.createElement('button');
    delBtn.className = 'btn-outline';
    delBtn.textContent = '🗑';
    delBtn.onclick = () => {
      const next = getTallyEntries().filter((item) => item.id !== e.id);
      localStorage.setItem('hs_tally_entries', JSON.stringify(next));
      renderTallyEntries();
    };
    row.appendChild(info);
    row.appendChild(delBtn);
    listEl.appendChild(row);
  });
}

document.getElementById('addTallyBtn')?.addEventListener('click', () => {
  const customer = document.getElementById('tallyCustomerInput').value.trim() || 'কাস্টমার';
  const amount = Number(document.getElementById('tallyAmountInput').value);
  const type = document.getElementById('tallyTypeSelect').value;
  if (!amount || amount <= 0) return;
  const entries = getTallyEntries();
  entries.unshift({
    id: Date.now(),
    customer,
    amount,
    type,
    date: new Date().toLocaleDateString('bn-BD')
  });
  localStorage.setItem('hs_tally_entries', JSON.stringify(entries.slice(0, 100)));
  document.getElementById('tallyCustomerInput').value = '';
  document.getElementById('tallyAmountInput').value = '';
  renderTallyEntries();
});
renderTallyEntries();

// 3. Birthday Reminders (Local + Cloud Firestore /users/{uid}/birthdays sync)
function getLocalBirthdays() {
  try {
    return JSON.parse(localStorage.getItem('hs_birthdays') || '[]');
  } catch {
    return [];
  }
}

function getEffectiveBirthdays() {
  if (Array.isArray(state.cloudBirthdays)) {
    const cloudNames = new Set(state.cloudBirthdays.map((b) => `${b.name.toLowerCase()}_${b.day}_${b.month}`));
    const localOnly = getLocalBirthdays().filter((b) => !cloudNames.has(`${(b.name || '').toLowerCase()}_${b.day}_${b.month}`));
    return [...state.cloudBirthdays, ...localOnly];
  }
  return getLocalBirthdays();
}

async function upsertBirthdayToCloud(b) {
  if (!state.cloudUser || !state.cloudDb || !state.cloudFsMod) return false;
  try {
    const fs = state.cloudFsMod;
    const docId = String(b.id || ('bday_' + Date.now())).replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 100) || ('bday_' + Date.now());
    const safeDay = Math.min(31, Math.max(1, Math.trunc(Number(b.day) || 1)));
    const safeMonth = Math.min(12, Math.max(1, Math.trunc(Number(b.month) || 1)));
    const safeName = String(b.name || 'Friend').trim().slice(0, 100) || 'Friend';
    const safePhone = String(b.phone || '').trim().slice(0, 80);
    let safeWish = String(b.wish || '').trim().slice(0, 600);
    if (safeWish.length < 2) safeWish = `শুভ জন্মদিন ${safeName}!`;
    await fs.setDoc(fs.doc(state.cloudDb, 'users', state.cloudUser.uid, 'birthdays', docId), {
      userId: state.cloudUser.uid,
      personName: safeName,
      birthDay: safeDay,
      birthMonth: safeMonth,
      phoneOrContact: safePhone,
      wishMessage: safeWish,
      lastWishedYear: Number.isInteger(b.lastWishedYear) ? b.lastWishedYear : 0,
      createdAt: fs.serverTimestamp(),
      updatedAt: fs.serverTimestamp()
    });
    return true;
  } catch (_) {
    return false;
  }
}

async function deleteBirthdayEntry(b) {
  localStorage.setItem('hs_birthdays', JSON.stringify(getLocalBirthdays().filter((x) => String(x.id) !== String(b.id))));
  if (b.cloudDocId && state.cloudUser && state.cloudDb && state.cloudFsMod) {
    try {
      const fs = state.cloudFsMod;
      await fs.deleteDoc(fs.doc(state.cloudDb, 'users', state.cloudUser.uid, 'birthdays', b.cloudDocId));
    } catch (_) {}
  }
  renderBirthdays();
}

function renderBirthdays() {
  const listEl = document.getElementById('birthdayList');
  if (!listEl) return;
  const list = getEffectiveBirthdays();
  listEl.innerHTML = list.length === 0
    ? '<div style="font-size:12px;color:var(--muted);">কোনো জন্মদিনের রিমাইন্ডার নেই।</div>'
    : '';
  list.forEach((b) => {
    const row = document.createElement('div');
    row.className = 'hub-item';
    const info = document.createElement('div');
    info.innerHTML = `<strong></strong><br><small style="color:var(--muted);"></small>`;
    info.querySelector('strong').textContent = `${b.name} (${b.day}/${b.month})${b.cloudDocId ? ' ☁️' : ''}`;
    info.querySelector('small').textContent = b.wish;
    const actions = document.createElement('div');
    actions.className = 'pill-group';
    const sendBtn = document.createElement('button');
    sendBtn.className = 'btn-secondary';
    sendBtn.textContent = 'WhatsApp';
    sendBtn.onclick = () => {
      const cleanPhone = (b.phone || '').replace(/[^\d+]/g, '');
      const url = cleanPhone
        ? `https://api.whatsapp.com/send?phone=${encodeURIComponent(cleanPhone)}&text=${encodeURIComponent(b.wish)}`
        : `https://api.whatsapp.com/send?text=${encodeURIComponent(b.wish)}`;
      window.open(url, '_blank');
    };
    const delBtn = document.createElement('button');
    delBtn.className = 'btn-outline';
    delBtn.textContent = '🗑';
    delBtn.onclick = () => deleteBirthdayEntry(b);
    actions.appendChild(sendBtn);
    actions.appendChild(delBtn);
    row.appendChild(info);
    row.appendChild(actions);
    listEl.appendChild(row);
  });
}

document.getElementById('genAiWishBtn')?.addEventListener('click', async () => {
  const name = document.getElementById('bdayNameInput').value.trim() || 'বন্ধু';
  const wishInput = document.getElementById('bdayWishInput');
  if (!state.apiKey) {
    wishInput.value = `শুভ জন্মদিন ${name}! আপনার জীবন আনন্দ, সুস্বাস্থ্য ও সাফল্যে ভরে উঠুক।`;
    return;
  }
  wishInput.value = 'AI শুভেচ্ছা লিখছে...';
  const res = await callGeminiFlash([
    { text: `Write a warm, heartfelt short birthday wish in Bangla for ${name}. Return only the message text.` }
  ]);
  wishInput.value = res.ok && res.text
    ? res.text.trim()
    : `শুভ জন্মদিন ${name}! আপনার জীবন আনন্দ ও সাফল্যে ভরে উঠুক।`;
});

document.getElementById('saveBirthdayBtn')?.addEventListener('click', async () => {
  const name = document.getElementById('bdayNameInput').value.trim();
  const phone = document.getElementById('bdayPhoneInput').value.trim();
  const day = Math.min(31, Math.max(1, Math.trunc(Number(document.getElementById('bdayDayInput').value) || new Date().getDate())));
  const month = Math.min(12, Math.max(1, Math.trunc(Number(document.getElementById('bdayMonthInput').value) || (new Date().getMonth() + 1))));
  const wish = document.getElementById('bdayWishInput').value.trim() || `শুভ জন্মদিন ${name || 'প্রিয়'}!`;
  if (!name) return;
  const entry = {
    id: `bday_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
    name,
    phone,
    day,
    month,
    wish,
    lastWishedYear: 0
  };
  const synced = await upsertBirthdayToCloud(entry);
  if (!synced) {
    const list = getLocalBirthdays();
    list.unshift(entry);
    localStorage.setItem('hs_birthdays', JSON.stringify(list));
  }
  document.getElementById('bdayNameInput').value = '';
  document.getElementById('bdayPhoneInput').value = '';
  document.getElementById('bdayWishInput').value = '';
  renderBirthdays();
});
renderBirthdays();

// 4. Smart Translator & Writer
async function runSmartWriterTask(instruction) {
  const text = document.getElementById('writerInput').value.trim();
  const outEl = document.getElementById('writerOutput');
  const speakBtn = document.getElementById('speakWriterBtn');
  const copyBtn = document.getElementById('copyWriterBtn');
  if (!text) return;
  if (!state.apiKey) {
    outEl.style.display = 'block';
    outEl.textContent = 'দয়া করে মাস্টার ট্যাব থেকে আপনার Gemini API Key সেভ করুন।';
    return;
  }
  if (!checkAndIncrementUsage()) return;
  outEl.style.display = 'block';
  outEl.textContent = 'তৈরি হচ্ছে...';
  try {
    const res = await callGeminiFlash([{ text: `${instruction}\n\nText:\n${text}` }]);
    outEl.textContent = res.ok ? (res.text || 'কোনো ফলাফল পাওয়া যায়নি।') : `ত্রুটি: ${res.error}`;
    if (res.ok && res.text) {
      speakBtn?.classList.remove('hidden');
      copyBtn?.classList.remove('hidden');
    }
  } catch {
    outEl.textContent = 'নেটওয়ার্ক সমস্যা হয়েছে।';
  }
}

document.getElementById('transBnEnBtn')?.addEventListener('click', () =>
  runSmartWriterTask('Translate accurately into clear, natural English.')
);
document.getElementById('transEnBnBtn')?.addEventListener('click', () =>
  runSmartWriterTask('Translate accurately into natural, standard Bangla (প্রমিত বাংলা).')
);
document.getElementById('fixGrammarBtn')?.addEventListener('click', () =>
  runSmartWriterTask('Fix all spelling and grammar mistakes neatly in the same language (Bangla or English).')
);
document.getElementById('officialLetterBtn')?.addEventListener('click', () =>
  runSmartWriterTask('Draft a polite, professional official email or application based on this request in both Bangla and English.')
);
document.getElementById('speakWriterBtn')?.addEventListener('click', () => {
  speakText(document.getElementById('writerOutput')?.textContent || '');
});
document.getElementById('copyWriterBtn')?.addEventListener('click', () => {
  const txt = document.getElementById('writerOutput')?.textContent || '';
  if (txt && navigator.clipboard) {
    navigator.clipboard.writeText(txt).then(() => {
      document.getElementById('copyWriterBtn').textContent = '✓ কপি হয়েছে';
      setTimeout(() => { document.getElementById('copyWriterBtn').textContent = '📋 কপি করুন'; }, 1600);
    });
  }
});

// 5. Daily Voice Diary
function renderDiaryLogs() {
  const listEl = document.getElementById('diaryLogList');
  if (!listEl) return;
  const logs = getDiaryLogs();
  listEl.innerHTML = logs.length === 0
    ? '<div style="font-size:12px;color:var(--muted);">আজ এখনো কোনো ডায়েরি লগ জমা হয়নি।</div>'
    : '';
  logs.forEach((entry) => {
    const row = document.createElement('div');
    row.className = 'hub-item';
    const d = document.createElement('div');
    d.textContent = `[${entry.time}] ${entry.role}: ${entry.text}`;
    row.appendChild(d);
    listEl.appendChild(row);
  });
}

document.getElementById('genDiarySummaryBtn')?.addEventListener('click', async () => {
  const outEl = document.getElementById('diarySummaryOutput');
  const emailRow = document.getElementById('diaryEmailRow');
  const logs = getDiaryLogs();
  if (!logs.length) {
    outEl.style.display = 'block';
    outEl.textContent = 'আজ এখনো কোনো কথা/কাজের লগ নেই। ভয়েস AI ট্যাবে কথা বললে ডায়েরি আপডেট হবে।';
    return;
  }
  if (!state.apiKey) {
    outEl.style.display = 'block';
    outEl.textContent = logs.slice(0, 12).map((l) => `• [${l.time}] ${l.role}: ${l.text}`).join('\n');
    emailRow?.classList.remove('hidden');
    return;
  }
  outEl.style.display = 'block';
  outEl.textContent = 'আজকের ডায়েরি সারাংশ তৈরি হচ্ছে...';
  const joined = logs.slice(0, 25).map((l) => `[${l.time}] ${l.role}: ${l.text}`).join('\n');
  const res = await callGeminiFlash([
    { text: `Summarize today's conversations and key tasks neatly in Bangla bullet points:\n\n${joined}` }
  ]);
  outEl.textContent = res.ok && res.text ? res.text : joined;
  emailRow?.classList.remove('hidden');
});

document.getElementById('clearDiaryBtn')?.addEventListener('click', () => {
  localStorage.removeItem('hs_diary_logs');
  renderDiaryLogs();
  const outEl = document.getElementById('diarySummaryOutput');
  if (outEl) outEl.style.display = 'none';
  document.getElementById('diaryEmailRow')?.classList.add('hidden');
});

document.getElementById('emailDiaryBtn')?.addEventListener('click', () => {
  const to = document.getElementById('diaryEmailInput')?.value.trim() || '';
  const body = document.getElementById('diarySummaryOutput')?.textContent || '';
  const subject = encodeURIComponent(`Hey Shuvo Daily Summary — ${new Date().toLocaleDateString()}`);
  window.location.href = `mailto:${encodeURIComponent(to)}?subject=${subject}&body=${encodeURIComponent(body)}`;
});
renderDiaryLogs();

// Master Security & Controls
const masterLockGate = document.getElementById('masterLockGate');
const masterUnlockedView = document.getElementById('masterUnlockedView');
const masterLoginEmail = document.getElementById('masterLoginEmail');
const masterLoginPassword = document.getElementById('masterLoginPassword');
const masterLoginBtn = document.getElementById('masterLoginBtn');
const masterCloudOwnerBtn = document.getElementById('masterCloudOwnerBtn');
const masterAuthError = document.getElementById('masterAuthError');
const masterLockBtn = document.getElementById('masterLockBtn');

function unlockMasterView() {
  masterLockGate.style.display = 'none';
  masterUnlockedView.style.display = 'flex';
  masterAuthError.textContent = '';
  masterLoginPassword.value = '';
}

masterLoginBtn.addEventListener('click', () => {
  const email = (masterLoginEmail.value || '').trim().toLowerCase();
  const pass = masterLoginPassword.value || '';
  const emailHash = sha256Hex(MASTER_SALT + email);
  const passHash = sha256Hex(MASTER_SALT + pass);

  const isEmailAllowed = AUTHORIZED_EMAIL_HASHES.includes(emailHash);
  const isPassCorrect = passHash === getMasterPasswordHash();

  if (isEmailAllowed && isPassCorrect) {
    unlockMasterView();
  } else {
    masterAuthError.textContent = '❌ অননুমোদিত ইমেইল অথবা ভুল পাসওয়ার্ড! সঠিক তথ্য দিন।';
  }
});

masterCloudOwnerBtn?.addEventListener('click', () => {
  if (state.isCloudOwner && state.cloudUser) {
    unlockMasterView();
  }
});

masterLockBtn.addEventListener('click', () => {
  masterUnlockedView.style.display = 'none';
  masterLockGate.style.display = 'flex';
});

// Master Password Changer
const oldMasterPassInput = document.getElementById('oldMasterPassInput');
const newMasterPassInput = document.getElementById('newMasterPassInput');
const confirmMasterPassInput = document.getElementById('confirmMasterPassInput');
const passChangeStatusText = document.getElementById('passChangeStatusText');
const updateMasterPassBtn = document.getElementById('updateMasterPassBtn');

updateMasterPassBtn.addEventListener('click', () => {
  const oldP = oldMasterPassInput.value || '';
  const newP = newMasterPassInput.value || '';
  const confP = confirmMasterPassInput.value || '';

  const oldHash = sha256Hex(MASTER_SALT + oldP);
  if (oldHash !== getMasterPasswordHash()) {
    passChangeStatusText.style.color = 'var(--danger)';
    passChangeStatusText.textContent = '❌ বর্তমান পাসওয়ার্ড সঠিক নয়!';
    return;
  }
  if (!newP || newP.length < 6) {
    passChangeStatusText.style.color = 'var(--danger)';
    passChangeStatusText.textContent = '❌ নতুন পাসওয়ার্ড কমপক্ষে ৬ অক্ষরের হতে হবে!';
    return;
  }
  if (newP !== confP) {
    passChangeStatusText.style.color = 'var(--danger)';
    passChangeStatusText.textContent = '❌ নতুন দুটি পাসওয়ার্ড মিলছে না!';
    return;
  }

  localStorage.setItem('hs_master_pass_hash', sha256Hex(MASTER_SALT + newP));
  passChangeStatusText.style.color = 'var(--primary)';
  passChangeStatusText.textContent = '✓ মাস্টার পাসওয়ার্ড সফলভাবে আপডেট হয়েছে!';
  oldMasterPassInput.value = '';
  newMasterPassInput.value = '';
  confirmMasterPassInput.value = '';
});

// Master Controls & Cloud Push
async function syncPricingToCloudIfOwner() {
  if (!state.cloudUser || !state.cloudDb || !state.cloudFsMod || !state.isCloudOwner) return false;
  try {
    const fs = state.cloudFsMod;
    await fs.updateDoc(fs.doc(state.cloudDb, 'customer_configs', 'global'), {
      monthlyPaidFeeBdt: state.monthlyFee,
      freeDailyLimit: state.freeLimit,
      globalPaidModeEnabled: state.globalPaid,
      updatedByUid: state.cloudUser.uid,
      updatedAt: fs.serverTimestamp()
    });
    return true;
  } catch {
    return false;
  }
}

document.getElementById('apiKeyInput').value = state.apiKey;
document.getElementById('monthlyFeeInput').value = state.monthlyFee;
document.getElementById('freeLimitInput').value = state.freeLimit;

document.getElementById('saveApiKeyBtn').addEventListener('click', () => {
  state.apiKey = document.getElementById('apiKeyInput').value.trim();
  localStorage.setItem('hs_api_key', state.apiKey);
  document.getElementById('masterStatusText').textContent = '✓ API Key সেভ হয়েছে!';
});

document.getElementById('toggleGlobalPaidBtn').addEventListener('click', async () => {
  state.globalPaid = !state.globalPaid;
  localStorage.setItem('hs_global_paid', JSON.stringify(state.globalPaid));
  updatePlanUI();
  const synced = await syncPricingToCloudIfOwner();
  if (synced) {
    document.getElementById('masterStatusText').textContent = '✓ গ্লোবাল পেইড মোড ক্লাউডে আপডেট হয়েছে!';
  }
});

document.getElementById('setPaid30Btn').addEventListener('click', () => {
  state.isPaid = true;
  state.paidUntil = Date.now() + 30 * 24 * 60 * 60 * 1000;
  localStorage.setItem('hs_is_paid', 'true');
  localStorage.setItem('hs_paid_until', String(state.paidUntil));
  updatePlanUI();
});

document.getElementById('setPaidLifeBtn').addEventListener('click', () => {
  state.isPaid = true;
  state.paidUntil = 0;
  localStorage.setItem('hs_is_paid', 'true');
  localStorage.setItem('hs_paid_until', '0');
  updatePlanUI();
});

document.getElementById('setFreeBtn').addEventListener('click', () => {
  state.isPaid = false;
  state.paidUntil = 0;
  localStorage.setItem('hs_is_paid', 'false');
  localStorage.setItem('hs_paid_until', '0');
  updatePlanUI();
});

document.getElementById('bonus10Btn').addEventListener('click', () => {
  const today = new Date().toISOString().slice(0, 10);
  if (state.usageDate !== today) {
    state.usageDate = today;
    state.usageCount = 0;
  }
  state.usageCount = Math.max(0, state.usageCount - 10);
  localStorage.setItem('hs_usage_date', state.usageDate);
  localStorage.setItem('hs_usage_count', String(state.usageCount));
  updatePlanUI();
});

document.getElementById('savePricingBtn').addEventListener('click', async () => {
  state.monthlyFee = Math.max(0, Number(document.getElementById('monthlyFeeInput').value) || 299);
  state.freeLimit = Math.max(1, Number(document.getElementById('freeLimitInput').value) || 25);
  localStorage.setItem('hs_monthly_fee', String(state.monthlyFee));
  localStorage.setItem('hs_free_limit', String(state.freeLimit));
  updatePlanUI();
  const synced = await syncPricingToCloudIfOwner();
  document.getElementById('masterStatusText').textContent = synced
    ? '✓ ফি ও লিমিট লোকাল এবং ক্লাউড (Android+Web) দুই জায়গায় সেভ হয়েছে!'
    : '✓ ফি ও ফ্রি লিমিট আপডেট হয়েছে!';
});

// Avatar Interaction & 3-Way Voice Switch Controls
const quickSwitchVoiceBtn = document.getElementById('quickSwitchVoiceBtn');
if (quickSwitchVoiceBtn) {
  quickSwitchVoiceBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    state.voiceProfile =
      state.voiceProfile === 'FEMALE'
        ? 'MALE'
        : state.voiceProfile === 'MALE'
        ? 'DEEP'
        : 'FEMALE';
    localStorage.setItem('hs_voice_profile', state.voiceProfile);
    updateAvatarUI();
    const reply =
      state.voiceProfile === 'FEMALE'
        ? 'জি বস, এখন থেকে আমি স্পষ্ট নারী কণ্ঠে কথা বলছি।'
        : state.voiceProfile === 'DEEP'
        ? 'জি বস, এখন থেকে আমি গম্ভীর ও ভারী কণ্ঠে কথা বলছি।'
        : 'জি বস, এখন থেকে আমি স্পষ্ট পুরুষ কণ্ঠে কথা বলছি।';
    addChatMessage(reply, 'ai');
    speakText(reply);
  });
}

const cyclePhotoBtn = document.getElementById('cyclePhotoBtn');
if (cyclePhotoBtn) {
  cyclePhotoBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    const prof = state.voiceProfile || 'FEMALE';
    const list = AVATAR_CATALOG[prof] || AVATAR_CATALOG.FEMALE;
    const hasCustom = Boolean(state.customAvatarDataUrl);

    if (state.useCustomAvatar) {
      // Custom -> wrap back to index 0
      state.useCustomAvatar = false;
      localStorage.setItem('hs_use_custom_avatar', 'false');
      if (prof === 'FEMALE') {
        state.femalePhotoIndex = 0;
        localStorage.setItem('hs_female_photo_idx', '0');
      } else {
        state.malePhotoIndex = 0;
        localStorage.setItem('hs_male_photo_idx', '0');
      }
    } else {
      const curIdx = prof === 'FEMALE' ? state.femalePhotoIndex : state.malePhotoIndex;
      if (hasCustom && curIdx >= list.length - 1) {
        state.useCustomAvatar = true;
        localStorage.setItem('hs_use_custom_avatar', 'true');
      } else {
        const nextIdx = (curIdx + 1) % list.length;
        if (prof === 'FEMALE') {
          state.femalePhotoIndex = nextIdx;
          localStorage.setItem('hs_female_photo_idx', String(nextIdx));
        } else {
          state.malePhotoIndex = nextIdx;
          localStorage.setItem('hs_male_photo_idx', String(nextIdx));
        }
      }
    }
    updateAvatarUI();
  });
}

// Custom Portrait Upload, Reset & Mic Engine Toggle
const customAvatarInput = document.getElementById('customAvatarInput');
const uploadCustomAvatarBtn = document.getElementById('uploadCustomAvatarBtn');
const resetCustomAvatarBtn = document.getElementById('resetCustomAvatarBtn');
const toggleMicEngineBtn = document.getElementById('toggleMicEngineBtn');

if (uploadCustomAvatarBtn && customAvatarInput) {
  uploadCustomAvatarBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    customAvatarInput.click();
  });
  customAvatarInput.addEventListener('change', () => {
    const file = customAvatarInput.files?.[0];
    customAvatarInput.value = '';
    if (!file || !file.type.startsWith('image/')) return;
    const img = new Image();
    const objUrl = URL.createObjectURL(file);
    img.onload = () => {
      URL.revokeObjectURL(objUrl);
      const maxDim = 512;
      let w = img.naturalWidth || 480;
      let h = img.naturalHeight || 640;
      if (w > maxDim || h > maxDim) {
        const ratio = Math.min(maxDim / w, maxDim / h);
        w = Math.max(1, Math.round(w * ratio));
        h = Math.max(1, Math.round(h * ratio));
      }
      const c = document.createElement('canvas');
      c.width = w;
      c.height = h;
      c.getContext('2d').drawImage(img, 0, 0, w, h);
      const dataUrl = c.toDataURL('image/jpeg', 0.82);
      state.customAvatarDataUrl = dataUrl;
      state.useCustomAvatar = true;
      localStorage.setItem('hs_custom_avatar_dataurl', dataUrl);
      localStorage.setItem('hs_use_custom_avatar', 'true');
      updateAvatarUI();
    };
    img.onerror = () => URL.revokeObjectURL(objUrl);
    img.src = objUrl;
  });
}

if (resetCustomAvatarBtn) {
  resetCustomAvatarBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    state.customAvatarDataUrl = '';
    state.useCustomAvatar = false;
    localStorage.removeItem('hs_custom_avatar_dataurl');
    localStorage.setItem('hs_use_custom_avatar', 'false');
    updateAvatarUI();
  });
}

if (toggleMicEngineBtn) {
  toggleMicEngineBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    state.micEngineMode =
      state.micEngineMode === 'AUTO'
        ? 'VAD'
        : state.micEngineMode === 'VAD'
        ? 'SPEECH'
        : 'AUTO';
    localStorage.setItem('hs_mic_engine', state.micEngineMode);
    state.speechFailStreak = 0;
    if (state.isListening) stopSpeechRecognition();
    updateAvatarUI();
    if (state.autoMode && state.activeTab === 'panel-voice' && !state.isSpeaking && !state.isProcessing) {
      setTimeout(startSpeechRecognition, 250);
    }
  });
}

const avatarPhotoWrapper = document.getElementById('avatarPhotoWrapper');
if (avatarPhotoWrapper) {
  avatarPhotoWrapper.addEventListener('click', () => {
    if (state.isSpeaking) {
      stopSpeaking();
    } else if (state.isListening) {
      stopSpeechRecognition();
    } else if (!state.isProcessing) {
      state.micPermissionDenied = false;
      startSpeechRecognition();
    }
  });
}

// --- Real Firebase Web <-> Android Cloud Sync & Cross-Device Transfer ---
const FIREBASE_WEB_CONFIG = {
  apiKey: ['AIzaSyBSHajPcdnJs', 'Ure-DLcWFZzmIU1jw5bcXo'].join(''),
  authDomain: 'gen-lang-client-0859862767.firebaseapp.com',
  projectId: 'gen-lang-client-0859862767',
  appId: '1:422729323371:android:32ce3f3a85fbe98dccb22e'
};
const FIRESTORE_DB_ID = 'ai-studio-android-heyshuvo-e0f9334f-97f8-4c40-873c-5e30979bd0af';

async function initFirebaseCloudSync() {
  try {
    const [appMod, authMod, fsMod] = await Promise.all([
      import('https://www.gstatic.com/firebasejs/11.0.2/firebase-app.js'),
      import('https://www.gstatic.com/firebasejs/11.0.2/firebase-auth.js'),
      import('https://www.gstatic.com/firebasejs/11.0.2/firebase-firestore.js')
    ]);
    const fbApp = appMod.initializeApp(FIREBASE_WEB_CONFIG);
    const fbAuth = authMod.getAuth(fbApp);
    const fbDb = fsMod.getFirestore(fbApp, FIRESTORE_DB_ID);
    state.cloudDb = fbDb;
    state.cloudFsMod = fsMod;

    await authMod.getRedirectResult(fbAuth).catch((err) => {
      if (err?.message) {
        cloudAuthBtn.title = `Cloud Sync ত্রুটি: ${err.message}`;
      }
    });

    authMod.onAuthStateChanged(fbAuth, async (user) => {
      state.cloudUser = user || null;
      if (user) {
        cloudAuthBtn.textContent = `☁️ ${user.displayName?.split(' ')[0] || 'Synced'}`;
        cloudAuthBtn.style.borderColor = 'var(--success)';
        cloudAuthBtn.style.color = 'var(--success)';

        const emailHash = sha256Hex(MASTER_SALT + (user.email || '').trim().toLowerCase());
        state.isCloudOwner = AUTHORIZED_EMAIL_HASHES.includes(emailHash);
        masterCloudOwnerBtn?.classList.toggle('hidden', !state.isCloudOwner);

        try {
          // 1. Live Global Customer Config (Banner, Pricing, Limits)
          const cfgRef = fsMod.doc(fbDb, 'customer_configs', 'global');
          fsMod.onSnapshot(
            cfgRef,
            (snap) => {
              if (snap.exists()) {
                const data = snap.data();
                if (data.announcementBanner) {
                  remoteBannerBar.textContent = `✦ ${data.announcementBanner}`;
                  remoteBannerBar.classList.remove('hidden');
                } else {
                  remoteBannerBar.classList.add('hidden');
                }
                if (typeof data.monthlyPaidFeeBdt === 'number') {
                  state.monthlyFee = data.monthlyPaidFeeBdt;
                  localStorage.setItem('hs_monthly_fee', String(state.monthlyFee));
                  const feeIn = document.getElementById('monthlyFeeInput');
                  if (feeIn) feeIn.value = String(state.monthlyFee);
                }
                if (typeof data.freeDailyLimit === 'number') {
                  state.freeLimit = data.freeDailyLimit;
                  localStorage.setItem('hs_free_limit', String(state.freeLimit));
                  const limIn = document.getElementById('freeLimitInput');
                  if (limIn) limIn.value = String(state.freeLimit);
                }
                if (typeof data.globalPaidModeEnabled === 'boolean') {
                  state.globalPaid = data.globalPaidModeEnabled;
                  localStorage.setItem('hs_global_paid', JSON.stringify(state.globalPaid));
                }
                updatePlanUI();
              }
            },
            (err) => {
              cloudAuthBtn.title = `Cloud Sync পাঠ ত্রুটি: ${err?.message || 'Permission denied'}`;
            }
          );

          // 2. Live User Account Subscription Tier
          const accRef = fsMod.doc(fbDb, 'accounts', user.uid);
          fsMod.onSnapshot(
            accRef,
            (snap) => {
              if (snap.exists()) {
                const acc = snap.data();
                if (acc.accountRole === 'owner') {
                  state.isCloudOwner = true;
                  masterCloudOwnerBtn?.classList.remove('hidden');
                }
                if (acc.subscriptionTier === 'paid' || acc.accountRole === 'owner') {
                  state.isPaid = true;
                  localStorage.setItem('hs_is_paid', 'true');
                } else if (acc.subscriptionTier === 'free') {
                  state.isPaid = false;
                  localStorage.setItem('hs_is_paid', 'false');
                }
                updatePlanUI();
              }
            },
            () => {}
          );
          // 3. Live User Birthdays (/users/{uid}/birthdays)
          if (state.birthdaysUnsubscribe) {
            try { state.birthdaysUnsubscribe(); } catch (_) {}
            state.birthdaysUnsubscribe = null;
          }
          const bdayColRef = fsMod.collection(fbDb, 'users', user.uid, 'birthdays');
          state.birthdaysUnsubscribe = fsMod.onSnapshot(
            bdayColRef,
            (snap) => {
              state.cloudBirthdays = snap.docs.map((d) => {
                const v = d.data();
                return {
                  id: d.id,
                  cloudDocId: d.id,
                  name: v.personName || 'Friend',
                  day: Number(v.birthDay) || 1,
                  month: Number(v.birthMonth) || 1,
                  phone: v.phoneOrContact || '',
                  wish: v.wishMessage || '',
                  lastWishedYear: Number(v.lastWishedYear) || 0
                };
              });
              renderBirthdays();
            },
            () => {}
          );
        } catch (err) {
          cloudAuthBtn.title = `Cloud Sync ত্রুটি: ${err?.message || 'Unknown'}`;
        }
      } else {
        if (state.birthdaysUnsubscribe) {
          try { state.birthdaysUnsubscribe(); } catch (_) {}
          state.birthdaysUnsubscribe = null;
        }
        state.cloudBirthdays = null;
        renderBirthdays();
        state.isCloudOwner = false;
        masterCloudOwnerBtn?.classList.add('hidden');
        cloudAuthBtn.textContent = '☁️ Sync';
        cloudAuthBtn.style.borderColor = '';
        cloudAuthBtn.style.color = '';
        remoteBannerBar.classList.add('hidden');
      }
    });

    cloudAuthBtn.addEventListener('click', async () => {
      if (state.cloudUser) {
        await authMod.signOut(fbAuth);
      } else {
        const provider = new authMod.GoogleAuthProvider();
        try {
          await authMod.signInWithPopup(fbAuth, provider);
        } catch (err) {
          const code = err?.code || '';
          if (code === 'auth/popup-blocked' || code === 'auth/operation-not-supported-in-this-environment') {
            await authMod.signInWithRedirect(fbAuth, provider).catch((rErr) => {
              remoteBannerBar.textContent = `⚠ Google Sign-In ত্রুটি: ${rErr?.message || code}`;
              remoteBannerBar.classList.remove('hidden');
            });
          } else if (code === 'auth/unauthorized-domain') {
            remoteBannerBar.textContent = `⚠ এই ওয়েব ডোমেইনটি Firebase Auth-এ অনুমোদিত নয় (${location.hostname})। Firebase Console-এ Authorized Domains-এ যোগ করুন অথবা লোকাল মোডে ব্যবহার করুন।`;
            remoteBannerBar.classList.remove('hidden');
          } else {
            remoteBannerBar.textContent = `⚠ Google Sign-In ত্রুটি (${code || 'unknown'}): আবার চেষ্টা করুন।`;
            remoteBannerBar.classList.remove('hidden');
          }
        }
      }
    });
  } catch (err) {
    cloudAuthBtn.title = `Offline Mode: ${err?.message || 'Firebase unavailable'}`;
  }
}
initFirebaseCloudSync();

// Real Cross-Device File Transfer Relay (with auto canvas compression + newest-first sort)
const fileTransferInput = document.getElementById('fileTransferInput');
const pairingCodeInput = document.getElementById('pairingCodeInput');
const uploadTransferBtn = document.getElementById('uploadTransferBtn');
const receiveTransferBtn = document.getElementById('receiveTransferBtn');
const fileTransferStatus = document.getElementById('fileTransferStatus');

function cleanPairingCode() {
  return (pairingCodeInput?.value || 'SHUVO1')
    .toUpperCase()
    .replace(/[^A-Z0-9_-]/g, '')
    .slice(0, 16);
}

async function compressImageFileForTransfer(file) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    const objectUrl = URL.createObjectURL(file);
    img.onload = async () => {
      URL.revokeObjectURL(objectUrl);
      const maxDim = 1280;
      let w = img.naturalWidth || 1024;
      let h = img.naturalHeight || 1024;
      if (w > maxDim || h > maxDim) {
        const ratio = Math.min(maxDim / w, maxDim / h);
        w = Math.max(1, Math.round(w * ratio));
        h = Math.max(1, Math.round(h * ratio));
      }
      const c = document.createElement('canvas');
      c.width = w;
      c.height = h;
      c.getContext('2d').drawImage(img, 0, 0, w, h);

      let blob = await new Promise((r) => c.toBlob(r, 'image/jpeg', 0.72));
      if (blob && blob.size > 540 * 1024) {
        blob = await new Promise((r) => c.toBlob(r, 'image/jpeg', 0.48));
      }
      if (!blob) return reject(new Error('Image compression failed'));
      resolve(blob);
    };
    img.onerror = () => {
      URL.revokeObjectURL(objectUrl);
      reject(new Error('Could not decode image'));
    };
    img.src = objectUrl;
  });
}

uploadTransferBtn?.addEventListener('click', async () => {
  const rawFile = fileTransferInput.files?.[0];
  if (!rawFile) {
    fileTransferStatus.textContent = 'দয়া করে একটি ফাইল সিলেক্ট করুন।';
    return;
  }
  const code = cleanPairingCode();
  if (code.length < 4) {
    fileTransferStatus.textContent = '❌ পেয়ারিং কোড কমপক্ষে ৪ অক্ষরের হতে হবে।';
    return;
  }
  if (!state.cloudUser || !state.cloudDb || !state.cloudFsMod) {
    fileTransferStatus.textContent = '☁️ মোবাইল-ওয়েব ট্রান্সফারের জন্য উপরের "☁️ Sync" বোতামে Google Sign-In করুন।';
    return;
  }

  let uploadBlob = rawFile;
  let uploadName = rawFile.name;
  let uploadMime = rawFile.type || 'application/octet-stream';

  if (rawFile.size > 550 * 1024 && rawFile.type?.startsWith('image/')) {
    fileTransferStatus.textContent = '🖼️ বড় ছবি স্বয়ংক্রিয়ভাবে কম্প্রেস করা হচ্ছে...';
    try {
      uploadBlob = await compressImageFileForTransfer(rawFile);
      uploadMime = 'image/jpeg';
      if (!uploadName.toLowerCase().endsWith('.jpg') && !uploadName.toLowerCase().endsWith('.jpeg')) {
        uploadName = uploadName.replace(/\.[^.]+$/, '') + '.jpg';
      }
    } catch (_) {}
  }

  if (uploadBlob.size > 550 * 1024) {
    fileTransferStatus.textContent = `❌ ক্লাউড রিলে সীমা ৫৫০ KB (বর্তমান ${(uploadBlob.size / 1024).toFixed(1)} KB)।`;
    return;
  }

  try {
    fileTransferStatus.textContent = `⚡ "${uploadName}" ক্লাউডে পাঠানো হচ্ছে (কোড: ${code})...`;
    const buf = await uploadBlob.arrayBuffer();
    const bytes = new Uint8Array(buf);
    let binary = '';
    for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
    const payloadBase64 = btoa(binary);
    const fs = state.cloudFsMod;
    const id = 'gtr_' + Math.random().toString(36).slice(2, 16);
    await fs.setDoc(fs.doc(state.cloudDb, 'gesture_transfers', id), {
      senderUid: state.cloudUser.uid,
      pairingCode: code,
      fileName: uploadName.slice(0, 180),
      mimeType: uploadMime.slice(0, 110),
      payloadBase64,
      fileSizeBytes: uploadBlob.size,
      status: 'grabbed',
      createdAt: fs.serverTimestamp(),
      updatedAt: fs.serverTimestamp()
    });
    fileTransferStatus.textContent = `✓ "${uploadName}" সফলভাবে ক্লাউড রিলেতে প্রস্তুত! অ্যান্ড্রয়েড অ্যাপে কোড ${code} দিয়ে গ্রহণ করুন।`;
  } catch (err) {
    fileTransferStatus.textContent = `❌ ট্রান্সফার ব্যর্থ: ${err?.message || 'আবার চেষ্টা করুন'}`;
  }
});

receiveTransferBtn?.addEventListener('click', async () => {
  const code = cleanPairingCode();
  if (code.length < 4) {
    fileTransferStatus.textContent = '❌ পেয়ারিং কোড কমপক্ষে ৪ অক্ষরের হতে হবে।';
    return;
  }
  if (!state.cloudUser || !state.cloudDb || !state.cloudFsMod) {
    fileTransferStatus.textContent = '☁️ ফাইল রিসিভ করতে প্রথমে উপরের "☁️ Sync" বোতামে Google Sign-In করুন।';
    return;
  }
  try {
    fileTransferStatus.textContent = `🔍 কোড "${code}" দিয়ে ক্লাউড ফাইল খোঁজা হচ্ছে...`;
    const fs = state.cloudFsMod;
    const q = fs.query(
      fs.collection(state.cloudDb, 'gesture_transfers'),
      fs.where('pairingCode', '==', code),
      fs.where('status', '==', 'grabbed')
    );
    const snap = await fs.getDocs(q);
    if (snap.empty) {
      fileTransferStatus.textContent = `কোনো অপেক্ষমাণ ফাইল পাওয়া যায়নি (কোড: ${code})।`;
      return;
    }
    const sortedDocs = [...snap.docs].sort((a, b) => {
      const tA = a.data().createdAt?.toMillis?.() || 0;
      const tB = b.data().createdAt?.toMillis?.() || 0;
      return tB - tA;
    });
    const newestDoc = sortedDocs[0];
    const data = newestDoc.data();
    const binStr = atob(data.payloadBase64);
    const bytes = new Uint8Array(binStr.length);
    for (let i = 0; i < binStr.length; i++) bytes[i] = binStr.charCodeAt(i);
    const blob = new Blob([bytes], { type: data.mimeType || 'application/octet-stream' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = data.fileName || 'received_file';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 5000);
    await fs.updateDoc(fs.doc(state.cloudDb, 'gesture_transfers', newestDoc.id), {
      status: 'received',
      updatedAt: fs.serverTimestamp()
    });
    fileTransferStatus.textContent = `✓ "${data.fileName}" সফলভাবে ডাউনলোড ও রিসিভ করা হয়েছে!`;
  } catch (err) {
    fileTransferStatus.textContent = `❌ রিসিভ ব্যর্থ: ${err?.message || 'আবার চেষ্টা করুন'}`;
  }
});

// PWA 1-Tap Install Handling (Android Chrome WebAPK + iOS/Browser fallback guide)
const installPwaBtn = document.getElementById('installPwaBtn');
const installGuideModal = document.getElementById('installGuideModal');
const closeInstallModalBtn = document.getElementById('closeInstallModalBtn');
const copyAppUrlBtn = document.getElementById('copyAppUrlBtn');
let deferredInstallPrompt = null;

function isRunningStandalonePwa() {
  return window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true;
}

if (isRunningStandalonePwa()) {
  installPwaBtn?.classList.add('hidden');
}

window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  deferredInstallPrompt = e;
  if (installPwaBtn && !isRunningStandalonePwa()) {
    installPwaBtn.classList.remove('hidden');
    installPwaBtn.textContent = '📲 ইনস্টল করুন';
  }
});

window.addEventListener('appinstalled', () => {
  deferredInstallPrompt = null;
  installPwaBtn?.classList.add('hidden');
  installGuideModal?.classList.remove('open');
  if (remoteBannerBar) {
    remoteBannerBar.textContent = '✓ Hey Shuvo সফলভাবে আপনার ডিভাইসে অ্যাপ হিসেবে ইনস্টল হয়েছে!';
    remoteBannerBar.classList.remove('hidden');
  }
});

installPwaBtn?.addEventListener('click', async () => {
  if (deferredInstallPrompt) {
    try {
      deferredInstallPrompt.prompt();
      const choice = await deferredInstallPrompt.userChoice;
      if (choice?.outcome === 'accepted') {
        deferredInstallPrompt = null;
        installPwaBtn.classList.add('hidden');
      }
      return;
    } catch (_) {}
  }
  installGuideModal?.classList.add('open');
});

closeInstallModalBtn?.addEventListener('click', () => {
  installGuideModal?.classList.remove('open');
});

copyAppUrlBtn?.addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText(window.location.href);
    copyAppUrlBtn.textContent = '✓ লিংক কপি হয়েছে!';
    setTimeout(() => { copyAppUrlBtn.textContent = '🔗 অ্যাপ লিংক কপি করুন'; }, 2000);
  } catch (_) {}
});

// Initial Avatar & Voice State setup
updateAvatarUI();

// Start Auto Voice on first user interaction (browser autoplay policy)
document.body.addEventListener('click', () => {
  if (state.activeTab === 'panel-voice' && state.autoMode && !state.isListening && !state.isSpeaking && !state.micPermissionDenied) {
    startSpeechRecognition();
  }
}, { once: true });
