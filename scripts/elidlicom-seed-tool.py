#!/usr/bin/env python3
"""Hydrate, export, and import @elidlicom local/seed history bundles (stdlib only)."""

from __future__ import annotations

import argparse
from contextlib import closing
from functools import wraps
from datetime import datetime
import fcntl
import gzip
import io
import json
import os
import re
import sqlite3
import sys
import tarfile
import tempfile
import time
from pathlib import Path
from typing import Any

OWNER_ID = "2095392916734976000"
OWNER_USERNAME = "elidlicom"
TWEET_KIND_MAP = {
    "P": "POST",
    "R": "REPLY",
    "Q": "QUOTE",
    "T": "RETWEET",
}
INBOUND_KIND_MAP = {
    "R": "REPLY_TO_ME",
    "Q": "QUOTE_ME",
    "M": "MENTION_ME",
}
METRIC_FIELDS = ("views", "likes", "replies", "reposts", "bookmarks")
GRAPH_FIELDS = ("fg", "fr", "fb", "gt", "gx", "gy", "gf", "re", "ra", "fo", "fro")
ACCOUNT_FILE = f"users/by-id/{OWNER_ID}/store_v1.json.gz"
PROFILE_FILE = "author_profile_elidlicom.json"
BADGES_FILE = "blue_verified_cache_v1.json"
SEED_FILE = "elidlicom_assistant_seed.jsonl.gz"
UNVERIFIED_MATERIAL = "history_material_unverified"
IMPORT_FILES = (ACCOUNT_FILE, PROFILE_FILE, BADGES_FILE)
STATUS_URL_RE = re.compile(r"https?://(?:www\.)?(?:x|twitter)\.com/(?:[\w]{1,15}|i/web)/status/(\d+)\b",
                           re.IGNORECASE | re.ASCII)


def atomic_write_bytes(dest: Path, data: bytes) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=".tmp_", dir=str(dest.parent))
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp_name, dest)
    except Exception:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


def parse_archive_markdown(md_path: Path, owner_post_ids: set[str]) -> dict[str, str]:
    """Use the stable post IDs of the verified owner snapshot, including across handle changes."""
    if not md_path.is_file():
        return {}
    content = md_path.read_text(encoding="utf-8", errors="replace")
    # Split into logical blocks by markdown headings or horizontal rules
    raw_blocks = re.split(r"(?m)^(?:---|#{1,6}\s+.*)$", content)
    enriched: dict[str, str] = {}
    for block in raw_blocks:
        matches = list(STATUS_URL_RE.finditer(block))
        for index, match in enumerate(matches):
            tid = match.group(1)
            if tid not in owner_post_ids:
                continue
            start = match.start() if len(matches) > 1 else 0
            end = matches[index + 1].start() if index + 1 < len(matches) else len(block)
            body = _clean_archive_block(block[start:end], match.group(0))
            if body and len(body) > len(enriched.get(tid, "")):
                enriched[tid] = body
    return enriched


def _clean_archive_block(block: str, url: str) -> str:
    lines: list[str] = []
    for line in block.splitlines():
        stripped = line.strip()
        if not stripped:
            if lines and lines[-1] != "":
                lines.append("")
            continue
        if url in stripped and (
            stripped == url
            or stripped.startswith("链接")
            or stripped.startswith("原文")
            or stripped.startswith("URL")
            or stripped.startswith("- [")
            or stripped.startswith("[")
        ):
            continue
        if re.match(r"^[-*]\s*(?:时间|发布时间|日期|状态|互动|浏览|点赞)\s*[:：]", stripped):
            continue
        lines.append(line.rstrip())
    text = "\n".join(lines).strip()
    return text


def ensure_assistant_schema(conn: sqlite3.Connection) -> None:
    ver = conn.execute("PRAGMA user_version").fetchone()[0]
    if ver > 1:
        raise RuntimeError(f"Unsupported assistant.sqlite user_version={ver}")
    conn.execute("PRAGMA journal_mode=WAL")
    if ver == 0:
        conn.execute(
            "CREATE TABLE IF NOT EXISTS revisions("
            "kind TEXT NOT NULL,id TEXT NOT NULL,ver INTEGER NOT NULL,data TEXT NOT NULL,"
            "PRIMARY KEY(kind,id,ver))"
        )
        conn.execute(
            "CREATE TABLE IF NOT EXISTS heads("
            "kind TEXT NOT NULL,id TEXT NOT NULL,ver INTEGER NOT NULL,changed INTEGER NOT NULL,"
            "PRIMARY KEY(kind,id))"
        )
        conn.execute("CREATE INDEX IF NOT EXISTS by_kind_time ON heads(kind,changed DESC,id)")
        conn.execute("PRAGMA user_version=1")


def _strip_meta(obj: dict[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in obj.items() if k not in ("id", "version", "updatedAt")}


def snapshot_owner(snapshot: dict) -> dict:
    profile = snapshot.get("me") if isinstance(snapshot, dict) else None
    if not isinstance(profile, dict) or str(profile.get("id") or "") != OWNER_ID:
        raise ValueError("Account snapshot owner does not match seed owner")
    if int(snapshot.get("ver") or 1) > 1:
        raise ValueError("Unsupported account snapshot version")
    return profile


def assistant_owner(conn: sqlite3.Connection) -> dict | None:
    row = conn.execute("SELECT data FROM heads JOIN revisions USING(kind,id,ver) "
                       "WHERE kind='meta' AND id='owner'").fetchone()
    if row is None:
        return None
    owner = json.loads(row[0])
    if str(owner.get("accountId") or "") != OWNER_ID:
        raise ValueError("Assistant owner does not match seed owner")
    return owner


def snapshot_binding(snapshot: dict) -> dict:
    profile = snapshot_owner(snapshot)
    handle = str(profile.get("username") or "").strip()
    return {"accountId": OWNER_ID, "username": handle or OWNER_USERNAME,
            "usernameObservedAt": int(snapshot.get("at") or 0) * 1000 if handle else 0}


def observe_owner(conn: sqlite3.Connection, incoming: dict, now_ms: int) -> tuple[dict, bool]:
    current = assistant_owner(conn)
    when = int(incoming.get("usernameObservedAt") or 0)
    if current is None:
        owner = dict(incoming)
    elif incoming.get("username") and (not current.get("username") or when > int(current.get("usernameObservedAt") or 0)):
        owner = {**current, "username": incoming["username"], "usernameObservedAt": when}
    else:
        return current, False
    return owner, upsert_record(conn, "meta", "owner", owner, now_ms)


def evidence_time(row: dict) -> int:
    return int(row.get("seedEvidenceAt", row.get("updatedAt")) or 0)


def merge_history_evidence(current: dict, incoming: dict) -> dict:
    earlier, later = sorted((incoming, current), key=evidence_time)
    merged = {**earlier, **{key: value for key, value in later.items() if value not in (None, "")}}
    merged["seedEvidenceAt"] = evidence_time(later)
    metric_times = {}
    for field in METRIC_FIELDS:
        measured = [row for row in (later, earlier) if row.get(field) is not None]
        if measured:
            def metric_time(row):
                return (row.get("seedMetricTimes") or {}).get(field, evidence_time(row))
            freshest = max(measured, key=metric_time)
            merged[field] = freshest[field]
            if metric_time(freshest) != merged["seedEvidenceAt"]:
                metric_times[field] = metric_time(freshest)
    merged.pop("seedMetricTimes", None)
    if metric_times:
        merged["seedMetricTimes"] = metric_times
    def changed(key: str) -> bool:
        return bool(earlier.get(key) and later.get(key) and earlier[key] != later[key])

    def article_uses_carrier_id(row: dict) -> bool:
        carrier = "quotedId" if row.get("articleRole") == "QUOTED" else "id"
        return bool(row.get("articleId") and row["articleId"] == row.get(carrier))

    new_quote = changed("quotedId") or changed("quotedAuthorId")
    new_article = (changed("articleRole") or (new_quote and earlier.get("articleRole") == "QUOTED")
                   or (changed("articleId") and not any(article_uses_carrier_id(row) for row in (earlier, later))))
    groups = (("text", "authorTextStatus", (), False), ("quotedText", "quotedTextStatus",
              ("quotedId", "quotedAuthorId", "quotedUsername"), new_quote),
              ("articleText", "articleTextStatus", ("articleId", "articleUrl", "articleRole"), new_article))
    for text_key, status_key, metadata, different_object in groups:
        def quality(row: dict) -> tuple:
            body = row.get(text_key) or ""
            return bool(body), row.get(status_key) == "FULL", len(body)
        best = max((later, earlier), key=quality)
        # Distinct linked objects must keep their own text, author, and status together.
        if different_object:
            best = later
            for key in (text_key, status_key, *metadata):
                merged.pop(key, None)
        for key in (text_key, status_key, *metadata):
            if best.get(key):
                merged[key] = best[key]
    if merged.get("articleTextStatus") == "FULL":
        merged["textStatus"] = "ARTICLE_FULL"
    elif merged.get("articleTextStatus") == "PREVIEW":
        merged["textStatus"] = "ARTICLE_PREVIEW"
    elif (new_article or new_quote) and merged.get("textStatus") in ("ARTICLE_FULL", "ARTICLE_PREVIEW"):
        if merged.get("kind") == "QUOTE" and not merged.get("quotedText"):
            merged["textStatus"] = "QUOTE_TEXT_MISSING"
        else:
            merged["textStatus"] = "TEXT_ONLY" if merged.get("text") or merged.get("quotedText") else (
                "MEDIA_ONLY" if merged.get("hasMedia") else "NO_ANALYZABLE_TEXT")
    elif merged.get("quotedTextStatus") == "FULL" and merged.get("textStatus") in ("QUOTE_TEXT_MISSING", "QUOTE_SOURCE_MISSING"):
        merged["textStatus"] = "TEXT_ONLY"
    return merged


def upsert_record(
    conn: sqlite3.Connection,
    kind: str,
    rec_id: str,
    payload: dict[str, Any],
    now_ms: int,
    *,
    force_write: bool = False,
    preserve_evidence: bool = False,
) -> bool:
    if preserve_evidence:
        payload = {**payload, "seedEvidenceAt": evidence_time(payload)}
    row = conn.execute(
        "SELECT h.ver, r.data FROM heads h JOIN revisions r USING(kind,id,ver) WHERE h.kind=? AND h.id=?",
        (kind, rec_id),
    ).fetchone()
    if row is not None:
        old_ver, old_json = int(row[0]), row[1]
        old_obj = json.loads(old_json)
        if preserve_evidence and kind == "history_material" and old_obj.get("authorId") != payload.get("authorId"):
            if old_obj.get("authorId"):
                raise ValueError("Conflicting author IDs for a history material")
            upsert_record(conn, UNVERIFIED_MATERIAL, rec_id, old_obj, now_ms, preserve_evidence=True)
        elif preserve_evidence:
            payload = merge_history_evidence(old_obj, payload)
        if not force_write:
            if _strip_meta(old_obj) == _strip_meta(payload):
                return False
        new_ver = old_ver + 1
    else:
        new_ver = 1

    final_doc = dict(payload)
    final_doc["id"] = rec_id
    final_doc["updatedAt"] = now_ms
    final_doc["version"] = new_ver
    encoded = json.dumps(final_doc, ensure_ascii=False, separators=(",", ":"))
    conn.execute(
        "INSERT INTO heads(kind,id,ver,changed) VALUES(?,?,?,?) "
        "ON CONFLICT(kind,id) DO UPDATE SET ver=excluded.ver, changed=excluded.changed",
        (kind, rec_id, new_ver, now_ms),
    )
    conn.execute(
        "INSERT INTO revisions(kind,id,ver,data) VALUES(?,?,?,?)",
        (kind, rec_id, new_ver, encoded),
    )
    return True


def build_history_material(
    tweet: dict[str, Any],
    me: dict[str, Any],
    archive_texts: dict[str, str],
) -> dict[str, Any]:
    tid = str(tweet.get("i") or "").strip()
    created_sec = int(tweet.get("c") or 0)
    published_ms = created_sec * 1000
    username = str(me.get("username") or OWNER_USERNAME)
    display_name = str(me.get("displayName") or username)
    author_id = str(me.get("id") or OWNER_ID)

    raw_text = str(tweet.get("x") or "")
    enriched = archive_texts.get(tid, "")
    text = enriched if enriched and (len(enriched) > len(raw_text) or raw_text.endswith(("...", "…"))) else raw_text

    kind_code = str(tweet.get("k") or "P")
    kind_name = TWEET_KIND_MAP.get(kind_code, "POST")
    flags = int(tweet.get("f") or 0)
    has_media = bool(flags & 1)
    self_thread = bool(flags & 4)
    has_substantive = bool(text.strip())
    text_status = "TEXT_ONLY" if has_substantive else ("MEDIA_ONLY" if has_media else "NO_ANALYZABLE_TEXT")
    clipped = not enriched and (raw_text.endswith(("...", "…")) or kind_code in ("R", "T") and len(raw_text) >= 120)
    author_text_status = ("PREVIEW" if clipped else "FULL") if has_substantive else "MISSING"

    doc: dict[str, Any] = {
        "id": tid,
        "authorId": author_id,
        "username": username,
        "displayName": display_name,
        "authorName": display_name,
        "authorAvatarUrl": str(me.get("avatarUrl") or ""),
        "authorVerified": bool(me.get("verified", False)),
        "authorFollowersCount": int(me.get("followersCount") or 0),
        "text": text,
        "quotedText": "",
        "articleText": "",
        "articleRole": "",
        "publishedAt": published_ms,
        "sourceUrl": f"https://x.com/{username}/status/{tid}",
        "textStatus": text_status,
        "authorTextStatus": author_text_status,
        "quotedTextStatus": "NOT_PRESENT",
        "articleTextStatus": "NOT_PRESENT",
        "quotedId": "",
        "quotedAuthorId": "",
        "quotedUsername": "",
        "articleId": "",
        "articleUrl": "",
        "sourceIdentity": "ROW_ID_VERIFIED",
        "kind": kind_name,
        "hasMedia": has_media,
        "selfThreadReply": self_thread,
        "pinned": False,
        "archiveOnly": True,
        "longHistory": True,
    }
    stats = tweet.get("s")
    unavailable = tweet.get("um")
    if isinstance(unavailable, list):
        for index, name in enumerate(METRIC_FIELDS):
            if name not in unavailable:
                doc[name] = int(stats[index] or 0) if isinstance(stats, list) and len(stats) == 5 else 0
    return doc


def build_history_interaction(
    item: dict[str, Any],
    me: dict[str, Any],
) -> tuple[str, dict[str, Any]]:
    tid = str(item.get("i") or "").strip()
    created_sec = int(item.get("c") or 0)
    published_ms = created_sec * 1000
    target_author_id = str(me.get("id") or OWNER_ID)
    target_username = str(me.get("username") or OWNER_USERNAME)
    peer_username = str(item.get("p") or "").strip().lstrip("@")
    peer_id = str(item.get("pi") or "")
    kind_code = str(item.get("k") or "R")
    kind_name = INBOUND_KIND_MAP.get(kind_code, "REPLY_TO_ME")
    rec_id = f"{target_author_id}:{tid}"
    doc: dict[str, Any] = {
        "id": rec_id,
        "targetAuthorId": target_author_id,
        "targetUsername": target_username,
        "peerId": peer_id,
        "peerUsername": peer_username,
        "publishedAt": published_ms,
        "kind": kind_name,
        "text": str(item.get("x") or ""),
        "sourceUrl": f"https://x.com/{peer_username}/status/{tid}" if peer_username else f"https://x.com/i/web/status/{tid}",
    }
    return rec_id, doc


OWNER_MATERIAL_FILTER = "json_extract(r.data,'$.authorId')=?"
OWNER_INTERACTION_FILTER = (
    "h.id LIKE ? AND coalesce(nullif(json_extract(r.data,'$.targetAuthorId'),''),?)=?"
)


def update_history_counts(conn: sqlite3.Connection, source: dict) -> None:
    """Count the union of locally present owner records, independently of the collection cursor."""
    tl_stats = conn.execute(
        "SELECT COUNT(*), MIN(CAST(json_extract(r.data,'$.publishedAt') AS INTEGER)), "
        "MAX(CAST(json_extract(r.data,'$.publishedAt') AS INTEGER)) "
        "FROM heads h JOIN revisions r USING(kind,id,ver) "
        "WHERE h.kind='history_material' AND " + OWNER_MATERIAL_FILTER,
        (OWNER_ID,),
    ).fetchone()
    in_stats = conn.execute(
        "SELECT COUNT(*), MIN(CAST(json_extract(r.data,'$.publishedAt') AS INTEGER)), "
        "MAX(CAST(json_extract(r.data,'$.publishedAt') AS INTEGER)) "
        "FROM heads h JOIN revisions r USING(kind,id,ver) "
        "WHERE h.kind='history_interaction' AND " + OWNER_INTERACTION_FILTER,
        (f"{OWNER_ID}:%", OWNER_ID, OWNER_ID),
    ).fetchone()

    for prefix, stats in (("timeline", tl_stats), ("inbound", in_stats)):
        for suffix, value in zip(("SavedCount", "OldestAt", "NewestAt"), stats):
            source[prefix + suffix] = int(value or 0)


def locked_cache_command(command):
    @wraps(command)
    def run(args):
        cache = Path(args.cache_dir).expanduser().resolve()
        if command.__name__ == "cmd_import_bundle":
            cache.mkdir(parents=True, exist_ok=True)
        if not cache.is_dir():
            return command(args)
        # FileChannel.lock in Java uses POSIX record locks; flock on a directory does not interoperate.
        with (cache / "owner-archive.lock").open("a+b") as lock:
            fcntl.lockf(lock, fcntl.LOCK_EX)
            db = cache / "content-assistant" / "assistant.sqlite"
            if db.is_file():
                with closing(sqlite3.connect(db, timeout=30)) as conn:
                    recover_import(cache, conn)
            return command(args)
    return run


@locked_cache_command
def cmd_hydrate_local(args: argparse.Namespace) -> int:
    cache_dir = Path(args.cache_dir).expanduser().resolve()
    store_path = cache_dir / "users" / "by-id" / OWNER_ID / "store_v1.json.gz"
    if not store_path.is_file():
        print(f"ERROR: store file not found: {store_path}", file=sys.stderr)
        return 1

    with gzip.open(store_path, "rt", encoding="utf-8") as fh:
        store_data = json.load(fh)
    me = snapshot_owner(store_data)
    tweets = [t for t in (store_data.get("t") or []) if isinstance(t, dict) and str(t.get("i") or "").strip()]
    inbound = [x for x in (store_data.get("i") or []) if isinstance(x, dict) and str(x.get("i") or "").strip()]

    archive_texts: dict[str, str] = {}
    if args.archive_md:
        archive_path = Path(args.archive_md).expanduser().resolve()
        if archive_path.is_file():
            archive_texts = parse_archive_markdown(archive_path, {str(t["i"]) for t in tweets})

    now_ms = int(time.time() * 1000)

    db_path = cache_dir / "content-assistant" / "assistant.sqlite"
    db_path.parent.mkdir(parents=True, exist_ok=True)

    mat_upserted = 0
    in_upserted = 0
    enriched_count = 0

    conn = sqlite3.connect(str(db_path), timeout=30.0)
    try:
        ensure_assistant_schema(conn)
        with conn:
            conn.execute("BEGIN IMMEDIATE")
            owner, _ = observe_owner(conn, snapshot_binding(store_data), now_ms)

            for tw in tweets:
                tid = str(tw.get("i") or "").strip()
                if tid in archive_texts and len(archive_texts[tid]) > len(str(tw.get("x") or "")):
                    enriched_count += 1
                doc = build_history_material(tw, me, archive_texts)
                doc["seedEvidenceAt"] = int(store_data.get("at") or 0) * 1000
                metric_times = {name: snapshot_metric(tw, index, int(store_data.get("at") or 0))[1] * 1000
                                for index, name in enumerate(METRIC_FIELDS) if name in doc}
                if any(when != doc["seedEvidenceAt"] for when in metric_times.values()):
                    doc["seedMetricTimes"] = {name: when for name, when in metric_times.items()
                                              if when != doc["seedEvidenceAt"]}
                if upsert_record(conn, "history_material", tid, doc, now_ms, preserve_evidence=True):
                    mat_upserted += 1

            for item in inbound:
                rec_id, doc = build_history_interaction(item, me)
                doc["seedEvidenceAt"] = int(store_data.get("at") or 0) * 1000
                if upsert_record(conn, "history_interaction", rec_id, doc, now_ms, preserve_evidence=True):
                    in_upserted += 1

            # Compute accurate counts & bounds across all @elidlicom rows currently in sqlite
            existing_hs_row = conn.execute(
                "SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver) "
                "WHERE h.kind='history_source' AND h.id=?",
                (OWNER_ID,),
            ).fetchone()
            hs = json.loads(existing_hs_row[0]) if existing_hs_row else {}

            hs.update(authorId=OWNER_ID, username=owner.get("username") or OWNER_USERNAME, rank=int(hs.get("rank") or 0),
                      owner=True, eligible=True)
            update_history_counts(conn, hs)
            upsert_record(conn, "history_source", OWNER_ID, hs, now_ms)
    finally:
        conn.close()

    print(
        json.dumps(
            {
                "action": "hydrate-local",
                "storeTweets": len(tweets),
                "storeInbound": len(inbound),
                "archiveEnriched": enriched_count,
                "materialsUpserted": mat_upserted,
                "interactionsUpserted": in_upserted,
                "timelineSavedCount": hs["timelineSavedCount"],
                "inboundSavedCount": hs["inboundSavedCount"],
            },
            ensure_ascii=False,
        )
    )
    return 0


@locked_cache_command
def cmd_export_bundle(args: argparse.Namespace) -> int:
    cache_dir = Path(args.cache_dir).expanduser().resolve()
    out_path = Path(args.out).expanduser().resolve()
    store_rel = Path("users") / "by-id" / OWNER_ID / "store_v1.json.gz"
    store_path = cache_dir / store_rel
    if not store_path.is_file():
        print(f"ERROR: store file not found: {store_path}", file=sys.stderr)
        return 1

    store_bytes = store_path.read_bytes()
    snapshot_owner(json.loads(gzip.decompress(store_bytes)))
    seed_buf = io.BytesIO()
    exported_records = 0
    db_path = cache_dir / "content-assistant" / "assistant.sqlite"
    with gzip.GzipFile(fileobj=seed_buf, mode="wb") as gz:
        if db_path.is_file():
            conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=30.0)
            try:
                conn.execute("BEGIN")
                assistant_owner(conn)
                queries = [
                    (
                        "SELECT h.kind, h.id, r.data FROM heads h JOIN revisions r USING(kind,id,ver) "
                        "WHERE h.kind='meta' AND h.id='owner'",
                        (),
                    ),
                    (
                        "SELECT h.kind, h.id, r.data FROM heads h JOIN revisions r USING(kind,id,ver) "
                        "WHERE h.kind='history_source' AND h.id=?",
                        (OWNER_ID,),
                    ),
                    (
                        "SELECT h.kind, h.id, r.data FROM heads h JOIN revisions r USING(kind,id,ver) "
                        "WHERE h.kind='history_material' AND " + OWNER_MATERIAL_FILTER,
                        (OWNER_ID,),
                    ),
                    (
                        "SELECT h.kind, h.id, r.data FROM heads h JOIN revisions r USING(kind,id,ver) "
                        "WHERE h.kind='history_interaction' AND " + OWNER_INTERACTION_FILTER,
                        (f"{OWNER_ID}:%", OWNER_ID, OWNER_ID),
                    ),
                ]
                for sql, params in queries:
                    for kind, rec_id, data_json in conn.execute(sql, params):
                        payload = json.loads(data_json)
                        if kind == "history_source":
                            update_history_counts(conn, payload)
                        line = json.dumps(
                            {"kind": kind, "id": rec_id, "data": payload},
                            ensure_ascii=False,
                            separators=(",", ":"),
                        )
                        gz.write((line + "\n").encode("utf-8"))
                        exported_records += 1
            finally:
                conn.close()

    seed_bytes = seed_buf.getvalue()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(prefix=".tmp_bundle_", suffix=".tar.gz", dir=str(out_path.parent))
    os.close(fd)
    try:
        with tarfile.open(tmp_name, "w:gz") as tf:
            store_info = tarfile.TarInfo(store_rel.as_posix())
            store_info.size = len(store_bytes)
            tf.addfile(store_info, io.BytesIO(store_bytes))
            for opt_name in ("author_profile_elidlicom.json", "blue_verified_cache_v1.json"):
                opt_path = cache_dir / opt_name
                if opt_path.is_file():
                    tf.add(opt_path, arcname=opt_name)
            info = tarfile.TarInfo(name="elidlicom_assistant_seed.jsonl.gz")
            info.size = len(seed_bytes)
            info.mtime = int(time.time())
            tf.addfile(info, io.BytesIO(seed_bytes))
        os.replace(tmp_name, out_path)
    except Exception:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise

    print(
        json.dumps(
            {
                "action": "export-bundle",
                "out": str(out_path),
                "bytes": out_path.stat().st_size,
                "assistantRecords": exported_records,
            },
            ensure_ascii=False,
        )
    )
    return 0


def merge_source_progress(current: dict[str, Any], seed: dict[str, Any]) -> dict[str, Any]:
    """Keep each collector's cursor, terminal state and watermark from the same checkpoint."""
    merged = {**seed, **current}
    for stream in ("timeline", "inbound"):
        check = "last" + stream.capitalize() + "CheckAt"
        def checkpoint(source: dict[str, Any]) -> tuple[int, bool]:
            observed = max(int(source.get(key) or 0) for key in (check, stream + "WatermarkAt", stream + "LastAttemptAt"))
            has_state = any(source.get(stream + key) for key in
                            ("Cursor", "IncrementalCursor", "BackfillComplete", "Exhausted", "Error", "RetryCount"))
            return observed, has_state
        chosen = seed if checkpoint(seed) > checkpoint(current) else current
        for key in set(seed) | set(current):
            if key == check or key.startswith(stream):
                if key in chosen:
                    merged[key] = chosen[key]
                else:
                    merged.pop(key, None)
    merged["lastSuccessAt"] = max(int(current.get("lastSuccessAt") or 0), int(seed.get("lastSuccessAt") or 0))
    return merged


def combined_coverage(ranges: list) -> list:
    intervals = []
    for start, end in sorted(ranges):
        if end < start:
            raise ValueError("Invalid coverage interval")
        if intervals and start <= intervals[-1][1]:
            intervals[-1][1] = max(intervals[-1][1], end)
        else:
            intervals.append([start, end])
    return intervals


def same_snapshot_post(older: dict, newer: dict, peer: str, identity: str) -> bool:
    def known(row, key):
        value = str(row.get(key) or "").strip()
        return "" if value == "-" else value

    for key in ("i", "c", "k", "r", identity):
        left, right = known(older, key), known(newer, key)
        if left and right and left != right:
            return False
    if known(older, identity) and known(newer, identity):
        return True
    left, right = known(older, peer).lstrip("@").lower(), known(newer, peer).lstrip("@").lower()
    return not left or not right or left == right


def snapshot_metric(row: dict, index: int, snapshot_at: int) -> tuple[int, int, int]:
    """Order independently by measured/raw/missing, evidence time, then value for a clock tie."""
    raw = row.get("s") or []
    value = int(raw[index] or 0) if index < len(raw) else 0
    measured = isinstance(row.get("um"), list) and METRIC_FIELDS[index] not in row["um"]
    if not measured and not value:
        return 0, 0, 0
    times = row.get("ms")
    when = times[index] if isinstance(times, list) and index < len(times) else (
        row.get("mt") or row.get("ma") or snapshot_at)
    return 2 if measured else 1, int(when or 0), value


def stamp_snapshot_metrics(row: dict, snapshot_at: int) -> dict:
    if isinstance(row.get("ms"), list) and len(row["ms"]) == len(METRIC_FIELDS):
        return row
    return {**row, "ms": [snapshot_metric(row, i, snapshot_at)[1] for i in range(len(METRIC_FIELDS))]}


def merge_snapshot_row(older: dict, newer: dict, stream: str) -> dict:
    if older == newer:
        return newer
    result = dict(newer)
    peer, identity = ("t", "ti") if stream == "t" else ("p", "pi")
    same_link = same_snapshot_post(older, newer, peer, identity)
    old_body, new_body = older.get("x") or "", newer.get("x") or ""
    quote_preview = (stream == "t" and {older.get("k"), newer.get("k")} == {"Q", "P"}
                     and old_body.startswith(new_body.rstrip().removesuffix("…").removesuffix("...").rstrip())
                     and same_snapshot_post({**older, "k": newer["k"]}, newer, peer, identity))
    if (same_link or quote_preview) and len(old_body.rstrip()) > len(new_body.rstrip()):
        result["x"] = old_body
    if same_link:
        if (older.get(peer) == newer.get(peer) and older.get("r") == newer.get("r")
                and older.get("c") == newer.get("c") and older.get("k") == newer.get("k")):
            carry = [identity]
            if stream == "i":
                carry.append("cv")
                same_receiver = (not older.get("ri") or not newer.get("ri") or older["ri"] == newer["ri"])
                if same_receiver and (not newer.get("ru") or not older.get("ru")
                                      or newer["ru"].casefold() == older["ru"].casefold()
                                      or older.get("ri") and older.get("ri") == newer.get("ri")):
                    carry.extend(("ru", "ri"))
            for key in carry:
                if not result.get(key) and older.get(key):
                    result[key] = older[key]
            if stream == "t" and result.get("cv") == "-" and older.get("cv") != "-":
                result["cv"] = older.get("cv")
    if stream == "i" or (older.get("c") and newer.get("c") and older["c"] != newer["c"]):
        return result
    # Counts belong to the stable tweet even when a lightweight response omits its quote or parent.
    metrics = [max(snapshot_metric(older, i, 0), snapshot_metric(newer, i, 0)) for i in range(len(METRIC_FIELDS))]
    fetched, observed = max((int(row.get("mt") or 0), bool(row.get("mo"))) for row in (older, newer))
    result.update(s=[value for _, _, value in metrics], ms=[when for _, when, _ in metrics],
                  um=[name for name, (rank, _, _) in zip(METRIC_FIELDS, metrics) if rank < 2],
                  mt=fetched, mo=bool(fetched and observed),
                  ma=max(int(row.get("ma") or 0) for row in (older, newer)))
    return result


def merge_snapshot_graph(merged: dict, older: dict, newer: dict) -> None:
    graph = max((newer, older), key=lambda snapshot: int(snapshot.get("gt") or 0))
    for key in GRAPH_FIELDS:
        if key in graph:
            merged[key] = graph[key]
        else:
            merged.pop(key, None)
    # Membership uses handles, which can be reassigned between graph observations.
    graph_users = graph.get("u") or {}
    users = merged["u"]
    for field in ("fg", "fr"):
        for handle in graph.get(field) or []:
            bound = graph_users.get(handle)
            if bound is None:
                users.pop(handle, None)
            elif not bound.get("id") or bound["id"] != (users.get(handle) or {}).get("id"):
                users[handle] = bound
    graph_at = int(graph.get("gt") or 0)
    if not graph_at or any(int(snapshot.get("gt") or 0) != graph_at for snapshot in (older, newer)):
        return
    if "re" in older or "re" in newer:
        by_peer = {}
        for snapshot in (newer, older):
            for check in snapshot.get("re") or []:
                if not check or not check.get("peerId") or not check.get("checkedAt"):
                    continue
                peer = check["peerId"]
                if peer not in by_peer or int(check["checkedAt"]) > int(by_peer[peer]["checkedAt"]):
                    by_peer[peer] = check
        merged["re"] = list(by_peer.values())
    if "ra" in older or "ra" in newer:
        attempts = {}
        for snapshot in (older, newer):
            for peer, attempted_at in (snapshot.get("ra") or {}).items():
                if peer and attempted_at is not None:
                    attempts[peer] = max(attempts.get(peer, 0), int(attempted_at))
        merged["ra"] = attempts


def merge_account_snapshots(current: dict, incoming: dict) -> dict:
    for snapshot in (current, incoming):
        snapshot_owner(snapshot)
    if current == incoming:
        return current
    older, newer = sorted((incoming, current), key=lambda snapshot: int(snapshot.get("at") or 0))
    merged = dict(newer)
    merged["u"] = {**(older.get("u") or {}), **(newer.get("u") or {})}
    proven = [snapshot for snapshot in (older, newer) if int(snapshot.get("cp") or 0) >= 1]
    coverage_sources = proven or (older, newer)
    merged["cp"] = min(int(snapshot["cp"]) for snapshot in proven) if proven else 0
    handles_differ = (older["me"].get("username") or "").casefold() != (newer["me"].get("username") or "").casefold()
    for stream in ("t", "i"):
        rows = {}
        for snapshot in (older, newer):
            for row in snapshot.get(stream) or []:
                if stream == "t":
                    row = stamp_snapshot_metrics(row, int(snapshot.get("at") or 0))
                elif (handles_differ and row.get("k") == "R" and not row.get("ri") and row.get("ru")
                      and row["ru"].lstrip("@").casefold() == (snapshot["me"].get("username") or "").casefold()):
                    row = {**row, "ri": snapshot["me"]["id"]}
                key = row["i"]
                rows[key] = merge_snapshot_row(rows[key], row, stream) if key in rows else row
        merged[stream] = sorted(rows.values(), key=lambda row: (-int(row.get("c") or 0), row["i"]))
        coverage = stream + "c"
        ranges = [span for snapshot in coverage_sources for span in (snapshot.get(coverage) or [])]
        merged[coverage] = combined_coverage(ranges)
    merge_snapshot_graph(merged, older, newer)
    for key in ("ds", "df"):
        if key in older or key in newer:
            merged[key] = max(int(snapshot.get(key) or 0) for snapshot in (older, newer))
    cycle = int(merged.get("df") or 0)
    finished = max((int(snapshot.get("dc") or 0) for snapshot in (older, newer)
                    if cycle > 0 and int(snapshot.get("df") or 0) == cycle), default=0)
    if cycle > 0 and finished >= cycle:
        merged["dc"] = finished
    else:
        merged.pop("dc", None)
    return merged


def prepare_import_files(contents: dict[str, bytes], originals: dict) -> dict[str, bytes]:
    changes = {}
    for name in IMPORT_FILES:
        if name not in contents:
            continue
        previous = originals[name]
        raw = contents[name]
        if name == ACCOUNT_FILE:
            incoming = json.loads(gzip.decompress(raw))
            old = json.loads(gzip.decompress(previous)) if previous is not None else None
            merged = merge_account_snapshots(old if old is not None else incoming, incoming)
        else:
            incoming = json.loads(raw)
            old = json.loads(previous) if previous is not None else None
            if not isinstance(incoming, dict) or (old is not None and not isinstance(old, dict)):
                raise ValueError(f"Invalid cache format in {name}")
            if name == BADGES_FILE:
                merged = dict(old or {})
                for key, entry in incoming.items():
                    observed = int(entry["firstObservedAt"])
                    if key not in merged or observed > int(merged[key]["firstObservedAt"]):
                        merged[key] = entry
            else:
                def expiry(profile: dict) -> float:
                    value = profile.get("expiresAt")
                    if isinstance(value, str):
                        return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
                    return float(value or 0)
                merged = incoming if old is None or expiry(incoming) > expiry(old) else old
        if old is None:
            changes[name] = raw
        elif merged != old:
            encoded = json.dumps(merged, ensure_ascii=False, separators=(",", ":")).encode()
            changes[name] = gzip.compress(encoded, mtime=0) if name == ACCOUNT_FILE else encoded
    return changes


def read_seed_bundle(path: Path) -> tuple[dict[str, bytes], list]:
    contents = {}
    with tarfile.open(path, "r:gz") as archive:
        for member in archive.getmembers():
            name = member.name.removeprefix("./")
            if name not in (*IMPORT_FILES, SEED_FILE) or not member.isfile():
                continue
            if name in contents:
                raise ValueError(f"Duplicate bundle member: {name}")
            contents[name] = archive.extractfile(member).read()
    if not contents:
        raise ValueError("Bundle contains no seed data")
    records = []
    packed = contents.pop(SEED_FILE, None)
    if packed is not None:
        for line in gzip.decompress(packed).decode().splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            kind, key, payload = row["kind"], row["id"], row["data"]
            if not isinstance(payload, dict) or not isinstance(key, str) or not key:
                raise ValueError("Invalid assistant seed record")
            if kind == "history_material":
                owner_ok = payload.get("authorId") == OWNER_ID
                if not payload.get("authorId") and str(payload.get("username") or "").lower() == OWNER_USERNAME:
                    # Preserve a legacy bundle's evidence without granting it to this author's archive.
                    kind = UNVERIFIED_MATERIAL
                    owner_ok = True
            elif kind == "history_interaction":
                owner_ok = key.startswith(OWNER_ID + ":") and payload.get("targetAuthorId", OWNER_ID) == OWNER_ID
            elif kind == "history_source":
                owner_ok = key == OWNER_ID and str(payload.get("authorId") or key) == OWNER_ID
            elif kind == "meta":
                owner_ok = key == "owner" and payload.get("accountId") == OWNER_ID
            else:
                owner_ok = False
            if not owner_ok:
                raise ValueError("Record does not belong to the owner history in this bundle")
            records.append((kind, key, payload))
    return contents, records


def apply_seed_records(conn: sqlite3.Connection, records: list, now_ms: int) -> int:
    source = None
    changed = 0
    for kind, key, payload in records:
        if kind == "history_source":
            source = payload
        elif kind == "meta":
            _, saved = observe_owner(conn, payload, now_ms)
            changed += saved
        else:
            changed += upsert_record(conn, kind, key, payload, now_ms, preserve_evidence=True)
    current = conn.execute("SELECT data FROM heads JOIN revisions USING(kind,id,ver) "
                           "WHERE kind='history_source' AND id=?", (OWNER_ID,)).fetchone()
    if current or source:
        merged = merge_source_progress(json.loads(current[0]), source) if current and source else (
            json.loads(current[0]) if current else dict(source))
        owner = assistant_owner(conn)
        if owner and owner.get("username"):
            merged["username"] = owner["username"]
        update_history_counts(conn, merged)
        changed += upsert_record(conn, "history_source", OWNER_ID, merged, now_ms)
    return changed


def sync_directory(directory: Path) -> None:
    fd = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def file_contents(path: Path) -> bytes | None:
    try:
        return path.read_bytes()
    except FileNotFoundError:
        return None


def install_import_file(dest: Path, expected: bytes | None, desired: bytes | None) -> None:
    if file_contents(dest) != expected:
        raise RuntimeError(f"Cache changed during seed import: {dest.name}")
    if desired is None:
        dest.unlink(missing_ok=True)
    else:
        atomic_write_bytes(dest, desired)
    sync_directory(dest.parent)


def recover_import(cache: Path, conn: sqlite3.Connection) -> int | None:
    # SQLite retains these before-images until the same transaction that writes the heads commits.
    if not conn.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='seed_import_undo'").fetchone():
        return
    entries = list(conn.execute("SELECT name,previous,intended FROM seed_import_undo"))
    if not entries:
        return
    files = {}
    records = []
    diverged = False
    for name, previous, intended in entries:
        if name == SEED_FILE:
            records = json.loads(gzip.decompress(intended))
            continue
        if name not in IMPORT_FILES:
            raise ValueError("Invalid entry in seed import journal")
        dest = cache / name
        current = file_contents(dest)
        diverged |= current not in (previous, intended)
        files[name] = (previous, intended, current)
    with conn:
        conn.execute("BEGIN IMMEDIATE")
        assistant_owner(conn)
        reapplied = None
        if diverged:
            # A later writer used the partially installed snapshot. Reapply the seed against that
            # writer's data instead of overwriting it with an older before-image.
            changes = prepare_import_files({name: entry[1] for name, entry in files.items()},
                                           {name: entry[2] for name, entry in files.items()})
            reapplied = apply_seed_records(conn, records, int(time.time() * 1000))
        else:
            changes = {name: previous for name, (previous, _, current) in files.items() if previous != current}
        for name, desired in changes.items():
            install_import_file(cache / name, files[name][2], desired)
        conn.execute("DELETE FROM seed_import_undo")
    return reapplied


@locked_cache_command
def cmd_import_bundle(args: argparse.Namespace) -> int:
    cache = Path(args.cache_dir).expanduser().resolve()
    bundle = Path(args.bundle).expanduser().resolve()
    if not bundle.is_file():
        print(f"ERROR: bundle not found: {bundle}", file=sys.stderr)
        return 1
    contents, records = read_seed_bundle(bundle)
    db_path = cache / "content-assistant" / "assistant.sqlite"
    db_path.parent.mkdir(parents=True, exist_ok=True)
    now_ms = int(time.time() * 1000)
    with closing(sqlite3.connect(db_path, timeout=30)) as conn:
        ensure_assistant_schema(conn)
        assistant_owner(conn)
        originals = {name: file_contents(cache / name) for name in contents}
        changes = prepare_import_files(contents, originals)
        if ACCOUNT_FILE in contents:
            account_bytes = changes.get(ACCOUNT_FILE) or originals[ACCOUNT_FILE]
            records.append(("meta", "owner", snapshot_binding(json.loads(gzip.decompress(account_bytes)))))
        conn.execute("PRAGMA synchronous=FULL")
        if changes:
            with conn:
                conn.execute("CREATE TABLE IF NOT EXISTS seed_import_undo("
                             "name TEXT PRIMARY KEY, previous BLOB, intended BLOB NOT NULL)")
                replay = gzip.compress(json.dumps(records, ensure_ascii=False, separators=(",", ":")).encode())
                conn.execute("INSERT INTO seed_import_undo VALUES(?,NULL,?)", (SEED_FILE, replay))
                for name, intended in changes.items():
                    conn.execute("INSERT INTO seed_import_undo VALUES(?,?,?)",
                                 (name, originals[name], intended))
        try:
            with conn:
                conn.execute("BEGIN IMMEDIATE")
                changed = apply_seed_records(conn, records, now_ms)
                for name, raw in changes.items():
                    install_import_file(cache / name, originals[name], raw)
                if changes:
                    conn.execute("DELETE FROM seed_import_undo")
        except Exception:
            changed = recover_import(cache, conn)
            if changed is None:
                raise
    print(json.dumps({"action": "import-bundle", "cacheDir": str(cache), "storeImported": ACCOUNT_FILE in changes,
                      "seedRecordsRead": len(records), "seedRecordsUpserted": changed}, ensure_ascii=False))
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Hydrate, export, and import @elidlicom history caches & content-assistant seed bundles."
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_hyd = sub.add_parser("hydrate-local", help="Hydrate assistant.sqlite from local store_v1.json.gz and optional archive markdown.")
    p_hyd.add_argument("--cache-dir", required=True, help="Path to disk cache directory (e.g. .cache/snapshots)")
    p_hyd.add_argument("--archive-md", default="", help="Optional markdown archive path to enrich truncated post bodies")
    p_hyd.set_defaults(func=cmd_hydrate_local)

    p_exp = sub.add_parser("export-bundle", help="Export @elidlicom store + assistant seed into a .tar.gz bundle.")
    p_exp.add_argument("--cache-dir", required=True, help="Path to disk cache directory")
    p_exp.add_argument("--out", required=True, help="Output .tar.gz path")
    p_exp.set_defaults(func=cmd_export_bundle)

    p_imp = sub.add_parser("import-bundle", help="Import @elidlicom bundle into a cache directory with its recorded coverage and collector progress.")
    p_imp.add_argument("--cache-dir", required=True, help="Target disk cache directory")
    p_imp.add_argument("--bundle", required=True, help="Input .tar.gz bundle path")
    p_imp.set_defaults(func=cmd_import_bundle)

    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
