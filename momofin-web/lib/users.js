// Gestion utilisateurs : inscription, connexion, sessions.
const bcrypt = require('bcryptjs');
const crypto = require('crypto');
const { pool } = require('./db');

const SESSION_DAYS = 30;

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
    await pool.query(
        `INSERT INTO devices (token, label, user_id) VALUES ($1, $2, $3)`,
        [deviceToken, name || cleanEmail, user.id]
    );
    return { user, deviceToken };
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

async function startSession(userId) {
    const token = randomToken(40);
    const expires = new Date(Date.now() + SESSION_DAYS * 24 * 3600 * 1000);
    await pool.query(
        `INSERT INTO sessions (token, user_id, expires_at) VALUES ($1, $2, $3)`,
        [token, userId, expires]
    );
    return { token, expires };
}

async function getSessionUser(sessionToken) {
    if (!sessionToken) return null;
    const { rows } = await pool.query(
        `SELECT u.id, u.email, u.name
         FROM sessions s JOIN users u ON s.user_id = u.id
         WHERE s.token = $1 AND s.expires_at > NOW()`,
        [sessionToken]
    );
    return rows[0] || null;
}

async function endSession(sessionToken) {
    if (!sessionToken) return;
    await pool.query('DELETE FROM sessions WHERE token = $1', [sessionToken]);
}

async function getUserDevices(userId) {
    const { rows } = await pool.query(
        `SELECT token, label, created_at FROM devices WHERE user_id = $1 ORDER BY created_at DESC`,
        [userId]
    );
    return rows;
}

async function createDevice(userId, label) {
    const token = randomToken(40);
    await pool.query(
        `INSERT INTO devices (token, label, user_id) VALUES ($1, $2, $3)`,
        [token, label || 'Téléphone', userId]
    );
    return token;
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
    createUser, authenticate, startSession, getSessionUser, endSession,
    getUserDevices, createDevice, deleteDevice,
    requireUser, attachUser, randomToken
};
