#!/usr/bin/env python3
"""Minimal DEX indexer: class -> {method names -> [(proto, access_flags)]}.

Used to verify FanqieHook hook targets against a concrete APK without needing
jadx/apktool round-trips. Reads classes*.dex straight out of the APK zip.
"""
from __future__ import annotations

import re
import struct
import sys
import zipfile


def _uleb128(buf: bytes, off: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7


class Dex:
    def __init__(self, buf: bytes):
        self.buf = buf
        (self.string_ids_size, self.string_ids_off,
         self.type_ids_size, self.type_ids_off,
         self.proto_ids_size, self.proto_ids_off,
         self.field_ids_size, self.field_ids_off,
         self.method_ids_size, self.method_ids_off,
         self.class_defs_size, self.class_defs_off) = struct.unpack_from(
            "<12I", buf, 56)
        self.strings = self._read_strings()
        self.types = [self.strings[struct.unpack_from("<I", buf, self.type_ids_off + 4 * i)[0]]
                      for i in range(self.type_ids_size)]
        self.protos = self._read_protos()
        self.classes: dict[str, dict[str, list[tuple[str, int]]]] = {}
        self.interfaces: dict[str, list[str]] = {}
        self.superclass: dict[str, str] = {}
        self.method_code: dict[tuple[str, str, str], int] = {}
        self.method_ids: list[tuple[int, int, int]] = []
        self.field_ids: list[tuple[int, int, int]] = []
        self.fields: dict[str, list[tuple[str, str, int]]] = {}
        self._read_method_ids()
        self._read_field_ids()
        self._read_classes()

    def _read_method_ids(self) -> None:
        self.method_ids = [
            struct.unpack_from("<HHI", self.buf, self.method_ids_off + 8 * i)
            for i in range(self.method_ids_size)]

    def _read_field_ids(self) -> None:
        self.field_ids = [
            struct.unpack_from("<HHI", self.buf, self.field_ids_off + 8 * i)
            for i in range(self.field_ids_size)]

    def _read_strings(self) -> list[str]:
        out = []
        for i in range(self.string_ids_size):
            off = struct.unpack_from("<I", self.buf, self.string_ids_off + 4 * i)[0]
            _, p = _uleb128(self.buf, off)  # utf16 length
            end = self.buf.index(b"\x00", p)
            out.append(self.buf[p:end].decode("utf-8", "replace"))
        return out

    def _read_protos(self) -> list[tuple[str, tuple[str, ...]]]:
        out = []
        for i in range(self.proto_ids_size):
            _, ret_idx, params_off = struct.unpack_from(
                "<III", self.buf, self.proto_ids_off + 12 * i)
            params: tuple[str, ...] = ()
            if params_off:
                size = struct.unpack_from("<I", self.buf, params_off)[0]
                params = tuple(
                    self.types[struct.unpack_from("<H", self.buf, params_off + 4 + 2 * j)[0]]
                    for j in range(size))
            out.append((self.types[ret_idx], params))
        return out

    def _read_classes(self) -> None:
        for i in range(self.class_defs_size):
            base = self.class_defs_off + 32 * i
            class_idx, _, superclass_idx, interfaces_off, _, _, class_data_off, _ = struct.unpack_from(
                "<8I", self.buf, base)
            desc = self.types[class_idx]
            if superclass_idx != 0xFFFFFFFF and superclass_idx < len(self.types):
                self.superclass[desc] = self.types[superclass_idx]
            if interfaces_off:
                size = struct.unpack_from("<I", self.buf, interfaces_off)[0]
                self.interfaces[desc] = [
                    self.types[struct.unpack_from("<H", self.buf, interfaces_off + 4 + 2 * j)[0]]
                    for j in range(size)]
            methods: dict[str, list[tuple[str, int]]] = {}
            flds: list[tuple[str, str, int]] = []
            if class_data_off:
                p = class_data_off
                sf, p = _uleb128(self.buf, p)
                inf, p = _uleb128(self.buf, p)
                dm, p = _uleb128(self.buf, p)
                vm, p = _uleb128(self.buf, p)
                # static / instance fields: each list has its own diff chain
                for count in (sf, inf):
                    fidx = 0
                    for _ in range(count):
                        diff, p = _uleb128(self.buf, p)
                        flags, p = _uleb128(self.buf, p)
                        fidx += diff
                        fcidx, ftidx, fnidx = struct.unpack_from(
                            "<HHI", self.buf, self.field_ids_off + 8 * fidx)
                        if fcidx != class_idx:
                            raise ValueError(
                                f"dex field parse drift in {desc}: field {fidx} of type {fcidx}")
                        flds.append((self.strings[fnidx], self.types[ftidx], flags))
                # NOTE: direct and virtual methods each start a fresh diff chain.
                for count in (dm, vm):
                    midx = 0
                    for _ in range(count):
                        diff, p = _uleb128(self.buf, p)
                        _flags, p = _uleb128(self.buf, p)
                        _code_off, p = _uleb128(self.buf, p)
                        midx += diff
                        cidx, pidx, nidx = struct.unpack_from(
                            "<HHI", self.buf, self.method_ids_off + 8 * midx)
                        if cidx != class_idx:
                            raise ValueError(
                                f"dex parse drift in {desc}: method {midx} belongs to type {cidx}")
                        name = self.strings[nidx]
                        ret, params = self.protos[pidx]
                        proto = "(" + "".join(params) + ")" + ret
                        methods.setdefault(name, []).append((proto, _flags))
                        if _code_off:
                            self.method_code[(desc, name, proto)] = _code_off
            self.classes[desc] = methods
            self.fields[desc] = flds


def _java_to_dex(name: str) -> str:
    """'String' -> 'Ljava/lang/String;'  /  'com.a.B' -> 'Lcom/a/B;'  / primitives kept."""
    prim = {"void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
            "int": "I", "long": "J", "float": "F", "double": "D"}
    if name in prim:
        return prim[name]
    if name.endswith("[]"):
        return "[" + _java_to_dex(name[:-2])
    if "." not in name:  # simple name -> java.lang (mirrors ClassResolver.resolveType)
        return "Ljava/lang/" + name + ";"
    return "L" + name.replace(".", "/") + ";"


def load(apk_path: str):
    merged: dict[str, dict[str, list[tuple[str, int]]]] = {}
    ifaces: dict[str, list[str]] = {}
    supers: dict[str, str] = {}
    flds: dict[str, list[tuple[str, str, int]]] = {}
    with zipfile.ZipFile(apk_path) as z:
        names = [n for n in z.namelist()
                 if re.fullmatch(r"classes\d*\.dex", n)]
        names.sort(key=lambda n: (len(n), n))
        for n in names:
            d = Dex(z.read(n))
            for cls, methods in d.classes.items():
                if cls in merged:
                    dst = merged[cls]
                    for m, sigs in methods.items():
                        dst.setdefault(m, []).extend(sigs)
                else:
                    merged[cls] = methods
            ifaces.update(d.interfaces)
            supers.update(d.superclass)
            flds.update(d.fields)
    return Index(merged, ifaces, supers, flds)


class Index:
    def __init__(self, classes, interfaces, superclass, fields=None):
        self.classes = classes
        self.interfaces = interfaces
        self.superclass = superclass
        self.fields = fields or {}

    def implementors(self, interface_java_name: str) -> list[str]:
        want = _java_to_dex(interface_java_name)
        return sorted(c for c, ifs in self.interfaces.items() if want in ifs)

    def family(self, dex_desc: str) -> set[str]:
        """Class + transitive supertypes/interfaces (where interface dispatch lands)."""
        seen: set[str] = set()
        stack = [dex_desc]
        while stack:
            cur = stack.pop()
            if cur in seen:
                continue
            seen.add(cur)
            sup = self.superclass.get(cur)
            if sup:
                stack.append(sup)
            stack.extend(self.interfaces.get(cur, ()))
        return seen


if __name__ == "__main__":
    idx = load(sys.argv[1])
    print("classes:", len(idx.classes))
    for cls in sys.argv[2:]:
        d = "L" + cls.replace(".", "/") + ";"
        if d not in idx.classes:
            print("MISSING CLASS", cls)
            continue
        print("CLASS", cls)
        for m, sigs in sorted(idx.classes[d].items()):
            print("   ", m, sigs)
