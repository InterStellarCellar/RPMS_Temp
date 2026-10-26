# JSON malformed corpus

Classpath fixtures for `JsonMalformedCorpusTest`.

- `*.json` — intentionally broken JSON (syntax only)
- `expectations.tsv` — filename, minimum issue count, required message substring

Manual UI copies (same payloads) also live under `dont-commit/examples/json-malformed/`.
When adding a fixture, update both places and `expectations.tsv`.
