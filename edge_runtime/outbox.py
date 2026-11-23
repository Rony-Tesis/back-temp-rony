"""Durable operation outbox and command deduplication, local to the JetMax."""
import json
from pathlib import Path
import sqlite3
import threading


class Outbox:
    def __init__(self, path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.database = sqlite3.connect(str(path), check_same_thread=False)
        self.database.execute('PRAGMA journal_mode=WAL')
        self.database.execute('CREATE TABLE IF NOT EXISTS operation (id TEXT PRIMARY KEY, payload TEXT NOT NULL, sent INTEGER NOT NULL DEFAULT 0)')
        self.database.execute('CREATE TABLE IF NOT EXISTS command (id TEXT PRIMARY KEY, payload TEXT NOT NULL)')
        self.database.commit()

    def operation(self, value):
        with self.lock, self.database:
            self.database.execute('INSERT OR IGNORE INTO operation(id,payload) VALUES (?,?)',
                                  (value['id'], json.dumps(value, ensure_ascii=False, allow_nan=False)))

    def pending(self):
        with self.lock:
            return [json.loads(row[0]) for row in self.database.execute('SELECT payload FROM operation WHERE sent=0 ORDER BY rowid LIMIT 16')]

    def acknowledge(self, ids):
        with self.lock, self.database:
            self.database.executemany('UPDATE operation SET sent=1 WHERE id=?', [(identity,) for identity in ids])

    def claim(self, command):
        raw = json.dumps(command, sort_keys=True, allow_nan=False)
        with self.lock, self.database:
            found = self.database.execute('SELECT payload FROM command WHERE id=?', (command['id'],)).fetchone()
            if found:
                if found[0] != raw:
                    raise ValueError('El identificador del comando cambió de contenido')
                return False
            self.database.execute('INSERT INTO command(id,payload) VALUES (?,?)', (command['id'], raw))
            return True

    def completed_cycles(self):
        with self.lock:
            return sum(json.loads(row[0])['result'] == 'unverified'
                       for row in self.database.execute('SELECT payload FROM operation'))

    def close(self):
        with self.lock:
            self.database.close()
