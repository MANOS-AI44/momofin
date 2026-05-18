// Routes : inscription, connexion, mon compte, déconnexion
const express = require('express');
const router = express.Router();
const users = require('../lib/users');

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

router.get('/deconnexion', async (req, res) => {
    await users.endSession(req.cookies?.session);
    res.clearCookie('session');
    res.redirect('/telecharger');
});

router.get('/mon-compte', users.requireUser, async (req, res) => {
    const devices = await users.getUserDevices(req.user.id);
    res.render('mon-compte', {
        user: req.user,
        devices,
        bienvenue: req.query.bienvenue === '1'
    });
});

router.post('/mon-compte/nouveau-token', users.requireUser, async (req, res) => {
    const label = (req.body.label || 'Téléphone').trim();
    await users.createDevice(req.user.id, label);
    res.redirect('/mon-compte');
});

router.post('/mon-compte/revoquer/:token', users.requireUser, async (req, res) => {
    await users.deleteDevice(req.user.id, req.params.token);
    res.redirect('/mon-compte');
});

module.exports = router;
