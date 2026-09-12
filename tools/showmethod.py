#!/usr/bin/env python3
"""Print a readable pseudo-disassembly of one method: const-string loads + invokes.

Used to judge the *semantics* of a call site (e.g. is this ad position a passive slot
or a user-initiated reward flow?) before adding it to the block list.

usage: python showmethod.py <apk> <class::methodName> [max_lines]
"""
from __future__ import annotations

import re
import struct
import sys
import zipfile

from dexindex import Dex
from xref import WIDTHS, INVOKE_OPS


def operands(d: Dex, base: int, i: int, op: int):
    unit = struct.unpack_from("<H", d.buf, base + 2 * i)[0]
    if op in range(0x6E, 0x73):
        count, g = unit >> 12, (unit >> 8) & 0xF
        midx = struct.unpack_from("<H", d.buf, base + 2 * (i + 1))[0]
        rw = struct.unpack_from("<H", d.buf, base + 2 * (i + 2))[0]
        regs = [rw & 0xF, (rw >> 4) & 0xF, (rw >> 8) & 0xF, (rw >> 12) & 0xF, g][:count]
        return regs, midx
    if op in range(0x74, 0x79):
        midx = struct.unpack_from("<H", d.buf, base + 2 * (i + 1))[0]
        first = struct.unpack_from("<H", d.buf, base + 2 * (i + 2))[0]
        return list(range(first, first + (unit >> 8))), midx
    return None, None


def main() -> int:
    apk, spec = sys.argv[1], sys.argv[2]
    limit = int(sys.argv[3]) if len(sys.argv) > 3 else 400
    cls, _, meth = spec.partition("::")
    want = "L" + cls.replace(".", "/") + ";"

    with zipfile.ZipFile(apk) as z:
        names = sorted((n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)),
                       key=lambda n: (len(n), n))
        for n in names:
            d = Dex(z.read(n))
            for (desc, name, proto), code_off in d.method_code.items():
                if desc != want or name != meth:
                    continue
                print(f"=== {cls}#{name}{proto}  (code_off={code_off}) ===")
                insns_size = struct.unpack_from("<I", d.buf, code_off + 12)[0]
                base = code_off + 16
                regs_hint: dict[int, str] = {}
                i = 0
                shown = 0
                while i < insns_size and shown < limit:
                    unit = struct.unpack_from("<H", d.buf, base + 2 * i)[0]
                    op = unit & 0xFF
                    wide = unit >> 8
                    if op == 0x00 and wide != 0:
                        if wide == 0x01:
                            i += struct.unpack_from("<H", d.buf, base + 2 * (i + 1))[0] * 2 + 4
                        elif wide == 0x02:
                            i += struct.unpack_from("<H", d.buf, base + 2 * (i + 1))[0] * 4 + 2
                        elif wide == 0x03:
                            ew = struct.unpack_from("<H", d.buf, base + 2 * (i + 1))[0]
                            sz = struct.unpack_from("<I", d.buf, base + 2 * (i + 2))[0]
                            i += 4 + (sz * ew + 1) // 2
                        else:
                            break
                        continue
                    if op in (0x1A, 0x1B):
                        dest = unit >> 8
                        sidx = (struct.unpack_from("<H", d.buf, base + 2 * (i + 1))[0]
                                if op == 0x1A else
                                struct.unpack_from("<I", d.buf, base + 2 * (i + 1))[0])
                        s = d.strings[sidx] if sidx < len(d.strings) else "?"
                        regs_hint[dest] = s
                        print(f"  {i:6d}  v{dest:<2d} = const-string {s!r}")
                        shown += 1
                    elif op in INVOKE_OPS:
                        regs, midx = operands(d, base, i, op)
                        if midx is not None and midx < len(d.method_ids):
                            cidx, pidx, nidx = d.method_ids[midx]
                            ret, params = d.protos[pidx]
                            callee = (d.types[cidx][1:-1].replace("/", ".") + "#" +
                                      d.strings[nidx] + "(" + "".join(params) + ")" + ret)
                            args = ", ".join(
                                f"v{r}={regs_hint.get(r, '?')!r}" if r in regs_hint else f"v{r}=?"
                                for r in (regs or []))
                            print(f"  {i:6d}  invoke {callee}")
                            print(f"          args: {args}")
                            shown += 1
                    i += WIDTHS[op]
                return 0
    print(f"method not found: {spec}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
