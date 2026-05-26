// API REST — appels en provenance de l'APK MoMo Fin
const express = require('express');
const router = express.Router();

const { pool } = require('../lib/db');
const { authDevice } = require('../lib/auth');
const parser = require('../lib/parser');

// Ping public — utile pour tester que le serveur est joignable
router.get('/ping', (req, res) => res.json({ ok: true, ts: new Date().toISOString() }));

// === Authentification depuis l'APK (inscription / connexion en direct) ===
const usersLib = require('../lib/users');

router.post('/auth/inscription', async (req, res) => {
    try {
        const { email, password, name } = req.body || {};
        const { user, deviceToken, deviceCode } = await usersLib.createUser(email, password, name);
        res.json({ ok: true, token: deviceToken, code: deviceCode, email: user.email, name: user.name });
    } catch (err) {
        let msg = err.message;
        if (err.code === '23505') msg = 'Cet email est déjà utilisé.';
        res.status(400).json({ ok: false, error: msg });
    }
});

router.post('/auth/code', async (req, res) => {
    try {
        const { code } = req.body || {};
        const d = await usersLib.deviceByCode(code);
        if (!d) return res.status(404).json({ ok: false, error: 'Code introuvable ou expiré' });
        res.json({ ok: true, token: d.token, label: d.label });
    } catch (err) {
        res.status(400).json({ ok: false, error: err.message });
    }
});

router.post('/auth/connexion', async (req, res) => {
    try {
        const { email, password } = req.body || {};
        const user = await usersLib.authenticate(email, password);
        if (!user) return res.status(401).json({ ok: false, error: 'Email ou mot de passe incorrect' });
        const devices = await usersLib.getUserDevices(user.id);
        let token = devices[0]?.token;
        if (!token) token = await usersLib.createDevice(user.id, 'Téléphone');
        res.json({ ok: true, token, email: user.email, name: user.name });
    } catch (err) {
        res.status(400).json({ ok: false, error: err.message });
    }
});


// Auth check — l'APK appelle cet endpoint pour vérifier que son token est valide
router.get('/whoami', authDevice, (req, res) => {
    res.json({ ok: true, token: req.deviceToken, label: req.deviceLabel });
});

/**
 * POST /api/transactions/sync
 * Body : { transactions: [ { sender, body, smsTimestamp, operator?, type?, amount?, currency?, reference?, ts? } ] }
 * L'APK envoie un lot de SMS Mobile Money. Le serveur réparse si nécessaire et insère (déduplication via UNIQUE).
 */
router.post('/transactions/sync', authDevice, async (req, res) => {
    const list = Array.isArray(req.body?.transactions) ? req.body.transactions : [];
    if (list.length === 0) return res.json({ inserted: 0 });

    const client = await pool.connect();
    try {
        await client.query('BEGIN');
        let inserted = 0;
        for (const item of list) {
            const sender = item.sender || '';
            const body = item.body || '';
            // Si le client a déjà parsé, on lui fait confiance ; sinon on reparse côté serveur.
            // STRICT : on reparse cote serveur. Si le SMS ne match aucun pattern, on ignore.
            const parsed = parser.parse(sender, body, item.smsTimestamp);
            if (!parsed) continue;

            const r = await client.query(
                `INSERT INTO transactions
                    (device_id, operator, type, subtype, amount, currency, reference, phone_number, ts, raw_sender, raw_body)
                 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11)
                 ON CONFLICT (device_id, raw_body, ts) DO NOTHING
                 RETURNING id`,
                [
                    req.deviceToken,
                    parsed.operator,
                    parsed.type,
                    parsed.subtype,
                    parsed.amount,
                    parsed.currency,
                    parsed.reference,
                    parsed.phone_number,
                    parsed.ts,
                    sender,
                    body
                ]
            );
            if (r.rowCount > 0) inserted++;
        }
        await client.query('COMMIT');
        res.json({ inserted, total: list.length });
    } catch (err) {
        await client.query('ROLLBACK');
        console.error('sync error', err);
        res.status(500).json({ error: err.message });
    } finally {
        client.release();
    }
});

// GET /api/transactions — pour debug / consultation depuis l'APK
router.get('/transactions', authDevice, async (req, res) => {
    const { rows } = await pool.query(
        `SELECT id, operator, type, subtype, amount, currency, reference, phone_number, ts, raw_sender
         FROM transactions
         WHERE device_id = $1
         ORDER BY ts DESC
         LIMIT 500`,
        [req.deviceToken]
    );
    res.json(rows);
});

// POST /api/patron — ajouter une entrée Reçu / Sortie patron
router.post('/patron', authDevice, async (req, res) => {
    const { type, amount, note } = req.body || {};
    if (!['RECU', 'SORTIE'].includes(type)) return res.status(400).json({ error: 'type invalide' });
    const a = Number(amount);
    if (!isFinite(a) || a <= 0) return res.status(400).json({ error: 'montant invalide' });
    const { rows } = await pool.query(
        `INSERT INTO patron_entries (device_id, type, amount, note, ts)
         VALUES ($1,$2,$3,$4,NOW()) RETURNING id, ts`,
        [req.deviceToken, type, a, note || '']
    );
    res.json(rows[0]);
});

router.get('/patron', authDevice, async (req, res) => {
    const { rows } = await pool.query(
        `SELECT id, type, amount, note, ts
         FROM patron_entries WHERE device_id = $1 ORDER BY ts DESC LIMIT 500`,
        [req.deviceToken]
    );
    res.json(rows);
});

router.delete('/patron/:id', authDevice, async (req, res) => {
    await pool.query(
        'DELETE FROM patron_entries WHERE id = $1 AND device_id = $2',
        [req.params.id, req.deviceToken]
    );
    res.json({ ok: true });
});


// ===== MES POINTS — saisie quotidienne =====
async function getUserIdForDevice(deviceToken) {
    const { rows } = await pool.query('SELECT user_id FROM devices WHERE token = $1', [deviceToken]);
    return rows[0]?.user_id || null;
}

router.post('/points', authDevice, async (req, res) => {
    const uid = await getUserIdForDevice(req.deviceToken);
    if (!uid) return res.status(400).json({ error: 'Aucun utilisateur associé à ce token' });
    const p = req.body || {};
    try {
        await pool.query(
            `INSERT INTO daily_points (user_id, day_key, om, momo, moov, wave, djamo, cfa, entree, sortie, note, updated_at)
             VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,NOW())
             ON CONFLICT (user_id, day_key) DO UPDATE SET
                om=$3, momo=$4, moov=$5, wave=$6, djamo=$7, cfa=$8,
                entree=$9, sortie=$10, note=$11, updated_at=NOW()`,
            [uid, p.day_key, p.om||0, p.momo||0, p.moov||0, p.wave||0,
             p.djamo||0, p.cfa||0, p.entree||0, p.sortie||0, p.note||'']
        );
        res.json({ ok: true });
    } catch (e) {
        res.status(500).json({ error: e.message });
    }
});

router.get('/points', authDevice, async (req, res) => {
    const uid = await getUserIdForDevice(req.deviceToken);
    if (!uid) return res.json([]);
    const { rows } = await pool.query(
        `SELECT day_key, om, momo, moov, wave, djamo, cfa, entree, sortie, note, updated_at
         FROM daily_points WHERE user_id = $1 ORDER BY day_key DESC LIMIT 500`,
        [uid]
    );
    res.json(rows);
});

router.delete('/points/:dayKey', authDevice, async (req, res) => {
    const uid = await getUserIdForDevice(req.deviceToken);
    if (!uid) return res.json({ ok: true });
    await pool.query('DELETE FROM daily_points WHERE user_id = $1 AND day_key = $2',
        [uid, req.params.dayKey]);
    res.json({ ok: true });
});



// ===== Gestion des appareils depuis l'APK (admin) =====
router.get('/devices', authDevice, async (req, res) => {
    const uid = await getUserIdForDevice(req.deviceToken);
    if (!uid) return res.json([]);
    const { rows } = await pool.query(
        `SELECT token, label, code, created_at FROM devices WHERE user_id = $1 ORDER BY created_at DESC`,
        [uid]
    );
    res.json(rows);
});

router.post('/devices', authDevice, async (req, res) => {
    const uid = await getUserIdForDevice(req.deviceToken);
    if (!uid) return res.status(401).json({ ok: false, error: 'Non autorisé' });
    const label = (req.body?.label || 'Téléphone').trim();
    try {
        const { token, code } = await usersLib.createDevice(uid, label);
        res.json({ ok: true, token, code, label });
    } catch (err) {
        res.status(400).json({ ok: false, error: err.message });
    }
});

router.delete('/devices/:token', authDevice, async (req, res) => {
    const uid = await getUserIdForDevice(req.deviceToken);
    if (!uid) return res.status(401).json({ ok: false });
    await pool.query(
        'DELETE FROM devices WHERE token = $1 AND user_id = $2 AND token != $3',
        [req.params.token, uid, req.deviceToken]
    );
    res.json({ ok: true });
});



// === Sync des dossiers Mes Comptes (folders) — push depuis l'app ===
// Body: { folders: [{ client_id, name, created_at, entries: [{ client_id, type, amount, note, ts }] }] }
// Strategie : REPLACE complet pour ce device (suppression + insertion). Idempotent.
router.post('/folders/sync', authDevice, async (req, res) => {
    const { folders } = req.body || {};
    if (!Array.isArray(folders)) return res.status(400).json({ error: 'folders array attendu' });
    // Securite : ne JAMAIS effacer le serveur si l'app envoie une liste vide
    if (folders.length === 0) return res.json({ ok: true, folders: 0, entries: 0, note: 'liste vide ignoree' });
    const conn = await pool.connect();
    try {
        await conn.query('BEGIN');
        // Supprimer uniquement les dossiers de l'app qui ne sont plus dans le payload
        const cids = folders.map(f => String(f.client_id || '')).filter(Boolean);
        await conn.query(
            `DELETE FROM folders WHERE device_id = $1 AND client_id <> ALL($2::text[])`,
            [req.deviceToken, cids]
        );
        let nbFolders = 0, nbEntries = 0;
        for (const f of folders) {
            const cid = String(f.client_id || '');
            if (!cid || !f.name) continue;
            // Upsert du dossier (conserve l'id existant -> conserve les entrees web liees)
            const fr = await conn.query(
                `INSERT INTO folders (device_id, client_id, name, created_at, updated_at)
                 VALUES ($1, $2, $3, COALESCE($4::timestamptz, NOW()), NOW())
                 ON CONFLICT (device_id, client_id)
                 DO UPDATE SET name = EXCLUDED.name, updated_at = NOW()
                 RETURNING id`,
                [req.deviceToken, cid, String(f.name).substring(0, 200),
                 f.created_at ? new Date(f.created_at).toISOString() : null]
            );
            const folderId = fr.rows[0].id;
            nbFolders++;
            // Effacer uniquement les entrees issues de l'APP (preserver celles ajoutees par l'admin web : client_id 'web_...')
            await conn.query(
                `DELETE FROM folder_entries WHERE folder_id = $1 AND (client_id IS NULL OR client_id NOT LIKE 'web\_%')`,
                [folderId]
            );
            for (const e of (f.entries || [])) {
                if (!['RECU','SORTIE'].includes(e.type)) continue;
                const amt = Number(e.amount);
                if (!isFinite(amt) || amt <= 0) continue;
                await conn.query(
                    `INSERT INTO folder_entries (folder_id, device_id, client_id, type, amount, note, ts)
                     VALUES ($1, $2, $3, $4, $5, $6, COALESCE($7::timestamptz, NOW()))
                     ON CONFLICT (folder_id, client_id)
                     DO UPDATE SET type = EXCLUDED.type, amount = EXCLUDED.amount, note = EXCLUDED.note, ts = EXCLUDED.ts`,
                    [folderId, req.deviceToken, String(e.client_id || ''),
                     e.type, amt, String(e.note || '').substring(0, 500),
                     e.ts ? new Date(e.ts).toISOString() : null]
                );
                nbEntries++;
            }
        }
        await conn.query('COMMIT');
        res.json({ ok: true, folders: nbFolders, entries: nbEntries });
    } catch (err) {
        await conn.query('ROLLBACK');
        console.error('Sync folders error:', err);
        res.status(500).json({ error: err.message });
    } finally {
        conn.release();
    }
});

// GET /api/folders : liste les folders du device authentifie (pour pull si besoin)
router.get('/folders', authDevice, async (req, res) => {
    const { pool } = require('../lib/db');
    const { rows: folders } = await pool.query(
        `SELECT id, client_id, name, created_at FROM folders
         WHERE device_id = $1 ORDER BY created_at DESC`,
        [req.deviceToken]
    );
    const { rows: entries } = await pool.query(
        `SELECT folder_id, client_id, type, amount, note, ts FROM folder_entries
         WHERE device_id = $1 ORDER BY ts DESC`,
        [req.deviceToken]
    );
    const byFolder = new Map();
    for (const e of entries) {
        if (!byFolder.has(e.folder_id)) byFolder.set(e.folder_id, []);
        byFolder.get(e.folder_id).push(e);
    }
    res.json(folders.map(f => ({ ...f, entries: byFolder.get(f.id) || [] })));
});



// GET /api/folders/all — retourne TOUS les folders des devices appartenant au meme user
// (l'admin et tous ses sous-comptes voient les meme comptes des autres boutiques en lecture)
router.get('/folders/all', authDevice, async (req, res) => {
    const { pool } = require('../lib/db');
    // 1. Trouver le user_id proprietaire du device authentifie
    const { rows: drows } = await pool.query(
        'SELECT user_id FROM devices WHERE token = $1', [req.deviceToken]
    );
    if (drows.length === 0 || !drows[0].user_id) {
        return res.status(403).json({ error: 'Device sans utilisateur rattache' });
    }
    const userId = drows[0].user_id;

    // 2. Charger tous les folders + entries des devices de ce user
    const { rows: folders } = await pool.query(
        `SELECT f.id, f.client_id, f.name, f.created_at, f.device_id,
                d.label AS device_label, d.code AS device_code
         FROM folders f
         JOIN devices d ON d.token = f.device_id
         WHERE d.user_id = $1
         ORDER BY d.label, f.created_at DESC`,
        [userId]
    );
    const { rows: entries } = await pool.query(
        `SELECT folder_id, client_id, type, amount, note, ts
         FROM folder_entries e
         WHERE folder_id IN (SELECT f.id FROM folders f JOIN devices d ON d.token = f.device_id WHERE d.user_id = $1)
         ORDER BY ts DESC`,
        [userId]
    );
    const byFolder = new Map();
    for (const e of entries) {
        if (!byFolder.has(e.folder_id)) byFolder.set(e.folder_id, []);
        byFolder.get(e.folder_id).push(e);
    }
    res.json(folders.map(f => ({
        id: f.id, client_id: f.client_id, name: f.name, created_at: f.created_at,
        device_id: f.device_id, device_label: f.device_label, device_code: f.device_code,
        is_own: f.device_id === req.deviceToken,
        entries: byFolder.get(f.id) || []
    })));
});


// === RECUS ===
// POST /api/receipts/sync : REPLACE complet des recus du device
router.post('/receipts/sync', authDevice, async (req, res) => {
    const { receipts } = req.body || {};
    if (!Array.isArray(receipts)) return res.status(400).json({ error: 'receipts array attendu' });
    const { pool } = require('../lib/db');
    const conn = await pool.connect();
    try {
        await conn.query('BEGIN');
        await conn.query('DELETE FROM receipts WHERE device_id = $1', [req.deviceToken]);
        let n = 0;
        for (const r of receipts) {
            const cid = String(r.client_id || '');
            if (!cid) continue;
            await conn.query(
                `INSERT INTO receipts (device_id, client_id, partner_name, client_name, objet, conditions, amount, currency, ts)
                 VALUES ($1,$2,$3,$4,$5,$6,$7,$8, COALESCE($9::timestamptz, NOW()))`,
                [req.deviceToken, cid,
                 String(r.partner_name || '').substring(0, 200),
                 String(r.client_name || '').substring(0, 200),
                 String(r.objet || '').substring(0, 300),
                 String(r.conditions || '').substring(0, 2000),
                 Number(r.amount) || 0,
                 String(r.currency || 'FCFA'),
                 r.ts ? new Date(r.ts).toISOString() : null]
            );
            n++;
        }
        await conn.query('COMMIT');
        res.json({ ok: true, receipts: n });
    } catch (e) {
        await conn.query('ROLLBACK');
        console.error('receipts sync error:', e);
        res.status(500).json({ error: e.message });
    } finally {
        conn.release();
    }
});

// GET /api/receipts : recus du device authentifie (pour restore)
router.get('/receipts', authDevice, async (req, res) => {
    const { pool } = require('../lib/db');
    const { rows } = await pool.query(
        `SELECT client_id, partner_name, client_name, objet, conditions, amount, currency, ts
         FROM receipts WHERE device_id = $1 ORDER BY ts DESC`,
        [req.deviceToken]
    );
    res.json(rows);
});

// GET /api/receipt-config : regles + cachet (URL) du proprietaire du device
router.get('/receipt-config', authDevice, async (req, res) => {
    const { pool } = require('../lib/db');
    const { rows: drows } = await pool.query('SELECT user_id FROM devices WHERE token = $1', [req.deviceToken]);
    if (!drows[0]?.user_id) return res.json({ rules: '', company: '', cachet: false });
    const uid = drows[0].user_id;
    const { rows } = await pool.query(
        'SELECT name, email, receipt_rules, (cachet_data IS NOT NULL) AS has_cachet FROM users WHERE id = $1', [uid]
    );
    const u = rows[0] || {};
    const { rows: objs } = await pool.query(
        'SELECT label, conditions FROM receipt_objects WHERE user_id = $1 ORDER BY label', [uid]
    );
    res.json({
        rules: u.receipt_rules || '',
        company: u.name || (u.email ? u.email.split('@')[0] : ''),
        cachet: !!u.has_cachet,
        cachet_url: u.has_cachet ? `/cachet/${uid}` : null,
        user_id: uid,
        objects: objs.map(o => ({ label: o.label, conditions: o.conditions || '' }))
    });
});


module.exports = router;
