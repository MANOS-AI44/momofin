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

/**
 * @param {WritableStream} res — flux de sortie (HTTP response)
 * @param {Array} transactions — [{ ts, type, amount, currency, reference, operator }]
 * @param {Array} patron — [{ ts, type, amount, note }]
 */
function generate(res, transactions, patron = []) {
    const doc = new PDFDocument({ size: 'A4', margin: 36 });
    doc.pipe(res);

    doc.fontSize(18).text('MoMo Fin — Rapport des transactions', { align: 'left' });
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
        doc.text('Heure', 36, yStart);
        doc.text('Type', 100, yStart);
        doc.text('Montant', 160, yStart);
        doc.text('Référence', 260, yStart);
        doc.text('Opérateur', 430, yStart);
        doc.moveDown(0.5);
        doc.moveTo(36, doc.y).lineTo(559, doc.y).strokeColor('#bbb').stroke();
        doc.moveDown(0.3);

        let recu = 0, sortie = 0;
        for (const t of list) {
            if (doc.y > 760) { doc.addPage(); }
            const y = doc.y;
            doc.fontSize(9).fillColor('black');
            doc.text(dfTime(t.ts), 36, y, { width: 60 });
            doc.text(t.type === 'RECU' ? 'Reçu' : (t.type === 'SORTIE' ? 'Sortie' : '—'), 100, y, { width: 55 });
            doc.text(`${fmtNumber(t.amount)} ${t.currency || ''}`, 160, y, { width: 95 });
            doc.text((t.reference || '—').substring(0, 30), 260, y, { width: 165 });
            doc.text(t.operator || '', 430, y, { width: 100 });
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
