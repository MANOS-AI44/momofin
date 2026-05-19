// Generation du PDF imprimable cote serveur (pdfkit).
const PDFDocument = require('pdfkit');

// fr-FR utilise U+202F (NARROW NO-BREAK SPACE) comme separateur de milliers,
// mais la fonte WinAnsi de pdfkit ne le rend pas (affiche '/'). On le remplace
// par un espace ASCII pour garantir un rendu correct sur tous les visualiseurs.
function fmtNumber(n) {
    return Number(n || 0)
        .toLocaleString('fr-FR', { minimumFractionDigits: 0, maximumFractionDigits: 2 })
        .replace(/[  ]/g, ' ');
}

function dayKey(iso) {
    const d = new Date(iso);
    return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate())).getTime();
}

function dfDay(iso) {
    return new Date(iso).toLocaleDateString('fr-FR', {
        weekday: 'long', day: '2-digit', month: 'long', year: 'numeric'
    });
}

function dfDateTime(iso) {
    const d = new Date(iso);
    return d.toLocaleDateString('fr-FR') + ' ' + d.toLocaleTimeString('fr-FR').substring(0, 5);
}

// Operateur deduit du prefixe local du numero (07=Orange, 05=MTN, 01=MOOV)
function phoneOpFromNum(phone) {
    if (!phone) return '';
    const p = String(phone).substring(0, 2);
    if (p === '07' || p === '08' || p === '09') return 'Orange';
    if (p === '05' || p === '04' || p === '06') return 'MTN';
    if (p === '01' || p === '02' || p === '03') return 'MOOV';
    return '';
}

const MARGIN = 36;
const PAGE_RIGHT = 559;

function generate(res, transactions, patron = [], meta = {}) {
    const doc = new PDFDocument({ size: 'A4', margin: MARGIN });
    doc.pipe(res);

    // FILTRE : ignorer les lignes hors-perimetre (anciennes donnees malformees)
    const cleanTx = (transactions || []).filter(t =>
        t && (t.type === 'RECU' || t.type === 'SORTIE') && Number(t.amount) > 0
    );

    const account = meta.accountName || 'MoMo Fin';
    const fmtFR = d => d ? new Date(d).toLocaleDateString('fr-FR') : null;
    let titleLine;
    if (meta.from && meta.to) {
        titleLine = `Transactions chez ${account} — du ${fmtFR(meta.from)} au ${fmtFR(meta.to)}`;
    } else if (meta.from) {
        titleLine = `Transactions chez ${account} — depuis le ${fmtFR(meta.from)}`;
    } else {
        titleLine = `Transactions chez ${account}`;
    }

    // En-tete avec logo
    if (meta.logoBuffer) {
        try {
            doc.image(meta.logoBuffer, MARGIN, MARGIN, { fit: [60, 60] });
            doc.fontSize(16).fillColor('#1565C0').text(titleLine, 110, 50, { align: 'left' });
            doc.moveDown(2);
        } catch (e) {
            doc.fontSize(16).fillColor('#1565C0').text(titleLine, MARGIN, doc.y, { align: 'left' });
        }
    } else {
        doc.fontSize(16).fillColor('#1565C0').text(titleLine, MARGIN, doc.y, { align: 'left' });
    }
    doc.fillColor('black');
    doc.fontSize(9).fillColor('#555').text(`Genere le ${new Date().toLocaleString('fr-FR').replace(/[  ]/g, ' ')}`, MARGIN, doc.y);
    doc.moveDown();
    doc.fillColor('black');

    // Regrouper par jour
    const groups = new Map();
    for (const t of cleanTx) {
        const k = dayKey(t.ts);
        if (!groups.has(k)) groups.set(k, []);
        groups.get(k).push(t);
    }
    const days = [...groups.keys()].sort((a, b) => b - a);

    let totalRecu = 0, totalSortie = 0;

    for (const day of days) {
        const list = groups.get(day).sort((a, b) => new Date(b.ts) - new Date(a.ts));
        doc.fontSize(13).fillColor('#1565C0').text(dfDay(new Date(day).toISOString()), MARGIN, doc.y);
        doc.fillColor('black');
        doc.moveDown(0.3);

        // Entetes colonnes
        const yStart = doc.y;
        doc.fontSize(10).fillColor('#555');
        doc.text('Date/Heure', MARGIN, yStart);
        doc.text('Type', 110, yStart);  // colonne 55px car 'T. envoyé' plus long
        doc.text('Montant', 160, yStart);
        doc.text('Numéro', 235, yStart);
        doc.text('Référence', 335, yStart);
        doc.text('Opérateur', 480, yStart);
        doc.moveDown(0.5);
        doc.moveTo(MARGIN, doc.y).lineTo(PAGE_RIGHT, doc.y).strokeColor('#bbb').stroke();
        doc.moveDown(0.3);

        let recu = 0, sortie = 0;
        for (const t of list) {
            if (doc.y > 760) { doc.addPage(); }
            const y = doc.y;
            doc.fontSize(9).fillColor('black');
            doc.text(dfDateTime(t.ts), MARGIN, y, { width: 70 });
            const label = (function() {
                if (t.subtype === 'DEPOT') return 'Dépôt';
                if (t.subtype === 'RETRAIT') return 'Retrait';
                if (t.subtype === 'TRANSFERT_ENVOYE') return 'T. envoyé';
                if (t.subtype === 'TRANSFERT_RECU') return 'T. reçu';
                return t.type === 'RECU' ? 'Retrait' : 'Dépôt';
            })();
            doc.text(label, 110, y, { width: 55 });
            doc.text(`${fmtNumber(t.amount)} ${t.currency || 'FCFA'}`, 160, y, { width: 70 });
            doc.text((t.phone_number || '—').substring(0, 14), 235, y, { width: 95 });
            doc.text((t.reference || '—').substring(0, 24), 335, y, { width: 140 });
            const effOp = phoneOpFromNum(t.phone_number) || t.operator || '';
            doc.text(effOp, 480, y, { width: 75 });
            doc.moveDown(0.8);
            if (t.type === 'RECU') recu += Number(t.amount);
            else if (t.type === 'SORTIE') sortie += Number(t.amount);
        }
        totalRecu += recu;
        totalSortie += sortie;

        // ⚠️ Reset position x avant les totaux (sinon ca reste colle a x=480)
        doc.moveTo(MARGIN, doc.y).lineTo(PAGE_RIGHT, doc.y).strokeColor('#bbb').stroke();
        doc.moveDown(0.3);
        const yTotals = doc.y;
        doc.fontSize(10).fillColor('black')
            .text(`Retrait : ${fmtNumber(recu)} FCFA`, MARGIN, yTotals, { continued: true })
            .text(`     |     Dépôt : ${fmtNumber(sortie)} FCFA`, { continued: true })
            .text(`     |     Solde : ${fmtNumber(recu - sortie)} FCFA`);
        doc.moveDown(1);
    }

    // Section saisies manuelles (PATRON / Mes Comptes)
    if (patron && patron.length > 0) {
        if (doc.y > 700) doc.addPage();
        doc.fontSize(13).fillColor('#1565C0').text('Saisies manuelles (Mes Comptes)', MARGIN, doc.y);
        doc.fillColor('black');
        doc.moveDown(0.4);
        let pRecu = 0, pSortie = 0;
        for (const e of patron) {
            if (doc.y > 760) doc.addPage();
            const label = e.type === 'RECU' ? 'Entrée' : 'Sortie';
            doc.fontSize(9).text(
                `${new Date(e.ts).toLocaleString('fr-FR').replace(/[  ]/g, ' ')}    ${label}    ${fmtNumber(e.amount)}    ${e.note || ''}`,
                MARGIN, doc.y
            );
            doc.moveDown(0.2);
            if (e.type === 'RECU') pRecu += Number(e.amount);
            else pSortie += Number(e.amount);
        }
        doc.moveDown(0.3);
        doc.moveTo(MARGIN, doc.y).lineTo(PAGE_RIGHT, doc.y).strokeColor('#bbb').stroke();
        doc.moveDown(0.3);
        doc.fontSize(11).text(
            `Saisies — Entrée : ${fmtNumber(pRecu)}     |     Sortie : ${fmtNumber(pSortie)}     |     Total : ${fmtNumber(pRecu - pSortie)}`,
            MARGIN, doc.y
        );
        doc.moveDown();
    }

    // Total general — encadre
    if (doc.y > 720) doc.addPage();
    doc.moveDown(0.5);
    doc.moveTo(MARGIN, doc.y).lineTo(PAGE_RIGHT, doc.y).strokeColor('#1565C0').lineWidth(2).stroke();
    doc.moveDown(0.5);
    doc.fontSize(13).fillColor('#1565C0').text('TOTAL GÉNÉRAL', MARGIN, doc.y);
    doc.moveDown(0.3);
    doc.fontSize(11).fillColor('black')
        .text(`Total Retrait : ${fmtNumber(totalRecu)} FCFA`, MARGIN, doc.y, { continued: true })
        .text(`     |     Total Dépôt : ${fmtNumber(totalSortie)} FCFA`, { continued: true })
        .text(`     |     Solde : ${fmtNumber(totalRecu - totalSortie)} FCFA`);
    doc.lineWidth(1);

    doc.end();
}

module.exports = { generate, dayKey, fmtNumber, phoneOpFromNum };
