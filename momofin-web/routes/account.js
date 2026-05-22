// Routes : inscription, connexion, mon compte, déconnexion
const express = require('express');
const router = express.Router();
const users = require('../lib/users');

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
const multer = require('multer');
const { pool } = require('../lib/db');

const upload = multer({
    storage: multer.memoryStorage(),
    limits: { fileSize: 2 * 1024 * 1024 }, // 2 MB
    fileFilter: (req, file, cb) => {
        const allowed = ['image/png','image/jpeg','image/jpg','image/svg+xml','image/webp'];
        cb(null, allowed.includes(file.mimetype));
    }
});

// Affiche la page inscription
router.get('/inscription', (req, res) => {
    if (req.user) return res.redirect('/mon-compte');
    res.render('inscription', { error: null, values: {} });
});

router.post('/inscription', async (req, res) => {
    const { email, password, name } = req.body || {};
    try {
        const { user, deviceToken } = await users.createUser(email, password, name);
        const session = await users.startSession(user.id);
        res.cookie('session', session.token, {
            httpOnly: true,
            secure: true,
            sameSite: 'lax',
            expires: session.expires
        });
        res.redirect('/mon-compte?bienvenue=1');
    } catch (err) {
        let msg = err.message;
        if (err.code === '23505') msg = 'Cet email est déjà utilisé.';
        res.status(400).render('inscription', {
            error: msg,
            values: { email, name }
        });
    }
});

router.get('/connexion', (req, res) => {
    if (req.user) return res.redirect('/mon-compte');
    res.render('connexion', { error: null, values: {} });
});

router.post('/connexion', async (req, res) => {
    const { email, password } = req.body || {};
    const u = await users.authenticate(email, password);
    if (!u) {
        return res.status(401).render('connexion', {
            error: 'Email ou mot de passe incorrect.',
            values: { email }
        });
    }
    const session = await users.startSession(u.id);
    res.cookie('session', session.token, {
        httpOnly: true,
        secure: true,
        sameSite: 'lax',
        expires: session.expires
    });
    res.redirect('/mon-compte');
});

router.post('/connexion-code', async (req, res) => {
    try {
        const code = (req.body.code || '').toUpperCase().replace(/[^A-Z0-9]/g, '');
        const d = await users.deviceByCode(code);
        if (!d) return res.status(401).render('connexion', { error: 'Code introuvable.', values: {} });
        const session = await users.startSession(d.user_id, d.token);
        res.cookie('session', session.token, {
            httpOnly: true, secure: true, sameSite: 'lax', expires: session.expires
        });
        res.redirect('/');
    } catch (err) {
        res.status(400).render('connexion', { error: err.message, values: {} });
    }
});

// === Logo personnalisé ===
router.post('/mon-compte/logo', adminOnly, upload.single('logo'), async (req, res) => {
    if (!req.file) return res.redirect('/mon-compte?logo_error=1');
    try {
        await pool.query(
            'UPDATE users SET logo_data = $1, logo_mime = $2 WHERE id = $3',
            [req.file.buffer, req.file.mimetype, req.user.id]
        );
        res.redirect('/mon-compte?logo_ok=1');
    } catch (err) {
        res.redirect('/mon-compte?logo_error=2');
    }
});

router.post('/mon-compte/logo/supprimer', adminOnly, async (req, res) => {
    await pool.query('UPDATE users SET logo_data = NULL, logo_mime = NULL WHERE id = $1', [req.user.id]);
    res.redirect('/mon-compte');
});

// === Config des recus (regles + cachet) ===
router.get('/recus-config', adminOnly, async (req, res) => {
    const { rows } = await pool.query(
        'SELECT receipt_rules, (cachet_data IS NOT NULL) AS has_cachet FROM users WHERE id = $1',
        [req.user.id]
    );
    res.render('recus-config', {
        user: req.user,
        rules: rows[0]?.receipt_rules || '',
        hasCachet: !!rows[0]?.has_cachet
    });
});

router.post('/recus-config/regles', adminOnly, async (req, res) => {
    const rules = (req.body.rules || '').substring(0, 2000);
    await pool.query('UPDATE users SET receipt_rules = $1 WHERE id = $2', [rules, req.user.id]);
    res.redirect('/recus-config?ok=1');
});

router.post('/recus-config/cachet', adminOnly, upload.single('cachet'), async (req, res) => {
    if (!req.file) return res.redirect('/recus-config?cachet_error=1');
    try {
        await pool.query(
            'UPDATE users SET cachet_data = $1, cachet_mime = $2 WHERE id = $3',
            [req.file.buffer, req.file.mimetype, req.user.id]
        );
        res.redirect('/recus-config?cachet_ok=1');
    } catch (e) {
        res.redirect('/recus-config?cachet_error=2');
    }
});

router.post('/recus-config/cachet/supprimer', adminOnly, async (req, res) => {
    await pool.query('UPDATE users SET cachet_data = NULL, cachet_mime = NULL WHERE id = $1', [req.user.id]);
    res.redirect('/recus-config');
});

// Servir le logo (public — pour intégrer dans PDFs, mails, partage)
router.get('/logo/:userId', async (req, res) => {
    const { rows } = await pool.query(
        'SELECT logo_data, logo_mime FROM users WHERE id = $1', [req.params.userId]
    );
    if (rows.length === 0 || !rows[0].logo_data) {
        return res.status(404).send('Pas de logo');
    }
    res.setHeader('Content-Type', rows[0].logo_mime || 'image/png');
    res.setHeader('Cache-Control', 'public, max-age=300'); // 5 min
    res.send(rows[0].logo_data);
});

router.get('/cachet/:userId', async (req, res) => {
    const { rows } = await pool.query(
        'SELECT cachet_data, cachet_mime FROM users WHERE id = $1', [req.params.userId]
    );
    if (rows.length === 0 || !rows[0].cachet_data) {
        return res.status(404).send('Pas de cachet');
    }
    res.setHeader('Content-Type', rows[0].cachet_mime || 'image/png');
    res.setHeader('Cache-Control', 'public, max-age=300');
    res.send(rows[0].cachet_data);
});

router.get('/deconnexion', async (req, res) => {
    await users.endSession(req.cookies?.session);
    res.clearCookie('session');
    res.redirect('/telecharger');
});

router.get('/mon-compte', adminOnly, async (req, res) => {
    const devices = await users.getUserDevices(req.user.id);
    res.render('mon-compte', {
        user: req.user,
        devices,
        query: req.query,
        bienvenue: req.query.bienvenue === '1'
    });
});

router.post('/mon-compte/nouveau-token', adminOnly, async (req, res) => {
    const label = (req.body.label || 'Téléphone').trim();
    await users.createDevice(req.user.id, label);
    res.redirect('/mon-compte');
});

router.post('/mon-compte/revoquer/:token', adminOnly, async (req, res) => {
    await users.deleteDevice(req.user.id, req.params.token);
    res.redirect('/mon-compte');
});

module.exports = router;
