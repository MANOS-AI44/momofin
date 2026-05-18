// Couche d'accès PostgreSQL.
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
        // === 1) Creation des tables (idempotent : CREATE IF NOT EXISTS) ===
        await client.query(`
            CREATE TABLE IF NOT EXISTS users (
                id BIGSERIAL PRIMARY KEY,
                email TEXT UNIQUE NOT NULL,
                password_hash TEXT NOT NULL,
                name TEXT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );
        `);
        await client.query(`
            CREATE TABLE IF NOT EXISTS sessions (
                token TEXT PRIMARY KEY,
                user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                expires_at TIMESTAMPTZ NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );
        `);
        await client.query(`
            CREATE TABLE IF NOT EXISTS devices (
                token TEXT PRIMARY KEY,
                label TEXT,
                user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
            );
        `);
        await client.query(`
            CREATE TABLE IF NOT EXISTS daily_points (
                id BIGSERIAL PRIMARY KEY,
                user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
                day_key BIGINT NOT NULL,
                om NUMERIC(18,2) DEFAULT 0,
                momo NUMERIC(18,2) DEFAULT 0,
                moov NUMERIC(18,2) DEFAULT 0,
                wave NUMERIC(18,2) DEFAULT 0,
                djamo NUMERIC(18,2) DEFAULT 0,
                cfa NUMERIC(18,2) DEFAULT 0,
                entree NUMERIC(18,2) DEFAULT 0,
                sortie NUMERIC(18,2) DEFAULT 0,
                note TEXT,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                UNIQUE(user_id, day_key)
            );
        `);
        await client.query(`
            CREATE TABLE IF NOT EXISTS transactions (
                id BIGSERIAL PRIMARY KEY,
                device_id TEXT NOT NULL,
                operator TEXT,
                type TEXT NOT NULL CHECK (type IN ('RECU','SORTIE','INCONNU')),
                amount NUMERIC(18,2) NOT NULL DEFAULT 0,
                currency TEXT,
                reference TEXT,
                phone_number TEXT,
                ts TIMESTAMPTZ NOT NULL,
                raw_sender TEXT,
                raw_body TEXT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                UNIQUE(device_id, raw_body, ts)
            );
        `);
        await client.query(`
            CREATE TABLE IF NOT EXISTS folders (
                id BIGSERIAL PRIMARY KEY,
                device_id TEXT NOT NULL,
                client_id TEXT,
                name TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                UNIQUE(device_id, client_id)
            );
        `);
        await client.query(`
            CREATE TABLE IF NOT EXISTS folder_entries (
                id BIGSERIAL PRIMARY KEY,
                folder_id BIGINT NOT NULL REFERENCES folders(id) ON DELETE CASCADE,
                device_id TEXT NOT NULL,
                client_id TEXT,
                type TEXT NOT NULL CHECK (type IN ('RECU','SORTIE')),
                amount NUMERIC(18,2) NOT NULL,
                note TEXT,
                ts TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                UNIQUE(folder_id, client_id)
            );
        `);
        await client.query(`CREATE INDEX IF NOT EXISTS idx_folders_device ON folders(device_id);`);
        await client.query(`CREATE INDEX IF NOT EXISTS idx_folder_entries_folder ON folder_entries(folder_id);`);
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

        // === 2) Migrations de schema (idempotentes : ADD COLUMN IF NOT EXISTS) ===
        // Sur tables qui existent forcement apres les CREATE TABLE ci-dessus.
        await client.query(`ALTER TABLE devices ADD COLUMN IF NOT EXISTS user_id BIGINT REFERENCES users(id) ON DELETE CASCADE;`);
        await client.query(`ALTER TABLE devices ADD COLUMN IF NOT EXISTS code TEXT UNIQUE;`);
        await client.query(`ALTER TABLE sessions ADD COLUMN IF NOT EXISTS device_token TEXT REFERENCES devices(token) ON DELETE CASCADE;`);
        await client.query(`ALTER TABLE users ADD COLUMN IF NOT EXISTS logo_data BYTEA;`);
        await client.query(`ALTER TABLE users ADD COLUMN IF NOT EXISTS logo_mime TEXT;`);
        await client.query(`ALTER TABLE transactions ADD COLUMN IF NOT EXISTS phone_number TEXT;`);

        // === 3) Migrations de donnees (idempotentes : conditions WHERE filtrent) ===
        // Normaliser les numeros CI (strip prefixe 225) sur les anciennes lignes
        await client.query(`UPDATE transactions
            SET phone_number = SUBSTRING(phone_number FROM 4)
            WHERE phone_number LIKE '225%' AND LENGTH(phone_number) > 10;`);
        // Re-deriver l'operateur depuis le prefixe du numero pour les anciennes lignes 'Autre'
        await client.query(`UPDATE transactions SET operator = 'Orange'
            WHERE (operator = 'Autre' OR operator IS NULL OR operator = '')
            AND phone_number ~ '^0[789]'`);
        await client.query(`UPDATE transactions SET operator = 'MTN'
            WHERE (operator = 'Autre' OR operator IS NULL OR operator = '')
            AND phone_number ~ '^0[456]'`);
        await client.query(`UPDATE transactions SET operator = 'MOOV'
            WHERE (operator = 'Autre' OR operator IS NULL OR operator = '')
            AND phone_number ~ '^0[123]'`);
        // Normalisation : Moov -> MOOV pour coherence d'affichage
        await client.query(`UPDATE transactions SET operator = 'MOOV' WHERE operator = 'Moov';`);

        // === 4) Index (idempotents : CREATE INDEX IF NOT EXISTS) ===
        await client.query(`CREATE INDEX IF NOT EXISTS idx_tx_ts ON transactions(ts DESC);`);
        await client.query(`CREATE INDEX IF NOT EXISTS idx_patron_ts ON patron_entries(ts DESC);`);
        await client.query(`CREATE INDEX IF NOT EXISTS idx_sessions_user ON sessions(user_id);`);

        // === 5) Device par defaut (seed) ===
        const { rows } = await client.query('SELECT COUNT(*)::int AS n FROM devices');
        if (rows[0].n === 0 && process.env.DEFAULT_DEVICE_TOKEN) {
            await client.query(
                'INSERT INTO devices (token, label) VALUES ($1, $2) ON CONFLICT DO NOTHING',
                [process.env.DEFAULT_DEVICE_TOKEN, 'Téléphone par défaut']
            );
        }
    } finally {
        client.release();
    }
}

module.exports = { pool, init };
