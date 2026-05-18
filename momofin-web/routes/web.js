// Routes web (tableau de bord HTML + génération PDF à la demande)
const express = require('express');
const router = express.Router();

const { pool } = require('../lib/db');
const { requireUser } = require('../lib/users');
const pdf = require('../lib/pdf');

// =====================================================================
// Routes PUBLIQUES (pas de mot de passe) — page de téléchargement APK
// =====================================================================

router.get('/telecharger', async (req, res) => {
    const fs = require('fs');
    const path = require('path');
    const localDir = path.join(__dirname, '..', 'public', 'apks');

    // Récupérer le logo du premier user (= admin) pour l'afficher publiquement
    let logoUrl = null;
    try {
        const { rows } = await pool.query('SELECT id FROM users WHERE logo_data IS NOT NULL ORDER BY id ASC LIMIT 1');
        if (rows[0]) logoUrl = `/logo/${rows[0].id}`;
    } catch (_) {}

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
        logoUrl,
        repo,
        
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
// Routes protégées : exigent un utilisateur connecté
function adminOnly(req, res, next) {
    if (!req.user) return res.redirect('/connexion');
    if (req.user.isSubAccount) {
        return res.status(403).send(`
            <html><body style="font-family:sans-serif;padding:40px;text-align:center;">
                <h2 style="color:#DC2626;">🔒 Accès réservé à l'administrateur</h2>
                <p>Cette page n'est pas accessible aux sous-comptes.</p>
                <p><a href="/" style="color:#1565C0;">← Retour au tableau de bord</a></p>
            </body></html>
        `);
    }
    next();
}

function protect(req, res, next) {
    if (!req.user) return res.redirect('/connexion?next=' + encodeURIComponent(req.originalUrl));
    next();
}

// Dashboard principal — liste regroupée par jour
router.get('/', protect, async (req, res) => {
    const { from, to } = parseRange(req.query);
    const days = await loadDays(req.user, from, to);
    const totals = sumTotals(days);
    const accountName = req.user.isSubAccount ? `${req.user.deviceLabel} (sous-compte)` : (req.user.name || req.user.email.split('@')[0]);

    // Pour l'admin : totaux par appareil sur la periode choisie
    let deviceTotals = [];
    if (!req.user.isSubAccount) {
        const conds = ['d.user_id = $1']; const args = [req.user.id];
        if (from) { args.push(from.toISOString()); conds.push(`(t.ts IS NULL OR t.ts >= $${args.length})`); }
        if (to)   { args.push(to.toISOString());   conds.push(`(t.ts IS NULL OR t.ts <= $${args.length})`); }
        const { rows } = await pool.query(
            `SELECT d.token, d.label, d.code, d.created_at,
                    COALESCE(SUM(CASE WHEN t.type='RECU'   THEN t.amount END), 0) AS recu,
                    COALESCE(SUM(CASE WHEN t.type='SORTIE' THEN t.amount END), 0) AS sortie,
                    COUNT(t.id) AS nb_tx
             FROM devices d
             LEFT JOIN transactions t ON t.device_id = d.token
              AND ${conds.slice(1).length ? conds.slice(1).join(' AND ') : 'TRUE'}
             WHERE d.user_id = $1
             GROUP BY d.token, d.label, d.code, d.created_at
             ORDER BY d.created_at DESC`,
            args
        );
        deviceTotals = rows.map(r => ({
            token: r.token, label: r.label, code: r.code,
            recu: Number(r.recu), sortie: Number(r.sortie), nb_tx: Number(r.nb_tx)
        }));
    }

    res.render('index', { user: req.user, days, totals, fmt, from, to, accountName, deviceTotals });
});

function parseRange(q) {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    let from = null, to = null;
    if (q.preset === 'today') { from = today; to = new Date(today.getTime() + 86400000 - 1); }
    else if (q.preset === 'week') { from = new Date(today.getTime() - 6 * 86400000); to = new Date(today.getTime() + 86400000 - 1); }
    else if (q.preset === 'month') { from = new Date(today.getFullYear(), today.getMonth(), 1); to = new Date(today.getFullYear(), today.getMonth() + 1, 1, 0, 0, -1); }
    else if (q.preset === 'year') { from = new Date(today.getFullYear(), 0, 1); to = new Date(today.getFullYear() + 1, 0, 1, 0, 0, -1); }
    else {
        if (q.from) from = new Date(q.from);
        if (q.to) { to = new Date(q.to); to.setHours(23, 59, 59); }
    }
    return { from, to };
}

async function getAccountName() {
    // Premier utilisateur = nom de compte par défaut (admin)
    try {
        const { rows } = await pool.query('SELECT name, email FROM users ORDER BY id ASC LIMIT 1');
        return rows[0]?.name || rows[0]?.email?.split('@')[0] || 'MoMo Fin';
    } catch (_) { return 'MoMo Fin'; }
}

// Section PATRON
router.get('/patron', protect, async (req, res) => {
    const { rows: entries } = await pool.query(
        req.user.deviceToken
          ? `SELECT id, type, amount, note, ts, device_id FROM patron_entries WHERE device_id = $1 ORDER BY ts DESC`
          : `SELECT id, type, amount, note, ts, device_id FROM patron_entries WHERE device_id IN (SELECT token FROM devices WHERE user_id = $1) ORDER BY ts DESC`,
        [req.user.deviceToken || req.user.id]
    );
    const recu = entries.filter(e => e.type === 'RECU').reduce((s, e) => s + Number(e.amount), 0);
    const sortie = entries.filter(e => e.type === 'SORTIE').reduce((s, e) => s + Number(e.amount), 0);
    res.render('patron', { user: req.user, entries, recu, sortie, total: recu - sortie, fmt });
});

// Ajouter une entrée PATRON depuis le web
router.post('/patron', protect, async (req, res) => {
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

router.post('/patron/:id/delete', protect, async (req, res) => {
    await pool.query('DELETE FROM patron_entries WHERE id = $1', [req.params.id]);
    res.redirect('/patron');
});

// Génération PDF (avec ou sans le pwd dans la requête)
router.get('/pdf', protect, async (req, res) => {
    const { from, to } = parseRange(req.query);
    const conds = []; const args = [];
    if (from) { args.push(from.toISOString()); conds.push(`ts >= $${args.length}`); }
    if (to) { args.push(to.toISOString()); conds.push(`ts <= $${args.length}`); }
    const where = conds.length ? 'WHERE ' + conds.join(' AND ') : '';
    const { rows: tx } = await pool.query(
        `SELECT operator, type, amount, currency, reference, phone_number, ts FROM transactions ${where} ORDER BY ts DESC`,
        args
    );
    const accountName = req.user.isSubAccount ? `${req.user.deviceLabel} (sous-compte)` : (req.user.name || req.user.email.split('@')[0]);
    const fmtDate = d => d ? d.toISOString().substring(0, 10) : null;
    const filename = (() => {
        const safe = accountName.replace(/[^A-Za-z0-9_-]/g, '_');
        if (from && to) return `${safe}_du_${fmtDate(from)}_au_${fmtDate(to)}.pdf`;
        return `${safe}_${Date.now()}.pdf`;
    })();
    res.setHeader('Content-Type', 'application/pdf');
    res.setHeader('Content-Disposition', `inline; filename="${filename}"`);
    // Récupérer le logo de l'utilisateur si présent
    let logoBuffer = null;
    try {
        const { rows: lrows } = await pool.query('SELECT logo_data FROM users WHERE id = $1', [req.user.id]);
        if (lrows[0]?.logo_data) logoBuffer = lrows[0].logo_data;
    } catch (_) {}
    pdf.generate(res, tx, [], { accountName, from, to, logoBuffer });
});

// Page Devices : créer / lister les tokens d'appariement avec les APK

// MES POINTS
router.get('/points', protect, async (req, res) => {
    let where, args;
    if (req.user.deviceToken) {
        // Sous-compte : pas d'isolation par device pour points (les points sont au niveau user)
        where = `WHERE user_id = $1`; args = [req.user.id];
    } else {
        where = `WHERE user_id = $1`; args = [req.user.id];
    }
    const { rows } = await pool.query(
        `SELECT day_key, om, momo, moov, wave, djamo, cfa, entree, sortie, note, updated_at
         FROM daily_points ${where} ORDER BY day_key DESC LIMIT 500`, args);
    res.render('points', { rows, fmt, user: req.user });
});

router.get('/devices', adminOnly, async (req, res) => {
    const { rows } = await pool.query(
        'SELECT token, label, code, created_at FROM devices WHERE user_id = $1 ORDER BY created_at DESC',
        [req.user.id]
    );
    res.render('devices', { user: req.user, devices: rows });
});

router.post('/devices', adminOnly, async (req, res) => {
    const label = (req.body.label || 'Téléphone').trim();
    const users = require('../lib/users');
    await users.createDevice(req.user.id, label);
    res.redirect('/devices');
});

router.post('/devices/:token/delete', adminOnly, async (req, res) => {
    await pool.query('DELETE FROM devices WHERE token = $1 AND user_id = $2', [req.params.token, req.user.id]);
    res.redirect('/devices');
});

// --- Helpers ---

async function loadDays(user, from, to) {
    let where, args;
    if (user.deviceToken) {
        // sous-compte : filtre par device
        where = `WHERE device_id = $1`; args = [user.deviceToken];
    } else {
        // admin : tous les devices de l'utilisateur
        where = `WHERE device_id IN (SELECT token FROM devices WHERE user_id = $1)`;
        args = [user.id];
    }
    if (from) { args.push(from.toISOString()); where += ` AND ts >= $${args.length}`; }
    if (to) { args.push(to.toISOString()); where += ` AND ts <= $${args.length}`; }
    const { rows } = await pool.query(
        `SELECT operator, type, amount, currency, reference, phone_number, ts
         FROM transactions ${where} ORDER BY ts DESC LIMIT 5000`,
        args
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


// Transactions d'un appareil precis (admin only)
router.get('/devices/:token/transactions', adminOnly, async (req, res) => {
    const token = req.params.token;
    // Verifier que le device appartient bien a l'admin
    const { rows: drows } = await pool.query(
        'SELECT token, label, code FROM devices WHERE token = $1 AND user_id = $2',
        [token, req.user.id]
    );
    if (drows.length === 0) return res.status(404).send('Appareil introuvable.');
    const device = drows[0];

    const { from, to } = parseRange(req.query);
    const typeFilter = (req.query.type || '').toUpperCase();
    // Charger les jours filtres pour CET appareil uniquement
    const conds = ['device_id = $1']; const args = [token];
    if (from) { args.push(from.toISOString()); conds.push(`ts >= $${args.length}`); }
    if (to)   { args.push(to.toISOString());   conds.push(`ts <= $${args.length}`); }
    if (typeFilter === 'RECU' || typeFilter === 'SORTIE') {
        args.push(typeFilter); conds.push(`type = $${args.length}`);
    }
    const { rows } = await pool.query(
        `SELECT operator, type, amount, currency, reference, phone_number, ts
         FROM transactions WHERE ${conds.join(' AND ')} ORDER BY ts DESC LIMIT 5000`,
        args
    );
    const grouped = new Map();
    for (const r of rows) {
        const d = new Date(r.ts);
        const k = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate())).toISOString();
        if (!grouped.has(k)) grouped.set(k, []);
        grouped.get(k).push(r);
    }
    const days = [...grouped.entries()]
        .sort((a, b) => new Date(b[0]) - new Date(a[0]))
        .map(([day, list]) => {
            const recu = list.filter(x => x.type === 'RECU').reduce((s, x) => s + Number(x.amount), 0);
            const sortie = list.filter(x => x.type === 'SORTIE').reduce((s, x) => s + Number(x.amount), 0);
            return { day, list, recu, sortie, solde: recu - sortie };
        });
    const totals = sumTotals(days);
    res.render('device-transactions', { user: req.user, device, days, totals, from, to, fmt, typeFilter });
});

// PDF des transactions d'un appareil precis
router.get('/devices/:token/pdf', adminOnly, async (req, res) => {
    const token = req.params.token;
    const { rows: drows } = await pool.query(
        'SELECT token, label FROM devices WHERE token = $1 AND user_id = $2',
        [token, req.user.id]
    );
    if (drows.length === 0) return res.status(404).send('Appareil introuvable.');
    const device = drows[0];
    const { from, to } = parseRange(req.query);
    const typeFilter = (req.query.type || '').toUpperCase();
    const conds = ['device_id = $1']; const args = [token];
    if (from) { args.push(from.toISOString()); conds.push(`ts >= $${args.length}`); }
    if (to)   { args.push(to.toISOString());   conds.push(`ts <= $${args.length}`); }
    if (typeFilter === 'RECU' || typeFilter === 'SORTIE') {
        args.push(typeFilter); conds.push(`type = $${args.length}`);
    }
    const { rows: tx } = await pool.query(
        `SELECT operator, type, amount, currency, reference, phone_number, ts
         FROM transactions WHERE ${conds.join(' AND ')} ORDER BY ts DESC`,
        args
    );
    const typeLabel = typeFilter === 'RECU' ? ' (Retrait)' : (typeFilter === 'SORTIE' ? ' (Depot)' : '');
    const accountName = `${req.user.name || req.user.email.split('@')[0]} — ${device.label || 'Appareil'}${typeLabel}`;
    const fmtDate = d => d ? d.toISOString().substring(0, 10) : null;
    const safe = accountName.replace(/[^A-Za-z0-9_-]/g, '_');
    const filename = (from && to) ? `${safe}_du_${fmtDate(from)}_au_${fmtDate(to)}.pdf` : `${safe}_${Date.now()}.pdf`;
    res.setHeader('Content-Type', 'application/pdf');
    res.setHeader('Content-Disposition', `inline; filename="${filename}"`);
    let logoBuffer = null;
    try {
        const { rows: lrows } = await pool.query('SELECT logo_data FROM users WHERE id = $1', [req.user.id]);
        if (lrows[0]?.logo_data) logoBuffer = lrows[0].logo_data;
    } catch (_) {}
    pdf.generate(res, tx, [], { accountName, from, to, logoBuffer });
});



// Nettoyer les transactions invalides (qui ne matchent plus le parser strict)
router.get('/nettoyer-transactions', adminOnly, async (req, res) => {
    // Compter d'abord
    const { rows: txs } = await pool.query(
        `SELECT id, raw_sender, raw_body FROM transactions
         WHERE device_id IN (SELECT token FROM devices WHERE user_id = $1)`,
        [req.user.id]
    );
    const parser = require('../lib/parser');
    const toDelete = [];
    for (const t of txs) {
        const r = parser.parse(t.raw_sender || '', t.raw_body || '', Date.now());
        if (!r) toDelete.push(t.id);
    }
    res.send(`
        <html><body style="font-family:sans-serif; max-width:600px; margin:40px auto; padding:20px;">
            <h2>🧹 Nettoyage des transactions</h2>
            <p>Total transactions de votre compte : <strong>${txs.length}</strong></p>
            <p>Transactions invalides a supprimer (ne matchent plus le parser strict) : <strong style="color:#DC2626;">${toDelete.length}</strong></p>
            ${toDelete.length === 0
                ? '<p style="color:#059669;">Aucune transaction a supprimer. Tout est conforme aux 6 patterns.</p>'
                : `<form method="POST" action="/nettoyer-transactions" onsubmit="return confirm('Supprimer definitivement ${toDelete.length} transactions invalides ?')">
                       <button type="submit" style="background:#DC2626; color:white; border:none; padding:12px 24px; border-radius:6px; font-size:16px; cursor:pointer;">Supprimer ${toDelete.length} transactions</button>
                   </form>`}
            <p><a href="/" style="color:#1565C0;">← Retour au tableau de bord</a></p>
        </body></html>
    `);
});

router.post('/nettoyer-transactions', adminOnly, async (req, res) => {
    const { rows: txs } = await pool.query(
        `SELECT id, raw_sender, raw_body FROM transactions
         WHERE device_id IN (SELECT token FROM devices WHERE user_id = $1)`,
        [req.user.id]
    );
    const parser = require('../lib/parser');
    const toDelete = [];
    for (const t of txs) {
        const r = parser.parse(t.raw_sender || '', t.raw_body || '', Date.now());
        if (!r) toDelete.push(t.id);
    }
    if (toDelete.length > 0) {
        await pool.query(`DELETE FROM transactions WHERE id = ANY($1::bigint[])`, [toDelete]);
    }
    res.send(`
        <html><body style="font-family:sans-serif; max-width:600px; margin:40px auto; padding:20px;">
            <h2>✓ Nettoyage termine</h2>
            <p><strong>${toDelete.length}</strong> transactions invalides supprimees.</p>
            <p><a href="/" style="color:#1565C0;">← Retour au tableau de bord</a></p>
        </body></html>
    `);
});


module.exports = router;
