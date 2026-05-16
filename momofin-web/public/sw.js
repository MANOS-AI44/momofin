// Service worker minimal pour rendre MoMo Fin Web installable (PWA)
// Stratégie : network-first pour les routes dynamiques, cache pour les assets statiques.

const CACHE = 'momofin-v1';
const STATIC_ASSETS = [
    '/telecharger',
    '/style.css',
    '/icon.svg',
    '/icon-192.svg',
    '/icon-512.svg',
    '/manifest.webmanifest'
];

self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(CACHE).then(c => c.addAll(STATIC_ASSETS)).catch(() => {})
    );
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys().then(keys =>
            Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
        )
    );
    self.clients.claim();
});

self.addEventListener('fetch', (event) => {
    const url = new URL(event.request.url);
    if (event.request.method !== 'GET') return;
    if (STATIC_ASSETS.includes(url.pathname)) {
        event.respondWith(
            caches.match(event.request).then(r => r || fetch(event.request))
        );
        return;
    }
    // Network-first pour le reste
    event.respondWith(
        fetch(event.request).catch(() => caches.match(event.request))
    );
});
