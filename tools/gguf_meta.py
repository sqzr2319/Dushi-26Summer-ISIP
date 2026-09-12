"""Read GGUF metadata key/values from a file (or a truncated head of one).

Why this exists: the app ships `Qwen3.5-2B-Q4_0.gguf` + a generically named
`mmproj-F16.gguf`, and the vision path produces exactly 12 tokens of word salad on
every backend, which is the signature of a vision adapter that does not belong to
the text model. This dumps both files' metadata so the mismatch is visible rather
than guessed.

Usage:
    python tools/gguf_meta.py <file> [--max-bytes N]
"""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path

# GGUF value type ids -> (struct format, human name). Arrays are handled separately.
# Ids follow gguf.h: 0..7 are the fixed-width scalars, 7 being a 1-byte bool
# (there is no "?" struct format — it is 'B' with only 0/1 meaningful).
SCALAR = {
    0: ("B", "uint8"),
    1: ("b", "int8"),
    2: ("H", "uint16"),
    3: ("h", "int16"),
    4: ("I", "uint32"),
    5: ("i", "int32"),
    6: ("f", "float32"),
    7: ("B", "bool"),
    8: ("Q", "string"),
    9: ("Q", "array"),
    10: ("Q", "uint64"),
    11: ("q", "int64"),
    12: ("d", "float64"),
}


class Reader:
    def __init__(self, buf: bytes) -> None:
        self.buf = buf
        self.pos = 0

    def take(self, n: int) -> bytes:
        if self.pos + n > len(self.buf):
            raise EOFError(f"need {n} bytes at {self.pos}, only {len(self.buf)} available")
        out = self.buf[self.pos : self.pos + n]
        self.pos += n
        return out

    def u32(self) -> int:
        return struct.unpack("<I", self.take(4))[0]

    def u64(self) -> int:
        return struct.unpack("<Q", self.take(8))[0]

    def string(self) -> str:
        n = self.u64()
        return self.take(n).decode("utf-8", errors="replace")

    def value(self, type_id: int):
        if type_id == 8:
            return self.string()
        if type_id == 9:
            elem_type = self.u32()
            count = self.u64()
            if count > 4096:
                for _ in range(count):
                    self._skip_value(elem_type)
                return f"<array[{count}] of {SCALAR.get(elem_type, ('?', '?'))[1]} (skipped)>"
            return [self.value(elem_type) for _ in range(count)]
        fmt, name = SCALAR.get(type_id, (None, None))
        if fmt is None:
            raise ValueError(f"unknown value type {type_id}")
        size = struct.calcsize("<" + fmt)
        raw = struct.unpack("<" + fmt, self.take(size))[0]
        return bool(raw) if name == "bool" else raw

    def _skip_value(self, type_id: int) -> None:
        if type_id == 8:
            self.take(self.u64())
            return
        if type_id == 9:
            elem_type = self.u32()
            count = self.u64()
            for _ in range(count):
                self._skip_value(elem_type)
            return
        fmt, _ = SCALAR.get(type_id, (None, None))
        if fmt is None:
            raise ValueError(f"unknown value type {type_id}")
        self.take(struct.calcsize("<" + fmt))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("path")
    ap.add_argument("--max-bytes", type=int, default=64 << 20,
                    help="read at most this many bytes (metadata lives at the start)")
    ap.add_argument("--filter", default=None,
                    help="only print keys containing this substring")
    ap.add_argument("--truncate", type=int, default=300,
                    help="truncate values to this many chars (0 = no limit)")
    args = ap.parse_args()

    p = Path(args.path)
    if not p.is_file():
        print(f"not a file: {p}", file=sys.stderr)
        return 1

    with p.open("rb") as f:
        buf = f.read(args.max_bytes)
    print(f"# {p.name}: read {len(buf):,} of {p.stat().st_size:,} bytes")

    r = Reader(buf)
    magic = r.take(4)
    if magic != b"GGUF":
        print(f"not a GGUF file (magic={magic!r})", file=sys.stderr)
        return 1
    version = r.u32()
    n_tensors = r.u64()
    n_kv = r.u64()
    print(f"# GGUF v{version}, {n_tensors:,} tensors, {n_kv:,} kv pairs\n")

    for _ in range(n_kv):
        key = r.string()
        type_id = r.u32()
        try:
            val = r.value(type_id)
        except (EOFError, ValueError) as e:
            print(f"{key:52s} <truncated: {e}>")
            break
        if args.filter and args.filter.lower() not in key.lower():
            continue
        text = str(val)
        limit = args.truncate
        if limit and len(text) > limit:
            text = text[:limit] + f" …(+{len(text) - limit} chars)"
        print(f"{key:52s} = {text}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
