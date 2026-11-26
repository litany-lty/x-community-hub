#!/usr/bin/env python3
"""Hydrate, export, and import @elidlicom local/seed history bundles (stdlib only)."""

from __future__ import annotations

import argparse
import email.utils
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
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

OWNER_ID = "2095392916734976000"
OWNER_USERNAME = "elidlicom"
TIMELINE_PROVIDER = "TWITTERAPI_IO"
TIMELINE_ENDPOINT = "/twitter/user/last_tweets"
INBOUND_ENDPOINT = "/twitter/user/mentions"
THIRTY_DAYS_SEC = 30 * 86400

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
STATUS_URL_RE = re.compile(r"https?://(?:x|twitter)\.com/elidlicom/status/(\d+)", re.IGNORECASE)


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


def parse_joined_epoch(joined: str | None) -> int | None:
    if not joined or not isinstance(joined, str):
        return None
    raw = joined.strip()
    if not raw:
        return None
    try:
        dt = email.utils.parsedate_to_datetime(raw)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return int(dt.timestamp())
    except Exception:
        pass
    try:
        dt = datetime.fromisoformat(raw.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return int(dt.timestamp())
    except Exception:
        return None


def parse_archive_markdown(md_path: Path) -> dict[str, str]:
    """Extract tweet_id -> cleaned body text from an archive markdown file."""
    if not md_path.is_file():
        return {}
    content = md_path.read_text(encoding="utf-8", errors="replace")
    # Split into logical blocks by markdown headings or horizontal rules
    raw_blocks = re.split(r"(?m)^(?:---|#{1,6}\s+.*)$", content)
    enriched: dict[str, str] = {}
    for block in raw_blocks:
        matches = list(STATUS_URL_RE.finditer(block))
        if not matches:
            continue
        if len(matches) == 1:
            tid = matches[0].group(1)
            body = _clean_archive_block(block, matches[0].group(0))
            if body and len(body) > len(enriched.get(tid, "")):
                enriched[tid] = body
        else:
            # Multiple status links in one section: slice between consecutive status links
            for idx, m in enumerate(matches):
                tid = m.group(1)
                start = m.start()
                end = matches[idx + 1].start() if idx + 1 < len(matches) else len(block)
                body = _clean_archive_block(block[start:end], m.group(0))
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
    conn.execute("PRAGMA journal_mode=WAL")
    ver = conn.execute("PRAGMA user_version").fetchone()[0]
    if ver > 1:
        raise RuntimeError(f"Unsupported assistant.sqlite user_version={ver}")
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


def upsert_record(
    conn: sqlite3.Connection,
    kind: str,
    rec_id: str,
    payload: dict[str, Any],
    now_ms: int,
    *,
    force_write: bool = False,
) -> bool:
    row = conn.execute(
        "SELECT h.ver, r.data FROM heads h JOIN revisions r USING(kind,id,ver) WHERE h.kind=? AND h.id=?",
        (kind, rec_id),
    ).fetchone()
    if row is not None:
        old_ver, old_json = int(row[0]), row[1]
        if not force_write:
            try:
                old_obj = json.loads(old_json)
                if _strip_meta(old_obj) == _strip_meta(payload):
                    return False
            except Exception:
                pass
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
    author_text_status = "FULL" if has_substantive else "MISSING"

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
    if isinstance(stats, list) and len(stats) >= 5:
        doc["views"] = int(stats[0] or 0)
        doc["likes"] = int(stats[1] or 0)
        doc["replies"] = int(stats[2] or 0)
        doc["reposts"] = int(stats[3] or 0)
        doc["bookmarks"] = int(stats[4] or 0)
    return doc


def build_history_interaction(
    item: dict[str, Any],
    me: dict[str, Any],
    users_by_lower: dict[str, dict[str, Any]],
) -> tuple[str, dict[str, Any]]:
    tid = str(item.get("i") or "").strip()
    created_sec = int(item.get("c") or 0)
    published_ms = created_sec * 1000
    target_author_id = str(me.get("id") or OWNER_ID)
    target_username = str(me.get("username") or OWNER_USERNAME)
    peer_username = str(item.get("p") or "").strip().lstrip("@")
    peer_meta = users_by_lower.get(peer_username.lower(), {})
    peer_id = str(peer_meta.get("id") or "")
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


def cmd_hydrate_local(args: argparse.Namespace) -> int:
    cache_dir = Path(args.cache_dir).expanduser().resolve()
    store_path = cache_dir / "users" / "by-id" / OWNER_ID / "store_v1.json.gz"
    if not store_path.is_file():
        print(f"ERROR: store file not found: {store_path}", file=sys.stderr)
        return 1

    with gzip.open(store_path, "rt", encoding="utf-8") as fh:
        store_data = json.load(fh)

    archive_texts: dict[str, str] = {}
    if args.archive_md:
        archive_path = Path(args.archive_md).expanduser().resolve()
        if archive_path.is_file():
            archive_texts = parse_archive_markdown(archive_path)

    me = store_data.get("me") or {"id": OWNER_ID, "username": OWNER_USERNAME}
    synced_at_sec = int(store_data.get("at") or int(time.time()))
    watermark_ms = synced_at_sec * 1000
    now_ms = int(time.time() * 1000)

    raw_users = store_data.get("u") or {}
    users_by_lower = {str(k).lower(): v for k, v in raw_users.items() if isinstance(v, dict)}
    tweets = [t for t in (store_data.get("t") or []) if isinstance(t, dict) and str(t.get("i") or "").strip()]
    inbound = [x for x in (store_data.get("i") or []) if isinstance(x, dict) and str(x.get("i") or "").strip()]

    db_path = cache_dir / "content-assistant" / "assistant.sqlite"
    db_path.parent.mkdir(parents=True, exist_ok=True)

    mat_upserted = 0
    in_upserted = 0
    enriched_count = 0

    conn = sqlite3.connect(str(db_path), timeout=30.0)
    try:
        ensure_assistant_schema(conn)
        with conn:
            # Upsert meta/owner only if missing
            existing_owner = conn.execute(
                "SELECT 1 FROM heads WHERE kind='meta' AND id='owner'"
            ).fetchone()
            if existing_owner is None:
                upsert_record(
                    conn,
                    "meta",
                    "owner",
                    {"accountId": OWNER_ID, "username": OWNER_USERNAME},
                    now_ms,
                )

            tl_times: list[int] = []
            for tw in tweets:
                tid = str(tw.get("i") or "").strip()
                if tid in archive_texts and len(archive_texts[tid]) > len(str(tw.get("x") or "")):
                    enriched_count += 1
                doc = build_history_material(tw, me, archive_texts)
                if doc["publishedAt"] > 0:
                    tl_times.append(doc["publishedAt"])
                if upsert_record(conn, "history_material", tid, doc, now_ms):
                    mat_upserted += 1

            in_times: list[int] = []
            for item in inbound:
                rec_id, doc = build_history_interaction(item, me, users_by_lower)
                if doc["publishedAt"] > 0:
                    in_times.append(doc["publishedAt"])
                if upsert_record(conn, "history_interaction", rec_id, doc, now_ms):
                    in_upserted += 1

            # Compute accurate counts & bounds across all @elidlicom rows currently in sqlite
            existing_hs_row = conn.execute(
                "SELECT r.data FROM heads h JOIN revisions r USING(kind,id,ver) "
                "WHERE h.kind='history_source' AND h.id=?",
                (OWNER_ID,),
            ).fetchone()
            hs = json.loads(existing_hs_row[0]) if existing_hs_row else {}

            tl_stats = conn.execute(
                "SELECT COUNT(*), MIN(CAST(json_extract(r.data,'$.publishedAt') AS INTEGER)), "
                "MAX(CAST(json_extract(r.data,'$.publishedAt') AS INTEGER)) "
                "FROM heads h JOIN revisions r USING(kind,id,ver) "
                "WHERE h.kind='history_material' AND (json_extract(r.data,'$.authorId')=? "
                "OR lower(coalesce(json_extract(r.data,'$.username'),''))=?)",
                (OWNER_ID, OWNER_USERNAME),
            ).fetchone()
            in_stats = conn.execute(
                "SELECT COUNT(*), MIN(CAST(json_extract(r.data,'$.publishedAt') AS INTEGER)), "
                "MAX(CAST(json_extract(r.data,'$.publishedAt') AS INTEGER)) "
                "FROM heads h JOIN revisions r USING(kind,id,ver) "
                "WHERE h.kind='history_interaction' AND (h.id LIKE ? "
                "OR json_extract(r.data,'$.targetAuthorId')=?)",
                (f"{OWNER_ID}:%", OWNER_ID),
            ).fetchone()

            tl_count = int(tl_stats[0] or len(tweets))
            tl_oldest = int(tl_stats[1] or (min(tl_times) if tl_times else 0))
            tl_newest = int(tl_stats[2] or (max(tl_times) if tl_times else 0))
            in_count = int(in_stats[0] or len(inbound))
            in_oldest = int(in_stats[1] or (min(in_times) if in_times else 0))
            in_newest = int(in_stats[2] or (max(in_times) if in_times else 0))

            hs.update(
                {
                    "authorId": OWNER_ID,
                    "username": OWNER_USERNAME,
                    "rank": int(hs.get("rank", 0)),
                    "owner": True,
                    "eligible": True,
                    "qualificationWindowDays": int(hs.get("qualificationWindowDays", 30)),
                    "qualificationProvisional": bool(hs.get("qualificationProvisional", False)),
                    "qualificationAt": int(hs.get("qualificationAt", now_ms)),
                    "timelineSavedCount": tl_count,
                    "inboundSavedCount": in_count,
                    "timelineOldestAt": tl_oldest,
                    "timelineNewestAt": tl_newest,
                    "inboundOldestAt": in_oldest,
                    "inboundNewestAt": in_newest,
                    "timelineBackfillComplete": True,
                    "inboundBackfillComplete": True,
                    "timelineExhausted": True,
                    "inboundExhausted": True,
                    "timelineCursor": "",
                    "inboundCursor": "",
                    "timelineCursorProvider": TIMELINE_PROVIDER,
                    "timelineCursorEndpoint": TIMELINE_ENDPOINT,
                    "inboundCursorProvider": TIMELINE_PROVIDER,
                    "inboundCursorEndpoint": INBOUND_ENDPOINT,
                    "timelineCursorFailures": 0,
                    "inboundCursorFailures": 0,
                    "timelineWatermarkAt": watermark_ms,
                    "inboundWatermarkAt": watermark_ms,
                    "lastTimelineCheckAt": now_ms,
                    "lastInboundCheckAt": now_ms,
                    "lastSuccessAt": now_ms,
                    "timelineTerminalReason": "UPSTREAM_EXHAUSTED",
                    "inboundTerminalReason": "UPSTREAM_EXHAUSTED",
                    "timelineError": "",
                    "inboundError": "",
                    "timelineRetryCount": 0,
                    "inboundRetryCount": 0,
                    "timelineRetryExhausted": False,
                    "inboundRetryExhausted": False,
                    "nextDimension": "TIMELINE",
                    "error": "",
                    "gaps": [],
                }
            )
            # Remove orphaned history_material / history_interaction heads from retired non-owner history sources
            conn.execute(
                "DELETE FROM heads WHERE kind='history_material' AND id IN ("
                "SELECT h.id FROM heads h JOIN revisions r USING(kind,id,ver) "
                "WHERE h.kind='history_material' AND json_extract(r.data,'$.authorId')!=? "
                "AND lower(coalesce(json_extract(r.data,'$.username'),''))!=?)",
                (OWNER_ID, OWNER_USERNAME),
            )
            conn.execute(
                "DELETE FROM heads WHERE kind='history_interaction' AND id NOT LIKE ? "
                "AND id IN (SELECT h.id FROM heads h JOIN revisions r USING(kind,id,ver) "
                "WHERE h.kind='history_interaction' AND coalesce(json_extract(r.data,'$.targetAuthorId'),'')!=?)",
                (f"{OWNER_ID}:%", OWNER_ID),
            )
            conn.execute(
                "DELETE FROM heads WHERE kind='history_source' AND id!=?",
                (OWNER_ID,),
            )

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
                "timelineSavedCount": tl_count,
                "inboundSavedCount": in_count,
            },
            ensure_ascii=False,
        )
    )
    return 0


def cmd_export_bundle(args: argparse.Namespace) -> int:
    cache_dir = Path(args.cache_dir).expanduser().resolve()
    out_path = Path(args.out).expanduser().resolve()
    store_rel = Path("users") / "by-id" / OWNER_ID / "store_v1.json.gz"
    store_path = cache_dir / store_rel
    if not store_path.is_file():
        print(f"ERROR: store file not found: {store_path}", file=sys.stderr)
        return 1

    seed_buf = io.BytesIO()
    exported_records = 0
    db_path = cache_dir / "content-assistant" / "assistant.sqlite"
    with gzip.GzipFile(fileobj=seed_buf, mode="wb") as gz:
        if db_path.is_file():
            conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=30.0)
            try:
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
                        "WHERE h.kind='history_material' AND (json_extract(r.data,'$.authorId')=? "
                        "OR lower(coalesce(json_extract(r.data,'$.username'),''))=?)",
                        (OWNER_ID, OWNER_USERNAME),
                    ),
                    (
                        "SELECT h.kind, h.id, r.data FROM heads h JOIN revisions r USING(kind,id,ver) "
                        "WHERE h.kind='history_interaction' AND (h.id LIKE ? "
                        "OR json_extract(r.data,'$.targetAuthorId')=?)",
                        (f"{OWNER_ID}:%", OWNER_ID),
                    ),
                ]
                for sql, params in queries:
                    for kind, rec_id, data_json in conn.execute(sql, params):
                        line = json.dumps(
                            {"kind": kind, "id": rec_id, "data": json.loads(data_json)},
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
            tf.add(store_path, arcname=store_rel.as_posix())
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


def _merge_and_extend_coverage(
    raw_intervals: list[Any],
    floor_sec: int,
    now_sec: int,
) -> list[list[int]]:
    intervals: list[list[int]] = []
    for item in raw_intervals or []:
        if isinstance(item, (list, tuple)) and len(item) >= 2:
            a, b = int(item[0]), int(item[1])
            if b >= a:
                intervals.append([a, b])
    intervals.append([floor_sec, now_sec])
    intervals.sort(key=lambda pair: (pair[0], pair[1]))
    merged: list[list[int]] = []
    for a, b in intervals:
        if not merged or a > merged[-1][1] + 1:
            merged.append([a, b])
        else:
            merged[-1][1] = max(merged[-1][1], b)
    # Guarantee contiguous tail reaches now_sec
    if merged:
        merged[-1][1] = max(merged[-1][1], now_sec)
    return merged


def cmd_import_bundle(args: argparse.Namespace) -> int:
    cache_dir = Path(args.cache_dir).expanduser().resolve()
    bundle_path = Path(args.bundle).expanduser().resolve()
    if not bundle_path.is_file():
        print(f"ERROR: bundle not found: {bundle_path}", file=sys.stderr)
        return 1

    now_sec = int(time.time())
    now_ms = now_sec * 1000
    cutoff_30 = now_sec - THIRTY_DAYS_SEC

    store_rel = f"users/by-id/{OWNER_ID}/store_v1.json.gz"
    store_imported = False
    seed_upserted = 0
    seed_total = 0

    with tarfile.open(bundle_path, "r:gz") as tf:
        members = {m.name.lstrip("./"): m for m in tf.getmembers() if m.isfile()}

        if store_rel in members:
            raw_gz = tf.extractfile(members[store_rel]).read()
            store_data = json.loads(gzip.decompress(raw_gz).decode("utf-8"))
            me = store_data.get("me") or {}
            tweets = store_data.get("t") or []
            joined_sec = parse_joined_epoch(me.get("joinedDate"))
            existing_tc = store_data.get("tc") or []
            existing_tc_min = min((int(x[0]) for x in existing_tc if isinstance(x, list) and len(x) >= 2), default=cutoff_30)
            tc_floor = min(cutoff_30, existing_tc_min)
            if joined_sec is not None:
                tc_floor = min(tc_floor, joined_sec)
            elif isinstance(me.get("totalPostsCount"), int) and me["totalPostsCount"] > len(tweets):
                me["totalPostsCount"] = len(tweets)
            if isinstance(me.get("p"), int) and me["p"] > len(tweets):
                me["p"] = len(tweets)

            existing_ic = store_data.get("ic") or []
            existing_ic_min = min((int(x[0]) for x in existing_ic if isinstance(x, list) and len(x) >= 2), default=cutoff_30)
            ic_floor = min(cutoff_30, existing_ic_min)
            inbounds = store_data.get("i") or []
            max_ib_ts = max((int(x.get("c") or 0) for x in inbounds if isinstance(x, dict)), default=0)
            ic_ceil = max_ib_ts if max_ib_ts > ic_floor else now_sec

            store_data["at"] = now_sec
            store_data["tc"] = _merge_and_extend_coverage(existing_tc, tc_floor, now_sec)
            store_data["ic"] = _merge_and_extend_coverage(existing_ic, ic_floor, ic_ceil)

            out_gz = gzip.compress(
                json.dumps(store_data, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            )
            atomic_write_bytes(cache_dir / store_rel, out_gz)
            store_imported = True

        for opt_name in ("author_profile_elidlicom.json", "blue_verified_cache_v1.json"):
            if opt_name in members:
                raw_opt = tf.extractfile(members[opt_name]).read()
                atomic_write_bytes(cache_dir / opt_name, raw_opt)

        if "elidlicom_assistant_seed.jsonl.gz" in members:
            seed_gz = tf.extractfile(members["elidlicom_assistant_seed.jsonl.gz"]).read()
            seed_lines = gzip.decompress(seed_gz).decode("utf-8").splitlines()
            db_path = cache_dir / "content-assistant" / "assistant.sqlite"
            db_path.parent.mkdir(parents=True, exist_ok=True)
            conn = sqlite3.connect(str(db_path), timeout=30.0)
            try:
                ensure_assistant_schema(conn)
                with conn:
                    for line in seed_lines:
                        if not line.strip():
                            continue
                        rec = json.loads(line)
                        kind = str(rec["kind"])
                        rec_id = str(rec["id"])
                        payload = dict(rec["data"])
                        seed_total += 1
                        if kind == "meta" and rec_id == "owner":
                            exists = conn.execute(
                                "SELECT 1 FROM heads WHERE kind='meta' AND id='owner'"
                            ).fetchone()
                            if exists is not None:
                                continue
                        elif kind == "history_source" and rec_id == OWNER_ID:
                            payload.update(
                                {
                                    "lastTimelineCheckAt": now_ms,
                                    "lastInboundCheckAt": now_ms,
                                    "timelineWatermarkAt": now_ms,
                                    "inboundWatermarkAt": now_ms,
                                    "lastSuccessAt": now_ms,
                                    "timelineBackfillComplete": True,
                                    "inboundBackfillComplete": True,
                                    "timelineExhausted": True,
                                    "inboundExhausted": True,
                                    "timelineCursor": "",
                                    "inboundCursor": "",
                                    "timelineError": "",
                                    "inboundError": "",
                                    "timelineRetryCount": 0,
                                    "inboundRetryCount": 0,
                                    "timelineRetryExhausted": False,
                                    "inboundRetryExhausted": False,
                                    "error": "",
                                }
                            )
                        if upsert_record(conn, kind, rec_id, payload, now_ms):
                            seed_upserted += 1
            finally:
                conn.close()

    print(
        json.dumps(
            {
                "action": "import-bundle",
                "cacheDir": str(cache_dir),
                "storeImported": store_imported,
                "seedRecordsRead": seed_total,
                "seedRecordsUpserted": seed_upserted,
            },
            ensure_ascii=False,
        )
    )
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

    p_imp = sub.add_parser("import-bundle", help="Import @elidlicom bundle into a cache directory with anti-refetch watermarks.")
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
