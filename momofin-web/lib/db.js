// Couche d'accès PostgreSQL.
// Railway fournit DATABASE_URL automatiquement quand on ajoute le plugin PostgreSQL.
const { Pool } = require('pg');

const pool = new Pool({
    connectionString: process.env.DATABASE_URL,
    ssl: process.env.DATABASE_URL && process.env.DATABASE_URL.includes('railway')
        ? { rejectUnauthorized: false }
        : false
});

async function init() {
    const client = await pool.connect();
    try {
        await client.query(`
            CREATE TABLE IF NOT EXISTS transactions (
                id BIGSERIAL PRIMARY KEY,
                device_id TEXT NOT NULL,
                operator TEXT,
                type TEXT NOT NULL CHECK (type IN ('RECU','SORTIE','INCONNU')),
                amount NUMERIC(18,2) NOT NULL DEFAULT 0,
                currency TEXT,
                reference TEXT,
                ts TIMESTAMPTZ NOT NULL,
                raw_sender TEXT,
                raw_body TEXT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                UNIQUE(device_id, raw_body, ts)
            );
        `);
        await client.query(`
            CREATE TABLE IF NOT EXISTS patron_entries (
                id BIGSERIAL PRIMARY KEY,
                device_id TEXT NOT NULL,
                type TEXT NOT NULL CHECK (type IN ('RECU','SORTIE')),
                amount NUMERIC(18,2) NOT NULL,
                note TEXT,
                ts TIMESTAMPTZ NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );
        `);
        await client.query(`
            CREATE TABLE IF NOT EXISTS devices (
                token TEXT PRIMARY KEY,
                label TEXT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );
        `);
        await client.query(`CREATE INDEX IF NOT EXISTS idx_tx_ts ON transactions(ts DESC);`);
        await client.query(`CREATE INDEX IF NOT EXISTS idx_patron_ts ON patron_entries(ts DESC);`);

        // Token par défaut si aucune device n'est inscrite (premier démarrage)
        const { rows } = await client.query('SELECT COUNT(*)::int AS n FROM devices');
        if (rows[0].n === 0 && process.env.DEFAULT_DEVICE_TOKEN) {
            await client.query(
                'INSERT INTO devices (token, label) VALUES ($1, $2) ON CONFLICT DO NOTHING',
                [process.env.DEFAULT_DEVICE_TOKEN, 'Téléphone par défaut']
            );
            console.log('Device par défaut inscrite.');
        }
    } finally {
        client.release();
    }
}

module.exports = { pool, init };
