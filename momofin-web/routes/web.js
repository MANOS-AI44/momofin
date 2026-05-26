// Routes web (tableau de bord HTML + génération PDF à la demande)
const express = require('express');
const router = express.Router();

const { pool } = require('../lib/db');
const { requireUser } = require('../lib/users');
const pdf = require('../lib/pdf');
const parser = require('../lib/parser');

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
    const subtypeFilter = (req.query.subtype || '').toUpperCase();
    const days = await loadDays(req.user, from, to, subtypeFilter);
    const totals = sumTotals(days);
    const accountName = req.user.isSubAccount ? `${req.user.deviceLabel} (sous-compte)` : (req.user.name || req.user.email.split('@')[0]);

    // Pour l'admin : bilan complet par assistant/boutique sur la periode choisie
    let deviceTotals = [];
    if (!req.user.isSubAccount) {
        // 2 requetes en parallele : transactions SMS + saisies manuelles
        const args = [req.user.id];
        const tCond = [];
        if (from) { args.push(from.toISOString()); tCond.push(`t.ts >= $${args.length}`); }
        if (to)   { args.push(to.toISOString());   tCond.push(`t.ts <= $${args.length}`); }
        const tWhere = tCond.length ? 'AND ' + tCond.join(' AND ') : '';

        const argsP = [req.user.id];
        const pCond = [];
        if (from) { argsP.push(from.toISOString()); pCond.push(`p.ts >= $${argsP.length}`); }
        if (to)   { argsP.push(to.toISOString());   pCond.push(`p.ts <= $${argsP.length}`); }
        const pWhere = pCond.length ? 'AND ' + pCond.join(' AND ') : '';

        const [txAgg, patAgg] = await Promise.all([
            pool.query(
                `SELECT d.token, d.label, d.code, d.created_at,
                        COALESCE(SUM(CASE WHEN t.type='RECU'   THEN t.amount END), 0) AS recu,
                        COALESCE(SUM(CASE WHEN t.type='SORTIE' THEN t.amount END), 0) AS sortie,
                        COUNT(t.id) AS nb_tx,
                        COUNT(DISTINCT DATE(t.ts)) AS nb_jours,
                        MAX(t.ts) AS last_tx
                 FROM devices d
                 LEFT JOIN transactions t ON t.device_id = d.token ${tWhere}
                 WHERE d.user_id = $1
                 GROUP BY d.token, d.label, d.code, d.created_at
                 ORDER BY d.created_at DESC`,
                args
            ),
            pool.query(
                `SELECT d.token,
                        COUNT(p.id) AS nb_patron,
                        COALESCE(SUM(CASE WHEN p.type='RECU'   THEN p.amount END), 0) AS p_entree,
                        COALESCE(SUM(CASE WHEN p.type='SORTIE' THEN p.amount END), 0) AS p_sortie,
                        MAX(p.ts) AS last_patron
                 FROM devices d
                 LEFT JOIN patron_entries p ON p.device_id = d.token ${pWhere}
                 WHERE d.user_id = $1
                 GROUP BY d.token`,
                argsP
            )
        ]);

        const patByToken = new Map();
        for (const r of patAgg.rows) patByToken.set(r.token, r);

        deviceTotals = txAgg.rows.map(r => {
            const p = patByToken.get(r.token) || {};
            const lastTx = r.last_tx ? new Date(r.last_tx).getTime() : 0;
            const lastPat = p.last_patron ? new Date(p.last_patron).getTime() : 0;
            const lastActivity = Math.max(lastTx, lastPat);
            return {
                token: r.token, label: r.label, code: r.code,
                recu: Number(r.recu), sortie: Number(r.sortie),
                nb_tx: Number(r.nb_tx), nb_jours: Number(r.nb_jours || 0),
                nb_patron: Number(p.nb_patron || 0),
                p_entree: Number(p.p_entree || 0), p_sortie: Number(p.p_sortie || 0),
                lastActivity: lastActivity > 0 ? new Date(lastActivity).toISOString() : null
            };
        });
    }

    res.render('index', { user: req.user, days, totals, fmt, from, to, accountName, deviceTotals, subtypeFilter });
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
    const subtypeFilter = (req.query.subtype || '').toUpperCase();
    const conds = []; const args = [];
    if (from) { args.push(from.toISOString()); conds.push(`ts >= $${args.length}`); }
    if (to) { args.push(to.toISOString()); conds.push(`ts <= $${args.length}`); }
    if (subtypeFilter === 'CAISSE') {
        conds.push(`(subtype IN ('DEPOT','RETRAIT') OR subtype IS NULL)`);
    } else if (subtypeFilter === 'TRANSFERTS') {
        conds.push(`(subtype IN ('TRANSFERT_ENVOYE','TRANSFERT_RECU') OR subtype IS NULL)`);
    } else if (['DEPOT','RETRAIT','TRANSFERT_ENVOYE','TRANSFERT_RECU'].includes(subtypeFilter)) {
        args.push(subtypeFilter); conds.push(`(subtype = $${args.length} OR subtype IS NULL)`);
    }
    const where = conds.length ? 'WHERE ' + conds.join(' AND ') : '';
    const { rows: tx } = await pool.query(
        `SELECT operator, type, subtype, amount, currency, reference, phone_number, ts, raw_sender, raw_body FROM transactions ${where} ORDER BY ts DESC`,
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
    pdf.generate(res, tx.map(reparseRow), [], { accountName, from, to, logoBuffer });
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


// Filet de securite : re-parse a la volee depuis raw_body pour aligner avec ce que l'app voit.
function reparseRow(t) {
    if (!t.raw_body) return t;
    const r = parser.parse(t.raw_sender || '', t.raw_body || '', new Date(t.ts).getTime());
    if (!r) return t;
    return {
        ...t,
        type: r.type,
        // ⚠️ Toujours utiliser le subtype du parser actuel (ecrase le stocke potentiellement obsolete)
        subtype: r.subtype,
        amount: r.amount,
        currency: r.currency || t.currency,
        phone_number: r.phone_number || t.phone_number,
        reference: r.reference || t.reference,
        operator: r.operator || t.operator
    };
}

// --- Helpers ---

async function loadDays(user, from, to, subtypeFilter) {
    let where, args;
    if (user.deviceToken) {
        where = `WHERE device_id = $1`; args = [user.deviceToken];
    } else {
        where = `WHERE device_id IN (SELECT token FROM devices WHERE user_id = $1)`;
        args = [user.id];
    }
    if (from) { args.push(from.toISOString()); where += ` AND ts >= $${args.length}`; }
    if (to)   { args.push(to.toISOString());   where += ` AND ts <= $${args.length}`; }
    // Filtre subtype : applique avant reparseRow (sur valeur stockee) ; le filet de securite filtrera apres
    if (subtypeFilter === 'CAISSE') {
        where += ` AND (subtype IN ('DEPOT','RETRAIT') OR subtype IS NULL)`;
    } else if (subtypeFilter === 'TRANSFERTS') {
        where += ` AND (subtype IN ('TRANSFERT_ENVOYE','TRANSFERT_RECU') OR subtype IS NULL)`;
    } else if (['DEPOT','RETRAIT','TRANSFERT_ENVOYE','TRANSFERT_RECU'].includes(subtypeFilter)) {
        args.push(subtypeFilter); where += ` AND (subtype = $${args.length} OR subtype IS NULL)`;
    }
    const { rows } = await pool.query(
        `SELECT operator, type, subtype, amount, currency, reference, phone_number, ts, raw_sender, raw_body
         FROM transactions ${where} ORDER BY ts DESC LIMIT 5000`,
        args
    );
    const grouped = new Map();
    for (const raw of rows) {
        const r = reparseRow(raw);
        // Filet de securite : si filtre subtype, ecarter les lignes qui ne correspondent pas apres re-parse
        if (subtypeFilter === 'CAISSE' && r.subtype && !['DEPOT','RETRAIT'].includes(r.subtype)) continue;
        else if (subtypeFilter === 'TRANSFERTS' && r.subtype && !['TRANSFERT_ENVOYE','TRANSFERT_RECU'].includes(r.subtype)) continue;
        else if (['DEPOT','RETRAIT','TRANSFERT_ENVOYE','TRANSFERT_RECU'].includes(subtypeFilter)
                 && r.subtype && r.subtype !== subtypeFilter) continue;
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
    // typeFilter peut etre subtype (DEPOT/RETRAIT/TRANSFERT_ENVOYE/TRANSFERT_RECU) ou type (RECU/SORTIE)
    if (typeFilter === 'CAISSE') {
        conds.push(`subtype IN ('DEPOT','RETRAIT')`);
    } else if (typeFilter === 'TRANSFERTS') {
        conds.push(`subtype IN ('TRANSFERT_ENVOYE','TRANSFERT_RECU')`);
    } else if (['DEPOT','RETRAIT','TRANSFERT_ENVOYE','TRANSFERT_RECU'].includes(typeFilter)) {
        args.push(typeFilter); conds.push(`subtype = $${args.length}`);
    } else if (typeFilter === 'RECU' || typeFilter === 'SORTIE') {
        args.push(typeFilter); conds.push(`type = $${args.length}`);
    }
    const { rows } = await pool.query(
        `SELECT operator, type, subtype, amount, currency, reference, phone_number, ts, raw_sender, raw_body
         FROM transactions WHERE ${conds.join(' AND ')} ORDER BY ts DESC LIMIT 5000`,
        args
    );
    const grouped = new Map();
    for (const raw of rows) {
        const r = reparseRow(raw);
        // Filet de securite : si filtre subtype, ecarter les lignes qui ne correspondent pas apres re-parse
        if (typeFilter === 'CAISSE' && r.subtype && !['DEPOT','RETRAIT'].includes(r.subtype)) continue;
        else if (typeFilter === 'TRANSFERTS' && r.subtype && !['TRANSFERT_ENVOYE','TRANSFERT_RECU'].includes(r.subtype)) continue;
        else if (['DEPOT','RETRAIT','TRANSFERT_ENVOYE','TRANSFERT_RECU'].includes(typeFilter)
                 && r.subtype && r.subtype !== typeFilter) continue;
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
    if (typeFilter === 'CAISSE') {
        conds.push(`subtype IN ('DEPOT','RETRAIT')`);
    } else if (typeFilter === 'TRANSFERTS') {
        conds.push(`subtype IN ('TRANSFERT_ENVOYE','TRANSFERT_RECU')`);
    } else if (['DEPOT','RETRAIT','TRANSFERT_ENVOYE','TRANSFERT_RECU'].includes(typeFilter)) {
        args.push(typeFilter); conds.push(`subtype = $${args.length}`);
    } else if (typeFilter === 'RECU' || typeFilter === 'SORTIE') {
        args.push(typeFilter); conds.push(`type = $${args.length}`);
    }
    const { rows: tx } = await pool.query(
        `SELECT operator, type, subtype, amount, currency, reference, phone_number, ts, raw_sender, raw_body
         FROM transactions WHERE ${conds.join(' AND ')} ORDER BY ts DESC`,
        args
    );
    const typeLabel = {
        'DEPOT': ' (Depot)', 'RETRAIT': ' (Retrait)',
        'TRANSFERT_ENVOYE': ' (Transfert envoye)', 'TRANSFERT_RECU': ' (Transfert recu)',
        'RECU': ' (Retrait)', 'SORTIE': ' (Depot)'
    }[typeFilter] || '';
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
    pdf.generate(res, tx.map(reparseRow), [], { accountName, from, to, logoBuffer });
});



// === Re-traitement complet : re-parse, met a jour, deduplique, supprime les invalides ===
router.get('/nettoyer-transactions', adminOnly, async (req, res) => {

    const { rows: txs } = await pool.query(
        `SELECT id, device_id, operator, type, amount, phone_number, reference, ts, raw_sender, raw_body
         FROM transactions
         WHERE device_id IN (SELECT token FROM devices WHERE user_id = $1)`,
        [req.user.id]
    );
    // Analyse : combien a supprimer (rejets + doublons) + combien a mettre a jour
    const seen = new Map(); // cle = device_id + raw_body — garde le plus ancien id
    let toDelete = 0, toUpdate = 0, duplicates = 0, invalid = 0;
    for (const t of txs) {
        const r = parser.parse(t.raw_sender || '', t.raw_body || '', new Date(t.ts).getTime());
        if (!r) { toDelete++; invalid++; continue; }
        const key = (t.device_id || '') + '|||' + (t.raw_body || '');
        if (seen.has(key)) { toDelete++; duplicates++; continue; }
        seen.set(key, t.id);
        const numNorm = parser.normalizePhone ? parser.normalizePhone(t.phone_number || '') : (t.phone_number || '');
        const diff = (r.type !== t.type)
            || (Math.abs(Number(r.amount) - Number(t.amount)) > 0.01)
            || ((r.phone_number || '') !== numNorm)
            || ((r.reference || '') !== (t.reference || ''))
            || ((r.operator || '') !== (t.operator || ''));
        if (diff) toUpdate++;
    }
    res.send(`
        <html><body style="font-family:sans-serif; max-width:680px; margin:40px auto; padding:24px; line-height:1.6;">
            <h2>🔄 Re-traitement des transactions</h2>
            <p>Cette operation re-parse toutes les transactions a partir du texte SMS d'origine
               avec le parser strict actuel, met a jour les valeurs erronees,
               supprime les doublons et les SMS hors-perimetre.</p>
            <table style="border-collapse:collapse; width:100%; margin:16px 0;">
              <tr><td style="padding:8px; border-bottom:1px solid #eee;">Total transactions en base</td><td style="text-align:right; padding:8px; border-bottom:1px solid #eee;"><strong>${txs.length}</strong></td></tr>
              <tr><td style="padding:8px; border-bottom:1px solid #eee; color:#DC2626;">A supprimer (parser strict les rejette)</td><td style="text-align:right; padding:8px; border-bottom:1px solid #eee; color:#DC2626;"><strong>${invalid}</strong></td></tr>
              <tr><td style="padding:8px; border-bottom:1px solid #eee; color:#DC2626;">A supprimer (doublons memes SMS)</td><td style="text-align:right; padding:8px; border-bottom:1px solid #eee; color:#DC2626;"><strong>${duplicates}</strong></td></tr>
              <tr><td style="padding:8px; border-bottom:1px solid #eee; color:#F59E0B;">A mettre a jour (type, montant, ref... corriges)</td><td style="text-align:right; padding:8px; border-bottom:1px solid #eee; color:#F59E0B;"><strong>${toUpdate}</strong></td></tr>
              <tr><td style="padding:8px;">A conserver telles quelles</td><td style="text-align:right; padding:8px;"><strong>${txs.length - toDelete - toUpdate}</strong></td></tr>
            </table>
            ${(toDelete === 0 && toUpdate === 0)
                ? '<p style="color:#059669; font-size:16px;">✓ Aucune action necessaire. Toutes les transactions sont coherentes avec le parser actuel.</p>'
                : `<form method="POST" action="/nettoyer-transactions" onsubmit="return confirm('Confirmer ? ${toDelete} suppressions + ${toUpdate} mises a jour.')">
                       <button type="submit" style="background:#1565C0; color:white; border:none; padding:14px 28px; border-radius:8px; font-size:16px; cursor:pointer; font-weight:bold;">🔄 Re-traiter maintenant</button>
                   </form>`}
            <p style="margin-top:24px;"><a href="/" style="color:#1565C0;">← Retour au tableau de bord</a></p>
        </body></html>
    `);
});

router.post('/nettoyer-transactions', adminOnly, async (req, res) => {

    const { rows: txs } = await pool.query(
        `SELECT id, device_id, operator, type, amount, phone_number, reference, ts, raw_sender, raw_body
         FROM transactions
         WHERE device_id IN (SELECT token FROM devices WHERE user_id = $1)`,
        [req.user.id]
    );
    const seen = new Map();
    const toDeleteIds = [];
    const toUpdate = []; // {id, type, amount, phone, ref, operator}
    for (const t of txs) {
        const r = parser.parse(t.raw_sender || '', t.raw_body || '', new Date(t.ts).getTime());
        if (!r) { toDeleteIds.push(t.id); continue; }
        const key = (t.device_id || '') + '|||' + (t.raw_body || '');
        if (seen.has(key)) { toDeleteIds.push(t.id); continue; }
        seen.set(key, t.id);
        const numNorm = parser.normalizePhone ? parser.normalizePhone(t.phone_number || '') : (t.phone_number || '');
        const diff = (r.type !== t.type)
            || (Math.abs(Number(r.amount) - Number(t.amount)) > 0.01)
            || ((r.phone_number || '') !== numNorm)
            || ((r.reference || '') !== (t.reference || ''))
            || ((r.operator || '') !== (t.operator || ''));
        if (diff) toUpdate.push({ id: t.id, type: r.type, amount: r.amount, phone: r.phone_number, ref: r.reference, op: r.operator });
    }
    const client = await pool.connect();
    try {
        await client.query('BEGIN');
        if (toDeleteIds.length > 0) {
            await client.query(`DELETE FROM transactions WHERE id = ANY($1::bigint[])`, [toDeleteIds]);
        }
        for (const u of toUpdate) {
            await client.query(
                `UPDATE transactions SET type=$1, amount=$2, phone_number=$3, reference=$4, operator=$5
                 WHERE id=$6`,
                [u.type, u.amount, u.phone, u.ref, u.op, u.id]
            );
        }
        await client.query('COMMIT');
    } catch (e) {
        await client.query('ROLLBACK');
        console.error(e);
        client.release();
        return res.status(500).send('Erreur : ' + e.message);
    }
    client.release();
    res.send(`
        <html><body style="font-family:sans-serif; max-width:600px; margin:40px auto; padding:24px;">
            <h2>✓ Re-traitement termine</h2>
            <p><strong style="color:#DC2626;">${toDeleteIds.length}</strong> transactions supprimees (invalides + doublons)</p>
            <p><strong style="color:#F59E0B;">${toUpdate.length}</strong> transactions corrigees (type/montant/ref)</p>
            <p>Le tableau de bord est maintenant synchronise avec ce que l'app voit reellement sur le telephone.</p>
            <p style="margin-top:20px;"><a href="/" style="background:#1565C0; color:white; padding:10px 20px; text-decoration:none; border-radius:6px;">← Retour au tableau de bord</a></p>
        </body></html>
    `);
});



// Operateur effectif : utilise celui stocke, ou le derive du prefixe du numero si 'Autre'/vide
function effectiveOperator(tx) {
    const stored = (tx.operator || '').trim();
    if (stored && stored !== 'Autre') return stored;
    const phone = String(tx.phone_number || '');
    const p2 = phone.substring(0, 2);
    if (p2 === '07' || p2 === '08' || p2 === '09') return 'Orange';
    if (p2 === '05' || p2 === '04' || p2 === '06') return 'MTN';
    if (p2 === '01' || p2 === '02' || p2 === '03') return 'MOOV';
    return stored || 'Autre';
}


// Vue admin : tous les Mes Comptes (folders) de tous les assistants
router.get('/comptes', adminOnly, async (req, res) => {
    const { rows: folders } = await pool.query(
        `SELECT f.id, f.name, f.created_at, f.device_id,
                d.label AS device_label, d.code AS device_code,
                COUNT(e.id)                                       AS nb_entries,
                COALESCE(SUM(CASE WHEN e.type='RECU' THEN e.amount END), 0)   AS total_entree,
                COALESCE(SUM(CASE WHEN e.type='SORTIE' THEN e.amount END), 0) AS total_sortie
         FROM folders f
         JOIN devices d ON d.token = f.device_id AND d.user_id = $1
         LEFT JOIN folder_entries e ON e.folder_id = f.id
         GROUP BY f.id, f.name, f.created_at, f.device_id, d.label, d.code
         ORDER BY d.label, f.created_at DESC`,
        [req.user.id]
    );
    // Regrouper par device
    const byDevice = new Map();
    for (const f of folders) {
        if (!byDevice.has(f.device_id)) {
            byDevice.set(f.device_id, { label: f.device_label, code: f.device_code, folders: [] });
        }
        byDevice.get(f.device_id).folders.push({
            id: f.id, name: f.name, created_at: f.created_at,
            nb_entries: Number(f.nb_entries),
            total_entree: Number(f.total_entree),
            total_sortie: Number(f.total_sortie),
            solde: Number(f.total_entree) - Number(f.total_sortie)
        });
    }
    res.render('comptes', { user: req.user, groups: [...byDevice.values()], fmt });
});

router.get('/comptes/:id', adminOnly, async (req, res) => {
    const { rows: frows } = await pool.query(
        `SELECT f.id, f.name, f.created_at, d.label AS device_label, d.code AS device_code
         FROM folders f
         JOIN devices d ON d.token = f.device_id AND d.user_id = $1
         WHERE f.id = $2`, [req.user.id, req.params.id]
    );
    if (frows.length === 0) return res.status(404).send('Dossier introuvable.');
    const folder = frows[0];
    const { rows: entries } = await pool.query(
        `SELECT id, type, amount, note, ts FROM folder_entries
         WHERE folder_id = $1 ORDER BY ts DESC`, [folder.id]
    );
    const totalE = entries.filter(e => e.type === 'RECU').reduce((s, e) => s + Number(e.amount), 0);
    const totalS = entries.filter(e => e.type === 'SORTIE').reduce((s, e) => s + Number(e.amount), 0);
    res.render('compte-detail', { user: req.user, folder, entries, totalE, totalS, solde: totalE - totalS, fmt });
});

// Admin ajoute une entree/sortie sur le compte d'une boutique (carnet partage)
router.post('/comptes/:id/entry', adminOnly, async (req, res) => {
    const { rows } = await pool.query(
        `SELECT f.id, f.device_id FROM folders f
         JOIN devices d ON d.token = f.device_id AND d.user_id = $1
         WHERE f.id = $2`, [req.user.id, req.params.id]
    );
    if (rows.length === 0) return res.status(404).send('Dossier introuvable.');
    const type = (req.body.type === 'RECU') ? 'RECU' : 'SORTIE';
    const amt = Number(String(req.body.amount || '').replace(/\s/g, '').replace(',', '.')) || 0;
    if (amt > 0) {
        const cid = 'web_' + Date.now();
        await pool.query(
            `INSERT INTO folder_entries (folder_id, device_id, client_id, type, amount, note, ts)
             VALUES ($1, $2, $3, $4, $5, $6, NOW())`,
            [rows[0].id, rows[0].device_id, cid, type, amt, String(req.body.note || '').substring(0, 500)]
        );
    }
    res.redirect('/comptes/' + req.params.id);
});

// Admin supprime une entree d'un compte de boutique
router.post('/comptes/:id/entry/:eid/supprimer', adminOnly, async (req, res) => {
    const { rows } = await pool.query(
        `SELECT f.id FROM folders f
         JOIN devices d ON d.token = f.device_id AND d.user_id = $1
         WHERE f.id = $2`, [req.user.id, req.params.id]
    );
    if (rows.length === 0) return res.status(404).send('Dossier introuvable.');
    await pool.query('DELETE FROM folder_entries WHERE id = $1 AND folder_id = $2',
        [req.params.eid, req.params.id]);
    res.redirect('/comptes/' + req.params.id);
});



// Page publique : guide d'installation de l'APK
router.get('/installer', async (req, res) => {
    res.render('installer', { user: req.user || null, repo: process.env.GITHUB_REPO || '' });
});

module.exports = router;
module.exports.effectiveOperator = effectiveOperator;
