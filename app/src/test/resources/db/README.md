These synthetic Room fixtures contain schema definitions only, with no user data.

- uncompressed-30.json: schema30 exported before compression changes (f7fd8f07b1ee2b803f9aa522a6508e8e).
- compression-v2-31.json: exported schema31 from the first sidebar v2 build (a0bf38a5398aa43d5d0574c4d2b9b45c).
- compression-v2-32.json: exported schema32 from the identity-compatibility repair build (389b859ade0257d5d1b36f337a0f232b).
- legacy-compression-31.json: reconstructed previous test build, schema30 plus compressed_summary TEXT DEFAULT '' and compressed_node_ids TEXT DEFAULT '[]'. Its identity is 8e4c29d7b3fab0e016562727f23bdf43, exactly matching the reported device error. Identity was recomputed using the Room2.8.4 compiler's SchemaIdentityKey algorithm, cross-checked against both exported schemas. The old schema31 export had been removed when its source changes were reverted.

CompressionSchemaMigrationTest opens databases with these structures using actual Room33 and its registered data-preserving migrations. It also verifies persisted checkpoints through Room save/reopen/backup and a single-round undo. It does not load an APK's native Requery/tokenizer libraries or run device UI.
