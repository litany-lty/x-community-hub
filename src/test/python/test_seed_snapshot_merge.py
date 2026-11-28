import gzip
import importlib.util
import contextlib
import io
import itertools
import json
from pathlib import Path
import sqlite3
import tarfile
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("seed", ROOT / "scripts/elidlicom-seed-tool.py")
seed = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(seed)


def snapshot(anchor, **fields):
    return {"ver": 1, "me": {"id": seed.OWNER_ID, "username": seed.OWNER_USERNAME}, "at": anchor, **fields}


def tweet(**fields):
    return {"i": "post", "c": 10, "k": "P", **fields}


class SeedSnapshotMergeTest(unittest.TestCase):
    def test_full_import_orders_preserve_per_field_observations_and_unverified_raw_values(self):
        archived_text = "The full historical text with its final paragraph."
        full = snapshot(110, t=[tweet(x=archived_text, mt=100, ma=100, mo=True, um=[], s=[50, 5, 5, 5, 5]),
                                tweet(i="raw", s=[77, 0, 0, 0, 0]),
                                tweet(i="reclassified", k="Q", ti="linked", s=[77, 0, 0, 0, 0])])
        middle = snapshot(160, t=[tweet(x="The full…", mt=150, ma=150, mo=True, um=[], s=[60, 6, 6, 6, 6]),
                                  tweet(i="raw", s=[79, 0, 0, 0, 0])])
        partial = snapshot(210, t=[tweet(ma=200, um=["views", "reposts", "bookmarks"], s=[0, 7, 0, 0, 0]),
                                   tweet(i="raw", s=[0, 17, 0, 0, 0]),
                                   tweet(i="reclassified", k="P", x="preview", ma=200, s=[0, 2, 0, 0, 0],
                                         um=["views", "replies", "reposts", "bookmarks"])])
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            root = Path(directory)
            bundles = []
            for data in (full, middle, partial):
                filename = root / f'{data["at"]}.tar.gz'
                encoded = gzip.compress(json.dumps(data).encode())
                with tarfile.open(filename, "w:gz") as archive:
                    info = tarfile.TarInfo(seed.ACCOUNT_FILE)
                    info.size = len(encoded)
                    archive.addfile(info, io.BytesIO(encoded))
                bundles.append(filename)
            expected_rows = None
            for index, order in enumerate(itertools.permutations(bundles)):
                cache = root / f'cache-{index}'
                for filename in (*order, order[0]):
                    self.assertEqual(0, seed.main(["import-bundle", "--cache-dir", str(cache), "--bundle", str(filename)]))
                rows = json.loads(gzip.decompress((cache / seed.ACCOUNT_FILE).read_bytes()))["t"]
                self.assertEqual([60, 7, 0, 6, 6], rows[0]["s"], order)
                self.assertEqual(archived_text, rows[0]["x"], order)
                self.assertEqual([79, 17, 0, 0, 0], rows[1]["s"], order)
                self.assertEqual([77, 2, 0, 0, 0], rows[2]["s"], order)
                self.assertCountEqual(seed.METRIC_FIELDS, rows[1]["um"])
                self.assertEqual((150, True, 200), (rows[0]["mt"], rows[0]["mo"], rows[0]["ma"]))
                if expected_rows is None:
                    expected_rows = rows
                self.assertEqual(expected_rows, rows)
                self.assertEqual(0, seed.main(["hydrate-local", "--cache-dir", str(cache)]))
                with contextlib.closing(sqlite3.connect(cache / "content-assistant/assistant.sqlite")) as db:
                    material = json.loads(db.execute("SELECT data FROM heads JOIN revisions USING(kind,id,ver) "
                                                     "WHERE kind='history_material' AND id='post'").fetchone()[0])
                self.assertEqual(200000, material["seedMetricTimes"]["likes"])
                self.assertEqual(150000, material["seedMetricTimes"]["views"])
                self.assertEqual(archived_text, material["text"])

    def test_reclassification_keeps_post_metrics_without_attaching_a_different_objects_text(self):
        old = snapshot(100, t=[tweet(k="Q", ti="target-a", x="quote from the previous object", s=[77, 4, 0, 0, 0])])
        recent = snapshot(200, t=[tweet(k="P", x="preview", s=[0, 2, 0, 0, 0], ma=195,
                                        um=["views", "replies", "reposts", "bookmarks"])])
        for local, incoming in ((old, recent), (recent, old)):
            row, = self.imported(local, incoming)["t"]
            self.assertEqual([77, 2, 0, 0, 0], row["s"])
            self.assertIn("views", row["um"])
            self.assertNotIn("likes", row["um"])
            self.assertEqual("preview", row["x"])
        preview = snapshot(200, t=[tweet(k="P", x="quote from…", ma=195)])
        for local, incoming in ((old, preview), (preview, old)):
            row, = self.imported(local, incoming)["t"]
            self.assertEqual("quote from the previous object", row["x"])
        conflict = snapshot(250, t=[tweet(k="Q", ti="target-b", x="different", um=["views", "likes"])])
        row, = self.imported(old, conflict)["t"]
        self.assertEqual(("different", "target-b", 77), (row["x"], row["ti"], row["s"][0]))

    def imported(self, local, incoming):
        def encoded(data):
            return gzip.compress(json.dumps(data).encode(), mtime=0)

        with tempfile.TemporaryDirectory() as directory:
            cache = Path(directory)
            path = cache / seed.ACCOUNT_FILE
            seed.atomic_write_bytes(path, encoded(local))
            content = {seed.ACCOUNT_FILE: encoded(incoming)}
            for name, raw in seed.prepare_import_files(content, {seed.ACCOUNT_FILE: path.read_bytes()}).items():
                seed.atomic_write_bytes(cache / name, raw)
            self.assertEqual({}, seed.prepare_import_files(content, {seed.ACCOUNT_FILE: path.read_bytes()}),
                             "reimport scheduled a second write")
            return json.loads(gzip.decompress(path.read_bytes()))

    def test_legacy_coverage_keeps_rows_and_only_proven_intervals_survive(self):
        proven = snapshot(80, cp=1, tc=[[50, 80]], t=[{"i": "new-post"}], ic=[[40, 45]])
        legacy = snapshot(100, tc=[[1, 99]], i=[{"i": "older-reply"}], ic=[[5, 50]])
        for local, incoming in ((proven, legacy), (legacy, proven)):
            with self.subTest(local_at=local["at"]):
                merged = self.imported(local, incoming)
                self.assertEqual(1, merged["cp"])
                self.assertEqual([[50, 80]], merged["tc"])
                self.assertEqual([[40, 45]], merged["ic"])
                self.assertEqual(["new-post"], [r["i"] for r in merged["t"]])
                self.assertEqual(["older-reply"], [r["i"] for r in merged["i"]])
        marked_empty = self.imported(legacy, snapshot(105, cp=1))
        self.assertEqual((1, [], []), (marked_empty["cp"], marked_empty["tc"], marked_empty["ic"]))
        both_legacy = self.imported(legacy, snapshot(105, tc=[[200, 220]]))
        self.assertFalse(both_legacy.get("cp"))
        self.assertEqual([[1, 99], [200, 220]], both_legacy["tc"])

    def test_completion_time_must_belong_to_selected_cycle(self):
        measured = snapshot(100, ds=90, df=90, dc=110)
        later_legacy = snapshot(150, ds=120, df=120)
        another_partial = snapshot(160, ds=150, df=90)
        for left, right in ((measured, later_legacy), (later_legacy, measured)):
            result = self.imported(left, right)
            self.assertEqual(120, result["df"])
            self.assertNotIn("dc", result)
        self.assertEqual(110, self.imported(another_partial, measured)["dc"])

    def test_graph_binds_handles_to_its_own_users_and_excludes_previous_checks(self):
        graph = snapshot(100, gt=99, fg=["again"], fr=["known"], u={"again": {"id": "b"}, "known": {"id": "c"}},
                         fo={"b": 0}, re=[{"peerId": "b", "handle": "again", "checkedAt": 101}], ra={"b": 101000})
        other = snapshot(110, gt=88, fr=["again"], u={"again": {"id": "a"}, "other": {"id": "d"}},
                         re=[{"peerId": "a", "handle": "again", "checkedAt": 115}], ra={"a": 115000})
        for local, incoming in ((graph, other), (other, graph)):
            with self.subTest(local_at=local["at"]):
                merged = self.imported(local, incoming)
                self.assertEqual(99, merged["gt"])
                self.assertEqual("b", merged["u"]["again"]["id"])
                self.assertEqual("d", merged["u"]["other"]["id"])
                for field in ("fg", "fr", "fo", "re", "ra"):
                    self.assertEqual(graph[field], merged[field])

    def test_direct_reply_on_older_owner_handle_keeps_its_historical_receiver_id(self):
        old = snapshot(100, i=[{"i": "one", "c": 10, "k": "R", "p": "peer", "r": "root", "ru": "elidlicom"},
                               {"i": "two", "c": 11, "k": "R", "p": "peer", "r": "another", "ru": "third"}])
        new = snapshot(200, me={"id": seed.OWNER_ID, "username": "renamed"},
                       i=[{"i": "one", "c": 10, "k": "R", "p": "peer", "r": "root"}])
        for local, incoming in ((old, new), (new, old)):
            rows = {row["i"]: row for row in self.imported(local, incoming)["i"]}
            self.assertEqual(seed.OWNER_ID, rows["one"]["ri"])
            self.assertNotIn("ri", rows["two"])

    def test_same_graph_preserves_newest_check_and_attempt_for_each_stable_id(self):
        def check(peer, when, followed):
            return {"peerId": peer, "handle": "user-" + peer, "following": True, "followedBy": followed,
                    "checkedAt": when}

        older = snapshot(150, gt=99, re=[check("a", 140, True), check("b", 120, False)],
                         ra={"a": 140000, "b": 125000})
        newer = snapshot(160, gt=99, re=[check("a", 110, False), check("c", 145, True)],
                         ra={"a": 110000, "c": 149000})
        for local, incoming in ((older, newer), (newer, older)):
            with self.subTest(local_at=local["at"]):
                merged = self.imported(local, incoming)
                self.assertEqual({"a": 140000, "b": 125000, "c": 149000}, merged["ra"])
                self.assertEqual({r["peerId"]: r for r in [check("a", 140, True), check("b", 120, False),
                                                            check("c", 145, True)]},
                                 {r["peerId"]: r for r in merged["re"]})

    def test_graph_keeps_newer_profile_for_same_id_without_guessing_unrecorded_binding(self):
        graph = snapshot(100, gt=95, fg=["same", "unbound"], u={"same": {"id": "b", "n": "former name"}})
        other = snapshot(110, gt=80, u={"same": {"id": "b", "n": "new name"}, "unbound": {"id": "outsider"}})
        for local, incoming in ((graph, other), (other, graph)):
            with self.subTest(local_at=local["at"]):
                merged = self.imported(local, incoming)
                self.assertEqual("new name", merged["u"]["same"]["n"])
                self.assertNotIn("unbound", merged["u"])

    def test_partial_attempt_updates_known_fields_without_erasing_last_full_measurement(self):
        complete = snapshot(150, t=[tweet(mt=100, ma=120, mo=True, s=[10, 9, 8, 7, 6], um=[])])
        partial = snapshot(250, t=[tweet(ma=200, s=[35, 0, 4, 3, 0], um=["bookmarks"])])
        for local, incoming in ((complete, partial), (partial, complete)):
            with self.subTest(local_at=local["at"]):
                row, = self.imported(local, incoming)["t"]
                self.assertEqual((100, 200, True), (row["mt"], row["ma"], row["mo"]))
                self.assertEqual([35, 0, 4, 3, 6], row["s"])
                self.assertEqual([], row["um"])

    def test_missing_availability_is_unknown_and_attempt_time_cannot_replace_observation(self):
        observed = snapshot(120, t=[tweet(mt=80, ma=90, mo=True, um=["bookmarks"])])
        legacy = snapshot(200, t=[tweet(ma=180, s=[88, 7, 4, 3, 6])])
        for local, incoming in ((observed, legacy), (legacy, observed)):
            with self.subTest(local_at=local["at"]):
                row, = self.imported(local, incoming)["t"]
                # The four known fields are measured zeros. The fifth retains its unverified raw value.
                self.assertEqual([0, 0, 0, 0, 6], row["s"])
                self.assertEqual(["bookmarks"], row["um"])
                self.assertEqual((80, 180, True), (row["mt"], row["ma"], row["mo"]))

    def test_legacy_raw_counts_and_full_body_survive_a_more_recent_preview(self):
        old = snapshot(100, t=[tweet(x="This is the complete published body", s=[70, 8, 3, 2, 1])])
        clipped = snapshot(200, t=[tweet(x="This is…", s=[81, 9, 4, 5, 2])])
        for local, incoming in ((old, clipped), (clipped, old)):
            with self.subTest(local_at=local["at"]):
                row, = self.imported(local, incoming)["t"]
                self.assertEqual(clipped["t"][0]["s"], row["s"])
                self.assertCountEqual(seed.METRIC_FIELDS, row["um"])
                self.assertEqual(old["t"][0]["x"], row["x"])
                doc = seed.build_history_material(row, old["me"], {})
                self.assertFalse(set(seed.METRIC_FIELDS).intersection(doc))

    def test_sparse_copy_without_raw_metrics_cannot_clear_legacy_evidence(self):
        rich = snapshot(100, t=[tweet(x="完整原文", s=[77, 3, 9, 0, 5])])
        sparse = snapshot(200, t=[tweet(x="完整原文", ma=190)])
        for local, incoming in ((rich, sparse), (sparse, rich)):
            row, = self.imported(local, incoming)["t"]
            self.assertEqual([77, 3, 9, 0, 5], row["s"])
            self.assertCountEqual(seed.METRIC_FIELDS, row["um"])

    def test_same_verified_post_keeps_body_across_missing_parent_target_and_handle_rename(self):
        body = "完整的历史正文；最近的轻量回包只有开头。"
        for old_rel, new_rel in (
            ({"r": "-"}, {"r": "parent", "t": "firstknown", "ti": "21"}),
            ({"t": "former", "ti": "21", "r": "parent"}, {"t": "renamed", "ti": "21", "r": "parent"}),
            ({"t": "oldname"}, {"t": "OldName", "ti": "21"}),
        ):
            old = snapshot(100, t=[tweet(k="R", x=body, **old_rel)])
            recent = snapshot(200, t=[tweet(k="R", x="完整…", **new_rel)])
            for local, incoming in ((old, recent), (recent, old)):
                with self.subTest(old=old_rel, new=new_rel, local=local["at"]):
                    row, = self.imported(local, incoming)["t"]
                    self.assertEqual(body, row["x"])
                    self.assertEqual(new_rel["ti"], row["ti"])
        for conflicting in ({"ti": "other-id"}, {"r": "different-parent"}, {"c": 15}, {"k": "T"}):
            original = tweet(k="R", x=body, ti="21", r="parent")
            different = {**original, **conflicting, "x": "短文"}
            row, = self.imported(snapshot(100, t=[original]), snapshot(200, t=[different]))["t"]
            self.assertEqual("短文", row["x"])

    def test_estimated_metric_time_does_not_inherit_observed_flag_from_older_time(self):
        estimated = snapshot(280, t=[tweet(mt=240, ma=270, um=[])])
        for actual_time in (160, 240):
            with self.subTest(actual_time=actual_time):
                actual = snapshot(180, t=[tweet(mt=actual_time, ma=actual_time, um=[], mo=True)])
                row, = self.imported(estimated, actual)["t"]
                self.assertEqual((240, 270, actual_time == 240), (row["mt"], row["ma"], row["mo"]))


if __name__ == "__main__":
    unittest.main()
