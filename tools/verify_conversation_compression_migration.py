"""Synthetic SQLite verification of Room's generated 30 -> 31 migration.

Run after :app:kspDebugKotlin. This does not replace Android Room/device tests.
No application database or private chat content is accessed.
"""
import json
import re
import sqlite3
import tempfile
from contextlib import closing
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCHEMAS = ROOT / "app/schemas/me.rerere.rikkahub.data.db.AppDatabase"
GENERATED = ROOT / (
    "app/build/generated/ksp/debug/kotlin/me/rerere/rikkahub/data/db/"
    "AppDatabase_AutoMigration_30_31_Impl.kt"
)


def verify():
    old = json.loads((SCHEMAS / "30.json").read_text(encoding="utf-8"))["database"]
    new = json.loads((SCHEMAS / "31.json").read_text(encoding="utf-8"))["database"]
    statements = re.findall(r'connection\.execSQL\("([^"]+)"\)', GENERATED.read_text(encoding="utf-8"))
    assert len(statements) == 4
    assert all(sql.startswith("ALTER TABLE `ConversationEntity` ADD COLUMN ") for sql in statements)

    def migrate(db):
        for sql in statements:
            db.execute(sql)
        db.execute("PRAGMA user_version = 31")
        db.commit()

    def snapshot(db, path):
        db.execute("VACUUM INTO ?", (str(path),))

    with tempfile.TemporaryDirectory(prefix="orangechat-compression-synthetic-") as tmp, \
            closing(sqlite3.connect(Path(tmp) / "original.sqlite")) as db:
        directory = Path(tmp)
        db.execute("PRAGMA foreign_keys = ON")
        for entity in old["entities"]:
            db.execute(entity["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
            for index in entity.get("indices", []):
                db.execute(index["createSql"].replace("${TABLE_NAME}", entity["tableName"]))
        db.execute("PRAGMA user_version = 30")
        db.execute(
            "INSERT INTO ConversationEntity (id,title,nodes,create_at,update_at) VALUES (?,?,?,?,?)",
            ("synthetic-conversation", "synthetic title", "[]", 1, 2),
        )
        for index in range(6):
            db.execute("INSERT INTO message_node VALUES (?,?,?,?,?)", (
                f"synthetic-node-{index}", "synthetic-conversation", index,
                json.dumps([{"id": f"synthetic-message-{index}", "parts": ["RAW_ONLY_7K2"],
                             "attachment": "file:///synthetic.png"}, {"id": f"alternative-{index}"}]), 0,
            ))
        db.commit()
        original_row = db.execute("SELECT * FROM ConversationEntity").fetchone()
        original_nodes = db.execute("SELECT * FROM message_node ORDER BY node_index").fetchall()
        snapshot(db, directory / "old-backup.sqlite")
        migrate(db)
        migrated_row = db.execute("SELECT * FROM ConversationEntity").fetchone()
        assert migrated_row[:len(original_row)] == original_row
        assert migrated_row[len(original_row):] == ("[]", "[]", 0, 1)
        assert db.execute("SELECT * FROM message_node ORDER BY node_index").fetchall() == original_nodes

        for entity in new["entities"]:
            columns = db.execute(f'PRAGMA table_info("{entity["tableName"]}")').fetchall()
            assert {col[1] for col in columns} == {field["columnName"] for field in entity["fields"]}
            for field in entity["fields"]:
                column = next(col for col in columns if col[1] == field["columnName"])
                assert column[2] == field["affinity"]
                assert bool(column[3]) == field.get("notNull", False)
        summary = json.dumps([{"id": "synthetic-summary", "text": "SUMMARY_ONLY_9Q4", "sendToModel": False}])
        coverage = json.dumps([f"synthetic-node-{i}" for i in range(4)])
        db.execute("UPDATE ConversationEntity SET compression_summaries=?, compressed_node_ids=?, "
                   "compression_revision=2, send_compressed_summary=0", (summary, coverage))
        db.commit()
        snapshot(db, directory / "new-backup.sqlite")
        with closing(sqlite3.connect(directory / "new-backup.sqlite")) as restored:
            assert restored.execute("PRAGMA user_version").fetchone()[0] == 31
            assert restored.execute("SELECT compression_summaries,compressed_node_ids,compression_revision,"
                                    "send_compressed_summary FROM ConversationEntity").fetchone() == (summary, coverage, 2, 0)
            assert restored.execute("SELECT * FROM message_node ORDER BY node_index").fetchall() == original_nodes
            assert restored.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
            assert restored.execute("PRAGMA foreign_key_check").fetchall() == []
        with closing(sqlite3.connect(directory / "old-backup.sqlite")) as restored_old:
            migrate(restored_old)
            assert restored_old.execute("SELECT * FROM ConversationEntity").fetchone() == migrated_row
            assert restored_old.execute("SELECT * FROM message_node ORDER BY node_index").fetchall() == original_nodes
        db.close()
    return {"result": "pass", "migration": "30->31", "generated_statements": statements,
            "verified": ["old fields unchanged", "six nodes and alternatives unchanged", "new defaults",
                         "schema columns and affinities", "new backup roundtrip with disabled selections",
                         "old backup upgrade", "integrity and foreign keys"],
            "limitation": "Host SQLite synthetic data; Android Room/device validation was not run."}


if __name__ == "__main__":
    print(json.dumps(verify(), ensure_ascii=False, indent=2))
