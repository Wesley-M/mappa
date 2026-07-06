# The native `.mappa` format

A map serializes to a compact, versioned binary document. It's for machines — save it, ship it, reopen
it — while the fluent builder stays the human-facing way to author one.

```java
byte[] bytes = map.toBytes();
map.write(Path.of("store.mappa"));      // or Mappa.write(map, path)

MappaMap reopened = Mappa.read(Path.of("store.mappa"));   // or Mappa.read(byte[])
```

`read` round-trips a map exactly: same title, entities, fields, and relationships, in the same order.

## Layout

A document is a short header followed by a compressed body:

```
+-----------+---------+---------+---------------------------+
| "MAPPA"   | version | flags   | deflate-compressed body   |
| 5 bytes   | 1 byte  | 1 byte  | ...                       |
+-----------+---------+---------+---------------------------+
```

- **Magic** — the ASCII bytes `MAPPA`. `read` rejects anything else with an `IOException`, so a wrong
  file fails loudly instead of drawing garbage.
- **Version** — a single byte. An unknown version is refused rather than guessed at.
- **Flags** — a bitset; bit 0 marks the body as deflate-compressed (it always is today).

The body, once inflated, is a **string table** followed by the model. Every name and type — `"orders"`,
`"uuid"`, `"text"` — is written once into the table and referenced by index everywhere else, so a schema
that repeats `"text"` across forty fields pays for it once. Integers are LEB128 variable-length, so small
counts and indices cost a single byte.

That combination — a de-duplicated string table plus deflate — is why a `.mappa` document comes out
smaller than the raw text of the names it carries, let alone an equivalent JSON.

After the model comes an optional **positions** block (format v2): a saved box centre per hand-arranged
entity, rounded to whole pixels and zig-zag encoded so a negative coordinate stays a short varint. It's how
a diagram reopens exactly as you arranged it — and, when every box is placed, lets the view skip auto-layout
entirely. A freshly built map writes an empty block, so it costs one byte.

## Why not JSON

JSON would work, and for interchange with other tools it might still be the right call — but for Mappa's
*own* save format it loses on every axis that matters here: it repeats every key and type string, it has
no version stamp to guard a future format change, and it invites hand-editing of a structure the builder
already validates. The binary format is deliberately opaque: author maps through the API, persist them
through the codec.

## Compatibility

The version byte is the contract. Within a major version, older documents keep reading; a breaking change
bumps the version and old readers refuse the new bytes by design rather than misinterpreting them. There
is no silent best-effort parse — a document either round-trips faithfully or errors.
