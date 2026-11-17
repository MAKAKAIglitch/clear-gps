#!/usr/bin/env python3
"""Reproduce both Clear GPS fingerprint SQL query timings (Python stdlib only).

Run: python3 tools/benchmarks/radio_fingerprint_sql.py
These are SQLite SQL microbenchmarks, not Android/device positioning timings.
"""
import argparse
import random
import sqlite3
import statistics
import time
from dataclasses import dataclass

HISTORY_MS = 90 * 24 * 60 * 60 * 1000
MAX_SCANS = 250
EXCLUDED = ("unlocated", "local-radio-estimate", "radio-estimate")
NOW = 1_800_000_000_000


@dataclass(frozen=True)
class Key:
    kind: str
    identifier: str
    compact: str | None


def mac_for(value):
    return ":".join(f"{(value + n * 37) % 256:02x}" for n in range(6))


def make_keys():
    result = []
    for n in range(48):
        address = mac_for(n + 1)
        result.append(Key("wifi", address, address.replace(":", "")))
    for n in range(16):
        address = mac_for(n + 101)
        result.append(Key("ble", address.replace(":", "-"), address.replace(":", "")))
    for n in range(16):
        result.append(Key("cell", f"lte:26201:{1000+n}:{500000+n}", None))
    return result


def database(size, expression_index):
    db = sqlite3.connect(":memory:")
    db.executescript("""
      PRAGMA journal_mode=OFF;
      PRAGMA synchronous=OFF;
      PRAGMA temp_store=MEMORY;
      CREATE TABLE radio_sources(id INTEGER PRIMARY KEY, enabled INTEGER, trust_weight REAL);
      CREATE TABLE radio_source_segment_settings(
        source_id INTEGER, day_key TEXT, radio_type TEXT, enabled INTEGER,
        trust_weight REAL, PRIMARY KEY(source_id,day_key,radio_type)
      );
      CREATE TABLE radio_observations(
        id INTEGER PRIMARY KEY, radio_type TEXT, identifier TEXT, latitude REAL,
        longitude REAL, accuracy REAL, rssi INTEGER, frequency INTEGER,
        observed_at INTEGER, position_source TEXT, source_id INTEGER
      );
      CREATE INDEX radio_obs_time ON radio_observations(observed_at DESC);
      CREATE INDEX radio_obs_source_time ON radio_observations(source_id,observed_at DESC);
      CREATE INDEX radio_obs_radio_time ON radio_observations(radio_type,identifier,observed_at DESC);
      CREATE INDEX radio_obs_import_dedupe
        ON radio_observations(source_id,radio_type,identifier,observed_at,rssi,frequency);
      INSERT INTO radio_sources VALUES(1,1,1.0);
    """)
    rng = random.Random(20261117)
    keys = make_keys()
    start = NOW - HISTORY_MS
    slots = max(100, min(2000, size // 12))
    rows = []
    for _ in range(size):
        if rng.random() < 0.70:
            key = keys[rng.randrange(len(keys))]
            kind, identifier = key.kind, key.identifier
        else:
            kind = ("wifi", "ble", "cell")[rng.randrange(3)]
            identifier = (
                f"lte:26201:{rng.randrange(1,65535)}:{rng.randrange(100000,999999)}"
                if kind == "cell" else mac_for(rng.randrange(256,65000))
            )
        if kind in ("wifi", "ble") and rng.random() < 0.25:
            compact = identifier.replace(":", "").replace("-", "").replace(".", "")
            style = rng.randrange(3)
            identifier = (
                compact if style == 0 else
                "-".join(compact[i:i+2] for i in range(0,12,2)) if style == 1 else
                ".".join(compact[i:i+4] for i in range(0,12,4))
            )
        observed = start + rng.randrange(slots) * (HISTORY_MS // slots)
        rows.append((
            kind, identifier, 37.0+rng.random()*20.0, -120.0+rng.random()*20.0,
            5.0+rng.random()*30.0, rng.randrange(-100,-35),
            rng.choice((0,2412,2437,5180,5500)), observed, "manual-reference", 1
        ))
    db.executemany("""
      INSERT INTO radio_observations(
        radio_type,identifier,latitude,longitude,accuracy,rssi,frequency,
        observed_at,position_source,source_id
      ) VALUES(?,?,?,?,?,?,?,?,?,?)
    """, rows)
    if expression_index:
        db.execute("""
          CREATE INDEX radio_obs_radio_compact_time ON radio_observations(
            radio_type,
            replace(replace(replace(lower(identifier),':',''),'-',''),'.',''),
            observed_at DESC
          )
        """)
    return db


def legacy_query(keys):
    terms, args = [], [NOW-HISTORY_MS, NOW, *EXCLUDED]
    for key in keys:
        if key.compact is not None:
            terms.append("(o.radio_type=? AND replace(replace(replace(lower(o.identifier),':',''),'-',''),'.','')=?)")
            args.extend((key.kind, key.compact))
        else:
            terms.append("(o.radio_type=? AND o.identifier=?)")
            args.extend((key.kind, key.identifier))
    args.append(MAX_SCANS)
    sql = f"""
      SELECT o.observed_at
      FROM radio_observations o
      JOIN radio_sources s ON s.id=o.source_id
      LEFT JOIN radio_source_segment_settings sg ON sg.source_id=o.source_id
        AND sg.day_key=strftime('%Y-%m-%d',o.observed_at/1000,'unixepoch','localtime')
        AND sg.radio_type=o.radio_type
      WHERE s.enabled=1 AND s.trust_weight>0
        AND (sg.enabled IS NULL OR sg.enabled=1)
        AND (sg.trust_weight IS NULL OR sg.trust_weight>0)
        AND o.observed_at>=? AND o.observed_at<=?
        AND o.latitude IS NOT NULL AND o.longitude IS NOT NULL
        AND o.position_source NOT IN (?,?,?)
        AND ({' OR '.join(terms)})
      GROUP BY o.observed_at
      ORDER BY COUNT(DISTINCT o.radio_type||':'||o.identifier) DESC,o.observed_at DESC
      LIMIT ?
    """
    return sql, args


def cte_query(keys):
    mac = list(dict.fromkeys(
        (k.kind, k.compact) for k in keys
        if k.kind in ("wifi", "ble") and k.compact is not None
    ))
    exact = [k for k in keys if k.kind not in ("wifi", "ble") or k.compact is None]
    mac_values = ", ".join("(?,?)" for _ in mac) if mac else "('', '')"
    exact_values = ", ".join("(?,?)" for _ in exact) if exact else "('', '')"
    args = [v for pair in mac for v in pair]
    args += [v for k in exact for v in (k.kind, k.identifier)]
    args += [NOW-HISTORY_MS, NOW, *EXCLUDED, NOW-HISTORY_MS, NOW, *EXCLUDED, MAX_SCANS]
    sql = f"""
      WITH requested_mac(radio_type,compact_identifier) AS (VALUES {mac_values}),
           requested_exact(radio_type,identifier) AS (VALUES {exact_values}),
           candidates AS (
        SELECT o.observed_at,o.radio_type,o.identifier
        FROM requested_mac r
        JOIN radio_observations o ON o.radio_type=r.radio_type
          AND replace(replace(replace(lower(o.identifier),':',''),'-',''),'.','')=r.compact_identifier
        JOIN radio_sources s ON s.id=o.source_id
        LEFT JOIN radio_source_segment_settings sg ON sg.source_id=o.source_id
          AND sg.day_key=strftime('%Y-%m-%d',o.observed_at/1000,'unixepoch','localtime')
          AND sg.radio_type=o.radio_type
        WHERE s.enabled=1 AND s.trust_weight>0
          AND (sg.enabled IS NULL OR sg.enabled=1)
          AND (sg.trust_weight IS NULL OR sg.trust_weight>0)
          AND o.observed_at>=? AND o.observed_at<=?
          AND o.latitude IS NOT NULL AND o.longitude IS NOT NULL
          AND o.position_source NOT IN (?,?,?)
        UNION ALL
        SELECT o.observed_at,o.radio_type,o.identifier
        FROM requested_exact r
        JOIN radio_observations o ON o.radio_type=r.radio_type AND o.identifier=r.identifier
        JOIN radio_sources s ON s.id=o.source_id
        LEFT JOIN radio_source_segment_settings sg ON sg.source_id=o.source_id
          AND sg.day_key=strftime('%Y-%m-%d',o.observed_at/1000,'unixepoch','localtime')
          AND sg.radio_type=o.radio_type
        WHERE s.enabled=1 AND s.trust_weight>0
          AND (sg.enabled IS NULL OR sg.enabled=1)
          AND (sg.trust_weight IS NULL OR sg.trust_weight>0)
          AND o.observed_at>=? AND o.observed_at<=?
          AND o.latitude IS NOT NULL AND o.longitude IS NOT NULL
          AND o.position_source NOT IN (?,?,?)
      )
      SELECT observed_at FROM candidates
      GROUP BY observed_at
      ORDER BY COUNT(DISTINCT radio_type||':'||identifier) DESC,observed_at DESC
      LIMIT ?
    """
    return sql, args


def expansion_query(timestamps):
    placeholders = ", ".join("?" for _ in timestamps)
    args = [str(row[0]) for row in timestamps] + list(EXCLUDED)
    sql = f"""
      SELECT o.radio_type, o.identifier, o.latitude, o.longitude,
             o.accuracy / MAX(s.trust_weight * COALESCE(sg.trust_weight, 1.0), 0.01) AS effective_accuracy,
             o.rssi, o.observed_at, o.position_source
      FROM radio_observations o JOIN radio_sources s ON s.id=o.source_id
      LEFT JOIN radio_source_segment_settings sg ON sg.source_id=o.source_id
        AND sg.day_key=strftime('%Y-%m-%d',o.observed_at/1000,'unixepoch','localtime')
        AND sg.radio_type=o.radio_type
      WHERE s.enabled=1 AND s.trust_weight>0
        AND (sg.enabled IS NULL OR sg.enabled=1)
        AND (sg.trust_weight IS NULL OR sg.trust_weight>0)
        AND o.observed_at IN ({placeholders})
        AND o.latitude IS NOT NULL AND o.longitude IS NOT NULL
        AND o.position_source NOT IN (?,?,?)
      ORDER BY observed_at DESC
      LIMIT 20000
    """
    return sql, args


def median_ms(db, sql, args, repeats):
    for _ in range(2):
        db.execute(sql, args).fetchall()
    times, rows = [], []
    for _ in range(repeats):
        started = time.perf_counter_ns()
        rows = db.execute(sql, args).fetchall()
        times.append((time.perf_counter_ns()-started)/1_000_000.0)
    return statistics.median(times), rows


def run_size(size, repeats):
    keys = make_keys()
    old_sql, old_args = legacy_query(keys)
    new_sql, new_args = cte_query(keys)
    baseline, indexed = database(size, False), database(size, True)
    try:
        old_ms, old_rows = median_ms(baseline, old_sql, old_args, repeats)
        old_index_ms, old_index_rows = median_ms(indexed, old_sql, old_args, repeats)
        new_ms, new_rows = median_ms(indexed, new_sql, new_args, repeats)
        if old_rows != old_index_rows or old_rows != new_rows:
            raise RuntimeError(f"Candidate timestamps differ at {size} observations")

        expand_sql, expand_args = expansion_query(new_rows)
        old_expand_ms, old_samples = median_ms(baseline, expand_sql, expand_args, repeats)
        new_expand_ms, new_samples = median_ms(indexed, expand_sql, expand_args, repeats)
        if old_samples != new_samples:
            raise RuntimeError(f"Expanded fingerprint rows differ at {size} observations")

        plan = " | ".join(row[3] for row in indexed.execute("EXPLAIN QUERY PLAN "+new_sql, new_args))
        if "radio_obs_radio_compact_time" not in plan:
            raise RuntimeError("SQLite did not select the compact MAC expression index")
        old_total = old_ms + old_expand_ms
        new_total = new_ms + new_expand_ms
        print(
            f"rows={size:>7} timestamps={len(new_rows):>3} samples={len(new_samples):>5} "
            f"legacy_candidate={old_ms:>8.2f}ms "
            f"legacy_total_sql={old_total:>8.2f}ms "
            f"cte_candidate={new_ms:>7.2f}ms "
            f"expansion={new_expand_ms:>6.2f}ms "
            f"cte_total_sql={new_total:>7.2f}ms "
            f"speedup={old_total/new_total:>5.2f}x"
        )
        print("  CTE plan: "+plan[:1200])
    finally:
        baseline.close()
        indexed.close()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sizes", nargs="+", type=int, default=[1000,10000,50000,500000])
    parser.add_argument("--repeats", type=int, default=5)
    args = parser.parse_args()
    if any(size < MAX_FINGERPRINT_KEYS for size in args.sizes):
        parser.error(f"each size must be at least {MAX_FINGERPRINT_KEYS}")
    if args.repeats < 3:
        parser.error("--repeats must be at least 3")
    print(f"SQLite {sqlite3.sqlite_version}; repeats={args.repeats}; active_keys={MAX_FINGERPRINT_KEYS}")
    for size in args.sizes:
        run_size(size, args.repeats)


if __name__ == "__main__":
    main()
