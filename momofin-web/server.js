// MoMo Fin Web — entrée principale
require('dotenv').config();
const express = require('express');
const path = require('path');
const rateLimit = require('express-rate-limit');

const db = require('./lib/db');
const apiRoutes = require('./routes/api');
const webRoutes = require('./routes/web');

const app = express();
const PORT = process.env.PORT || 3000;

app.set('view engine', 'ejs');
app.set('views', path.join(__dirname, 'views'));
app.use(express.json({ limit: '5mb' }));
app.use(express.urlencoded({ extended: true }));
app.use(express.static(path.join(__dirname, 'public')));

// Limitation simple anti-abus sur /api/*
app.use('/api/', rateLimit({
    windowMs: 60_000,
    max: 120,
    standardHeaders: true,
    legacyHeaders: false
}));

app.use('/api', apiRoutes);
app.use('/', webRoutes);

// Initialiser la base au démarrage
db.init().then(() => {
    app.listen(PORT, () => {
        console.log(`MoMo Fin Web démarré sur le port ${PORT}`);
    });
}).catch((err) => {
    console.error('Erreur d\'initialisation DB :', err);
    process.exit(1);
});
