// Parser SMS Mobile Money — version JavaScript du parser Kotlin.
// Utilisé pour vérifier/réparser côté serveur si l'APK envoie un SMS brut.

const CURRENCY = '(RWF|XOF|XAF|UGX|GHS|KES|TZS|FCFA|CFA|USD|EUR)';

const SENDERS = [
    'momo', 'mtn', 'mtn momo', 'm-money', 'mtnmomo',
    'orange', 'orangemoney', 'orange money', 'om',
    'airtel', 'airtelmoney', 'airtel money'
];

const KEYWORDS = [
    'momo', 'mobile money', 'transaction', 'received', 'sent',
    'reçu', 'envoy', 'transfert', 'transfer', 'ref',
    'rwf', 'xof', 'xaf', 'ugx', 'ghs', 'kes', 'tzs', 'fcfa',
    'id:', 'txn', 'txid'
];

const RECU_KEYWORDS = [
    'received', 'you have received', 'credited', 'credit',
    'reçu', 'vous avez reçu', 'crédité'
];

const SORTIE_KEYWORDS = [
    'sent', 'you have sent', 'payment of', 'paid', 'debited', 'withdrawn',
    'envoyé', 'vous avez envoyé', 'paiement', 'retrait', 'débité', 'transfert vers'
];

const REF_PATTERNS = [
    /(?:Financial Transaction Id|Transaction Id|TxId|TXID|Txn Id|Trans\.? Id)\s*[:#]?\s*([A-Z0-9]+)/i,
    /(?:Ref(?:erence|érence)?|Réf)\.?\s*[:#]?\s*([A-Za-z0-9]+)/i,
    /\bID\s*[:#]\s*([A-Za-z0-9]+)/i,
    /\b(\d{8,16})\b/
];

const AMOUNT_RE = new RegExp(`(?:^|[^A-Za-z0-9])([0-9][0-9 ,.]{0,15})\\s*${CURRENCY}`, 'i');
const AMOUNT_RE_2 = new RegExp(`${CURRENCY}\\s*([0-9][0-9 ,.]{0,15})`, 'i');

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
    return 'Autre';
}

function normalize(raw) {
    const cleaned = raw.replace(/ /g, '');
    if (cleaned.match(/,/g)?.length === 1 && cleaned.indexOf(',') > cleaned.indexOf('.')) {
        return parseFloat(cleaned.replace(/\./g, '').replace(',', '.'));
    }
    if (cleaned.match(/\./g)?.length === 1 && cleaned.indexOf('.') > cleaned.indexOf(',')) {
        return parseFloat(cleaned.replace(/,/g, ''));
    }
    return parseFloat(cleaned.replace(/,/g, '').replace(/ /g, '')) || 0;
}

function extractAmount(body) {
    let m = body.match(AMOUNT_RE);
    if (m) return { amount: normalize(m[1].trim()), currency: m[2].toUpperCase() };
    m = body.match(AMOUNT_RE_2);
    if (m) return { amount: normalize(m[2].trim()), currency: m[1].toUpperCase() };
    return { amount: 0, currency: '' };
}

function extractReference(body) {
    for (const p of REF_PATTERNS) {
        const m = body.match(p);
        if (m && m[1] && m[1].length >= 4 && m[1].length <= 32) return m[1].toUpperCase();
    }
    return '';
}

function extractDate(body) {
    const m = body.match(/(\d{2,4}[/-]\d{2}[/-]\d{2,4}\s+\d{1,2}:\d{2}(?::\d{2})?)/);
    if (!m) return null;
    const raw = m[1].replace('-', '/').replace('-', '/');
    // Essayer plusieurs formats simples
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
    const ts = extractDate(body || '') || new Date(smsTimestamp || Date.now()).toISOString();

    return { operator, type, amount, currency, reference, ts };
}

module.exports = { parse, isMomoSms, detectOperator };
