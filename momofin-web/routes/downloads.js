// Public download routes: no account, session lookup or database query required.
const router = require('express').Router();
const apkUrl = 'https://github.com/MANOS-AI44/momofin/releases/download/v1.1.0-preview.2/MoMoFin-test.apk';
router.get('/telecharger', (req, res) => {
    res.set('Cache-Control', 'public, max-age=300');
    res.render('telecharger');
});
router.get('/telecharger/android.apk', (req, res) => {
    res.set('Cache-Control', 'no-store');
    res.redirect(302, apkUrl);
});
router.get('/apk', (req, res) => res.redirect('/telecharger'));
module.exports = router;
