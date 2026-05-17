// Routes web (tableau de bord HTML + génération PDF à la demande)
const express = require('express');
const router = express.Router();

const { pool } = require('../lib/db');
const { authAdmin } = require('../lib/auth');
const pdf = require('../lib/pdf');

// =====================================================================
// Routes PUBLIQUES (pas de mot de passe) — page de téléchargement APK
// =====================================================================

router.get('/telecharger', (req, res) => {
    const fs = require('fs');
    const path = require('path');
    const localDir = path.join(__dirname, '..', 'public', 'apks');

    // Priorité 1 : APK hébergés localement dans public/apks/
    const localSms = fs.existsSync(path.join(localDir, 'MoMoSMS-debug.apk'))
        ? '/apks/MoMoSMS-debug.apk' : null;
    const localFin = fs.existsSync(path.join(localDir, 'MoMoFin-debug.apk'))
        ? '/apks/MoMoFin-debug.apk' : null;

    // Priorité 2 : URL GitHub release (si pas d'APK local)
    const repo = process.env.GITHUB_REPO || '';
    const release = process.env.GITHUB_RELEASE_TAG || 'latest';
    const base = repo
        ? `https://github.com/${repo}/releases/download/${release}`
        : null;

    res.render('telecharger', {
        user: req.user || null,
        repo,
        smsApk: localSms || (base ? `${base}/MoMoSMS-debug.apk` : null),
        finApk: localFin || (base ? `${base}/MoMoFin-debug.apk` : null),
        source: localSms || localFin ? 'local' : (repo ? 'github' : 'none'),
        webAppUrl: (process.env.PUBLIC_URL || `${req.protocol}://${req.get('host')}`)
    });
});

// Alias pratique : /apk → page téléchargement
router.get('/apk', (req, res) => res.redirect('/telecharger'));

// Manifest et service worker pour la PWA — publics
router.get('/manifest.webmanifest', (req, res) => {
    res.setHeader('Content-Type', 'application/manifest+json');
    res.send(JSON.stringify({
        name: 'MoMo Fin',
        short_name: 'MoMo Fin',
        description: 'Suivi des transactions Mobile Money',
        start_url: '/',
        display: 'standalone',
        background_color: '#f4f6fa',
        theme_color: '#1565C0',
        orientation: 'portrait',
        icons: [
            { src: '/icon.svg', sizes: 'any', type: 'image/svg+xml', purpose: 'any maskable' },
            { src: '/icon-192.svg', sizes: '192x192', type: 'image/svg+xml' },
            { src: '/icon-512.svg', sizes: '512x512', type: 'image/svg+xml' }
        ]
    }));
});

// =====================================================================
// Au-delà, toutes les routes sont protégées par mot de passe admin
// =====================================================================
router.use(authAdmin);

// Dashboard principal — liste regroupée par jour
router.get('/', async (req, res) => {
    const days = await loadDays();
    const totals = sumTotals(days);
    res.render('index', { days, totals, fmt });
});

// Section PATRON
router.get('/patron', async (req, res) => {
    const { rows: entries } = await pool.query(
        'SELECT id, type, amount, note, ts, device_id FROM patron_entries ORDER BY ts DESC'
    );
    const recu = entries.filter(e => e.type === 'RECU').reduce((s, e) => s + Number(e.amount), 0);
    const sortie = entries.filter(e => e.type === 'SORTIE').reduce((s, e) => s + Number(e.amount), 0);
    res.render('patron', { entries, recu, sortie, total: recu - sortie, fmt });
});

// Ajouter une entrée PATRON depuis le web
router.post('/patron', async (req, res) => {
    const { type, amount, note, device_id } = req.body;
    const a = Number((amount || '').toString().replace(',', '.'));
    if (!['RECU', 'SORTIE'].includes(type) || !isFinite(a) || a <= 0) {
        return res.redirect('/patron');
    }
    await pool.query(
        `INSERT INTO patron_entries (device_id, type, amount, note, ts)
         VALUES ($1,$2,$3,$4,NOW())`,
        [device_id || 'web-admin', type, a, note || '']
    );
    res.redirect('/patron');
});

router.post('/patron/:id/delete', async (req, res) => {
    await pool.query('DELETE FROM patron_entries WHERE id = $1', [req.params.id]);
    res.redirect('/patron');
});

// Génération PDF (avec ou sans le pwd dans la requête)
router.get('/pdf', async (req, res) => {
    const { rows: tx } = await pool.query(
        'SELECT operator, type, amount, currency, reference, phone_number, ts FROM transactions ORDER BY ts DESC'
    );
    const { rows: patronList } = await pool.query(
        'SELECT type, amount, note, ts FROM patron_entries ORDER BY ts DESC'
    );
    res.setHeader('Content-Type', 'application/pdf');
    res.setHeader('Content-Disposition', `inline; filename="MoMoFin_${Date.now()}.pdf"`);
    pdf.generate(res, tx, patronList);
});

// Page Devices : créer / lister les tokens d'appariement avec les APK
router.get('/devices', async (req, res) => {
    const { rows } = await pool.query('SELECT token, label, created_at FROM devices ORDER BY created_at DESC');
    res.render('devices', { devices: rows });
});

router.post('/devices', async (req, res) => {
    const label = (req.body.label || 'Téléphone').trim();
    const token = generateToken();
    await pool.query('INSERT INTO devices (token, label) VALUES ($1, $2)', [token, label]);
    res.redirect('/devices');
});

router.post('/devices/:token/delete', async (req, res) => {
    await pool.query('DELETE FROM devices WHERE token = $1', [req.params.token]);
    res.redirect('/devices');
});

// --- Helpers ---

async function loadDays() {
    const { rows } = await pool.query(
        `SELECT operator, type, amount, currency, reference, phone_number, ts
         FROM transactions ORDER BY ts DESC LIMIT 2000`
    );
    const grouped = new Map();
    for (const r of rows) {
        const d = new Date(r.ts);
        const k = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate())).toISOString();
        if (!grouped.has(k)) grouped.set(k, []);
        grouped.get(k).push(r);
    }
    return [...grouped.entries()]
        .sort((a, b) => new Date(b[0]) - new Date(a[0]))
        .map(([day, list]) => {
            const recu = list.filter(x => x.type === 'RECU').reduce((s, x) => s + Number(x.amount), 0);
            const sortie = list.filter(x => x.type === 'SORTIE').reduce((s, x) => s + Number(x.amount), 0);
            return { day, list, recu, sortie, solde: recu - sortie };
        });
}

function sumTotals(days) {
    return days.reduce(
        (acc, d) => ({ recu: acc.recu + d.recu, sortie: acc.sortie + d.sortie }),
        { recu: 0, sortie: 0 }
    );
}

function fmt(n) {
    return Number(n || 0).toLocaleString('fr-FR', { maximumFractionDigits: 2 });
}

function generateToken() {
    return [...Array(32)]
        .map(() => 'abcdefghijklmnopqrstuvwxyz0123456789'[Math.floor(Math.random() * 36)])
        .join('');
}

module.exports = router;
