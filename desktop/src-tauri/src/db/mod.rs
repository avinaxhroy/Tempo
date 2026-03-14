pub mod models;

use models::{PairingInfo, Play, PlayStatus, Settings, SyncRecord};
use rusqlite::{params, Connection};
use std::path::Path;

pub struct Database {
    conn: Connection,
    db_path: std::path::PathBuf,
}

impl Database {
    pub fn new(path: &Path) -> Result<Self, rusqlite::Error> {
        let conn = Connection::open(path)?;

        // Enable WAL mode for better concurrent access and crash resilience
        conn.execute_batch("PRAGMA journal_mode=WAL;")?;
        // Enable foreign keys
        conn.execute_batch("PRAGMA foreign_keys=ON;")?;

        let db = Database {
            conn,
            db_path: path.to_path_buf(),
        };
        db.initialize_tables()?;
        Ok(db)
    }

    /// Create a backup of the database file.
    /// Returns the backup path on success.
    pub fn backup(&self) -> Result<std::path::PathBuf, rusqlite::Error> {
        let backup_path = self.db_path.with_extension("db.bak");
        let mut dst = Connection::open(&backup_path)?;
        let backup = rusqlite::backup::Backup::new(&self.conn, &mut dst)?;
        backup.run_to_completion(100, std::time::Duration::from_millis(50), None)?;
        log::info!("Database backed up to {:?}", backup_path);
        Ok(backup_path)
    }

    /// Run an integrity check on the database.
    pub fn check_integrity(&self) -> Result<bool, rusqlite::Error> {
        let result: String = self.conn.query_row(
            "PRAGMA integrity_check",
            [],
            |row| row.get(0),
        )?;
        Ok(result == "ok")
    }

    fn initialize_tables(&self) -> Result<(), rusqlite::Error> {
        self.conn.execute_batch(
            "
            CREATE TABLE IF NOT EXISTS scrobbles (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                artist TEXT NOT NULL,
                album TEXT DEFAULT '',
                duration_ms INTEGER DEFAULT 0,
                timestamp_utc INTEGER NOT NULL,
                source_app TEXT DEFAULT 'Unknown',
                status TEXT DEFAULT 'queued',
                listened_ms INTEGER DEFAULT 0,
                skipped INTEGER DEFAULT 0,
                replay_count INTEGER DEFAULT 0,
                is_muted INTEGER DEFAULT 0,
                completion_percentage REAL DEFAULT 0.0,
                pause_count INTEGER DEFAULT 0,
                seek_count INTEGER DEFAULT 0,
                session_id TEXT DEFAULT '',
                site TEXT DEFAULT '',
                content_type TEXT DEFAULT 'MUSIC',
                volume_level REAL DEFAULT -1.0,
                created_at TEXT DEFAULT (datetime('now'))
            );

            CREATE TABLE IF NOT EXISTS pairing (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                phone_ip TEXT NOT NULL,
                phone_port INTEGER NOT NULL DEFAULT 8765,
                auth_token TEXT NOT NULL,
                device_name TEXT DEFAULT '',
                paired_at TEXT DEFAULT (datetime('now'))
            );

            CREATE TABLE IF NOT EXISTS settings (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                sync_interval_minutes INTEGER DEFAULT 30,
                auto_detect_enabled INTEGER DEFAULT 1,
                polling_interval_seconds INTEGER DEFAULT 5,
                minimize_to_tray INTEGER DEFAULT 1,
                start_on_boot INTEGER DEFAULT 0,
                theme TEXT DEFAULT 'dark',
                low_battery_threshold INTEGER DEFAULT 15
            );

            CREATE TABLE IF NOT EXISTS sync_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                synced_count INTEGER NOT NULL,
                status TEXT NOT NULL,
                error_message TEXT,
                synced_at TEXT DEFAULT (datetime('now'))
            );

            INSERT OR IGNORE INTO settings (id) VALUES (1);

            CREATE INDEX IF NOT EXISTS idx_scrobbles_status ON scrobbles(status);
            CREATE INDEX IF NOT EXISTS idx_scrobbles_timestamp ON scrobbles(timestamp_utc);
            CREATE INDEX IF NOT EXISTS idx_scrobbles_session ON scrobbles(session_id);
            CREATE INDEX IF NOT EXISTS idx_scrobbles_content_type ON scrobbles(content_type);

            -- User-defined known artist/band names that should never be split by the parser
            CREATE TABLE IF NOT EXISTS user_known_artists (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                normalized_name TEXT NOT NULL,
                created_at TEXT DEFAULT (datetime('now')),
                UNIQUE(normalized_name)
            );

            -- User-defined YouTube channels known to be music artists
            CREATE TABLE IF NOT EXISTS user_youtube_channels (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                channel_name TEXT NOT NULL,
                normalized_name TEXT NOT NULL,
                created_at TEXT DEFAULT (datetime('now')),
                UNIQUE(normalized_name)
            );

            CREATE INDEX IF NOT EXISTS idx_user_known_artists_norm ON user_known_artists(normalized_name);
            CREATE INDEX IF NOT EXISTS idx_user_youtube_channels_norm ON user_youtube_channels(normalized_name);
            ",
        )?;

        // Run migrations for existing databases (adds new columns if missing)
        self.run_migrations()?;

        Ok(())
    }

    /// Run schema migrations to add new columns to existing databases.
    /// Each ALTER TABLE is idempotent — it silently ignores if the column already exists.
    fn run_migrations(&self) -> Result<(), rusqlite::Error> {
        let migration_columns = [
            ("replay_count", "INTEGER DEFAULT 0"),
            ("is_muted", "INTEGER DEFAULT 0"),
            ("completion_percentage", "REAL DEFAULT 0.0"),
            ("pause_count", "INTEGER DEFAULT 0"),
            ("seek_count", "INTEGER DEFAULT 0"),
            ("session_id", "TEXT DEFAULT ''"),
            ("site", "TEXT DEFAULT ''"),
            ("content_type", "TEXT DEFAULT 'MUSIC'"),
            ("volume_level", "REAL DEFAULT -1.0"),
        ];

        for (col_name, col_type) in &migration_columns {
            let sql = format!(
                "ALTER TABLE scrobbles ADD COLUMN {} {}",
                col_name, col_type
            );
            // Ignore "duplicate column name" errors — means migration already ran
            match self.conn.execute(&sql, []) {
                Ok(_) => log::info!("Migration: added column '{}' to scrobbles", col_name),
                Err(rusqlite::Error::ExecuteReturnedResults) => {}
                Err(e) if e.to_string().contains("duplicate column") => {}
                Err(e) => log::debug!("Migration column '{}': {}", col_name, e),
            }
        }

        // Migrate settings table (add new columns if missing)
        let settings_migrations = [
            ("low_battery_threshold", "INTEGER DEFAULT 15"),
        ];
        for (col_name, col_type) in &settings_migrations {
            let sql = format!(
                "ALTER TABLE settings ADD COLUMN {} {}",
                col_name, col_type
            );
            match self.conn.execute(&sql, []) {
                Ok(_) => log::info!("Migration: added column '{}' to settings", col_name),
                Err(rusqlite::Error::ExecuteReturnedResults) => {}
                Err(e) if e.to_string().contains("duplicate column") => {}
                Err(e) => log::debug!("Migration settings column '{}': {}", col_name, e),
            }
        }

        // Create new indexes if they don't exist
        self.conn.execute_batch(
            "
            CREATE INDEX IF NOT EXISTS idx_scrobbles_session ON scrobbles(session_id);
            CREATE INDEX IF NOT EXISTS idx_scrobbles_content_type ON scrobbles(content_type);
            ",
        )?;

        Ok(())
    }

    // --- Plays ---

    pub fn insert_play(&self, play: &Play) -> Result<i64, rusqlite::Error> {
        self.conn.execute(
            "INSERT INTO scrobbles (title, artist, album, duration_ms, timestamp_utc, source_app, status, listened_ms, skipped, replay_count, is_muted, completion_percentage, pause_count, seek_count, session_id, site, content_type, volume_level)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18)",
            params![
                play.title,
                play.artist,
                play.album,
                play.duration_ms,
                play.timestamp_utc,
                play.source_app,
                play.status.as_str(),
                play.listened_ms,
                play.skipped as i32,
                play.replay_count,
                play.is_muted as i32,
                play.completion_percentage,
                play.pause_count,
                play.seek_count,
                play.session_id,
                play.site,
                play.content_type,
                play.volume_level,
            ],
        )?;
        Ok(self.conn.last_insert_rowid())
    }

    pub fn has_recent_play(&self, title: &str, artist: &str, timestamp: i64) -> Result<bool, rusqlite::Error> {
        let window = 60_000; // ±60 seconds
        let count: i64 = self.conn.query_row(
            "SELECT COUNT(*) FROM scrobbles WHERE title = ?1 AND artist = ?2
             AND timestamp_utc BETWEEN ?3 AND ?4",
            params![title, artist, timestamp - window, timestamp + window],
            |row| row.get(0),
        )?;
        Ok(count > 0)
    }

    /// Returns plays with status 'queued' or 'failed' — everything waiting to be synced.
    pub fn get_pending_plays(&self) -> Result<Vec<Play>, rusqlite::Error> {
        let mut stmt = self.conn.prepare(
            "SELECT id, title, artist, album, duration_ms, timestamp_utc, source_app, status, listened_ms, skipped,
                    replay_count, is_muted, completion_percentage, pause_count, seek_count, session_id, site, content_type, volume_level
             FROM scrobbles WHERE status IN ('queued', 'failed') ORDER BY timestamp_utc ASC",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(Play {
                id: Some(row.get(0)?),
                title: row.get(1)?,
                artist: row.get(2)?,
                album: row.get(3)?,
                duration_ms: row.get(4)?,
                timestamp_utc: row.get(5)?,
                source_app: row.get(6)?,
                status: PlayStatus::from_str(&row.get::<_, String>(7)?),
                listened_ms: row.get(8)?,
                skipped: row.get::<_, i32>(9)? != 0,
                replay_count: row.get::<_, u32>(10).unwrap_or(0),
                is_muted: row.get::<_, i32>(11).unwrap_or(0) != 0,
                completion_percentage: row.get::<_, f64>(12).unwrap_or(0.0),
                pause_count: row.get::<_, u32>(13).unwrap_or(0),
                seek_count: row.get::<_, u32>(14).unwrap_or(0),
                session_id: row.get::<_, String>(15).unwrap_or_default(),
                site: row.get::<_, String>(16).unwrap_or_default(),
                content_type: row.get::<_, String>(17).unwrap_or_else(|_| "MUSIC".to_string()),
                volume_level: row.get::<_, f64>(18).unwrap_or(-1.0),
            })
        })?;
        rows.collect()
    }

    pub fn get_queued_plays(&self) -> Result<Vec<Play>, rusqlite::Error> {
        let mut stmt = self.conn.prepare(
            "SELECT id, title, artist, album, duration_ms, timestamp_utc, source_app, status, listened_ms, skipped,
                    replay_count, is_muted, completion_percentage, pause_count, seek_count, session_id, site, content_type, volume_level
             FROM scrobbles WHERE status = 'queued' ORDER BY timestamp_utc ASC",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(Play {
                id: Some(row.get(0)?),
                title: row.get(1)?,
                artist: row.get(2)?,
                album: row.get(3)?,
                duration_ms: row.get(4)?,
                timestamp_utc: row.get(5)?,
                source_app: row.get(6)?,
                status: PlayStatus::from_str(&row.get::<_, String>(7)?),
                listened_ms: row.get(8)?,
                skipped: row.get::<_, i32>(9)? != 0,
                replay_count: row.get::<_, u32>(10).unwrap_or(0),
                is_muted: row.get::<_, i32>(11).unwrap_or(0) != 0,
                completion_percentage: row.get::<_, f64>(12).unwrap_or(0.0),
                pause_count: row.get::<_, u32>(13).unwrap_or(0),
                seek_count: row.get::<_, u32>(14).unwrap_or(0),
                session_id: row.get::<_, String>(15).unwrap_or_default(),
                site: row.get::<_, String>(16).unwrap_or_default(),
                content_type: row.get::<_, String>(17).unwrap_or_else(|_| "MUSIC".to_string()),
                volume_level: row.get::<_, f64>(18).unwrap_or(-1.0),
            })
        })?;
        rows.collect()
    }

    pub fn mark_plays_synced(&self, ids: &[i64]) -> Result<(), rusqlite::Error> {
        if ids.is_empty() {
            return Ok(());
        }
        let placeholders: Vec<String> = ids.iter().map(|_| "?".to_string()).collect();
        let sql = format!(
            "UPDATE scrobbles SET status = 'synced' WHERE id IN ({})",
            placeholders.join(",")
        );
        let params: Vec<Box<dyn rusqlite::types::ToSql>> = ids
            .iter()
            .map(|id| Box::new(*id) as Box<dyn rusqlite::types::ToSql>)
            .collect();
        let param_refs: Vec<&dyn rusqlite::types::ToSql> = params.iter().map(|p| p.as_ref()).collect();
        self.conn.execute(&sql, param_refs.as_slice())?;
        Ok(())
    }

    pub fn get_recent_plays(&self, limit: usize) -> Result<Vec<Play>, rusqlite::Error> {
        let mut stmt = self.conn.prepare(
            "SELECT id, title, artist, album, duration_ms, timestamp_utc, source_app, status, listened_ms, skipped,
                    replay_count, is_muted, completion_percentage, pause_count, seek_count, session_id, site, content_type, volume_level
             FROM scrobbles ORDER BY timestamp_utc DESC LIMIT ?1",
        )?;
        let rows = stmt.query_map([limit as i64], |row| {
            Ok(Play {
                id: Some(row.get(0)?),
                title: row.get(1)?,
                artist: row.get(2)?,
                album: row.get(3)?,
                duration_ms: row.get(4)?,
                timestamp_utc: row.get(5)?,
                source_app: row.get(6)?,
                status: PlayStatus::from_str(&row.get::<_, String>(7)?),
                listened_ms: row.get(8)?,
                skipped: row.get::<_, i32>(9)? != 0,
                replay_count: row.get::<_, u32>(10).unwrap_or(0),
                is_muted: row.get::<_, i32>(11).unwrap_or(0) != 0,
                completion_percentage: row.get::<_, f64>(12).unwrap_or(0.0),
                pause_count: row.get::<_, u32>(13).unwrap_or(0),
                seek_count: row.get::<_, u32>(14).unwrap_or(0),
                session_id: row.get::<_, String>(15).unwrap_or_default(),
                site: row.get::<_, String>(16).unwrap_or_default(),
                content_type: row.get::<_, String>(17).unwrap_or_else(|_| "MUSIC".to_string()),
                volume_level: row.get::<_, f64>(18).unwrap_or(-1.0),
            })
        })?;
        rows.collect()
    }

    pub fn delete_play(&self, id: i64) -> Result<usize, rusqlite::Error> {
        let deleted = self
            .conn
            .execute("DELETE FROM scrobbles WHERE id = ?1", params![id])?;
        Ok(deleted)
    }

    pub fn delete_plays(&self, ids: &[i64]) -> Result<usize, rusqlite::Error> {
        if ids.is_empty() {
            return Ok(0);
        }

        let placeholders: Vec<String> = ids.iter().map(|_| "?".to_string()).collect();
        let sql = format!(
            "DELETE FROM scrobbles WHERE id IN ({})",
            placeholders.join(",")
        );
        let params: Vec<Box<dyn rusqlite::types::ToSql>> = ids
            .iter()
            .map(|id| Box::new(*id) as Box<dyn rusqlite::types::ToSql>)
            .collect();
        let param_refs: Vec<&dyn rusqlite::types::ToSql> =
            params.iter().map(|param| param.as_ref()).collect();

        let deleted = self.conn.execute(&sql, param_refs.as_slice())?;
        Ok(deleted)
    }

    pub fn get_play_count(&self) -> Result<(i64, i64), rusqlite::Error> {
        let queued: i64 = self.conn.query_row(
            "SELECT COUNT(*) FROM scrobbles WHERE status = 'queued'",
            [],
            |row| row.get(0),
        )?;
        let total: i64 = self.conn.query_row(
            "SELECT COUNT(*) FROM scrobbles",
            [],
            |row| row.get(0),
        )?;
        Ok((queued, total))
    }

    pub fn clear_queue(&self) -> Result<usize, rusqlite::Error> {
        let count = self.conn.execute("DELETE FROM scrobbles WHERE status = 'queued'", [])?;
        Ok(count)
    }

    // --- Pairing ---

    pub fn save_pairing(&self, info: &PairingInfo) -> Result<(), rusqlite::Error> {
        self.conn.execute(
            "INSERT INTO pairing (id, phone_ip, phone_port, auth_token, device_name, paired_at)
             VALUES (1, ?1, ?2, ?3, ?4, COALESCE(?5, datetime('now')))
             ON CONFLICT(id) DO UPDATE SET
                 phone_ip = excluded.phone_ip,
                 phone_port = excluded.phone_port,
                 auth_token = excluded.auth_token,
                 device_name = excluded.device_name,
                 paired_at = COALESCE(?5, pairing.paired_at, datetime('now'))",
            params![
                info.phone_ip,
                info.phone_port,
                info.auth_token,
                info.device_name,
                info.paired_at,
            ],
        )?;
        Ok(())
    }

    pub fn get_pairing(&self) -> Result<Option<PairingInfo>, rusqlite::Error> {
        let mut stmt = self.conn.prepare(
            "SELECT phone_ip, phone_port, auth_token, device_name, paired_at FROM pairing WHERE id = 1",
        )?;
        let result = stmt.query_row([], |row| {
            Ok(PairingInfo {
                phone_ip: row.get(0)?,
                phone_port: row.get(1)?,
                auth_token: row.get(2)?,
                device_name: row.get(3)?,
                paired_at: row.get(4)?,
            })
        });
        match result {
            Ok(info) => Ok(Some(info)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(e),
        }
    }

    pub fn delete_pairing(&self) -> Result<(), rusqlite::Error> {
        self.conn.execute("DELETE FROM pairing WHERE id = 1", [])?;
        Ok(())
    }

    // --- Settings ---

    pub fn get_settings(&self) -> Result<Settings, rusqlite::Error> {
        self.conn.query_row(
            "SELECT sync_interval_minutes, auto_detect_enabled, polling_interval_seconds,
                    minimize_to_tray, start_on_boot, theme, low_battery_threshold
             FROM settings WHERE id = 1",
            [],
            |row| {
                Ok(Settings {
                    sync_interval_minutes: row.get(0)?,
                    auto_detect_enabled: row.get::<_, i32>(1)? != 0,
                    polling_interval_seconds: row.get(2)?,
                    minimize_to_tray: row.get::<_, i32>(3)? != 0,
                    start_on_boot: row.get::<_, i32>(4)? != 0,
                    theme: row.get(5)?,
                    low_battery_threshold: row.get::<_, i32>(6).unwrap_or(15),
                })
            },
        )
    }

    pub fn update_settings(&self, settings: &Settings) -> Result<(), rusqlite::Error> {
        self.conn.execute(
            "UPDATE settings SET sync_interval_minutes = ?1, auto_detect_enabled = ?2,
             polling_interval_seconds = ?3, minimize_to_tray = ?4, start_on_boot = ?5, theme = ?6,
             low_battery_threshold = ?7
             WHERE id = 1",
            params![
                settings.sync_interval_minutes,
                settings.auto_detect_enabled as i32,
                settings.polling_interval_seconds,
                settings.minimize_to_tray as i32,
                settings.start_on_boot as i32,
                settings.theme,
                settings.low_battery_threshold,
            ],
        )?;
        Ok(())
    }

    // --- Sync History ---

    pub fn record_sync(&self, count: i64, status: &str, error: Option<&str>) -> Result<(), rusqlite::Error> {
        self.conn.execute(
            "INSERT INTO sync_history (synced_count, status, error_message) VALUES (?1, ?2, ?3)",
            params![count, status, error],
        )?;
        Ok(())
    }

    pub fn get_last_sync(&self) -> Result<Option<SyncRecord>, rusqlite::Error> {
        let mut stmt = self.conn.prepare(
            "SELECT id, synced_count, status, error_message, synced_at
             FROM sync_history ORDER BY synced_at DESC LIMIT 1",
        )?;
        let result = stmt.query_row([], |row| {
            Ok(SyncRecord {
                id: row.get(0)?,
                synced_count: row.get(1)?,
                status: row.get(2)?,
                error_message: row.get(3)?,
                synced_at: row.get(4)?,
            })
        });
        match result {
            Ok(rec) => Ok(Some(rec)),
            Err(rusqlite::Error::QueryReturnedNoRows) => Ok(None),
            Err(e) => Err(e),
        }
    }

    pub fn get_stats(&self) -> Result<models::AppStats, rusqlite::Error> {
        let total_plays: i64 = self.conn.query_row(
            "SELECT COUNT(*) FROM scrobbles", [], |r| r.get(0)
        )?;
        let queued: i64 = self.conn.query_row(
            "SELECT COUNT(*) FROM scrobbles WHERE status = 'queued'", [], |r| r.get(0)
        )?;
        let synced: i64 = self.conn.query_row(
            "SELECT COUNT(*) FROM scrobbles WHERE status = 'synced'", [], |r| r.get(0)
        )?;
        let total_syncs: i64 = self.conn.query_row(
            "SELECT COUNT(*) FROM sync_history WHERE status = 'success'", [], |r| r.get(0)
        )?;
        let top_artist: Option<String> = self.conn.query_row(
            "SELECT artist FROM scrobbles GROUP BY artist ORDER BY COUNT(*) DESC LIMIT 1",
            [],
            |r| r.get(0),
        ).ok();
        let top_track: Option<String> = self.conn.query_row(
            "SELECT title || ' - ' || artist FROM scrobbles GROUP BY title, artist ORDER BY COUNT(*) DESC LIMIT 1",
            [],
            |r| r.get(0),
        ).ok();

        Ok(models::AppStats {
            total_plays,
            queued,
            synced,
            total_syncs,
            top_artist,
            top_track,
        })
    }

    // --- Sync History (extended) ---

    pub fn get_sync_history(&self, limit: usize) -> Result<Vec<SyncRecord>, rusqlite::Error> {
        let mut stmt = self.conn.prepare(
            "SELECT id, synced_count, status, error_message, synced_at
             FROM sync_history ORDER BY synced_at DESC LIMIT ?1",
        )?;
        let rows = stmt.query_map([limit as i64], |row| {
            Ok(SyncRecord {
                id: row.get(0)?,
                synced_count: row.get(1)?,
                status: row.get(2)?,
                error_message: row.get(3)?,
                synced_at: row.get(4)?,
            })
        })?;
        rows.collect()
    }

    /// Returns (success_count, fail_count, total_tracks_synced).
    pub fn get_sync_summary(&self) -> Result<(i64, i64, i64), rusqlite::Error> {
        let success: i64 = self.conn.query_row(
            "SELECT COUNT(*) FROM sync_history WHERE status = 'success'",
            [],
            |r| r.get(0),
        )?;
        let failed: i64 = self.conn.query_row(
            "SELECT COUNT(*) FROM sync_history WHERE status = 'failed'",
            [],
            |r| r.get(0),
        )?;
        let total_tracks: i64 = self.conn.query_row(
            "SELECT COALESCE(SUM(synced_count), 0) FROM sync_history WHERE status = 'success'",
            [],
            |r| r.get(0),
        )?;
        Ok((success, failed, total_tracks))
    }

    /// Returns a breakdown of scrobbles by source_app.
    pub fn get_source_breakdown(&self) -> Result<Vec<(String, i64)>, rusqlite::Error> {
        let mut stmt = self.conn.prepare(
            "SELECT source_app, COUNT(*) as cnt FROM scrobbles
             GROUP BY source_app ORDER BY cnt DESC",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?))
        })?;
        rows.collect()
    }

    // --- User Known Artists ---

    /// Insert a user-known artist. Returns the row ID, or Ok(0) if duplicate.
    pub fn insert_user_known_artist(&self, name: &str) -> Result<i64, rusqlite::Error> {
        let normalized = name.trim().to_lowercase();
        match self.conn.execute(
            "INSERT OR IGNORE INTO user_known_artists (name, normalized_name) VALUES (?1, ?2)",
            params![name.trim(), normalized],
        ) {
            Ok(0) => Ok(0), // Duplicate ignored
            Ok(_) => Ok(self.conn.last_insert_rowid()),
            Err(e) => Err(e),
        }
    }

    /// Get all user-known artist names (for display).
    pub fn get_user_known_artists(&self) -> Result<Vec<(i64, String)>, rusqlite::Error> {
        let mut stmt = self
            .conn
            .prepare("SELECT id, name FROM user_known_artists ORDER BY name ASC")?;
        let rows = stmt.query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?;
        rows.collect()
    }

    /// Get all normalized names for parser lookup (loaded at startup).
    pub fn get_user_known_artist_names(&self) -> Result<Vec<String>, rusqlite::Error> {
        let mut stmt = self
            .conn
            .prepare("SELECT normalized_name FROM user_known_artists")?;
        let rows = stmt.query_map([], |row| row.get(0))?;
        rows.collect()
    }

    /// Delete a user-known artist by ID.
    pub fn delete_user_known_artist(&self, id: i64) -> Result<bool, rusqlite::Error> {
        let count = self
            .conn
            .execute("DELETE FROM user_known_artists WHERE id = ?1", params![id])?;
        Ok(count > 0)
    }

    // --- User YouTube Channels ---

    /// Insert a user YouTube channel. Returns the row ID, or Ok(0) if duplicate.
    pub fn insert_user_youtube_channel(&self, channel_name: &str) -> Result<i64, rusqlite::Error> {
        let normalized = channel_name.trim().to_lowercase();
        match self.conn.execute(
            "INSERT OR IGNORE INTO user_youtube_channels (channel_name, normalized_name) VALUES (?1, ?2)",
            params![channel_name.trim(), normalized],
        ) {
            Ok(0) => Ok(0),
            Ok(_) => Ok(self.conn.last_insert_rowid()),
            Err(e) => Err(e),
        }
    }

    /// Get all user YouTube channels (for display).
    pub fn get_user_youtube_channels(&self) -> Result<Vec<(i64, String)>, rusqlite::Error> {
        let mut stmt = self
            .conn
            .prepare("SELECT id, channel_name FROM user_youtube_channels ORDER BY channel_name ASC")?;
        let rows = stmt.query_map([], |row| Ok((row.get(0)?, row.get(1)?)))?;
        rows.collect()
    }

    /// Get all normalized channel names for lookup (loaded at startup).
    pub fn get_user_youtube_channel_names(&self) -> Result<Vec<String>, rusqlite::Error> {
        let mut stmt = self
            .conn
            .prepare("SELECT normalized_name FROM user_youtube_channels")?;
        let rows = stmt.query_map([], |row| row.get(0))?;
        rows.collect()
    }

    /// Delete a user YouTube channel by ID.
    pub fn delete_user_youtube_channel(&self, id: i64) -> Result<bool, rusqlite::Error> {
        let count = self.conn.execute(
            "DELETE FROM user_youtube_channels WHERE id = ?1",
            params![id],
        )?;
        Ok(count > 0)
    }

    // --- Export ---

    /// Get ALL plays (for export). No limit.
    pub fn get_all_plays(&self) -> Result<Vec<Play>, rusqlite::Error> {
        let mut stmt = self.conn.prepare(
            "SELECT id, title, artist, album, duration_ms, timestamp_utc, source_app, status, listened_ms, skipped,
                    replay_count, is_muted, completion_percentage, pause_count, seek_count, session_id, site, content_type, volume_level
             FROM scrobbles ORDER BY timestamp_utc DESC",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(Play {
                id: Some(row.get(0)?),
                title: row.get(1)?,
                artist: row.get(2)?,
                album: row.get(3)?,
                duration_ms: row.get(4)?,
                timestamp_utc: row.get(5)?,
                source_app: row.get(6)?,
                status: PlayStatus::from_str(&row.get::<_, String>(7)?),
                listened_ms: row.get(8)?,
                skipped: row.get::<_, i32>(9)? != 0,
                replay_count: row.get::<_, u32>(10).unwrap_or(0),
                is_muted: row.get::<_, i32>(11).unwrap_or(0) != 0,
                completion_percentage: row.get::<_, f64>(12).unwrap_or(0.0),
                pause_count: row.get::<_, u32>(13).unwrap_or(0),
                seek_count: row.get::<_, u32>(14).unwrap_or(0),
                session_id: row.get::<_, String>(15).unwrap_or_default(),
                site: row.get::<_, String>(16).unwrap_or_default(),
                content_type: row.get::<_, String>(17).unwrap_or_else(|_| "MUSIC".to_string()),
                volume_level: row.get::<_, f64>(18).unwrap_or(-1.0),
            })
        })?;
        rows.collect()
    }

    // --- Failed Sync Retry ---

    /// Reset failed plays back to queued so they can be retried.
    pub fn reset_failed_to_queued(&self) -> Result<usize, rusqlite::Error> {
        let count = self.conn.execute(
            "UPDATE scrobbles SET status = 'queued' WHERE status = 'failed'",
            [],
        )?;
        if count > 0 {
            log::info!("Reset {} failed plays back to queued for retry", count);
        }
        Ok(count)
    }

    /// Mark specific plays as failed.
    pub fn mark_plays_failed(&self, ids: &[i64]) -> Result<(), rusqlite::Error> {
        if ids.is_empty() {
            return Ok(());
        }
        let placeholders: Vec<String> = ids.iter().map(|_| "?".to_string()).collect();
        let sql = format!(
            "UPDATE scrobbles SET status = 'failed' WHERE id IN ({})",
            placeholders.join(",")
        );
        let params: Vec<Box<dyn rusqlite::types::ToSql>> = ids
            .iter()
            .map(|id| Box::new(*id) as Box<dyn rusqlite::types::ToSql>)
            .collect();
        let param_refs: Vec<&dyn rusqlite::types::ToSql> = params.iter().map(|p| p.as_ref()).collect();
        self.conn.execute(&sql, param_refs.as_slice())?;
        Ok(())
    }
}
