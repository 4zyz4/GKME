package com.zyz4.gkme.web

import java.nio.charset.StandardCharsets

/** QR 码纠错等级。 */
enum class QrEcc { LOW, MEDIUM, QUARTILE, HIGH }

/**
 * 极简、零依赖的 QR Code (model 2) 编码器，输出方形模块矩阵；支持 byte 模式与
 * 1..40 版本，自动选择版本与掩码。渲染由调用方负责。
 *
 * 算法移植自 Project Nayuki 的参考实现
 * (https://www.nayuki.io/page/qr-code-generator-library，MIT License)，
 * 与电脑端 `Core/QrCode.cs` 保持一致。
 */
class QrCode private constructor(
    val version: Int,
    val errorCorrectionLevel: QrEcc,
    dataCodewords: ByteArray,
    requestedMask: Int,
) {
    val size: Int = version * 4 + 17
    val mask: Int

    private val modules = Array(size) { BooleanArray(size) }
    private val isFunction = Array(size) { BooleanArray(size) }

    init {
        require(version in MIN_VERSION..MAX_VERSION) { "版本越界" }
        require(dataCodewords.size == getNumDataCodewords(version, errorCorrectionLevel)) {
            "数据长度与版本不符"
        }
        require(requestedMask in -1..7) { "掩码越界" }

        drawFunctionPatterns()
        val allCodewords = addEccAndInterleave(dataCodewords)
        drawCodewords(allCodewords)

        var m = requestedMask
        if (m == -1) {
            var bestMask = 0
            var minPenalty = Int.MAX_VALUE
            for (i in 0 until 8) {
                applyMask(i)
                drawFormatBits(i)
                val penalty = getPenaltyScore()
                if (penalty < minPenalty) {
                    bestMask = i
                    minPenalty = penalty
                }
                applyMask(i) // Undo
            }
            m = bestMask
        }

        mask = m
        applyMask(mask)
        drawFormatBits(mask)
    }

    fun getModule(x: Int, y: Int): Boolean {
        if (x < 0 || x >= size || y < 0 || y >= size) return false
        return modules[y][x]
    }

    // ────────────────────────── Capacity helpers ──────────────────────────

    private fun getNumRawDataModules(): Int {
        var result = (16 * version + 128) * version + 64
        if (version >= 2) {
            val numAlign = version / 7 + 2
            result -= (25 * numAlign - 10) * numAlign - 55
            if (version >= 7) result -= 36
        }
        return result
    }

    // ────────────────────────── Function patterns ──────────────────────────

    private fun drawFunctionPatterns() {
        for (i in 0 until size) {
            setFunctionModule(6, i, i % 2 == 0)
            setFunctionModule(i, 6, i % 2 == 0)
        }

        drawFinderPattern(3, 3)
        drawFinderPattern(size - 4, 3)
        drawFinderPattern(3, size - 4)

        val alignPatPos = getAlignmentPatternPositions()
        val numAlign = alignPatPos.size
        for (i in 0 until numAlign) {
            for (j in 0 until numAlign) {
                if (!((i == 0 && j == 0) || (i == 0 && j == numAlign - 1) || (i == numAlign - 1 && j == 0))) {
                    drawAlignmentPattern(alignPatPos[i], alignPatPos[j])
                }
            }
        }

        drawFormatBits(0)
        drawVersion()
    }

    private fun drawFinderPattern(x: Int, y: Int) {
        for (dy in -4..4) {
            for (dx in -4..4) {
                val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until size && yy in 0 until size) {
                    setFunctionModule(xx, yy, dist != 2 && dist != 4)
                }
            }
        }
    }

    private fun drawAlignmentPattern(x: Int, y: Int) {
        for (dy in -2..2) {
            for (dx in -2..2) {
                setFunctionModule(x + dx, y + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
            }
        }
    }

    private fun getAlignmentPatternPositions(): IntArray {
        if (version == 1) return IntArray(0)
        val numAlign = version / 7 + 2
        val step = if (version == 32) {
            26
        } else {
            (version * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
        }
        val result = IntArray(numAlign)
        result[0] = 6
        var i = numAlign - 1
        var pos = size - 7
        while (i >= 1) {
            result[i] = pos
            i--
            pos -= step
        }
        return result
    }

    private fun setFunctionModule(x: Int, y: Int, isDark: Boolean) {
        modules[y][x] = isDark
        isFunction[y][x] = true
    }

    // ────────────────────────── Format & version info ──────────────────────────

    private fun drawFormatBits(mask: Int) {
        val data = formatBits(errorCorrectionLevel) shl 3 or mask
        var rem = data
        for (i in 0 until 10) rem = (rem shl 1) xor ((rem shr 9) * 0x537)
        val bits = (data shl 10 or rem) xor 0x5412

        for (i in 0..5) setFunctionModule(8, i, getBit(bits, i))
        setFunctionModule(8, 7, getBit(bits, 6))
        setFunctionModule(8, 8, getBit(bits, 7))
        setFunctionModule(7, 8, getBit(bits, 8))
        for (i in 9 until 15) setFunctionModule(14 - i, 8, getBit(bits, i))

        for (i in 0 until 8) setFunctionModule(size - 1 - i, 8, getBit(bits, i))
        for (i in 8 until 15) setFunctionModule(8, size - 15 + i, getBit(bits, i))
        setFunctionModule(8, size - 8, true)
    }

    private fun drawVersion() {
        if (version < 7) return
        var rem = version
        for (i in 0 until 12) rem = (rem shl 1) xor ((rem shr 11) * 0x1F25)
        val bits = version shl 12 or rem

        for (i in 0 until 18) {
            val color = getBit(bits, i)
            val a = size - 11 + i % 3
            val b = i / 3
            setFunctionModule(a, b, color)
            setFunctionModule(b, a, color)
        }
    }

    // ────────────────────────── Codeword placement ──────────────────────────

    private fun drawCodewords(data: ByteArray) {
        require(data.size == getNumRawDataModules() / 8) { "码字数量与版本不符" }

        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0 until 2) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x] && i < data.size * 8) {
                        val dark = getBit(data[i ushr 3].toInt() and 0xFF, 7 - (i and 7))
                        modules[y][x] = dark
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask(mask: Int) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (x / 3 + y / 2) % 2 == 0
                    5 -> x * y % 2 + x * y % 3 == 0
                    6 -> (x * y % 2 + x * y % 3) % 2 == 0
                    7 -> ((x + y) % 2 + x * y % 3) % 2 == 0
                    else -> throw IllegalArgumentException("掩码越界")
                }
                if (!isFunction[y][x] && invert) modules[y][x] = !modules[y][x]
            }
        }
    }

    // ────────────────────────── Error correction ──────────────────────────

    private fun addEccAndInterleave(data: ByteArray): ByteArray {
        val numBlocks = NUM_ECC_BLOCKS[errorCorrectionLevel.ordinal][version]
        val blockEccLen = ECC_CODEWORDS_PER_BLOCK[errorCorrectionLevel.ordinal][version]
        val rawCodewords = getNumRawDataModules() / 8
        val numShortBlocks = numBlocks - rawCodewords % numBlocks
        val shortBlockLen = rawCodewords / numBlocks

        val blocks = ArrayList<IntArray>()
        val rsDiv = reedSolomonComputeDivisor(blockEccLen)
        var k = 0
        for (i in 0 until numBlocks) {
            val datLen = shortBlockLen - blockEccLen + (if (i < numShortBlocks) 0 else 1)
            val dat = IntArray(datLen)
            for (t in 0 until datLen) dat[t] = data[k + t].toInt() and 0xFF
            k += datLen
            val ecc = reedSolomonComputeRemainder(dat, rsDiv)
            val datArr = if (i < numShortBlocks) {
                IntArray(datLen + 1) { if (it < datLen) dat[it] else 0 }
            } else {
                dat
            }
            blocks.add(datArr + ecc)
        }

        val result = ByteArray(rawCodewords)
        var idx = 0
        val blockLen = blocks[0].size
        for (i in 0 until blockLen) {
            for (j in blocks.indices) {
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) {
                    result[idx++] = blocks[j][i].toByte()
                }
            }
        }
        return result
    }

    private fun reedSolomonComputeDivisor(degree: Int): IntArray {
        val result = IntArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = reedSolomonMultiply(result[j], root)
                if (j + 1 < degree) result[j] = result[j] xor result[j + 1]
            }
            root = reedSolomonMultiply(root, 0x02)
        }
        return result
    }

    private fun reedSolomonComputeRemainder(data: IntArray, divisor: IntArray): IntArray {
        val result = IntArray(divisor.size)
        for (b in data) {
            val factor = b xor result[0]
            System.arraycopy(result, 1, result, 0, result.size - 1)
            result[result.size - 1] = 0
            for (i in result.indices) result[i] = result[i] xor reedSolomonMultiply(divisor[i], factor)
        }
        return result
    }

    private fun reedSolomonMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z shr 7) * 0x11D)
            z = z xor (((y shr i) and 1) * x)
        }
        return z and 0xFF
    }

    // ────────────────────────── Penalty scoring ──────────────────────────

    private fun getPenaltyScore(): Int {
        var result = 0

        for (y in 0 until size) {
            var runColor = false
            var runX = 0
            val runHistory = IntArray(7)
            for (x in 0 until size) {
                if (modules[y][x] == runColor) {
                    runX++
                    if (runX == 5) result += PENALTY_N1 else if (runX > 5) result++
                } else {
                    finderPenaltyAddHistory(runX, runHistory)
                    if (!runColor) result += finderPenaltyCountPatterns(runHistory) * PENALTY_N3
                    runColor = modules[y][x]
                    runX = 1
                }
            }
            result += finderPenaltyTerminateAndCount(runColor, runX, runHistory) * PENALTY_N3
        }

        for (x in 0 until size) {
            var runColor = false
            var runY = 0
            val runHistory = IntArray(7)
            for (y in 0 until size) {
                if (modules[y][x] == runColor) {
                    runY++
                    if (runY == 5) result += PENALTY_N1 else if (runY > 5) result++
                } else {
                    finderPenaltyAddHistory(runY, runHistory)
                    if (!runColor) result += finderPenaltyCountPatterns(runHistory) * PENALTY_N3
                    runColor = modules[y][x]
                    runY = 1
                }
            }
            result += finderPenaltyTerminateAndCount(runColor, runY, runHistory) * PENALTY_N3
        }

        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val color = modules[y][x]
                if (color == modules[y][x + 1] && color == modules[y + 1][x] && color == modules[y + 1][x + 1]) {
                    result += PENALTY_N2
                }
            }
        }

        var dark = 0
        for (row in modules) for (color in row) if (color) dark++
        val total = size * size
        val k = (kotlin.math.abs(dark * 20 - total * 10) + total - 1) / total - 1
        result += k * PENALTY_N4

        return result
    }

    private fun finderPenaltyCountPatterns(runHistory: IntArray): Int {
        val n = runHistory[1]
        val core = n > 0 && runHistory[2] == n && runHistory[3] == n * 3 &&
            runHistory[4] == n && runHistory[5] == n
        return (if (core && runHistory[0] >= n * 4 && runHistory[6] >= n) 1 else 0) +
            (if (core && runHistory[6] >= n * 4 && runHistory[0] >= n) 1 else 0)
    }

    private fun finderPenaltyTerminateAndCount(currentRunColor: Boolean, currentRunLengthIn: Int, runHistory: IntArray): Int {
        var currentRunLength = currentRunLengthIn
        if (currentRunColor) {
            finderPenaltyAddHistory(currentRunLength, runHistory)
            currentRunLength = 0
        }
        currentRunLength += size
        finderPenaltyAddHistory(currentRunLength, runHistory)
        return finderPenaltyCountPatterns(runHistory)
    }

    private fun finderPenaltyAddHistory(currentRunLengthIn: Int, runHistory: IntArray) {
        var currentRunLength = currentRunLengthIn
        if (runHistory[0] == 0) currentRunLength += size
        System.arraycopy(runHistory, 0, runHistory, 1, runHistory.size - 1)
        runHistory[0] = currentRunLength
    }

    // ────────────────────────── Bit buffer ──────────────────────────

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val length: Int get() = bits.size

        fun appendBits(value: Int, len: Int) {
            for (i in len - 1 downTo 0) bits.add((value ushr i) and 1 != 0)
        }

        fun toBytes(): ByteArray {
            val bytes = ByteArray(bits.size / 8)
            for (i in bits.indices) {
                if (bits[i]) bytes[i ushr 3] = (bytes[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
            }
            return bytes
        }
    }

    companion object {
        const val MIN_VERSION = 1
        const val MAX_VERSION = 40

        private const val PENALTY_N1 = 3
        private const val PENALTY_N2 = 3
        private const val PENALTY_N3 = 40
        private const val PENALTY_N4 = 10

        // 按索引顺序排列；索引 0 为占位，使版本号与下标对齐。
        private val ECC_CODEWORDS_PER_BLOCK = arrayOf(
            intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
            intArrayOf(-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
        )

        private val NUM_ECC_BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
            intArrayOf(-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68),
            intArrayOf(-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81),
        )

        /** 以 UTF-8 byte 模式编码 [text]，使用能容纳的最小版本。 */
        fun encodeText(text: String, ecl: QrEcc): QrCode {
            val data = text.toByteArray(StandardCharsets.UTF_8)

            var version = MIN_VERSION
            while (version <= MAX_VERSION) {
                val capacityBits = getNumDataCodewords(version, ecl) * 8
                val usedBits = 4 + byteModeCharCountBits(version) + data.size * 8
                if (usedBits <= capacityBits) break
                version++
            }
            require(version <= MAX_VERSION) { "数据过长，无法生成二维码" }

            val bb = BitBuffer()
            bb.appendBits(0x4, 4) // Byte 模式标识
            bb.appendBits(data.size, byteModeCharCountBits(version))
            for (b in data) bb.appendBits(b.toInt() and 0xFF, 8)

            val dataCapacityBits = getNumDataCodewords(version, ecl) * 8
            bb.appendBits(0, minOf(4, dataCapacityBits - bb.length)) // 终止符
            bb.appendBits(0, (8 - bb.length % 8) % 8) // 字节对齐
            var padByte = 0xEC
            while (bb.length < dataCapacityBits) {
                bb.appendBits(padByte, 8)
                padByte = padByte xor (0xEC xor 0x11)
            }

            return QrCode(version, ecl, bb.toBytes(), -1)
        }

        private fun getNumRawDataModules(ver: Int): Int {
            var result = (16 * ver + 128) * ver + 64
            if (ver >= 2) {
                val numAlign = ver / 7 + 2
                result -= (25 * numAlign - 10) * numAlign - 55
                if (ver >= 7) result -= 36
            }
            return result
        }

        private fun getNumDataCodewords(ver: Int, ecl: QrEcc): Int =
            getNumRawDataModules(ver) / 8 -
                ECC_CODEWORDS_PER_BLOCK[ecl.ordinal][ver] * NUM_ECC_BLOCKS[ecl.ordinal][ver]

        private fun byteModeCharCountBits(version: Int): Int = if (version <= 9) 8 else 16

        // ISO/IEC 18004 格式信息纠错字段：L=01, M=00, Q=11, H=10。
        private fun formatBits(ecl: QrEcc): Int = when (ecl) {
            QrEcc.LOW -> 1
            QrEcc.MEDIUM -> 0
            QrEcc.QUARTILE -> 3
            QrEcc.HIGH -> 2
        }

        private fun getBit(x: Int, i: Int): Boolean = ((x shr i) and 1) != 0
    }
}
