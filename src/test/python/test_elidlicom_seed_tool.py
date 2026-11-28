import argparse
import contextlib
import gzip
import importlib.util
import io
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("seed", ROOT / "scripts/elidlicom-seed-tool.py")
seed = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(seed)


class SeedCoverageTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = Path(tmp.name)
        self.src = self.root / "src"
        self.dest = self.root / "dest"
        self.store = Path("users") / "by-id" / seed.OWNER_ID / "store_v1.json.gz"
        self.db = Path("content-assistant/assistant.sqlite")
        self.bundle = self.root / "bundle.tar.gz"
        data = {
            "me": {"id": seed.OWNER_ID, "username": seed.OWNER_USERNAME, "totalPostsCount": 550, "p": 550},
            "at": 200, "tc": [[40, 50], [180, 200]], "ic": [[193, 196]],
            "t": [{"i": "10", "k": "P", "c": 190, "s": [50, 0, 0, 0, 0], "um": ["bookmarks"]}],
            "u": {"former": {"id": "new"}},
            "i": [{"i": "30", "p": "former", "pi": "old", "c": 195}, {"i": "31", "p": "former", "c": 196}],
        }
        self.raw = gzip.compress(json.dumps(data).encode())
        seed.atomic_write_bytes(self.src / self.store, self.raw)

    def head(self, root, kind, key):
        with contextlib.closing(sqlite3.connect(root / self.db)) as db:
            row = db.execute("SELECT data FROM heads JOIN revisions USING(kind,id,ver) WHERE kind=? AND id=?",
                             (kind, key)).fetchone()
        return json.loads(row[0])

    def run_cmd(self, cmd, **args):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, cmd(argparse.Namespace(**args)))

    def test_binding_and_collection_handle_follow_latest_verified_snapshot_not_import_or_hydrate_time(self):
        self.run_cmd(seed.cmd_hydrate_local, cache_dir=self.src, archive_md=None)
        self.run_cmd(seed.cmd_export_bundle, cache_dir=self.src, out=self.bundle)
        renamed = json.loads(gzip.decompress(self.raw))
        renamed["me"]["username"] = "renamed"
        renamed["at"] = 350
        seed.atomic_write_bytes(self.src / self.store, gzip.compress(json.dumps(renamed).encode()))
        self.run_cmd(seed.cmd_hydrate_local, cache_dir=self.src, archive_md=None)
        self.assertEqual("renamed", self.head(self.src, "meta", "owner")["username"])
        latest = self.root / "renamed.tar.gz"
        self.run_cmd(seed.cmd_export_bundle, cache_dir=self.src, out=latest)
        for bundle in (self.bundle, latest, self.bundle):
            self.run_cmd(seed.cmd_import_bundle, cache_dir=self.dest, bundle=bundle)
        self.assertEqual(350000, self.head(self.dest, "meta", "owner")["usernameObservedAt"])
        seed.atomic_write_bytes(self.dest / self.store, self.raw)
        self.run_cmd(seed.cmd_hydrate_local, cache_dir=self.dest, archive_md=None)
        for kind, key in (("meta", "owner"), ("history_source", seed.OWNER_ID)):
            self.assertEqual("renamed", self.head(self.dest, kind, key)["username"])

    def test_new_handle_markdown_only_enriches_ids_from_the_verified_owner_snapshot(self):
        # The snapshot still has the old name. A post ID persists across a rename.
        md = self.root / "archive.md"
        md.write_text("https://x.com/renamed/status/10\n完整原文，含有一段历史正文。\n---\n"
                      "https://x.com/elidlicom/status/99\n接手者的文章\n", encoding="utf-8")
        self.run_cmd(seed.cmd_hydrate_local, cache_dir=self.src, archive_md=md)
        self.assertEqual("完整原文，含有一段历史正文。", self.head(self.src, "history_material", "10")["text"])
        self.assertEqual(1, self.head(self.src, "history_source", seed.OWNER_ID)["timelineSavedCount"])

    def test_corrected_quote_cannot_inherit_another_carriers_full_article(self):
        self.run_cmd(seed.cmd_hydrate_local, cache_dir=self.src, archive_md=None)
        old = {"authorId": seed.OWNER_ID, "quotedId": "Q1", "quotedText": "quoted one", "quotedTextStatus": "FULL",
               "articleId": "article-1", "articleRole": "QUOTED", "articleText": "the full first article",
               "articleTextStatus": "FULL", "textStatus": "ARTICLE_FULL", "seedEvidenceAt": 100}
        new = {"authorId": seed.OWNER_ID, "quotedId": "Q2", "kind": "QUOTE", "seedEvidenceAt": 200}
        bundles = []
        for label, record in (("old", old), ("new", new)):
            with contextlib.closing(sqlite3.connect(self.src / self.db)) as db, db:
                seed.upsert_record(db, "history_material", "quote", record, record["seedEvidenceAt"])
            path = self.root / f"{label}.tar.gz"
            self.run_cmd(seed.cmd_export_bundle, cache_dir=self.src, out=path)
            bundles.append(path)
        for index, order in enumerate((bundles, list(reversed(bundles)))):
            target = self.dest / str(index)
            for path in (*order, order[0]):
                self.run_cmd(seed.cmd_import_bundle, cache_dir=target, bundle=path)
            row = self.head(target, "history_material", "quote")
            self.assertEqual("Q2", row["quotedId"])
            for key in ("articleId", "articleRole", "articleText", "quotedText"):
                self.assertFalse(row.get(key), (key, row))
            self.assertNotEqual("ARTICLE_FULL", row.get("textStatus"))
            self.assertNotEqual("FULL", row.get("articleTextStatus"))

    def test_legacy_handle_only_bundle_preserves_unverified_data_outside_author_exports_and_counts(self):
        self.run_cmd(seed.cmd_hydrate_local, cache_dir=self.src, archive_md=None)
        self.run_cmd(seed.cmd_export_bundle, cache_dir=self.src, out=self.bundle)
        contents, rows = seed.read_seed_bundle(self.bundle)
        rows.append(("history_material", "legacy", {"username": seed.OWNER_USERNAME, "text": "unverified original"}))
        rows.append(("history_material", "10", {"username": seed.OWNER_USERNAME, "likes": 4321, "updatedAt": 999999}))
        legacy_bundle = self.root / "legacy.tar.gz"
        contents[seed.SEED_FILE] = gzip.compress("\n".join(json.dumps({"kind": k, "id": i, "data": d})
                                                         for k, i, d in rows).encode())
        with tarfile.open(legacy_bundle, "w:gz") as archive:
            for name, blob in contents.items():
                info = tarfile.TarInfo(name)
                info.size = len(blob)
                archive.addfile(info, io.BytesIO(blob))
        self.run_cmd(seed.cmd_import_bundle, cache_dir=self.dest, bundle=legacy_bundle)
        self.assertEqual(0, self.head(self.dest, "history_material", "10")["likes"])
        self.assertEqual(1, self.head(self.dest, "history_source", seed.OWNER_ID)["timelineSavedCount"])
        with contextlib.closing(sqlite3.connect(self.dest / self.db)) as db, db:
            self.assertEqual(1, db.execute("SELECT count(*) FROM revisions WHERE id='legacy' "
                                           "AND json_extract(data,'$.text')='unverified original'").fetchone()[0])
            seed.upsert_record(db, "history_material", "legacy-local", {"username": seed.OWNER_USERNAME}, 100)
            source = self.head(self.dest, "history_source", seed.OWNER_ID)
            seed.upsert_record(db, "history_source", seed.OWNER_ID, {**source, "timelineSavedCount": 99}, 100)
        self.run_cmd(seed.cmd_export_bundle, cache_dir=self.dest, out=self.bundle)
        records = seed.read_seed_bundle(self.bundle)[1]
        self.assertEqual(["10"], [k for kind, k, _ in records if kind == "history_material"])
        archived_source = next(row for kind, _, row in records if kind == "history_source")
        self.assertEqual(1, archived_source["timelineSavedCount"])

    def test_renamed_owner_hydrates_exports_and_merges_into_the_same_stable_account(self):
        renamed = json.loads(gzip.decompress(self.raw))
        renamed["me"]["username"] = "new_owner_name"
        renamed["at"] = 300
        seed.atomic_write_bytes(self.src / self.store, gzip.compress(json.dumps(renamed).encode()))
        self.run_cmd(seed.cmd_hydrate_local, cache_dir=self.src, archive_md=None)
        self.run_cmd(seed.cmd_export_bundle, cache_dir=self.src, out=self.bundle)
        seed.atomic_write_bytes(self.dest / self.store, self.raw)
        self.run_cmd(seed.cmd_import_bundle, cache_dir=self.dest, bundle=self.bundle)
        self.run_cmd(seed.cmd_import_bundle, cache_dir=self.dest, bundle=self.bundle)
        saved = json.loads(gzip.decompress((self.dest / self.store).read_bytes()))
        self.assertEqual((seed.OWNER_ID, "new_owner_name"), (saved["me"]["id"], saved["me"]["username"]))
        for kind, key in (("meta", "owner"), ("history_source", seed.OWNER_ID), ("history_material", "10")):
            self.assertEqual("new_owner_name", self.head(self.dest, kind, key)["username"])
        self.assertEqual(1, self.head(self.dest, "history_material", "10")["version"])

    def test_hydrate_validates_snapshot_owner_before_creating_history_and_exporting(self):
        for profile in (None, {"username": seed.OWNER_USERNAME}, {"id": "other", "username": seed.OWNER_USERNAME}):
            with self.subTest(profile=profile):
                data = json.loads(gzip.decompress(self.raw))
                data["me"] = profile
                seed.atomic_write_bytes(self.src / self.store, gzip.compress(json.dumps(data).encode()))
                with self.assertRaisesRegex(ValueError, "owner"):
                    self.run_cmd(seed.cmd_hydrate_local, cache_dir=self.src, archive_md=None)
                self.assertFalse((self.src / self.db).exists())
                with self.assertRaisesRegex(ValueError, "owner"):
                    self.run_cmd(seed.cmd_export_bundle, cache_dir=self.src, out=self.bundle)
                self.assertFalse(self.bundle.exists())

    def test_hydrate_and_export_reject_conflicting_assistant_binding_without_touching_history(self):
        (self.src / self.db).parent.mkdir(parents=True)
        with contextlib.closing(sqlite3.connect(self.src / self.db)) as db, db:
            seed.ensure_assistant_schema(db)
            seed.upsert_record(db, "meta", "owner", {"accountId": "other", "username": seed.OWNER_USERNAME}, 123)
            db.execute("CREATE TABLE seed_import_undo(name TEXT PRIMARY KEY, previous BLOB, intended BLOB NOT NULL)")
            db.execute("INSERT INTO seed_import_undo VALUES(?,?,?)", (seed.ACCOUNT_FILE, None, self.raw))
        for cmd, args in ((seed.cmd_hydrate_local, {"archive_md": None}), (seed.cmd_export_bundle, {"out": self.bundle})):
            with self.subTest(cmd=cmd.__name__), self.assertRaisesRegex(ValueError, "owner"):
                self.run_cmd(cmd, cache_dir=self.src, **args)
        with contextlib.closing(sqlite3.connect(self.src / self.db)) as db:
            self.assertEqual([("meta", "owner", 1)], db.execute("SELECT kind,id,ver FROM heads").fetchall())
            self.assertEqual(1, db.execute("SELECT count(*) FROM seed_import_undo").fetchone()[0])
        self.assertEqual(self.raw, (self.src / self.store).read_bytes())
        self.assertFalse(self.bundle.exists())

    def test_mixed_age_import_preserves_both_histories_gaps_and_newest_observations(self):
        remote = json.loads(gzip.decompress(self.raw))
        remote["t"][0].update(mt=210, ma=215, mo=True)
        remote.update(gt=180, fr=["upstream"], cp=1)
        seed.atomic_write_bytes(self.src / self.store, gzip.compress(json.dumps(remote).encode()))
        self.run_cmd(seed.cmd_export_bundle, cache_dir=self.src, out=self.bundle)
        for anchor in (150, 200, 300):
            with self.subTest(local_anchor=anchor):
                dest = self.dest / str(anchor)
                local = {"me": {**remote["me"], "totalPostsCount": 880}, "at": anchor, "cp": 1,
                         "tc": [[10, 20], [45, 60]], "ic": [[120, 122]], "u": {"local": {"id": "u2"}},
                         "gt": 190, "fg": ["local"], "t": [
                             {"i": "local", "c": 15}, {"i": "10", "c": 190, "k": "P", "mt": 100, "ma": 220,
                                                       "s": [1, 4, 2, 1, 7], "um": []}],
                         "i": [{"i": "local", "c": 121}]}
                seed.atomic_write_bytes(dest / self.store, gzip.compress(json.dumps(local).encode()))
                self.run_cmd(seed.cmd_import_bundle, cache_dir=dest, bundle=self.bundle)
                result = (dest / self.store).read_bytes()
                merged = json.loads(gzip.decompress(result))
                self.assertEqual({row["i"] for row in merged["t"]}, {"10", "local"})
                self.assertEqual({row["i"] for row in merged["i"]}, {"30", "31", "local"})
                self.assertEqual(merged["tc"], [[10, 20], [40, 60], [180, 200]])
                self.assertEqual(merged["ic"], [[120, 122], [193, 196]])
                self.assertEqual(set(merged["u"]), {"local", "former"})
                self.assertEqual(merged["me"]["totalPostsCount"], 550 if anchor < 200 else 880)
                self.assertEqual(merged["gt"], 190)
                self.assertEqual(merged["fg"], ["local"])
                self.assertFalse(merged.get("fr"))
                tweet = next(t for t in merged["t"] if t["i"] == "10")
                self.assertEqual(tweet["s"], [50, 0, 0, 0, 7])
                self.assertEqual(tweet["um"], [])
                self.assertEqual(tweet["ma"], 220)
                self.run_cmd(seed.cmd_import_bundle, cache_dir=dest, bundle=self.bundle)
                self.assertEqual((dest / self.store).read_bytes(), result)

    def test_retired_sources_survive_hydration_and_are_excluded_from_owner_archive(self):
        self.run_cmd(seed.cmd_hydrate_local, cache_dir=self.src, archive_md=None)
        with contextlib.closing(sqlite3.connect(self.src / self.db)) as db, db:
            seed.upsert_record(db, "history_source", "peer", {"authorId": "peer", "rank": 1}, 100)
            seed.upsert_record(db, "history_material", "peer-old",
                               {"authorId": "peer", "username": seed.OWNER_USERNAME}, 100)
            seed.upsert_record(db, "history_interaction", "peer:old", {"targetAuthorId": "peer"}, 100)
        self.run_cmd(seed.cmd_hydrate_local, cache_dir=self.src, archive_md=None)
        self.assertEqual(self.head(self.src, "history_source", "peer")["rank"], 1)
        self.assertEqual(self.head(self.src, "history_interaction", "peer:old")["targetAuthorId"], "peer")
        self.assertEqual(self.head(self.src, "history_source", seed.OWNER_ID)["timelineSavedCount"], 1)
        self.run_cmd(seed.cmd_export_bundle, cache_dir=self.src, out=self.bundle)
        self.assertNotIn("peer-old", {key for _, key, _ in seed.read_seed_bundle(self.bundle)[1]})

    def test_hydration_and_bundle_keep_evidence_and_independent_resume_state(self):
        self.run_cmd(seed.cmd_hydrate_local, cache_dir=self.src, archive_md=None)
        hs = self.head(self.src, "history_source", seed.OWNER_ID)
        self.assertFalse(hs.get("timelineExhausted"))
        self.assertFalse(hs.get("inboundBackfillComplete"))
        self.assertFalse(hs.get("timelineWatermarkAt"))
        self.assertEqual(hs["timelineSavedCount"], 1)
        material = self.head(self.src, "history_material", "10")
        self.assertEqual(material["views"], 50)
        self.assertEqual(material["likes"], 0)
        self.assertNotIn("bookmarks", material)
        self.assertEqual(self.head(self.src, "history_interaction", seed.OWNER_ID + ":30")["peerId"], "old")
        self.assertEqual(self.head(self.src, "history_interaction", seed.OWNER_ID + ":31")["peerId"], "")
        progress = {"timelineWatermarkAt": 193000, "timelineCursor": "page-two", "timelineCursorProvider": "free",
                    "timelineBackfillComplete": False, "inboundWatermarkAt": 125000, "inboundCursor": "older",
                    "lastTimelineCheckAt": 199000, "inboundRetryCount": 2, "lastSuccessAt": 199000}
        hs.update(progress)
        with contextlib.closing(sqlite3.connect(self.src / self.db)) as db, db:
            seed.upsert_record(db, "history_source", seed.OWNER_ID, hs, 200000)
        self.run_cmd(seed.cmd_hydrate_local, cache_dir=self.src, archive_md=None)
        self.assertEqual(progress, {k: self.head(self.src, "history_source", seed.OWNER_ID)[k] for k in progress})
        self.run_cmd(seed.cmd_export_bundle, cache_dir=self.src, out=self.bundle)
        self.run_cmd(seed.cmd_import_bundle, cache_dir=self.dest, bundle=self.bundle)
        self.assertEqual((self.dest / self.store).read_bytes(), self.raw)
        imported = self.head(self.dest, "history_source", seed.OWNER_ID)
        self.assertEqual(progress, {k: imported[k] for k in progress})
        imported.update(inboundWatermarkAt=201000, inboundCursor="next", inboundRetryCount=0)
        with contextlib.closing(sqlite3.connect(self.dest / self.db)) as db, db:
            seed.upsert_record(db, "history_source", seed.OWNER_ID, imported, 202000)
        self.run_cmd(seed.cmd_import_bundle, cache_dir=self.dest, bundle=self.bundle)
        current = self.head(self.dest, "history_source", seed.OWNER_ID)
        self.assertEqual(current["inboundCursor"], "next")
        self.assertEqual(current["timelineCursor"], "page-two")

    def test_compact_hydration_and_import_keep_enriched_material_and_peer(self):
        self.run_cmd(seed.cmd_hydrate_local, cache_dir=self.src, archive_md=None)
        self.run_cmd(seed.cmd_export_bundle, cache_dir=self.src, out=self.bundle)
        self.run_cmd(seed.cmd_import_bundle, cache_dir=self.dest, bundle=self.bundle)
        enriched = {"text": "完整作者正文", "quotedText": "独立引用原文", "quotedTextStatus": "FULL",
                    "quotedId": "q", "quotedAuthorId": "qa", "quotedUsername": "original",
                    "articleText": "完整长文内容", "articleTextStatus": "FULL", "articleId": "a",
                    "articleRole": "QUOTED", "articleUrl": "https://example.test/article",
                    "authorTextStatus": "FULL", "textStatus": "ARTICLE_FULL", "bookmarks": 7}
        peer_key = seed.OWNER_ID + ":31"
        with contextlib.closing(sqlite3.connect(self.dest / self.db)) as db, db:
            mat = self.head(self.dest, "history_material", "10")
            seed.upsert_record(db, "history_material", "10", {**mat, **enriched}, mat["updatedAt"] + 1)
            peer = self.head(self.dest, "history_interaction", peer_key)
            seed.upsert_record(db, "history_interaction", peer_key, {**peer, "peerId": "stable"}, peer["updatedAt"] + 1)
        for cmd, args in ((seed.cmd_hydrate_local, {"archive_md": None}),
                          (seed.cmd_import_bundle, {"bundle": self.bundle})):
            self.run_cmd(cmd, cache_dir=self.dest, **args)
            material = self.head(self.dest, "history_material", "10")
            self.assertEqual({k: material.get(k) for k in enriched}, enriched)
            self.assertEqual(self.head(self.dest, "history_interaction", peer_key)["peerId"], "stable")

    def test_failed_import_restores_files_and_sqlite_then_retries(self):
        cache_name = "blue_verified_cache_v1.json"
        (self.src / cache_name).write_text('{"id:source":{"verified":true,"firstObservedAt":180}}')
        self.run_cmd(seed.cmd_hydrate_local, cache_dir=self.src, archive_md=None)
        self.run_cmd(seed.cmd_export_bundle, cache_dir=self.src, out=self.bundle)
        local = json.loads(gzip.decompress(self.raw))
        local.update(at=100, t=[{"i": "local", "c": 50}])
        old_store = gzip.compress(json.dumps(local).encode())
        seed.atomic_write_bytes(self.dest / self.store, old_store)
        old_cache = b'{"id:local":{"verified":true,"firstObservedAt":160}}'
        (self.dest / cache_name).write_bytes(old_cache)
        self.run_cmd(seed.cmd_hydrate_local, cache_dir=self.dest, archive_md=None)
        def history_ids():
            with contextlib.closing(sqlite3.connect(self.dest / self.db)) as db:
                return list(db.execute("SELECT kind,id,ver FROM heads ORDER BY kind,id"))
        previous_heads = history_ids()
        with contextlib.closing(sqlite3.connect(self.dest / self.db)) as db:
            db.execute("PRAGMA user_version=9")
        with self.assertRaisesRegex(RuntimeError, "user_version"):
            seed.cmd_import_bundle(argparse.Namespace(cache_dir=self.dest, bundle=self.bundle))
        self.assertEqual((self.dest / self.store).read_bytes(), old_store)
        self.assertEqual((self.dest / cache_name).read_bytes(), old_cache)
        with contextlib.closing(sqlite3.connect(self.dest / self.db)) as db:
            db.execute("PRAGMA user_version=1")
        replace = seed.os.replace
        interrupted = False
        def fail_once(src, dst):
            nonlocal interrupted
            if Path(dst) == (self.dest / cache_name).resolve() and not interrupted:
                interrupted = True
                raise OSError("injected second-file failure")
            replace(src, dst)
        with patch.object(seed.os, "replace", side_effect=fail_once), self.assertRaisesRegex(OSError, "injected"):
            seed.cmd_import_bundle(argparse.Namespace(cache_dir=self.dest, bundle=self.bundle))
        self.assertEqual((self.dest / self.store).read_bytes(), old_store)
        self.assertEqual((self.dest / cache_name).read_bytes(), old_cache)
        self.assertEqual(history_ids(), previous_heads)
        # Terminate the importer after replacing the account but before the second file,
        # bypassing all Python exception handlers, then open the cache with another seed command.
        child = """
import argparse, importlib.util, os, sys
from pathlib import Path
spec = importlib.util.spec_from_file_location("seed", sys.argv[1])
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)
replace = os.replace
def interrupt(src, dest):
    if Path(dest).name == "blue_verified_cache_v1.json":
        os._exit(78)
    replace(src, dest)
os.replace = interrupt
seed.cmd_import_bundle(argparse.Namespace(cache_dir=sys.argv[2], bundle=sys.argv[3]))
"""
        def interrupt_import():
            stopped = subprocess.run([sys.executable, "-B", "-c", child, str(ROOT / "scripts/elidlicom-seed-tool.py"),
                                      str(self.dest), str(self.bundle)], capture_output=True, timeout=15)
            self.assertEqual(stopped.returncode, 78, stopped.stderr)
        interrupt_import()
        self.assertNotEqual((self.dest / self.store).read_bytes(), old_store)
        self.run_cmd(seed.cmd_export_bundle, cache_dir=self.dest, out=self.root / "recovered.tar.gz")
        self.assertEqual((self.dest / self.store).read_bytes(), old_store)
        self.assertEqual(history_ids(), previous_heads)
        interrupt_import()
        # A running collector recorded another post after the crash. Recovery must preserve it.
        live = json.loads(gzip.decompress((self.dest / self.store).read_bytes()))
        live["t"].append({"i": "while-stopped", "c": 320})
        live["at"] = 321
        seed.atomic_write_bytes(self.dest / self.store, gzip.compress(json.dumps(live).encode()))
        with contextlib.closing(sqlite3.connect(self.dest / self.db)) as db, db:
            seed.upsert_record(db, "history_material", "while-stopped", {"authorId": seed.OWNER_ID}, 321000)
        self.run_cmd(seed.cmd_export_bundle, cache_dir=self.dest, out=self.root / "recovered.tar.gz")
        after = json.loads(gzip.decompress((self.dest / self.store).read_bytes()))
        self.assertEqual({t["i"] for t in after["t"]}, {"10", "local", "while-stopped"})
        self.run_cmd(seed.cmd_import_bundle, cache_dir=self.dest, bundle=self.bundle)
        self.assertEqual(set(json.loads((self.dest / cache_name).read_bytes())), {"id:local", "id:source"})
        self.assertIn(("history_material", "10", 1), history_ids())
        self.assertIn(("history_material", "local", 1), history_ids())
        self.assertEqual(self.head(self.dest, "history_source", seed.OWNER_ID)["timelineSavedCount"], 3)

    def test_import_orders_observations_by_source_time_across_multiple_bundles(self):
        first = self.root / "first.tar.gz"
        second = self.root / "second.tar.gz"
        self.run_cmd(seed.cmd_hydrate_local, cache_dir=self.src, archive_md=None)
        for observed, count, path in ((100, 10, first), (200, 0, second)):
            mat = {"authorId": seed.OWNER_ID, "views": count, "quotedId": "q" + str(observed),
                   "quotedText": str(observed), "quotedTextStatus": "FULL"}
            with contextlib.closing(sqlite3.connect(self.src / self.db)) as db, db:
                seed.upsert_record(db, "history_material", "10", mat, observed)
            self.run_cmd(seed.cmd_export_bundle, cache_dir=self.src, out=path)
        for path in (first, second, first, second):
            self.run_cmd(seed.cmd_import_bundle, cache_dir=self.dest, bundle=path)
        latest = self.head(self.dest, "history_material", "10")
        self.assertEqual((latest["views"], latest["quotedId"], latest["version"]), (0, "q200", 2))


if __name__ == "__main__":
    unittest.main()
