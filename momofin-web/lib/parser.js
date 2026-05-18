// Parser SMS Mobile Money — STRICT, base sur les 6 SMS REELS d'agent Orange/MTN/MOOV.
// N'accepte que ces formes exactes. Tout autre SMS est rejete (return null).

const CURRENCY = '(RWF|XOF|XAF|UGX|GHS|KES|TZS|FCFA|CFA|USD|EUR|F(?![a-zA-Z]))';
const AMOUNT_RE = new RegExp(`(?:^|[^A-Za-z0-9])([0-9][0-9 ,.]{0,15})\\s*${CURRENCY}`, 'gi');

// ===========================================================================
// PATTERNS CANONIQUES (au moins UN doit matcher)
// Note : pas de \b apres caracteres accentues (echoue en JS Unicode)
// ===========================================================================

// --- SORTIE (Depot cote agent : client vient deposer, agent envoie) ---
// (a) MTN/MOOV : "Vous avez envoye/envoye X FCFA ..."
const PAT_SORTIE_GENERIC = /vous\s+avez\s+envoy[eé][\s\S]{0,250}?(?:FCFA|CFA|XOF|XAF|RWF|\bF\b)/i;
// (b) Orange : "Le depot vers le X est reussi"
const PAT_SORTIE_ORANGE = /(?:^|[\s.])(?:le\s+)?d[eé]p[oô]?t\s+vers\s+(?:le\s+)?\+?\d[\d\s\-.]{6,18}\d[\s\S]{0,80}?(?:est\s+r[eé]ussi|reussi)/i;

// --- RECU (Retrait cote agent : client vient retirer, agent recoit) ---
// (a) generique : "Vous avez recu X FCFA"
const PAT_RECU_DIRECT = /vous\s+avez\s+re[çc]u[\s\S]{0,250}?(?:FCFA|CFA|XOF|XAF|RWF|\bF\b)/i;
// (b) MOOV : "Le numero X a envoye Y FCFA sur votre numero Z"
const PAT_RECU_INDIRECT = /(?:le\s+)?num[eé]ro\s+\+?\d[\d\s\-.]{6,18}\d\s+a\s+envoy[eé][\s\S]{0,250}?sur\s+votre\s+num[eé]ro/i;
// (c) Orange : "Retrait de X effectue"
const PAT_RECU_ORANGE = /retrait\s+de\s+\+?\d[\d\s\-.]{6,18}\d[\s\S]{0,40}?effectu[eé]/i;
// (d) MTN : "Le retrait initie ... a ete effectue" (avec ou sans "payer le montant")
const PAT_RECU_MTN = /retrait\s+initi[eé][\s\S]{0,250}?(?:a\s+[eé]t[eé]\s+effectu[eé]|payer\s+le\s+montant)/i;

// ===========================================================================
// Reference, telephone, date
// ===========================================================================
const REF_PATTERNS = [
    /(?:ID\s+Transaction|Transaction\s+ID|Transaction\s+Id|Financial Transaction Id|TxId|TXID|Txn Id|Trans\.? Id)\s*[:#]?\s*([A-Za-z0-9][A-Za-z0-9.\-_]{3,40})/i,
    /\b(?:Ref(?:erence|érence)?|Réf)\.?\s*[:#]?\s*([A-Za-z0-9][A-Za-z0-9.\-_]{3,40})/i,
    /\bID\s*[:#]?\s*([A-Za-z0-9][A-Za-z0-9.\-_]{3,40})/i,
    /\b(\d{8,16})\b/
];

const PHONE_SENDER = /(?:le\s+num[eé]ro\s+|num[eé]ro\s+)?(\+?\d[\d\s\-.]{6,18}\d)\s+a\s+envoy[eé]/i;
const PHONE_NUMERO = /\bnum[eé]ro\s+(\+?\d[\d\s\-.]{6,18}\d)/gi;
// "Retrait de X" : capter directement
const PHONE_RETRAIT_DE = /retrait\s+de\s+(\+?\d[\d\s\-.]{6,18}\d)/i;
// "depot vers le X"
const PHONE_DEPOT_VERS = /d[eé]p[oô]?t\s+vers\s+(?:le\s+)?(\+?\d[\d\s\-.]{6,18}\d)/i;
// "vers le X" / "au X" / "de X" etc.
const PHONE_NEAR = /\b(?:from|to|de|du|vers|à|a|au|chez)\b\s+(?:le\s+|la\s+|du\s+|des\s+|aux?\s+)?(?:[^()\d\n]{0,40}?)?\(?\s*(\+?\d[\d\s.]{6,18}\d)\s*\)?/gi;
const PHONE_INTL = /(\+\d[\d\s.]{6,18}\d)/g;
const PHONE_LONG = /(?<![\d])(\d{10,15})(?![\d])/g;
const PHONE_LOCAL = /(?<![\d])(0\d[\d\s.]{6,12}\d)(?![\d])/g;

const GOOD_KW = ['montant', 'recu', 'reçu', 'envoye', 'envoyé', 'a envoye', 'a envoyé', 'payer le montant'];
const BAD_KW = ['solde', 'frais', 'commission', 'balance', 'nouveau solde'];

// ===========================================================================
function detectType(body) {
    if (PAT_RECU_INDIRECT.test(body)) return 'RECU';
    if (PAT_RECU_DIRECT.test(body)) return 'RECU';
    if (PAT_RECU_ORANGE.test(body)) return 'RECU';
    if (PAT_RECU_MTN.test(body)) return 'RECU';
    if (PAT_SORTIE_GENERIC.test(body)) return 'SORTIE';
    if (PAT_SORTIE_ORANGE.test(body)) return 'SORTIE';
    return null;
}

function isMomoSms(sender, body) {
    return detectType(body || '') !== null;
}

function detectOperator(sender, body) {
    const s = (sender || '').toLowerCase().trim();
    const b = (body || '').toLowerCase();
    // === ORANGE === (sender exact +454 ou variantes Orange)
    if (s === '+454' || s === '454' || s.startsWith('+454') || s.includes('orange'))   return 'Orange';
    if (b.includes('orange money')) return 'Orange';
    // Signatures Orange dans le body (sender numerique)
    if (/id\s+transaction[:\s]+c[io]\d/i.test(body) || /vigilance\s+arnaque/i.test(body)) return 'Orange';
    // === MTN === (MobileMoney = MTN en Cote d'Ivoire)
    if (s.includes('mobilemoney') || s.includes('mobile money') || s.includes('mtn') || s.includes('momo') || s === 'mm')   return 'MTN';
    if (b.includes('mtn momo') || b.includes('mtn mobile money')) return 'MTN';
    // === MOOV ===
    if (s.includes('moovmoney') || s.includes('moov money') || s.includes('moov') || s.includes('flooz')) return 'MOOV';
    if (b.includes('moov money') || b.includes('flooz')) return 'MOOV';
    // Autres
    if (s.includes('airtel')) return 'Airtel';
    if (s.includes('wave') || b.includes('wave')) return 'Wave';
    return 'Autre';
}

function normalize(raw) {
    const cleaned = raw.replace(/ /g, '');
    if ((cleaned.match(/,/g)?.length || 0) === 1 && cleaned.indexOf(',') > cleaned.indexOf('.')) {
        return parseFloat(cleaned.replace(/\./g, '').replace(',', '.'));
    }
    if ((cleaned.match(/\./g)?.length || 0) === 1 && cleaned.indexOf('.') > cleaned.indexOf(',')) {
        return parseFloat(cleaned.replace(/,/g, ''));
    }
    return parseFloat(cleaned.replace(/,/g, '').replace(/ /g, '')) || 0;
}

function extractAmount(body) {
    const cands = [];
    let m;
    AMOUNT_RE.lastIndex = 0;
    while ((m = AMOUNT_RE.exec(body)) !== null) {
        const raw = m[1].trim();
        const cur = m[2].toUpperCase();
        const amount = normalize(raw);
        if (!amount || amount <= 0) continue;
        const ctx = body.substring(Math.max(0, m.index - 30), m.index).toLowerCase();
        let priority = 1;
        if (BAD_KW.some(k => ctx.includes(k))) priority = 0;
        else if (GOOD_KW.some(k => ctx.includes(k))) priority = 3;
        cands.push({ amount, cur, priority, pos: m.index });
    }
    if (cands.length === 0) return { amount: 0, currency: '' };
    cands.sort((a, b) => b.priority - a.priority || a.pos - b.pos);
    const c = cands[0];
    return { amount: c.amount, currency: c.cur === 'F' ? 'FCFA' : c.cur };
}

function extractReference(body) {
    for (const p of REF_PATTERNS) {
        const m = body.match(p);
        if (m && m[1]) {
            const trimmed = m[1].replace(/[.,;:\-_]+$/, '');
            if (trimmed.length >= 4 && trimmed.length <= 40) return trimmed.toUpperCase();
        }
    }
    return '';
}

function cleanPhone(raw) {
    const digitsOnly = raw.replace(/[^\d+]/g, '');
    const justDigits = digitsOnly.replace(/\+/g, '');
    if (justDigits.length < 8 || justDigits.length > 15) return '';
    return digitsOnly.startsWith('+') ? digitsOnly : justDigits;
}

function extractPhone(body) {
    // 1. "X a envoye" (Retrait MOOV : capter l'emetteur)
    const m1 = body.match(PHONE_SENDER);
    if (m1 && m1[1]) { const c = cleanPhone(m1[1]); if (c) return c; }
    // 2. "Retrait de X" (Retrait Orange)
    const m2 = body.match(PHONE_RETRAIT_DE);
    if (m2 && m2[1]) { const c = cleanPhone(m2[1]); if (c) return c; }
    // 3. "depot vers (le) X" (Depot Orange)
    const m3 = body.match(PHONE_DEPOT_VERS);
    if (m3 && m3[1]) { const c = cleanPhone(m3[1]); if (c) return c; }
    // 4. "numero X" en sautant "votre numero"
    PHONE_NUMERO.lastIndex = 0;
    let m;
    while ((m = PHONE_NUMERO.exec(body)) !== null) {
        const ctxStart = Math.max(0, m.index - 12);
        const ctx = body.substring(ctxStart, m.index).toLowerCase();
        if (ctx.includes('votre')) continue;
        const c = cleanPhone(m[1]);
        if (c) return c;
    }
    // 5. preposition + telephone
    for (const p of [PHONE_NEAR, PHONE_INTL, PHONE_LONG, PHONE_LOCAL]) {
        p.lastIndex = 0;
        let mm;
        while ((mm = p.exec(body)) !== null) {
            const c = cleanPhone(mm[1]);
            if (c) return c;
        }
    }
    return '';
}

function extractDate(body) {
    const m = body.match(/(\d{2,4}[/-]\d{2}[/-]\d{2,4})\s*(?:à\s+|a\s+)?(\d{1,2}:\d{2}(?::\d{2})?)/);
    if (!m) return null;
    const rawDate = m[1].replace(/-/g, '/');
    const rawTime = m[2];
    const d = rawDate.split('/');
    const t = rawTime.split(':');
    let year, month, day;
    if (d[0].length === 4) { year = +d[0]; month = +d[1]; day = +d[2]; }
    else { day = +d[0]; month = +d[1]; year = +d[2]; }
    const date = new Date(Date.UTC(year, month - 1, day, +t[0], +t[1], +(t[2] || 0)));
    return isNaN(date.getTime()) ? null : date.toISOString();
}

function parse(sender, body, smsTimestamp) {
    const type = detectType(body || '');
    if (!type) return null;
    const { amount, currency } = extractAmount(body || '');
    if (amount <= 0) return null;
    const operator = detectOperator(sender, body);
    const reference = extractReference(body || '');
    const phone_number = extractPhone(body || '');
    const ts = extractDate(body || '') || new Date(smsTimestamp || Date.now()).toISOString();
    return { operator, type, amount, currency, reference, phone_number, ts };
}

module.exports = { parse, isMomoSms, detectOperator, detectType };
