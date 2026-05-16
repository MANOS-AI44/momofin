// API REST — appels en provenance de l'APK MoMo Fin
const express = require('express');
const router = express.Router();

const { pool } = require('../lib/db');
const { authDevice } = require('../lib/auth');
const parser = require('../lib/parser');

// Ping public — utile pour tester que le serveur est joignable
router.get('/ping', (req, res) => res.json({ ok: true, ts: new Date().toISOString() }));

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
            const parsed = (item.type && item.amount !== undefined)
                ? {
                    operator: item.operator || parser.detectOperator(sender, body),
                    type: item.type,
                    amount: Number(item.amount) || 0,
                    currency: item.currency || '',
                    reference: item.reference || '',
                    ts: item.ts || new Date(item.smsTimestamp || Date.now()).toISOString()
                }
                : parser.parse(sender, body, item.smsTimestamp);

            const r = await client.query(
                `INSERT INTO transactions
                    (device_id, operator, type, amount, currency, reference, ts, raw_sender, raw_body)
                 VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9)
                 ON CONFLICT (device_id, raw_body, ts) DO NOTHING
                 RETURNING id`,
                [
                    req.deviceToken,
                    parsed.operator,
                    parsed.type,
                    parsed.amount,
                    parsed.currency,
                    parsed.reference,
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
        `SELECT id, operator, type, amount, currency, reference, ts, raw_sender
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

module.exports = router;
