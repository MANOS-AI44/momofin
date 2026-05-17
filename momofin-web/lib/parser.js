// Parser SMS Mobile Money — version JavaScript du parser Kotlin (mêmes règles).
const CURRENCY = '(RWF|XOF|XAF|UGX|GHS|KES|TZS|FCFA|CFA|USD|EUR|F(?![a-zA-Z]))';

const SENDERS = [
    'momo', 'mtn', 'mtn momo', 'm-money', 'mtnmomo',
    'orange', 'orangemoney', 'orange money', 'om',
    'airtel', 'airtelmoney', 'airtel money',
    'moov', 'wave', 'mobile money', 'cellulant'
];

const KEYWORDS = [
    'momo', 'mobile money', 'transaction', 'received', 'sent',
    'recu', 'reçu', 'envoy', 'transfert', 'transfer', 'ref',
    'rwf', 'xof', 'xaf', 'ugx', 'ghs', 'kes', 'tzs', 'fcfa',
    'id:', 'id transaction', 'txn', 'txid',
    'depot', 'retrait', 'solde'
];

const RECU_KEYWORDS = [
    'received', 'credited',
    'reçu', 'recu', 'vous avez reçu', 'vous avez recu', 'crédité', 'credite'
];

const SORTIE_KEYWORDS = [
    'sent', 'paid', 'debited', 'withdrawn',
    'envoyé', 'envoye', 'vous avez envoyé', 'vous avez envoye',
    'paiement', 'retrait', 'débité', 'debite',
    'transfert vers', 'depot vers', 'dépôt vers'
];

const REF_PATTERNS = [
    /(?:ID\s+Transaction|Transaction\s+ID|Transaction\s+Id|Financial Transaction Id|TxId|TXID|Txn Id|Trans\.? Id)\s*[:#]?\s*([A-Za-z0-9][A-Za-z0-9.\-_]{3,40})/i,
    /(?:Ref(?:erence|érence)?|Réf)\.?\s*[:#]?\s*([A-Za-z0-9][A-Za-z0-9.\-_]{3,40})/i,
    /\bID\s*[:#]\s*([A-Za-z0-9][A-Za-z0-9.\-_]{3,40})/i,
    /\b(\d{8,16})\b/
];

const AMOUNT_RE = new RegExp(`(?:^|[^A-Za-z0-9])([0-9][0-9 ,.]{0,15})\\s*${CURRENCY}`, 'gi');

const PHONE_NEAR = /\b(?:from|to|de|vers|à|au|chez)\b\s+(?:le\s+|la\s+|du\s+|des\s+|aux?\s+)?(?:[^()\d\n]{0,40}?)?\(?\s*(\+?\d[\d\s\-.]{6,18}\d)\s*\)?/gi;
const PHONE_PARENS = /\((\+?\d[\d\s\-.]{6,18}\d)\)/g;
const PHONE_INTL = /(\+\d[\d\s\-.]{6,18}\d)/g;
const PHONE_LOCAL = /(?<![\d])(0\d[\d\s\-.]{6,12}\d)(?![\d])/g;

const GOOD_KW = ['montant', 'recu', 'reçu', 'envoye', 'envoyé', 'retrait', 'depot vers', 'dépôt vers', 'payer', 'transfere', 'transféré'];
const BAD_KW = ['solde', 'frais', 'commission', 'balance'];

function isMomoSms(sender, body) {
    const s = (sender || '').toLowerCase();
    const b = (body || '').toLowerCase();
    if (SENDERS.some(x => s.includes(x))) return true;
    return KEYWORDS.filter(k => b.includes(k)).length >= 2;
}

function detectOperator(sender, body) {
    const s = (sender || '').toLowerCase();
    const b = (body || '').toLowerCase();
    if (s.includes('mtn') || s.includes('momo') || b.includes('momo')) return 'MTN';
    if (s.includes('orange') || b.includes('orange money')) return 'Orange';
    if (s.includes('airtel')) return 'Airtel';
    if (s.includes('moov')) return 'Moov';
    if (s.includes('wave')) return 'Wave';
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
    for (const p of [PHONE_NEAR, PHONE_PARENS, PHONE_INTL, PHONE_LOCAL]) {
        p.lastIndex = 0;
        let m;
        while ((m = p.exec(body)) !== null) {
            const c = cleanPhone(m[1]);
            if (c) return c;
        }
    }
    return '';
}

function extractDate(body) {
    const m = body.match(/(\d{2,4}[/-]\d{2}[/-]\d{2,4}\s+\d{1,2}:\d{2}(?::\d{2})?)/);
    if (!m) return null;
    const raw = m[1].replace(/-/g, '/');
    const parts = raw.split(/\s+/);
    if (parts.length < 2) return null;
    const d = parts[0].split('/');
    const t = parts[1].split(':');
    let year, month, day;
    if (d[0].length === 4) { year = +d[0]; month = +d[1]; day = +d[2]; }
    else { day = +d[0]; month = +d[1]; year = +d[2]; }
    const date = new Date(Date.UTC(year, month - 1, day, +t[0], +t[1], +(t[2] || 0)));
    return isNaN(date.getTime()) ? null : date.toISOString();
}

function parse(sender, body, smsTimestamp) {
    const operator = detectOperator(sender, body);
    const low = (body || '').toLowerCase();
    let type = 'INCONNU';
    if (RECU_KEYWORDS.some(k => low.includes(k))) type = 'RECU';
    else if (SORTIE_KEYWORDS.some(k => low.includes(k))) type = 'SORTIE';

    const { amount, currency } = extractAmount(body || '');
    const reference = extractReference(body || '');
    const phone_number = extractPhone(body || '');
    const ts = extractDate(body || '') || new Date(smsTimestamp || Date.now()).toISOString();

    return { operator, type, amount, currency, reference, phone_number, ts };
}

module.exports = { parse, isMomoSms, detectOperator };
