// Parser SMS Mobile Money — version JavaScript du parser Kotlin (mêmes règles).
// Adapté pour les 6 formats SMS de référence (Orange / MTN / MOOV — Dépôt et Retrait).
const CURRENCY = '(RWF|XOF|XAF|UGX|GHS|KES|TZS|FCFA|CFA|USD|EUR|F(?![a-zA-Z]))';

const SENDERS = [
    'momo', 'mtn', 'mtn momo', 'm-money', 'mtnmomo',
    'orange', 'orangemoney', 'orange money', 'om',
    'airtel', 'airtelmoney', 'airtel money',
    'moov', 'wave', 'mobile money', 'cellulant', 'flooz'
];

const KEYWORDS = [
    'momo', 'mobile money', 'transaction', 'received', 'sent',
    'recu', 'reçu', 'envoy', 'transfert', 'transfer', 'ref',
    'rwf', 'xof', 'xaf', 'ugx', 'ghs', 'kes', 'tzs', 'fcfa',
    'id:', 'id transaction', 'txn', 'txid',
    'depot', 'retrait', 'solde'
];

// Mots-clés génériques (fallback) — la détection prioritaire est plus haut dans detectType()
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

// Référence : autorise ':' collé sans espace (ex. "Ref:CO260512..."), points et tirets.
const REF_PATTERNS = [
    /(?:ID\s+Transaction|Transaction\s+ID|Transaction\s+Id|Financial Transaction Id|TxId|TXID|Txn Id|Trans\.? Id)\s*[:#]?\s*([A-Za-z0-9][A-Za-z0-9.\-_]{3,40})/i,
    /\b(?:Ref(?:erence|érence)?|Réf)\.?\s*[:#]?\s*([A-Za-z0-9][A-Za-z0-9.\-_]{3,40})/i,
    /\bID\s*[:#]?\s*([A-Za-z0-9][A-Za-z0-9.\-_]{3,40})/i,
    /\b(\d{8,16})\b/
];

const AMOUNT_RE = new RegExp(`(?:^|[^A-Za-z0-9])([0-9][0-9 ,.]{0,15})\\s*${CURRENCY}`, 'gi');

// Patterns téléphone : du plus spécifique au plus générique.
// PHONE_SENDER capture le numéro émetteur dans "Le numero X a envoye ... sur votre numero Z"
const PHONE_SENDER = /(?:le\s+num[eé]ro\s+|num[eé]ro\s+)?(\+?\d[\d\s\-.]{6,18}\d)\s+a\s+envoy[eé]/i;
// "au numero / du numero / sur le numero" etc.
const PHONE_NUMERO = /\bnum[eé]ro\s+(\+?\d[\d\s\-.]{6,18}\d)/gi;
const PHONE_NEAR = /\b(?:from|to|de|du|vers|à|a|au|chez)\b\s+(?:le\s+|la\s+|du\s+|des\s+|aux?\s+)?(?:[^()\d\n]{0,40}?)?\(?\s*(\+?\d[\d\s.]{6,18}\d)\s*\)?/gi;
const PHONE_PARENS = /\((\+?\d[\d\s\-.]{6,18}\d)\)/g;
const PHONE_INTL = /(\+\d[\d\s.]{6,18}\d)/g;
// Long suite de chiffres (10+) sans tirets — typique d'un numéro international ou local concaténé.
const PHONE_LONG = /(?<![\d])(\d{10,15})(?![\d])/g;
const PHONE_LOCAL = /(?<![\d])(0\d[\d\s.]{6,12}\d)(?![\d])/g;

const GOOD_KW = ['montant', 'recu', 'reçu', 'envoye', 'envoyé', 'a envoye', 'a envoyé', 'retrait', 'depot vers', 'dépôt vers', 'payer', 'transfere', 'transféré'];
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
    if (s.includes('moov') || b.includes('moov') || b.includes('flooz')) return 'Moov';
    if (s.includes('wave') || b.includes('wave')) return 'Wave';
    return 'Autre';
}

// Détection du type prioritaire — règles spécifiques d'abord, fallback mots-clés ensuite.
function detectType(body) {
    const low = (body || '').toLowerCase();

    // RECU spécifique : "Le numero X a envoye Y sur votre numero Z" (MOOV Retrait)
    if (/\bsur\s+votre\s+num[eé]ro\b/i.test(body) && /\ba\s+envoy[eé]\b/i.test(body)) return 'RECU';
    // RECU spécifique : "Vous avez reçu/recu"
    if (/\bvous\s+avez\s+re[çc]u\b/i.test(body)) return 'RECU';

    // SORTIE spécifique : "Vous avez envoyé/envoye"
    if (/\bvous\s+avez\s+envoy[eé]\b/i.test(body)) return 'SORTIE';
    // SORTIE spécifique : "Retrait initié" / "Payer le montant"
    if (/\bretrait\s+initi[eé]\b/i.test(body)) return 'SORTIE';
    if (/\bpayer\s+(le\s+)?montant\b/i.test(body)) return 'SORTIE';

    // Fallback générique
    if (RECU_KEYWORDS.some(k => low.includes(k))) return 'RECU';
    if (SORTIE_KEYWORDS.some(k => low.includes(k))) return 'SORTIE';
    return 'INCONNU';
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
    // Priorité 1 : "X a envoye" (cas MOOV Retrait — extrait l'expediteur)
    const m1 = body.match(PHONE_SENDER);
    if (m1 && m1[1]) {
        const c = cleanPhone(m1[1]);
        if (c) return c;
    }

    // Priorité 2 : "numero X" (premier match, généralement le destinataire/expediteur)
    PHONE_NUMERO.lastIndex = 0;
    let m;
    while ((m = PHONE_NUMERO.exec(body)) !== null) {
        // Ignorer "votre numero" (numero de l'agent lui-meme)
        const ctxStart = Math.max(0, m.index - 12);
        const ctx = body.substring(ctxStart, m.index).toLowerCase();
        if (ctx.includes('votre')) continue;
        const c = cleanPhone(m[1]);
        if (c) return c;
    }

    // Priorité 3 : prepositions classiques (de, du, vers, etc.)
    for (const p of [PHONE_NEAR, PHONE_PARENS, PHONE_INTL, PHONE_LONG, PHONE_LOCAL]) {
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
    // Accepte "à" entre date et heure (ex. "17/05/2026 a 19:18:36")
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
    const operator = detectOperator(sender, body);
    const type = detectType(body || '');
    const { amount, currency } = extractAmount(body || '');
    const reference = extractReference(body || '');
    const phone_number = extractPhone(body || '');
    const ts = extractDate(body || '') || new Date(smsTimestamp || Date.now()).toISOString();

    return { operator, type, amount, currency, reference, phone_number, ts };
}

module.exports = { parse, isMomoSms, detectOperator, detectType };
