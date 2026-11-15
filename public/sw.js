const CACHE_NAME = 'hey-shuvo-pwa-v7';
const ASSETS = [
  './',
  './index.html',
  './styles.css',
  './app.js',
  './manifest.json',
  './assets/icons/icon-192.png',
  './assets/icons/icon-512.png',
  './assets/icons/icon-maskable-512.png',
  './assets/avatars/avatar_female_chloe.webp',
  './assets/avatars/avatar_female_chloe_close.jpg',
  './assets/avatars/avatar_female_chloe_seated.jpg',
  './assets/avatars/avatar_male_connor_rk800.jpg',
  './assets/avatars/avatar_male_connor.jpg',
  './assets/avatars/avatar_male_connor_smile.jpg',
  './assets/avatars/avatar_male_connor_profile.jpg'
];

self.addEventListener('install', (e) => {
  e.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(ASSETS)).catch(() => {})
  );
  self.skipWaiting();
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener('fetch', (e) => {
  if (e.request.method !== 'GET') return;
  const url = new URL(e.request.url);
  if (url.origin !== self.location.origin) return;

  // Cache-first for static avatar images
  if (url.pathname.includes('/assets/avatars/')) {
    e.respondWith(
      caches.match(e.request).then((cached) =>
        cached ||
        fetch(e.request).then((res) => {
          const clone = res.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(e.request, clone));
          return res;
        })
      )
    );
    return;
  }

  // Network-first for HTML, JS, CSS, manifest so PWA updates deploy immediately
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        if (res && res.ok) {
          const clone = res.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(e.request, clone));
        }
        return res;
      })
      .catch(() => caches.match(e.request))
  );
});
