package dev.operit.fanqiehook

import java.util.zip.ZipFile

/**
 * Reads the host APK's `versionCode` directly out of its binary `AndroidManifest.xml`.
 *
 * Why this exists: the reflective routes in [FanqieModule.readVersionCode] are unreliable on
 * Android 14+. Measured on a OnePlus 9R / Android 14 / LSPosed 2.2.0:
 *
 *   - `PackageManager.getPackageArchiveInfo` → `NullPointerException: null receiver`
 *   - `ActivityThread.currentApplication()` → `IllegalStateException: currentApplication returned null`
 *     (expected: at `onPackageReady` the Application object does not exist yet)
 *
 * Both failures collapse the version check to `-1`, which the fail-open branch then waves through —
 * i.e. the gate silently stops gating exactly on the devices people run LSPosed on. Parsing the
 * manifest ourselves uses only public, stable container formats (ZIP + the AXML chunk layout), has
 * no hidden-API exposure, and needs no Context.
 *
 * Layout implemented (frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h):
 *
 *   ResChunk_header      { u16 type; u16 headerSize; u32 size }
 *   RES_XML_TYPE          0x0003   – file header
 *   RES_STRING_POOL_TYPE  0x0001   – string pool
 *   RES_XML_START_ELEMENT_TYPE 0x0102
 *
 *   String pool body (after the 8-byte chunk header):
 *     u32 stringCount; u32 styleCount; u32 flags; u32 stringsStart; u32 stylesStart;
 *     u32 stringOffsets[stringCount]; ... UTF-8 (flag 0x100) or UTF-16 string data
 *
 *   Start element node:
 *     ResChunk_header(8) + u32 lineNumber + u32 comment          → attrExt at +16
 *     attrExt: u32 ns; u32 name; u16 attributeStart; u16 attributeSize;
 *              u16 attributeCount; u16 idIndex; u16 classIndex; u16 styleIndex
 *     attribute: u32 ns; u32 name; u32 rawValue;
 *                u16 size; u8 res0; u8 dataType; u32 data
 */
internal object ApkVersion {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val TYPE_INT_DEC = 0x10
    private const val FLAG_UTF8 = 0x100

    /** Returns the APK's `versionCode`, or null when the manifest cannot be read/parsed. */
    fun readVersionCode(apkPath: String): Long? = runCatching {
        ZipFile(apkPath).use { zf ->
            val entry = zf.getEntry("AndroidManifest.xml") ?: return null
            val bytes = zf.getInputStream(entry).use { it.readBytes() }
            parseVersionCode(bytes)
        }
    }.getOrNull()

    private fun readStringPool(data: ByteArray, chunkStart: Int, headerSize: Int): StringPool =
        StringPool(data, chunkStart, headerSize)

    private fun parseVersionCode(data: ByteArray): Long? {
        if (data.size < 8) return null
        if (u16(data, 0).toInt() != RES_XML_TYPE) return null

        var strings: StringPool? = null
        var off = 8 // skip the file header chunk
        while (off + 8 <= data.size) {
            val type = u16(data, off).toInt()
            val headerSize = u16(data, off + 2).toInt()
            val size = u32(data, off + 4).toInt()
            if (size <= 0 || off + size > data.size) return null

            when (type) {
                RES_STRING_POOL_TYPE -> strings = readStringPool(data, off, headerSize)
                RES_XML_START_ELEMENT_TYPE -> {
                    val pool = strings ?: return null
                    val attrsOff = off + 16 // ResXMLTree_node header
                    val nameIdx = u32(data, attrsOff + 4).toInt()
                    if (nameIdx in 0 until pool.size && pool.get(nameIdx) == "manifest") {
                        val attributeStart = u16(data, attrsOff + 8).toInt()
                        val attributeSize = u16(data, attrsOff + 10).toInt()
                        val attributeCount = u16(data, attrsOff + 12).toInt()
                        var a = attrsOff + attributeStart
                        repeat(attributeCount) {
                            if (a + attributeSize <= data.size) {
                                val attrNameIdx = u32(data, a + 4).toInt()
                                val dataType = data[a + 15].toInt() and 0xFF
                                if (attrNameIdx in 0 until pool.size &&
                                    pool.get(attrNameIdx) == "versionCode" &&
                                    dataType == TYPE_INT_DEC
                                ) {
                                    return u32(data, a + 16).toLong() and 0xFFFFFFFFL
                                }
                            }
                            a += attributeSize
                        }
                    }
                }
            }
            off += size
        }
        return null
    }

    private class StringPool(private val data: ByteArray, private val base: Int, headerSize: Int) {
        private val offsets: IntArray
        private val count: Int
        private val utf8: Boolean
        private val stringsStart: Int

        init {
            count = u32(data, base + 8).toInt()
            val flags = u32(data, base + 16).toInt()
            utf8 = (flags and FLAG_UTF8) != 0
            stringsStart = u32(data, base + 20).toInt()
            val tableStart = base + headerSize
            offsets = IntArray(count) { i -> u32(data, tableStart + 4 * i).toInt() }
        }

        val size: Int get() = count

        fun get(index: Int): String? = runCatching {
            var p = base + stringsStart + offsets[index]
            if (utf8) {
                // u8/u16 utf16 length, then u8/u16 byte length, then MUTF-8 bytes
                p += skipVarLen8(p)
                val byteLen = readVarLen8(p)
                val start = byteLen.second
                String(data, start, byteLen.first, Charsets.UTF_8)
            } else {
                var len = u16(data, p).toInt()
                p += 2
                if (len and 0x8000 != 0) { // 32-bit length
                    len = ((len and 0x7FFF) shl 16) or u16(data, p).toInt()
                    p += 2
                }
                String(data, p, len * 2, Charsets.UTF_16LE)
            }
        }.getOrNull()

        /** Skips a 1-or-2 byte length field, returning the new offset. */
        private fun skipVarLen8(p: Int): Int = if (data[p].toInt() and 0x80 != 0) 2 else 1

        /** Reads a 1-or-2 byte length field, returning (length, offsetAfterField). */
        private fun readVarLen8(p: Int): Pair<Int, Int> {
            val first = data[p].toInt() and 0xFF
            return if (first and 0x80 != 0) {
                (((first and 0x7F) shl 8) or (data[p + 1].toInt() and 0xFF)) to (p + 2)
            } else {
                first to (p + 1)
            }
        }
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}
