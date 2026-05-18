// Gestion utilisateurs : inscription, connexion, sessions.
const bcrypt = require('bcryptjs');
const crypto = require('crypto');
const { pool } = require('./db');

const SESSION_DAYS = 30;

function shortCode() {
    // 6 caractères majuscules + chiffres (sans 0/O/1/I pour lisibilité)
    const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
    let out = '';
    for (let i = 0; i < 6; i++) {
        out += alphabet[Math.floor(Math.random() * alphabet.length)];
    }
    return out;
}

function randomToken(length = 40) {
    return crypto.randomBytes(length).toString('base64url').slice(0, length);
}

async function createUser(email, password, name) {
    const cleanEmail = (email || '').trim().toLowerCase();
    if (!cleanEmail || !cleanEmail.includes('@')) throw new Error('Email invalide');
    if (!password || password.length < 6) throw new Error('Mot de passe trop court (6 caractères minimum)');

    const hash = await bcrypt.hash(password, 10);
    const { rows } = await pool.query(
        `INSERT INTO users (email, password_hash, name) VALUES ($1, $2, $3)
         RETURNING id, email, name`,
        [cleanEmail, hash, (name || '').trim() || null]
    );
    const user = rows[0];

    // Génère automatiquement un token de device personnel
    const deviceToken = randomToken(40);
    const deviceCode = shortCode();
    await pool.query(
        `INSERT INTO devices (token, label, user_id, code) VALUES ($1, $2, $3, $4)`,
        [deviceToken, name || cleanEmail, user.id, deviceCode]
    );
    return { user, deviceToken, deviceCode };
}

async function authenticate(email, password) {
    const cleanEmail = (email || '').trim().toLowerCase();
    const { rows } = await pool.query(
        `SELECT id, email, name, password_hash FROM users WHERE email = $1`,
        [cleanEmail]
    );
    if (rows.length === 0) return null;
    const ok = await bcrypt.compare(password || '', rows[0].password_hash);
    if (!ok) return null;
    return { id: rows[0].id, email: rows[0].email, name: rows[0].name };
}

async function startSession(userId, deviceToken = null) {
    const token = randomToken(40);
    const expires = new Date(Date.now() + SESSION_DAYS * 24 * 3600 * 1000);
    await pool.query(
        `INSERT INTO sessions (token, user_id, expires_at, device_token) VALUES ($1, $2, $3, $4)`,
        [token, userId, expires, deviceToken]
    );
    return { token, expires };
}

async function getSessionUser(sessionToken) {
    if (!sessionToken) return null;
    const { rows } = await pool.query(
        `SELECT u.id, u.email, u.name, s.device_token,
                (SELECT label FROM devices WHERE token = s.device_token) AS device_label,
                (u.logo_data IS NOT NULL) AS has_logo
         FROM sessions s JOIN users u ON s.user_id = u.id
         WHERE s.token = $1 AND s.expires_at > NOW()`,
        [sessionToken]
    );
    const r = rows[0];
    if (!r) return null;
    return {
        id: r.id, email: r.email, name: r.name,
        deviceToken: r.device_token,
        deviceLabel: r.device_label,
        isSubAccount: !!r.device_token,
        hasLogo: !!r.has_logo,
        logoUrl: r.has_logo ? `/logo/${r.id}` : null
    };
}

async function endSession(sessionToken) {
    if (!sessionToken) return;
    await pool.query('DELETE FROM sessions WHERE token = $1', [sessionToken]);
}

async function getUserDevices(userId) {
    const { rows } = await pool.query(
        `SELECT token, label, code, created_at FROM devices WHERE user_id = $1 ORDER BY created_at DESC`,
        [userId]
    );
    return rows;
}

async function createDevice(userId, label) {
    const token = randomToken(40);
    const code = shortCode();
    await pool.query(
        `INSERT INTO devices (token, label, user_id, code) VALUES ($1, $2, $3, $4)`,
        [token, label || 'Téléphone', userId, code]
    );
    return { token, code };
}

async function deviceByCode(code) {
    const clean = (code || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
    const { rows } = await pool.query(
        'SELECT token, label, user_id FROM devices WHERE code = $1',
        [clean]
    );
    return rows[0] || null;
}

async function deleteDevice(userId, deviceToken) {
    await pool.query(
        `DELETE FROM devices WHERE user_id = $1 AND token = $2`,
        [userId, deviceToken]
    );
}

// Middleware Express : exige un utilisateur connecté
function requireUser(req, res, next) {
    if (!req.user) return res.redirect('/connexion');
    next();
}

// Middleware : attache req.user si le cookie de session est valide (sans bloquer)
async function attachUser(req, res, next) {
    try {
        const sessionToken = req.cookies?.session;
        req.user = await getSessionUser(sessionToken);
    } catch (_) {
        req.user = null;
    }
    next();
}

module.exports = {
    deviceByCode,
    createUser, authenticate, startSession, getSessionUser, endSession,
    getUserDevices, createDevice, deleteDevice,
    requireUser, attachUser, randomToken
};
