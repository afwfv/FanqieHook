#!/usr/bin/env python3
"""Call-site (xref) analysis for hook targets.

A method that still exists but is no longer called is a dead hook. This script
counts `invoke-*` sites per method across every dex in an APK, so a version bump
can be judged by "is the switch still wired?", not just "does the name exist?".

Also diffs the class-name sets of two APKs to surface newly added ad classes.
"""
from __future__ import annotations

import re
import struct
import sys
import zipfile

from dexindex import Dex, _uleb128

# instruction width in 16-bit code units, indexed by opcode (0x00-0xff)
WIDTHS = [1] * 256
for op, w in {
    0x02: 2, 0x03: 3, 0x05: 2, 0x06: 3, 0x08: 2, 0x09: 3,
    0x13: 2, 0x14: 3, 0x15: 2, 0x16: 2, 0x17: 3, 0x18: 5, 0x19: 2,
    0x1a: 2, 0x1b: 3, 0x1c: 2, 0x1f: 2, 0x20: 2, 0x22: 2, 0x23: 2,
    0x24: 3, 0x25: 3, 0x26: 3, 0x29: 2, 0x2a: 3, 0x2b: 3, 0x2c: 3,
}.items():
    WIDTHS[op] = w
for op in range(0x2D, 0x3E):        # cmp* + if-*
    WIDTHS[op] = 2
for op in range(0x44, 0x6E):        # aget/aput/iget/iput/sget/sput
    WIDTHS[op] = 2
for op in range(0x6E, 0x73):        # invoke-kind
    WIDTHS[op] = 3
for op in range(0x74, 0x79):        # invoke-kind/range
    WIDTHS[op] = 3
for op in range(0x90, 0xB0):        # binop
    WIDTHS[op] = 2
for op in range(0xD0, 0xE3):        # binop/lit16 + binop/lit8
    WIDTHS[op] = 2
WIDTHS[0xFA] = 4
WIDTHS[0xFB] = 4
WIDTHS[0xFC] = 3
WIDTHS[0xFD] = 3
WIDTHS[0xFE] = 2
WIDTHS[0xFF] = 2

INVOKE_OPS = set(range(0x6E, 0x73)) | set(range(0x74, 0x79)) | {0xFA, 0xFB, 0xFC, 0xFD}


def dex_xrefs(d: Dex) -> dict[tuple[str, str, str], int]:
    """(class, name, proto) -> number of invoke sites inside this dex."""
    out: dict[tuple[str, str, str], int] = {}
    for key, code_off in d.method_code.items():
        insns_size = struct.unpack_from("<I", d.buf, code_off + 12)[0]
        base = code_off + 16
        i = 0
        while i < insns_size:
            unit = struct.unpack_from("<H", d.buf, base + 2 * i)[0]
            op = unit & 0xFF
            wide = unit >> 8
            if op == 0x00 and wide != 0:
                # payload pseudo-instruction (idents: 0x0100 packed-switch,
                # 0x0200 sparse-switch, 0x0300 array-data)
                if wide == 0x01:      # packed-switch-payload
                    size = struct.unpack_from("<H", d.buf, base + 2 * (i + 1))[0]
                    i += size * 2 + 4
                elif wide == 0x02:    # sparse-switch-payload
                    size = struct.unpack_from("<H", d.buf, base + 2 * (i + 1))[0]
                    i += size * 4 + 2
                elif wide == 0x03:    # array-data-payload
                    elem_width = struct.unpack_from("<H", d.buf, base + 2 * (i + 1))[0]
                    size = struct.unpack_from("<I", d.buf, base + 2 * (i + 2))[0]
                    i += 4 + (size * elem_width + 1) // 2
                else:
                    break
                continue
            if op in INVOKE_OPS:
                midx = struct.unpack_from("<H", d.buf, base + 2 * (i + 1))[0]
                cidx, pidx, nidx = d.method_ids[midx]
                ret, params = d.protos[pidx]
                k = (d.types[cidx], d.strings[nidx], "(" + "".join(params) + ")" + ret)
                out[k] = out.get(k, 0) + 1
            i += WIDTHS[op]
    return out


def apk_xrefs(apk_path: str):
    merged: dict[tuple[str, str, str], int] = {}
    classes: set[str] = set()
    ifaces: dict[str, list[str]] = {}
    supers: dict[str, str] = {}
    with zipfile.ZipFile(apk_path) as z:
        names = sorted((n for n in z.namelist() if re.fullmatch(r"classes\d*\.dex", n)),
                       key=lambda n: (len(n), n))
        for n in names:
            d = Dex(z.read(n))
            classes |= set(d.classes)
            ifaces.update(d.interfaces)
            supers.update(d.superclass)
            for k, v in dex_xrefs(d).items():
                merged[k] = merged.get(k, 0) + v
    return merged, classes, ifaces, supers


def family(desc: str, ifaces: dict[str, list[str]], supers: dict[str, str]) -> set[str]:
    seen: set[str] = set()
    stack = [desc]
    while stack:
        cur = stack.pop()
        if cur in seen:
            continue
        seen.add(cur)
        sup = supers.get(cur)
        if sup:
            stack.append(sup)
        stack.extend(ifaces.get(cur, ()))
    return seen


def key(java_cls: str, name: str, params: list[str] | None, ret: str) -> tuple[str, str, str]:
    from dexindex import _java_to_dex
    ps = "" if params is None else "".join(_java_to_dex(p) for p in params)
    return ("L" + java_cls.replace(".", "/") + ";", name,
            "(" + ps + ")" + ("V" if ret == "void" else _java_to_dex(ret)))


# hook id -> (class, method, params, return) — params None = wildcard (match by name+return)
PROBES: list[tuple[str, str, str, list[str] | None, str]] = [
    ("read-flow-ad-line", "com.dragon.read.component.biz.impl.NsAdImpl", "needReadFlowAdLine", ["com.dragon.reader.lib.ReaderClient"], "boolean"),
    ("reader-video-ad", "com.dragon.read.component.biz.impl.NsAdImpl", "canReaderVideoAdShow", [], "boolean"),
    ("reader-ad-for-sati", "com.dragon.read.reader.ad.ReaderAdManager", "canLoadAd", ["String"], "boolean"),
    ("topview-main", "com.dragon.read.component.biz.impl.NsAdImpl", "checkCanShowTopViewInMainPage", ["com.dragon.read.base.AbsActivity"], "boolean"),
    ("topview-reader", "com.dragon.read.component.biz.impl.NsAdImpl", "checkCanShowTopViewInReader", ["com.dragon.read.base.AbsActivity", "com.dragon.reader.lib.ReaderClient", "String"], "boolean"),
    ("series-pause-enable", "com.dragon.read.ad.onestop.seriespause.impl.SeriesPauseAdImpl", "enablePauseAd", [], "boolean"),
    ("series-pause-show", "com.dragon.read.ad.onestop.seriespause.impl.SeriesPauseAdImpl", "canShowPauseAd", None, "boolean"),
    ("position-filter:NsAdImpl", "com.dragon.read.component.biz.impl.NsAdImpl", "checkAdAvailable", ["String", "String"], "boolean"),
    ("hide-vip-entrance", "com.dragon.read.component.biz.impl.NsVipImpl", "canShowVipEntranceHere", ["com.dragon.read.component.biz.api.data.VipEntrance"], "boolean"),
    ("hide-vip-entrance-in-ad", "com.dragon.read.component.biz.impl.NsVipImpl", "canShowVipEntranceInAd", [], "boolean"),
    ("reader-fetch-intercept", "com.dragon.read.reader.ad.ReaderAdManager", "needInterceptFetchAd", ["String"], "boolean"),
    ("inspire-disable-ad-gift", "com.dragon.read.component.biz.impl.NsAdImpl", "disableAdGift", [], "boolean"),
    ("inspire-disable-banner-dismiss-anim", "com.dragon.read.component.biz.impl.NsAdImpl", "disableBannerDismissAnimation", [], "boolean"),
    ("audio-info-flow-ad", "com.dragon.read.component.biz.impl.NsAdImpl", "enableRequestAudioInfoFlowAd", [], "boolean"),
    ("audio-patch-ad", "com.dragon.read.component.biz.impl.NsAdImpl", "enableRequestAudioPatchAd", [], "boolean"),
    ("series-banner-enable", "com.dragon.read.ad.onestop.seriesbanner.config.SeriesBannerAdConfig", "enableBanner", [], "boolean"),
    ("series-banner-sdk-settings", "com.dragon.read.ad.onestop.seriesbanner.config.SeriesBannerAdConfig", "enableSdkSettings", [], "boolean"),
    ("hongguo-banner-join-revert", "com.dragon.read.ad.banner.impl.HongguoBannerServiceImpl", "enableShortSeriesAdJoinRevert", [], "boolean"),
    ("short-series-ad-enable", "com.dragon.read.reader.ad.experiment.ExperimentUtil", "p", [], "boolean"),
    ("short-series-landscape-insert-ad", "com.dragon.read.reader.ad.experiment.ExperimentUtil", "q0", [], "boolean"),
    ("splash-ad-activity-open", "com.dragon.read.component.biz.impl.NsAdImpl", "openOpeningScreenAdActivity", ["android.content.Context", "com.dragon.read.report.PageRecorder"], "void"),
    ("splash-ad-brand-view", "com.dragon.read.ad.openingscreenad.OpeningScreenADActivity", "showBrandAdView", ["android.view.View"], "void"),
    ("splash-ad-imc-view", "com.dragon.read.ad.openingscreenad.OpeningScreenADActivity", "showImcSplashView", ["android.view.View"], "void"),
    ("splash-ad-natural-view", "com.dragon.read.ad.openingscreenad.OpeningScreenADActivity", "showNaturalAdView", ["android.view.View"], "void"),
    ("position-filter:impl", "com.dragon.read.ad.manager.NsAdConfigManagerApi", "checkAdAvailable", ["String", "String"], "boolean"),
]


def main() -> int:
    base_apk, new_apk = sys.argv[1], sys.argv[2]
    bx, bc, bi, bs = apk_xrefs(base_apk)
    nx, nc, ni, ns = apk_xrefs(new_apk)

    def by_sig(x):
        out: dict[tuple[str, str], dict[str, int]] = {}
        for (c, n, p), v in x.items():
            out.setdefault((n, p), {})[c] = v
        return out

    bxs, nxs = by_sig(bx), by_sig(nx)

    def count(xs, ifaces, supers, cls, name, params, ret):
        desc = "L" + cls.replace(".", "/") + ";"
        fam = family(desc, ifaces, supers)
        if params is None:
            tot = 0
            for (n, p), owners in xs.items():
                if n == name:
                    tot += sum(v for c, v in owners.items() if c in fam)
            return tot
        k = key(cls, name, params, ret)
        owners = xs.get((name, k[2]), {})
        return sum(v for c, v in owners.items() if c in fam)

    print(f"base: {base_apk}")
    print(f"new : {new_apk}")
    print("counts are invoke-sites across the target's whole type family "
          "(class + supertypes + interfaces), so interface dispatch is included\n")
    print(f"{'hook id':38s} {'73532':>8s} {'73732':>8s}  verdict")
    print("-" * 90)
    for hook_id, cls, name, params, ret in PROBES:
        b = count(bxs, bi, bs, cls, name, params, ret)
        n = count(nxs, ni, ns, cls, name, params, ret)
        if n == 0 and b == 0:
            verdict = "no call sites in either (entry point, not a switch)"
        elif n == 0:
            verdict = "!! HOOK NOW DEAD (had sites in 73532)"
        elif b == n:
            verdict = "stable"
        else:
            verdict = f"site count changed {b} -> {n}"
        print(f"{hook_id:38s} {b:8d} {n:8d}  {verdict}")

    added = sorted(c for c in nc - bc
                   if c.startswith("Lcom/dragon/read/ad/") or "ad" in c.split("/")[-2:][0].lower())
    removed = sorted(c for c in bc - nc if c.startswith("Lcom/dragon/read/ad/"))
    print(f"\nclasses total: 73532={len(bc)}  73732={len(nc)}")
    print(f"ad-namespace classes added in 73732: {len(added)}")
    for c in added[:60]:
        print("   +", c[1:-1].replace("/", "."))
    print(f"ad-namespace classes removed in 73732: {len(removed)}")
    for c in removed[:40]:
        print("   -", c[1:-1].replace("/", "."))
    return 0


if __name__ == "__main__":
    sys.exit(main())
