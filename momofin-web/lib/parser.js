// Parser SMS Mobile Money — STRICT.
// N'accepte que les 6 patterns de reference (Orange / MTN / MOOV — Depot et Retrait).
// Tout SMS qui ne match aucun pattern est rejete (parse renvoie null, isMomoSms renvoie false).

const CURRENCY = '(RWF|XOF|XAF|UGX|GHS|KES|TZS|FCFA|CFA|USD|EUR|F(?![a-zA-Z]))';
const AMOUNT_RE = new RegExp(`(?:^|[^A-Za-z0-9])([0-9][0-9 ,.]{0,15})\\s*${CURRENCY}`, 'gi');

// === Patterns canoniques (UN SEUL doit matcher pour accepter l'SMS) ===
// SORTIE (Depot cote agent) : "Vous avez envoye X FCFA ..."
const PAT_SORTIE = /\bvous\s+avez\s+envoy[eé]\b[\s\S]{0,200}?(?:FCFA|CFA|XOF|XAF|RWF|F\b)/i;
// RECU (Retrait cote agent) : "Vous avez recu X FCFA ..." OU "Le numero X a envoye Y FCFA ... sur votre numero Z"
const PAT_RECU_DIRECT = /\bvous\s+avez\s+re[çc]u\b[\s\S]{0,200}?(?:FCFA|CFA|XOF|XAF|RWF|F\b)/i;
const PAT_RECU_INDIRECT = /\b(?:le\s+)?num[eé]ro\s+\+?\d[\d\s\-.]{6,18}\d\s+a\s+envoy[eé]\b[\s\S]{0,200}?sur\s+votre\s+num[eé]ro/i;

// === Reference (ID Transaction / Ref / ID) ===
const REF_PATTERNS = [
    /(?:ID\s+Transaction|Transaction\s+ID|Transaction\s+Id|Financial Transaction Id|TxId|TXID|Txn Id|Trans\.? Id)\s*[:#]?\s*([A-Za-z0-9][A-Za-z0-9.\-_]{3,40})/i,
    /\b(?:Ref(?:erence|érence)?|Réf)\.?\s*[:#]?\s*([A-Za-z0-9][A-Za-z0-9.\-_]{3,40})/i,
    /\bID\s*[:#]?\s*([A-Za-z0-9][A-Za-z0-9.\-_]{3,40})/i,
    /\b(\d{8,16})\b/
];

// === Phone patterns ===
const PHONE_SENDER = /(?:le\s+num[eé]ro\s+|num[eé]ro\s+)?(\+?\d[\d\s\-.]{6,18}\d)\s+a\s+envoy[eé]/i;
const PHONE_NUMERO = /\bnum[eé]ro\s+(\+?\d[\d\s\-.]{6,18}\d)/gi;
const PHONE_NEAR = /\b(?:from|to|de|du|vers|à|a|au|chez)\b\s+(?:le\s+|la\s+|du\s+|des\s+|aux?\s+)?(?:[^()\d\n]{0,40}?)?\(?\s*(\+?\d[\d\s.]{6,18}\d)\s*\)?/gi;
const PHONE_INTL = /(\+\d[\d\s.]{6,18}\d)/g;
const PHONE_LONG = /(?<![\d])(\d{10,15})(?![\d])/g;
const PHONE_LOCAL = /(?<![\d])(0\d[\d\s.]{6,12}\d)(?![\d])/g;

// === Amount priority hints ===
const GOOD_KW = ['montant', 'recu', 'reçu', 'envoye', 'envoyé', 'a envoye', 'a envoyé'];
const BAD_KW = ['solde', 'frais', 'commission', 'balance', 'nouveau solde'];

function detectType(body) {
    // 1. RECU "indirect" (MOOV Retrait) — priorite max car contient "envoye" qui sinon serait SORTIE
    if (PAT_RECU_INDIRECT.test(body)) return 'RECU';
    // 2. RECU direct
    if (PAT_RECU_DIRECT.test(body)) return 'RECU';
    // 3. SORTIE (Depot)
    if (PAT_SORTIE.test(body)) return 'SORTIE';
    return null;
}

function isMomoSms(sender, body) {
    // STRICT : doit matcher un des patterns canoniques
    return detectType(body || '') !== null;
}

function detectOperator(sender, body) {
    const s = (sender || '').toLowerCase();
    const b = (body || '').toLowerCase();
    if (s.includes('mtn') || s.includes('momo') || b.includes('momo')) return 'MTN';
    if (s.includes('orange') || b.includes('orange money') || b.includes('orange')) return 'Orange';
    if (s.includes('moov') || b.includes('moov') || b.includes('flooz')) return 'Moov';
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
    // Priorite 1 : "X a envoye" (MOOV Retrait — extrait l'expediteur)
    const m1 = body.match(PHONE_SENDER);
    if (m1 && m1[1]) {
        const c = cleanPhone(m1[1]);
        if (c) return c;
    }
    // Priorite 2 : "numero X" en ignorant "votre numero"
    PHONE_NUMERO.lastIndex = 0;
    let m;
    while ((m = PHONE_NUMERO.exec(body)) !== null) {
        const ctxStart = Math.max(0, m.index - 12);
        const ctx = body.substring(ctxStart, m.index).toLowerCase();
        if (ctx.includes('votre')) continue;
        const c = cleanPhone(m[1]);
        if (c) return c;
    }
    // Priorite 3 : fallback
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

// parse() renvoie null si l'SMS ne correspond a aucun pattern accepte (rejet strict)
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
