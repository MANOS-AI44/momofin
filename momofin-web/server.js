// MoMo Fin Web — entrée principale
require('dotenv').config();
const express = require('express');
const path = require('path');
const rateLimit = require('express-rate-limit');
const cookieParser = require('cookie-parser');

const db = require('./lib/db');
const users = require('./lib/users');
const apiRoutes = require('./routes/api');
const webRoutes = require('./routes/web');
const accountRoutes = require('./routes/account');

const app = express();
const PORT = process.env.PORT || 3000;

app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, 'views'));
app.use(express.json({ limit: '5mb' }));
app.use(express.urlencoded({ extended: true }));
app.use(cookieParser());
app.use(express.static(path.join(__dirname, 'public')));

app.use('/api/', rateLimit({
    windowMs: 60_000,
    max: 120,
    standardHeaders: true,
    legacyHeaders: false
}));

// Middleware qui attache req.user si un cookie session existe (non bloquant)
app.use(users.attachUser);

app.use('/api', apiRoutes);
app.use('/', accountRoutes);
app.use('/', webRoutes);

// Gestionnaire d'erreurs global : évite que les erreurs EJS plantent le serveur
app.use((err, req, res, next) => {
    console.error('ERROR:', err.message);
    console.error(err.stack);
    res.status(500).send(`
        <html><body style="font-family:sans-serif;padding:40px;background:#fee2e2;">
            <h1 style="color:#991B1B;">⚠️ Erreur serveur</h1>
            <p>Une erreur est survenue. Détail :</p>
            <pre style="background:white;padding:14px;border-radius:6px;overflow:auto;">${err.message}</pre>
            <p><a href="/">Retour à l'accueil</a></p>
        </body></html>
    `);
});

db.init().then(() => {
    app.listen(PORT, () => {
        console.log(`MoMo Fin Web démarré sur le port ${PORT}`);
    });
}).catch((err) => {
    console.error('Erreur d\'initialisation DB :', err);
    process.exit(1);
});

