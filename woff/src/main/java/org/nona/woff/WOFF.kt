package org.nona.woff

import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.InflaterInputStream
import org.brotli.dec.BrotliInputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

object WOFF {

    /**
     * Decode WOFF file to OpenType font format
     */
    fun decodeToTTF(istream: InputStream) = parse(ByteBuffer.wrap(istream.readBytes()))

    /**
     * Decode WOFF file to OpenType font format
     */
    fun decodeToTTF(fis: FileInputStream) =  parse(fis.channel.map(
        FileChannel.MapMode.READ_ONLY, 0, fis.channel.size()))

    /**
     * Decode WOFF file to OpenType font format
     */
    fun decodeToTTF(buffer: ByteBuffer) = parse(buffer.duplicate())

    private val FLAG_TO_TAG = intArrayOf(
        0x636d6170, 0x68656164, 0x68686561, 0x686d7478,
        0x6d617870, 0x6e616d65, 0x4f532f32, 0x706f7374,
        0x63767420, 0x6670676d, 0x676c7966, 0x6c6f6361,
        0x70726570, 0x43464620, 0x564f5247, 0x45424454,
        0x45424c43, 0x67617370, 0x68646d78, 0x6b65726e,
        0x4c545348, 0x50434c54, 0x56444d58, 0x76686561,
        0x766d7478, 0x42415345, 0x47444546, 0x47504f53,
        0x47535542, 0x45425343, 0x4a535446, 0x4d415448,
        0x43424454, 0x43424c43, 0x434f4c52, 0x4350414c,
        0x53564720, 0x73626978, 0x61636e74, 0x61766172,
        0x62646174, 0x626c6f63, 0x62736c6e, 0x63766172,
        0x66647363, 0x66656174, 0x666d7478, 0x66766172,
        0x67766172, 0x68737479, 0x6a757374, 0x6c636172,
        0x6d6f7274, 0x6d6f7278, 0x6f706264, 0x70726f70,
        0x7472616b, 0x5a617066, 0x53696c66, 0x476c6174,
        0x476c6f63, 0x46656174, 0x53696c6c
    )

    private const val TAG_WOFF_HEADER = 0x774f4646
    private const val TAG_WOFF2_HEADER = 0x774f4632
    private const val TAG_TTCF_HEADER = 0x74746366
    private const val TAG_GLYF_TABLE = 0x676C7966
    private const val TAG_LOCA_TABLE = 0x6C6F6361
    private const val TAG_HEAD_TABLE = 0x68656164

    /// Regular glyph flags
    private const val GLYPH_ON_CURVE = 1 shl 0
    private const val GLYPH_X_SHORT = 1 shl 1
    private const val GLYPH_Y_SHORT = 1 shl 2
    private const val GLYPH_REPEAT = 1 shl 3
    private const val GLYPH_THIS_X_IS_SAME = 1 shl 4
    private const val GLYPH_THIS_Y_IS_SAME = 1 shl 5
    private const val GLYPH_OVERLAP_SIMPLE = 1 shl 6

    // Composite glyph flags
    private const val FLAG_ARG_1_AND_2_ARE_WORDS = 1 shl 0
    private const val FLAG_WE_HAVE_A_SCALE = 1 shl 3
    private const val FLAG_MORE_COMPONENTS = 1 shl 5
    private const val FLAG_WE_HAVE_AN_X_AND_Y_SCALE = 1 shl 6
    private const val FLAG_WE_HAVE_A_TWO_BY_TWO = 1 shl 7
    private const val FLAG_WE_HAVE_INSTRUCTIONS = 1 shl 8

    private fun parse(woff: ByteBuffer) : ByteArray? {
        woff.order(ByteOrder.BIG_ENDIAN)
        return when (woff.getTag(0)) {
            TAG_WOFF_HEADER -> parseWoff(woff)
            TAG_WOFF2_HEADER -> parseWoff2(woff)
            else -> null
        }
    }

    private class ByteBufferInputStream(private val buf: ByteBuffer) : InputStream() {
        override fun read(): Int = if (buf.hasRemaining()) buf.get().toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (!buf.hasRemaining()) {
                return -1
            }
            val minLen = min(len, buf.remaining())
            buf.get(b, off, minLen)
            return minLen
        }
    }

    private fun parseWoff(woff: ByteBuffer): ByteArray {
        val flavor = woff.getTag(4)
        require(woff.getUint32AsInt32Safe(8) == woff.capacity())
        val numTable = woff.getUint16(12)
        val totalSfntSize = woff.getUint32AsInt32Safe(16)

        val otf = ByteArray(totalSfntSize.round4Up())  // round up to 4 checksum calculation
        val outBuffer = ByteBuffer.wrap(otf).apply { order(ByteOrder.BIG_ENDIAN) }

        outBuffer.putTag(0, flavor)
        outBuffer.putUint16(4, numTable)
        val searchRange = (1 shl floor(log2(numTable.toDouble())).toInt()) * 16
        outBuffer.putUint16(6, searchRange)
        outBuffer.putUint16(8, floor(log2(numTable.toDouble())).toInt())
        outBuffer.putUint16(10, numTable * 16 - searchRange)

        var head = 12 + numTable * 16
        var checksumOffset = -1
        for (i in 0..<numTable) {
            val woffTableOffset = 44 + i * 20
            val tag = woff.getTag(woffTableOffset)
            val offset = woff.getUint32AsInt32Safe(woffTableOffset + 4)
            val compLength = woff.getUint32AsInt32Safe(woffTableOffset + 8)
            val origLength = woff.getUint32AsInt32Safe(woffTableOffset + 12)
            val origChecksum = woff.getUint32(woffTableOffset + 16)

            woff.position(offset)
            if (origLength == compLength) {
                woff.get(otf, head, compLength)
            } else {
                InflaterInputStream(ByteBufferInputStream(woff)).use {
                    var writtenLen = 0;
                    while (writtenLen != origLength) {
                        writtenLen += it.read(otf, head + writtenLen, origLength - writtenLen)
                    }
                    require(writtenLen == origLength)
                }
            }
            val otfTableOffset = 12 + i * 16
            outBuffer.putTag(otfTableOffset, tag)
            outBuffer.putUint32(otfTableOffset + 4, origChecksum)
            outBuffer.putUint32(otfTableOffset + 8, head)
            outBuffer.putUint32(otfTableOffset + 12, origLength)

            if (tag == TAG_HEAD_TABLE) {  // Keep checksumAdjustment offset
                checksumOffset = head + 8
            }

            head += 4 * ((origLength + 3) / 4)
        }
        require(head == totalSfntSize)
        require(checksumOffset != -1)

        // Recalculate checksumAdjustment
        outBuffer.putUint32(checksumOffset, 0)
        updateHeaderChecksum(ByteBuffer.wrap(otf), otf.size, checksumOffset)
        return otf
    }

    class Table(
        val tag: Int,
        val transformFlag: Int,
        val hasTransform: Boolean,
        val srcOffset: Int,
        val srcLength: Int,
        var dstOffset: Int = 0,
        var dstLength: Int = 0,
        var checksum: Long = 0
    )

    class WOFF2Header(
        val flavor: Int,
        val uncompLength: Int,
        val tables: List<Table>
    ) {
        fun sizeOfTableDirectory() = 12 + 16 * tables.size
        companion object {
            fun read(woff: ByteBuffer): WOFF2Header {
                val flavor = woff.getTag(4)
                require(woff.getUint32AsInt32Safe(8) == woff.capacity())
                val numTables = woff.getUint16(12)

                woff.position(48)  // Move to TableDirectory
                var srcOffset = 0
                var dstOffset = 12 + numTables * 16
                val tables = List(numTables) {
                    val flag = woff.getUint8()
                    val tag = if (flag and 0x3f == 0x3f) { woff.getTag() } else { FLAG_TO_TAG[flag and 0x3f] }
                    val transformFlag = (flag ushr 6) and 0x03
                    val hasTransform = if (tag == TAG_GLYF_TABLE || tag == TAG_LOCA_TABLE) {
                        transformFlag == 0
                    } else {
                        transformFlag != 0
                    }
                    val dstLength = woff.getUintBase128()
                    val transformLength = if (hasTransform) { woff.getUintBase128() } else { dstLength }
                    Table(tag, transformFlag, hasTransform, srcOffset, transformLength, dstOffset, dstLength).also {
                        srcOffset += transformLength
                        dstOffset += dstLength.round4Up()
                    }
                }
                return WOFF2Header(flavor, srcOffset, tables)
            }
        }
    }

    class CollectionHeader(
        val majorVersion: Int,
        val minorVersion: Int,
        val numFonts: Int,
        val entries: Array<CollectionEntry>
    ) {
        fun dsigSize() = if (majorVersion == 2 && minorVersion == 0) { 6 } else { 0 }
        fun ttcfHeaderSize() = 12 + dsigSize() + 4 * entries.size + entries.sumOf { 12 + 16 * it.indices.size }

        companion object {
            fun read(woff: ByteBuffer): CollectionHeader {
                val majorVersion = woff.getUint16()
                val minorVersion = woff.getUint16()
                val numFonts = woff.get255Uint16()
                val entries = Array(numFonts) { CollectionEntry.read(woff) }
                return CollectionHeader(majorVersion, minorVersion, numFonts, entries)
            }
        }
    }

    class CollectionEntry(
        val flavor: Int,
        val indices: IntArray
    ) {
        companion object {
            fun read(woff: ByteBuffer): CollectionEntry {
                val numTables = woff.get255Uint16()
                val flavor = woff.getTag()
                val indices = IntArray(numTables) { woff.get255Uint16() }
                return CollectionEntry(flavor, indices)
            }
        }
    }

    class CompressedGlyphTable(
        val numGlyphs: Int,
        val indexFormat: Int,
        val nContourStream: ByteBuffer,
        val nPointsStream: ByteBuffer,
        val flagStream: ByteBuffer,
        val glyphStream: ByteBuffer,
        val compositeStream: ByteBuffer,
        val bboxStream: ByteBuffer,
        val instructionStream: ByteBuffer,
        val overlapBitmapStream: ByteBuffer?
    ) {
        companion object {
            fun read(cGlyf: ByteBuffer): CompressedGlyphTable {
                val glyfStart = cGlyf.position()
                require(cGlyf.getUint16() == 0)
                val optionFlags = cGlyf.getUint16()
                val numGlyphs = cGlyf.getUint16()
                val indexFormat = cGlyf.getUint16()
                val nContourStreamSize = cGlyf.getUint32AsInt32Safe()
                val nPointsStreamSize = cGlyf.getUint32AsInt32Safe()
                val flagStreamSize = cGlyf.getUint32AsInt32Safe()
                val glyphStreamSize = cGlyf.getUint32AsInt32Safe()
                val compositeStreamSize = cGlyf.getUint32AsInt32Safe()
                val bboxStreamSize = cGlyf.getUint32AsInt32Safe()
                val instructionStreamSize = cGlyf.getUint32AsInt32Safe()

                var offset = 36 + glyfStart
                val nContourStream = cGlyf.duplicate().apply { position(offset) }
                offset += nContourStreamSize
                val nPointsStream = cGlyf.duplicate().apply { position(offset) }
                offset += nPointsStreamSize
                val flagStream = cGlyf.duplicate().apply { position(offset) }
                offset += flagStreamSize
                val glyphStream = cGlyf.duplicate().apply { position(offset) }
                offset += glyphStreamSize
                val compositeStream = cGlyf.duplicate().apply { position(offset) }
                offset += compositeStreamSize
                val bboxStream = cGlyf.duplicate().apply { position(offset) }
                offset += bboxStreamSize
                val instructionsStream = cGlyf.duplicate().apply { position(offset) }
                offset += instructionStreamSize
                val overlapBitmapStream = if ((optionFlags and 1) == 1) {
                    cGlyf.duplicate().apply { position(offset) }
                } else {
                    null
                }

                return CompressedGlyphTable(
                    numGlyphs, indexFormat, nContourStream, nPointsStream, flagStream,
                    glyphStream, compositeStream, bboxStream, instructionsStream, overlapBitmapStream)
            }
        }
    }

    private fun parseWoff2(woff: ByteBuffer): ByteArray {
        val header = WOFF2Header.read(woff)
        val collectionHeader = if (header.flavor == TAG_TTCF_HEADER) {
            CollectionHeader.read(woff)
        } else { null }

        // Uncompress table data
        val uncompressed = ByteArray(header.uncompLength)
        BrotliInputStream(ByteBufferInputStream(woff)).use {
            var writtenLen = 0
            while (true) {
                val len = it.read(uncompressed, writtenLen, header.uncompLength - writtenLen)
                if (len <= 0) {
                    break
                }
                writtenLen += len
            }
        }

        val tableStartOffset = collectionHeader?.ttcfHeaderSize() ?: header.sizeOfTableDirectory()

        // Reconstruct tables.
        var glyfTable: Table? = null
        var locaTable: Table? = null
        var headTable: Table? = null

        var realSize = tableStartOffset
        header.tables.forEach { table ->
            if (table.tag == TAG_GLYF_TABLE) {
                glyfTable = table
            } else if (table.tag == TAG_LOCA_TABLE) {
                locaTable = table
            } else if (table.tag == TAG_HEAD_TABLE) {
                headTable = table
            }

            table.dstOffset = realSize
            realSize += table.dstLength
            realSize = realSize.round4Up()
        }

        val otf = ByteArray(realSize)
        header.tables.forEach { table ->
            if (table.hasTransform) {
                require(table.tag == TAG_GLYF_TABLE || table.tag == TAG_LOCA_TABLE) {
                    "Only glyf/loca table transformation is supported."
                }
            } else {
                System.arraycopy(uncompressed, table.srcOffset, otf, table.dstOffset, table.srcLength)
            }
        }

        if (glyfTable != null) {
            reconstructGlyf(uncompressed, otf, requireNotNull(glyfTable), requireNotNull(locaTable))
        }

        header.tables.forEach { table ->
            table.checksum = calculateTableChecksum(ByteBuffer.wrap(otf), table)
        }

        if (collectionHeader == null) {
            val out = ByteBuffer.wrap(otf)
            out.putTag(header.flavor)
            val numTables = header.tables.size
            out.putUint16(numTables)
            val searchRange = (1 shl floor(log2(numTables.toDouble())).toInt()) * 16
            out.putUint16(searchRange)
            out.putUint16(floor(log2(numTables.toDouble())).toInt())
            out.putUint16((numTables * 16 - searchRange))
            header.tables.forEach { table ->
                out.putTag(table.tag)
                out.putUint32(table.checksum)
                out.putUint32( table.dstOffset)
                out.putUint32(table.dstLength)
            }
        } else {
            val out = ByteBuffer.wrap(otf)
            out.putTag(header.flavor)
            out.putUint16(collectionHeader.majorVersion)
            out.putUint16(collectionHeader.minorVersion)
            out.putUint32(collectionHeader.numFonts)

            var baseOffset = 12 + collectionHeader.dsigSize() + 4 * collectionHeader.numFonts
            collectionHeader.entries.forEach {
                out.putUint32(baseOffset)
                baseOffset += 12 + 16 * it.indices.size
            }

            if (collectionHeader.dsigSize() == 6) { // Write null DSIG tables
                out.putUint32(0)
                out.putUint32(0)
                out.putUint32(0)
            }

            collectionHeader.entries.forEach {
                out.putTag(it.flavor)
                val numTables = it.indices.size
                out.putUint16(numTables)
                val searchRange = (1 shl floor(log2(numTables.toDouble())).toInt()) * 16
                out.putUint16(searchRange)
                out.putUint16(floor(log2(numTables.toDouble())).toInt())
                out.putUint16((numTables * 16 - searchRange))
                it.indices.map { i -> header.tables[i] }.forEach { table ->
                    out.putTag(table.tag)
                    out.putUint32(table.checksum)
                    out.putUint32(table.dstOffset)
                    out.putUint32(table.dstLength)
                }
            }
        }
        updateHeaderChecksum(ByteBuffer.wrap(otf), otf.size, requireNotNull(headTable).dstOffset + 8)
        return otf
    }

    private fun signX(flag: Int) = if ((flag and 1) == 0) -1 else 1
    private fun signY(flag: Int) = if (((flag ushr 1)and 1) == 0) -1 else 1

    private fun decodeTripet(glyphStream: ByteBuffer, flagStream: ByteBuffer, nPoints: Int,
                             outXArray: IntArray, outYArray: IntArray, outFlagArray: BooleanArray) {
        var x = 0
        var y = 0
        for (i in 0..<nPoints) {
            val rawFlag = flagStream.getUint8()
            val onCurve = (rawFlag and 0x80) == 0
            val flag = rawFlag and 0x7f
            val dx: Int
            val dy: Int
            if (flag < 10) {
                val b0 = glyphStream.getUint8()
                dx = 0
                // Intentionally use signX for signing.
                dy = signX(flag) * (((flag and 14) shl 7) + b0)
            } else if (flag < 20) {
                val b0 = glyphStream.getUint8()
                dx = signX(flag) * ((((flag - 10) and 14) shl 7) + b0)
                dy = 0
            } else if (flag < 84) {
                val c = flag - 20
                val b0 = glyphStream.getUint8()
                dx = signX(flag) * ((1 + (c and 0x30) + (b0 ushr 4)))
                dy = signY(flag) * (1 + ((c and 0x0c) shl 2) + (b0 and 0x0f))
            } else if (flag < 120) {
                val c = flag - 84
                val b0 = glyphStream.getUint8()
                val b1 = glyphStream.getUint8()
                dx = signX(flag) * (1 + ((c / 12) shl 8) + b0)
                dy = signY(flag) * (1 + (((c % 12) ushr 2) shl 8) + b1)
            } else if (flag < 124) {
                val b0 = glyphStream.getUint8()
                val b1 = glyphStream.getUint8()
                val b2 = glyphStream.getUint8()
                dx = signX(flag) * ((b0 shl 4) + (b1 ushr 4))
                dy = signY(flag) * (((b1 and 0x0f) shl 8) + b2)
            } else {
                val b0 = glyphStream.getUint8()
                val b1 = glyphStream.getUint8()
                val b2 = glyphStream.getUint8()
                val b3 = glyphStream.getUint8()
                dx = signX(flag) * ((b0 shl 8) + b1)
                dy = signY(flag) * ((b2 shl 8) + b3)
            }

            x += dx
            y += dy
            outXArray[i] = x
            outYArray[i] = y
            outFlagArray[i] = onCurve
        }
    }

    private fun reconstructGlyf(uncompressed: ByteArray, otf: ByteArray, glyfTable: Table, locaTable: Table) {
        val cGlyfBuf = ByteBuffer.wrap(uncompressed).apply { order(ByteOrder.BIG_ENDIAN) }
        cGlyfBuf.position(glyfTable.srcOffset)

        val cGlyf = CompressedGlyphTable.read(cGlyfBuf)

        val locaOutBuffer = ByteBuffer.wrap(otf, locaTable.dstOffset, locaTable.dstLength)
        val glyfOutBuffer = ByteBuffer.wrap(otf, glyfTable.dstOffset, glyfTable.dstLength)

        val bboxStream = cGlyf.bboxStream
        val bboxStart = bboxStream.position()

        // Temporary buffer for reconstructing glyf table.
        val tmpBboxArray = ByteArray(8)
        var tmpXArray = IntArray(24)
        var tmpYArray = IntArray(24)
        var tmpFlagArray = BooleanArray(24)
        var tmpContourEndPoints = IntArray(10)
        var tmpInstructionArray = ByteArray(256)
        var tmpCompArray = ByteArray(256)

        val loca32bit = (cGlyf.indexFormat == 1)
        val glyfStartOffset = glyfOutBuffer.position()

        // Move bbox stream offset to the begining of the bbox. The hasBbox access is done by absolute index.
        bboxStream.position(bboxStream.position() + (((cGlyf.numGlyphs + 31) ushr 5) shl 2))

        for (i in 0..<cGlyf.numGlyphs) {
            val loca = glyfOutBuffer.position() - glyfStartOffset
            if (loca32bit) {
                locaOutBuffer.putUint32(loca)
            } else {
                locaOutBuffer.putUint16(loca / 2)
            }

            val hasBbox = (bboxStream.getUint8(bboxStart + (i ushr 3)) and (0x80 ushr (i and 7))) != 0
            val nContours = cGlyf.nContourStream.getInt16()
            if (nContours == 0) {  // Empty glyph
                // do nothing
            } else if (nContours == -1) {  // Composite glyph
                var flag = 0
                var hasInstruction = false
                val compositeStart = cGlyf.compositeStream.position()
                do {
                    flag = cGlyf.compositeStream.getUint16()  // Read it again, so don't move the position
                    hasInstruction = hasInstruction or ((flag and FLAG_WE_HAVE_INSTRUCTIONS) != 0)

                    var size = 2
                    size += if ((flag and FLAG_ARG_1_AND_2_ARE_WORDS) != 0) { 4 } else { 2 }
                    size += if ((flag and FLAG_WE_HAVE_A_SCALE) != 0) { 2 }
                    else if ((flag and FLAG_WE_HAVE_AN_X_AND_Y_SCALE) != 0) { 4 }
                    else if ((flag and FLAG_WE_HAVE_A_TWO_BY_TWO) != 0) { 8 }
                    else { 0 }

                    cGlyf.compositeStream.position(cGlyf.compositeStream.position() + size)
                } while ((flag and FLAG_MORE_COMPONENTS) != 0)
                val compositeLength = cGlyf.compositeStream.position() - compositeStart
                tmpCompArray = tmpCompArray.ensureCapacity(compositeLength)
                cGlyf.compositeStream.position(compositeStart)
                cGlyf.compositeStream.get(tmpCompArray, 0, compositeLength)

                var instSize = -1
                if (hasInstruction) {
                    instSize = cGlyf.glyphStream.get255Uint16()
                    tmpInstructionArray = tmpInstructionArray.ensureCapacity(instSize)
                    cGlyf.instructionStream.get(tmpInstructionArray, 0, instSize)
                }

                glyfOutBuffer.putUint16(0xffff)
                require(hasBbox)
                cGlyf.bboxStream.get(tmpBboxArray)
                glyfOutBuffer.put(tmpBboxArray)
                glyfOutBuffer.put(tmpCompArray, 0, compositeLength)
                if (hasInstruction) {
                    glyfOutBuffer.putUint16(instSize)
                    glyfOutBuffer.put(tmpInstructionArray, 0, instSize)
                }

                // WOFF2 reference impl pads perf glyph bounds for 4 byte alignment
                if ((glyfTable.dstOffset + glyfOutBuffer.position()) % 4 != 0) {
                    val pos = glyfOutBuffer.position() + glyfTable.dstOffset
                    glyfOutBuffer.position(((pos + 3) and 0x03.inv()) - glyfTable.dstOffset)
                }

            } else {  // Regular glyph
                tmpContourEndPoints = tmpContourEndPoints.ensureCapacity(nContours)
                var totalPoints = -1
                for (contours in 0..<nContours) {
                    val pos = cGlyf.nPointsStream.get255Uint16()
                    totalPoints += pos
                    tmpContourEndPoints[contours] = totalPoints
                }
                totalPoints += 1
                tmpXArray = tmpXArray.ensureCapacity(totalPoints)
                tmpYArray = tmpYArray.ensureCapacity(totalPoints)
                tmpFlagArray = tmpFlagArray.ensureCapacity(totalPoints)

                decodeTripet(cGlyf.glyphStream, cGlyf.flagStream, totalPoints, tmpXArray, tmpYArray, tmpFlagArray)

                glyfOutBuffer.putUint16(nContours)
                if (hasBbox) {
                    cGlyf.bboxStream.get(tmpBboxArray)
                    glyfOutBuffer.put(tmpBboxArray)
                } else {
                    glyfOutBuffer.putInt16(tmpXArray.min(totalPoints))
                    glyfOutBuffer.putInt16(tmpYArray.min(totalPoints))
                    glyfOutBuffer.putInt16(tmpXArray.max(totalPoints))
                    glyfOutBuffer.putInt16(tmpYArray.max(totalPoints))
                }
                for (endPtsIndex in 0..<nContours) {
                    glyfOutBuffer.putInt16(tmpContourEndPoints[endPtsIndex])
                }
                val instSize = cGlyf.glyphStream.get255Uint16()
                tmpInstructionArray = tmpInstructionArray.ensureCapacity(instSize)
                cGlyf.instructionStream.get(tmpInstructionArray, 0, instSize)
                glyfOutBuffer.putUint16(instSize)
                glyfOutBuffer.put(tmpInstructionArray, 0, instSize)

                val hasOverlapBitmap = cGlyf.overlapBitmapStream?.let {
                    it.getUint8(it.position() + (i ushr 3)) and (0x80 ushr (i and 7)) != 0
                } ?: false

                writePoints(totalPoints, tmpXArray, tmpYArray, tmpFlagArray, hasOverlapBitmap, glyfOutBuffer)

                // WOFF2 reference impl pads perf glyph bounds for 4 byte alignment
                if ((glyfTable.dstOffset + glyfOutBuffer.position()) % 4 != 0) {
                    val aligned = (glyfOutBuffer.position() + glyfTable.dstOffset).round4Up()
                    glyfOutBuffer.position(aligned - glyfTable.dstOffset)
                }
            }
        }

        // Write loca entry
        val loca = glyfOutBuffer.position() - glyfStartOffset
        if (loca32bit) {
            locaOutBuffer.putUint32(loca)
        } else {
            locaOutBuffer.putUint16(loca / 2)
        }
    }

    private fun writePoints(pointCount: Int, xArray: IntArray, yArray: IntArray, flagArray: BooleanArray,
                            hasOverlapBitmap: Boolean, out: ByteBuffer) {
        var lastX = 0
        var lastY = 0
        var lastFlag = -1
        var repeatCount = 0
        var flagOffset = out.position()
        var flagBytes = 0
        var xBytes = 0
        var yBytes = 0
        for (i in 0 until pointCount) {
            val x = xArray[i]
            val y = yArray[i]
            var flag = if (flagArray[i]) { GLYPH_ON_CURVE } else { 0 }
            if (hasOverlapBitmap && i == 0) {
                flag = flag or GLYPH_OVERLAP_SIMPLE
            }

            val dx = x - lastX
            val dy = y - lastY

            if (dx == 0) {
                flag = flag or GLYPH_THIS_X_IS_SAME
            } else if (dx > -256 && dx < 256) {
                flag = flag or GLYPH_X_SHORT
                flag = flag or if (dx > 0) { GLYPH_THIS_X_IS_SAME } else { 0 }
                xBytes ++
            } else {
                xBytes += 2
            }

            if (dy == 0) {
                flag = flag or GLYPH_THIS_Y_IS_SAME
            } else if (dy > -256 && dy < 256) {
                flag = flag or GLYPH_Y_SHORT
                flag = flag or if (dy > 0) { GLYPH_THIS_Y_IS_SAME } else { 0 }
                yBytes ++
            } else {
                yBytes += 2
            }

            if (flag == lastFlag && repeatCount != 255) {
                val newFlag = out.getUint8(flagOffset - 1) or GLYPH_REPEAT
                out.putUint8(flagOffset - 1, newFlag)
                repeatCount ++
            } else {
                if (repeatCount != 0) {
                    out.putUint8(flagOffset++, repeatCount)
                    flagBytes++
                }
                out.putUint8(flagOffset++, flag)
                flagBytes++
                repeatCount = 0
            }
            lastX = x
            lastY = y
            lastFlag = flag
        }
        if (repeatCount != 0) {
            out.putUint8(flagOffset++, repeatCount)
            flagBytes++
        }

        var xOffset = flagOffset
        var yOffset = xOffset + xBytes

        lastX = 0
        lastY = 0

        for (i in 0 until pointCount) {
            val x = xArray[i]
            val y = yArray[i]

            val dx = x - lastX
            val dy = y - lastY

            if (dx == 0) {
                // do nothing
            } else if (dx > -256 && dx < 256) {
                out.putUint8(xOffset++, abs(dx))
            } else {
                out.putUint16(xOffset, dx)
                xOffset += 2
            }
            lastX += dx

            if (dy == 0) {
                // do nothing
            } else if (dy > -256 && dy < 256) {
                out.putUint8(yOffset++, abs(dy))
            } else {
                out.putUint16(yOffset, dy)
                yOffset += 2
            }
            lastY += dy
        }
        out.position(out.position() + flagBytes + xBytes + yBytes)
    }

    private fun calculateTableChecksum(otf: ByteBuffer, table: Table): Long {
        if (table.tag == TAG_HEAD_TABLE) {
            otf.putUint32(table.dstOffset + 8, 0)
        }

        var checksum = 0L
        val alignedLength = table.dstLength and 3.inv()
        for (i in 0..<alignedLength / 4) {
            checksum += (otf.getUint32(table.dstOffset + i * 4)) and 0xFFFFFFFF
        }
        var lastValue = 0L
        for (i in alignedLength..<table.dstLength) {
            lastValue = lastValue or (otf.getUint8(table.dstOffset + i).toLong() shl (24 - 8 * (i and 3)))
        }
        checksum += lastValue
        return checksum and 0xFFFF_FFFFL
    }

    private fun updateHeaderChecksum(otf: ByteBuffer, size: Int, checksumOffset: Int) {
        var checksum = 0L
        val alignedLength = size and 3.inv()
        for (i in 0..<alignedLength / 4) {
            checksum += otf.getUint32(i * 4)
        }
        var lastValue = 0L
        for (i in alignedLength..<size) {
            lastValue = lastValue or (otf.getUint8(i).toLong() shl (24 - 8 * (i and 3)))
        }
        checksum += lastValue
        otf.putUint32(checksumOffset, (0xb1b0afba - (checksum and 0xFFFFFFFF)))
    }
}

// Array helper
private fun ByteArray.ensureCapacity(request: Int) = if (size > request) { this } else { ByteArray(request * 2) }
private fun IntArray.ensureCapacity(request: Int) = if (size > request) { this } else { IntArray(request * 2) }
private fun BooleanArray.ensureCapacity(request: Int) = if (size > request) { this } else { BooleanArray(request * 2) }
private fun IntArray.min(size: Int): Int {
    var r = this[0]
    for (i in 1..<size) {
        r = min(r, this[i])
    }
    return r
}
private fun IntArray.max(size: Int): Int {
    var r = this[0]
    for (i in 1..<size) {
        r = max(r, this[i])
    }
    return r
}

// Buffer accessor
private fun ByteBuffer.getUint8() = get().toInt() and 0xFF
private fun ByteBuffer.getUint8(i: Int) = get(i).toInt() and 0xFF
private fun ByteBuffer.getInt16() = getShort().toInt()
private fun ByteBuffer.getUint16() = getShort().toInt() and 0xFFFF
private fun ByteBuffer.getUint16(i: Int) = getShort(i).toInt() and 0xFFFF
private fun ByteBuffer.getUint32(i: Int) = getInt(i).toLong() and 0xFFFF_FFFF
private fun ByteBuffer.getUint32AsInt32Safe() = getInt().also { require(it in 0..Int.MAX_VALUE) }
private fun ByteBuffer.getUint32AsInt32Safe(i: Int) = getInt(i).also { require(it in 0..Int.MAX_VALUE) }
private fun ByteBuffer.getTag() = getInt()
private fun ByteBuffer.getTag(i: Int) = getInt(i)
private fun ByteBuffer.get255Uint16() : Int {
    val c = getUint8()
    return when (c) {
        253 -> getUint16()
        254 -> getUint8() + 253 * 2
        255 -> getUint8() + 253
        else -> c
    }
}
private fun ByteBuffer.getUintBase128(): Int {
    var r = 0
    for (i in 0..4) {
        val b = getUint8()
        r = (r shl 7) or (b and 0x7f)
        if ((b and 0x80) == 0) {
            return r
        }
    }
    require(false) { "Not reached here." }
    return -1
}

private fun ByteBuffer.putUint8(i: Int, value: Int) = put(i, (value and 0xFF).toByte())
private fun ByteBuffer.putUint16(value: Int) = putShort((value and 0xFFFF).toShort())
private fun ByteBuffer.putUint16(i: Int, value: Int) = putShort(i, (value and 0xFFFF).toShort())
private fun ByteBuffer.putInt16(value: Int) {
    require(value in Short.MIN_VALUE..Short.MAX_VALUE)
    putShort(value.toShort())
}
private fun ByteBuffer.putUint32(value: Long) = putInt((value and 0xFFFFFFFFL).toInt())
private fun ByteBuffer.putUint32(i: Int, value: Long) = putInt(i, (value and 0xFFFFFFFFL).toInt())
private fun ByteBuffer.putUint32(value: Int) {
    require(value >= 0)
    putInt(value)
}
private fun ByteBuffer.putUint32(i: Int, value: Int) {
    require(value >= 0)
    putInt(i, value)
}
private fun ByteBuffer.putTag(value: Int) = putInt(value)
private fun ByteBuffer.putTag(i: Int, value: Int) = putInt(i, value)

private fun Int.round4Up() = (this + 3) and 3.inv()