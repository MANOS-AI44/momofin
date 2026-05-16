// Authentification simple par token "device".
// L'APK envoie son token dans l'entête Authorization: Bearer <token>.
const { pool } = require('./db');

async function authDevice(req, res, next) {
    const auth = req.headers.authorization || '';
    const token = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
    if (!token) return res.status(401).json({ error: 'Token manquant' });

    const { rows } = await pool.query(
        'SELECT token, label FROM devices WHERE token = $1',
        [token]
    );
    if (rows.length === 0) return res.status(401).json({ error: 'Token invalide' });

    req.deviceToken = token;
    req.deviceLabel = rows[0].label;
    next();
}

// Auth admin pour le tableau de bord web (mot de passe simple via env ADMIN_PASSWORD).
function authAdmin(req, res, next) {
    const expected = process.env.ADMIN_PASSWORD;
    if (!expected) return next(); // pas de mot de passe défini → accès libre (dev)
    const got = req.query.pwd || req.cookies?.pwd || '';
    if (got === expected) return next();
    res.status(401).send(`
        <form method="GET" style="padding:24px;font-family:sans-serif;">
            <h2>Accès protégé</h2>
            <input type="password" name="pwd" placeholder="Mot de passe admin" autofocus />
            <button type="submit">Entrer</button>
        </form>
    `);
}

module.exports = { authDevice, authAdmin };
