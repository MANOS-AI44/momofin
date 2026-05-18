// Génération du PDF imprimable côté serveur (pdfkit).
const PDFDocument = require('pdfkit');

function fmtNumber(n) {
    return Number(n || 0).toLocaleString('fr-FR', {
        minimumFractionDigits: 0,
        maximumFractionDigits: 2
    });
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

function dfTime(iso) {
    return new Date(iso).toLocaleTimeString('fr-FR');
}

function dfDateTime(iso) {
    const d = new Date(iso);
    return d.toLocaleDateString('fr-FR') + ' ' + d.toLocaleTimeString('fr-FR').substring(0,5);
}

/**
 * @param {WritableStream} res — flux de sortie (HTTP response)
 * @param {Array} transactions — [{ ts, type, amount, currency, reference, operator }]
 * @param {Array} patron — [{ ts, type, amount, note }]
 */
function generate(res, transactions, patron = [], meta = {}) {
    const doc = new PDFDocument({ size: 'A4', margin: 36 });
    doc.pipe(res);

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

    // Header avec logo (si fourni)
    if (meta.logoBuffer) {
        try {
            doc.image(meta.logoBuffer, 36, 36, { fit: [60, 60] });
            doc.fontSize(16).fillColor('#1565C0').text(titleLine, 110, 50, { align: 'left' });
            doc.moveDown(2);
        } catch (e) {
            // Logo invalide → fallback texte seul
            doc.fontSize(16).fillColor('#1565C0').text(titleLine, { align: 'left' });
        }
    } else {
        doc.fontSize(16).fillColor('#1565C0').text(titleLine, { align: 'left' });
    }
    doc.fillColor('black');
    doc.fontSize(9).fillColor('#555').text(`Généré le ${new Date().toLocaleString('fr-FR')}`);
    doc.moveDown();
    doc.fillColor('black');

    // Regrouper par jour
    const groups = new Map();
    for (const t of transactions) {
        const k = dayKey(t.ts);
        if (!groups.has(k)) groups.set(k, []);
        groups.get(k).push(t);
    }
    const days = [...groups.keys()].sort((a, b) => b - a);

    let totalRecu = 0, totalSortie = 0;

    for (const day of days) {
        const list = groups.get(day).sort((a, b) => new Date(b.ts) - new Date(a.ts));
        doc.fontSize(13).fillColor('black').text(dfDay(new Date(day).toISOString()), { underline: false });
        doc.moveDown(0.3);

        // Entêtes
        const yStart = doc.y;
        doc.fontSize(10).fillColor('#333');
        doc.text('Date/Heure', 36, yStart);
        doc.text('Type', 110, yStart);
        doc.text('Montant', 155, yStart);
        doc.text('Numéro', 235, yStart);
        doc.text('Référence', 335, yStart);
        doc.text('Opérateur', 470, yStart);
        doc.moveDown(0.5);
        doc.moveTo(36, doc.y).lineTo(559, doc.y).strokeColor('#bbb').stroke();
        doc.moveDown(0.3);

        let recu = 0, sortie = 0;
        for (const t of list) {
            if (doc.y > 760) { doc.addPage(); }
            const y = doc.y;
            doc.fontSize(9).fillColor('black');
            doc.text(dfDateTime(t.ts), 36, y, { width: 70 });
            doc.text(t.type === 'RECU' ? 'Retrait' : (t.type === 'SORTIE' ? 'Dépôt' : '—'), 110, y, { width: 40 });
            doc.text(`${fmtNumber(t.amount)} ${t.currency || ''}`, 155, y, { width: 75 });
            const phoneOp = phoneOpFromNum(t.phone_number);
            const phoneDisplay = (t.phone_number || '—') + (phoneOp ? ` (${phoneOp})` : '');
            doc.text(phoneDisplay.substring(0, 20), 235, y, { width: 95 });
            doc.text((t.reference || '—').substring(0, 22), 335, y, { width: 130 });
            const effOp = phoneOpFromNum(t.phone_number) || t.operator || '';
            doc.text(effOp, 470, y, { width: 80 });
            doc.moveDown(0.8);
            if (t.type === 'RECU') recu += Number(t.amount);
            else if (t.type === 'SORTIE') sortie += Number(t.amount);
        }
        totalRecu += recu;
        totalSortie += sortie;

        doc.moveTo(36, doc.y).lineTo(559, doc.y).strokeColor('#bbb').stroke();
        doc.moveDown(0.3);
        doc.fontSize(10).fillColor('black').text(
            `Reçu : ${fmtNumber(recu)}    |    Sortie : ${fmtNumber(sortie)}    |    Solde : ${fmtNumber(recu - sortie)}`,
            { align: 'left' }
        );
        doc.moveDown();
    }

    // Section PATRON
    if (patron.length > 0) {
        if (doc.y > 700) doc.addPage();
        doc.fontSize(13).fillColor('black').text('Section PATRON (saisies manuelles)');
        doc.moveDown(0.4);
        let pRecu = 0, pSortie = 0;
        for (const e of patron) {
            if (doc.y > 760) doc.addPage();
            const label = e.type === 'RECU' ? 'Reçu' : 'Sortie';
            doc.fontSize(9).text(
                `${new Date(e.ts).toLocaleString('fr-FR')}    ${label}    ${fmtNumber(e.amount)}    ${e.note || ''}`
            );
            doc.moveDown(0.2);
            if (e.type === 'RECU') pRecu += Number(e.amount);
            else pSortie += Number(e.amount);
        }
        doc.moveDown(0.3);
        doc.moveTo(36, doc.y).lineTo(559, doc.y).strokeColor('#bbb').stroke();
        doc.moveDown(0.3);
        doc.fontSize(11).text(
            `Patron — Reçu : ${fmtNumber(pRecu)}    |    Sortie : ${fmtNumber(pSortie)}    |    Total : ${fmtNumber(pRecu - pSortie)}`
        );
        doc.moveDown();
    }

    // Total général
    if (doc.y > 740) doc.addPage();
    doc.moveTo(36, doc.y).lineTo(559, doc.y).strokeColor('#888').stroke();
    doc.moveDown(0.4);
    doc.fontSize(12).fillColor('black').text(
        `TOTAL GÉNÉRAL — Reçu : ${fmtNumber(totalRecu)}    |    Sortie : ${fmtNumber(totalSortie)}    |    Solde : ${fmtNumber(totalRecu - totalSortie)}`
    );

    doc.end();
}

module.exports = { generate, dayKey, fmtNumber };
