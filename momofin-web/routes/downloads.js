// Public download routes: no account, session lookup or database query required.
const router = require('express').Router();
const path = require('node:path');
const apkFile = path.join(__dirname, '../downloads/MoMoFin-test.apk');
router.get('/telecharger', (req, res) => {
    res.set('Cache-Control', 'public, max-age=300');
    res.render('telecharger');
});
router.get('/telecharger/android.apk', (req, res, next) => {
    res.set('Cache-Control', 'no-store');
    res.type('application/vnd.android.package-archive');
    res.set('X-Content-Type-Options', 'nosniff');
    res.download(apkFile, 'MoMoFin-test.apk', (err) => {
        if (err) next(err);
    });
});
router.get('/apk', (req, res) => res.redirect('/telecharger'));
module.exports = router;
