package dev.bekelaray.bbai.core.parser

import dev.bekelaray.bbai.core.io.RandomAccessReader
import dev.bekelaray.bbai.core.io.SliceReadSource
import dev.bekelaray.bbai.core.io.ascii
import dev.bekelaray.bbai.core.io.hex
import dev.bekelaray.bbai.core.io.leInt
import dev.bekelaray.bbai.core.io.leLong
import dev.bekelaray.bbai.core.io.leUShort
import dev.bekelaray.bbai.core.io.readExactAt
import dev.bekelaray.bbai.core.model.DetectionResult
import dev.bekelaray.bbai.core.model.InspectionResult
import dev.bekelaray.bbai.core.model.MetadataField
import dev.bekelaray.bbai.core.model.SupportStatus
import dev.bekelaray.bbai.core.model.SwitchFileKind
import dev.bekelaray.bbai.core.model.VirtualNode
import kotlin.math.min

class SwitchInspector(
    private val keyProvider: LawfulKeyProvider? = null,
) {
    private companion object {
        const val RomFsHeaderSize = 0x50
        const val RomFsEntryEmpty = 0xFFFF_FFFF.toInt()
        const val MaxRomFsTableBytes = 32L * 1024L * 1024L
    }

    suspend fun inspect(displayName: String, reader: RandomAccessReader): InspectionResult {
        val detection = detect(displayName, reader)
        return when (detection.kind) {
            SwitchFileKind.NSP,
            SwitchFileKind.PFS0,
            -> inspectPartitionFs(displayName, reader, baseOffset = 0L, isHfs0 = false)
            SwitchFileKind.XCI -> inspectXci(displayName, reader)
            SwitchFileKind.HFS0 -> inspectPartitionFs(displayName, reader, baseOffset = 0L, isHfs0 = true)
            SwitchFileKind.EXEFS -> inspectExeFs(displayName, reader)
            SwitchFileKind.ROMFS -> inspectRomFs(displayName, reader)
            SwitchFileKind.CNMT -> inspectCnmt(displayName, reader)
            SwitchFileKind.NACP -> inspectNacp(displayName, reader)
            SwitchFileKind.NPDM -> inspectNpdm(displayName, reader)
            SwitchFileKind.NRO -> inspectNro(displayName, reader)
            SwitchFileKind.NSO -> inspectNso(displayName, reader)
            SwitchFileKind.KIP -> inspectKip(displayName, reader)
            SwitchFileKind.NCA,
            SwitchFileKind.NCZ,
            -> encryptedOrDeferred(displayName, reader.size, detection)
            else -> InspectionResult(
                displayName = displayName,
                size = reader.size,
                detection = detection,
                supportStatus = if (detection.kind == SwitchFileKind.UNKNOWN) SupportStatus.UNSUPPORTED else SupportStatus.SUPPORTED,
                metadata = listOf(
                    MetadataField("Detected type", detection.kind.name),
                    MetadataField("Size", reader.size.toString()),
                ),
                warnings = detection.notes,
            )
        }
    }

    private suspend fun inspectXci(displayName: String, reader: RandomAccessReader): InspectionResult {
        val header = reader.readExactAt(0x100, min(reader.size - 0x100, 0x200).toInt())
        val rootOffset = 0x10000L
        val rootMagic = if (reader.size >= rootOffset + 4) reader.readExactAt(rootOffset, 4).decodeToString() else ""
        val metadata = buildList {
            add(MetadataField("Detected type", "XCI"))
            add(MetadataField("Header magic", if (header.size >= 4) header.ascii(0, 4) else ""))
            add(MetadataField("Root partition offset", "0x${rootOffset.toString(16)}"))
            add(MetadataField("Size", reader.size.toString()))
        }
        return if (rootMagic == "HFS0") {
            val nested = inspectPartitionFs(displayName, SliceReadSource(reader, rootOffset, reader.size - rootOffset), rootOffset, isHfs0 = true)
            nested.copy(
                detection = DetectionResult(SwitchFileKind.XCI, notes = listOf("Root HFS0 discovered at 0x10000.")),
                metadata = metadata + nested.metadata.drop(1),
            )
        } else {
            InspectionResult(
                displayName = displayName,
                size = reader.size,
                detection = DetectionResult(SwitchFileKind.XCI),
                supportStatus = SupportStatus.UNSUPPORTED,
                metadata = metadata,
                warnings = listOf("No readable root HFS0 found at 0x10000."),
            )
        }
    }

    private suspend fun inspectPartitionFs(
        displayName: String,
        reader: RandomAccessReader,
        baseOffset: Long,
        isHfs0: Boolean,
    ): InspectionResult {
        val header = reader.readExactAt(0, 0x10)
        val count = header.leInt(4)
        val stringTableSize = header.leInt(8)
        val entrySize = if (isHfs0) 0x40 else 0x18
        val headerSize = 0x10L + count.toLong() * entrySize + stringTableSize.toLong()
        if (count < 0 || stringTableSize < 0 || headerSize < 0 || headerSize > reader.size) {
            return invalid(displayName, reader.size, if (isHfs0) SwitchFileKind.HFS0 else SwitchFileKind.PFS0)
        }
        val flatEntries = mutableListOf<FlatEntry>()
        for (index in 0 until count) {
            val entry = reader.readExactAt(0x10L + index.toLong() * entrySize, entrySize)
            if (entry.size < entrySize) break
            val offset = entry.leLong(0)
            val size = entry.leLong(8)
            val stringOffset = entry.leInt(16)
            if (stringOffset < 0 || stringOffset >= stringTableSize) continue
            if (offset < 0 || size < 0 || headerSize + offset > reader.size || size > reader.size - headerSize - offset) continue
            val nameBytes = readNullTerminated(reader, 0x10L + count.toLong() * entrySize + stringOffset, 0x200)
            val name = sanitizeNodeName(nameBytes.decodeToString().ifBlank { "entry_$index" })
            val path = sanitizeRelativePath(name)
            flatEntries += FlatEntry(
                name = path.substringAfterLast('/'),
                path = path,
                offset = baseOffset + headerSize + offset,
                size = size,
                detection = detect(path, SliceReadSource(reader, headerSize + offset, size)),
            )
        }
        val kind = if (isHfs0) SwitchFileKind.HFS0 else if (displayName.lowercase().endsWith(".nsp")) SwitchFileKind.NSP else SwitchFileKind.PFS0
        return InspectionResult(
            displayName = displayName,
            size = reader.size,
            detection = DetectionResult(kind),
            supportStatus = SupportStatus.SUPPORTED,
            metadata = listOf(
                MetadataField("Detected type", kind.name),
                MetadataField("Entry count", count.toString()),
                MetadataField("String table size", stringTableSize.toString()),
                MetadataField("Header size", headerSize.toString()),
            ),
            entries = buildTree(flatEntries),
        )
    }

    private suspend fun inspectExeFs(displayName: String, reader: RandomAccessReader): InspectionResult {
        val header = reader.readExactAt(0, min(reader.size, 0x200).toInt())
        if (header.size < 0xC0) return invalid(displayName, reader.size, SwitchFileKind.EXEFS)
        val entries = mutableListOf<FlatEntry>()
        var nonEmptyCount = 0
        repeat(10) { index ->
            val entryOffset = index * 0x10
            val name = header.ascii(entryOffset, 0x8)
            val offset = header.leInt(entryOffset + 0x8).toUInt().toLong()
            val size = header.leInt(entryOffset + 0xC).toUInt().toLong()
            if (name.isBlank() || size == 0L) return@repeat
            if (offset > reader.size || size > reader.size || 0x200L + offset + size > reader.size) return@repeat
            nonEmptyCount += 1
            val fileName = normalizeExeFsName(name)
            val fileReader = SliceReadSource(reader, 0x200L + offset, size)
            entries += FlatEntry(
                name = fileName,
                path = fileName,
                offset = 0x200L + offset,
                size = size,
                detection = detect(fileName, fileReader),
            )
        }
        return InspectionResult(
            displayName = displayName,
            size = reader.size,
            detection = DetectionResult(SwitchFileKind.EXEFS),
            supportStatus = if (nonEmptyCount > 0) SupportStatus.SUPPORTED else SupportStatus.INVALID,
            metadata = listOf(
                MetadataField("Detected type", SwitchFileKind.EXEFS.name),
                MetadataField("Header size", "512"),
                MetadataField("Entry count", nonEmptyCount.toString()),
            ),
            entries = buildTree(entries),
            warnings = if (nonEmptyCount == 0) listOf("No readable ExeFS entries were found in the header.") else emptyList(),
        )
    }

    private suspend fun inspectRomFs(displayName: String, reader: RandomAccessReader): InspectionResult {
        val header = reader.readExactAt(0, min(reader.size, RomFsHeaderSize.toLong()).toInt())
        if (header.size < RomFsHeaderSize) return invalid(displayName, reader.size, SwitchFileKind.ROMFS)

        val headerSize = header.leLong(0)
        val dirHashOffset = header.leLong(0x08)
        val dirHashSize = header.leLong(0x10)
        val dirMetaOffset = header.leLong(0x18)
        val dirMetaSize = header.leLong(0x20)
        val fileHashOffset = header.leLong(0x28)
        val fileHashSize = header.leLong(0x30)
        val fileMetaOffset = header.leLong(0x38)
        val fileMetaSize = header.leLong(0x40)
        val dataOffset = header.leLong(0x48)

        val tables = listOf(
            dirHashOffset to dirHashSize,
            dirMetaOffset to dirMetaSize,
            fileHashOffset to fileHashSize,
            fileMetaOffset to fileMetaSize,
        )
        val invalidTable = tables.any { (offset, size) ->
            offset < 0 || size < 0 || size > MaxRomFsTableBytes || offset > reader.size || size > reader.size - offset
        }
        if (headerSize < RomFsHeaderSize || dataOffset < 0 || dataOffset > reader.size || invalidTable) {
            return invalid(displayName, reader.size, SwitchFileKind.ROMFS)
        }

        val directoryTable = reader.readExactAt(dirMetaOffset, dirMetaSize.toInt())
        val fileTable = reader.readExactAt(fileMetaOffset, fileMetaSize.toInt())
        if (directoryTable.size != dirMetaSize.toInt() || fileTable.size != fileMetaSize.toInt()) {
            return invalid(displayName, reader.size, SwitchFileKind.ROMFS)
        }

        val rootOffset = findRomFsRootDirectoryOffset(directoryTable)
        val rootEntry = parseRomFsDirectory(directoryTable, rootOffset) ?: return invalid(displayName, reader.size, SwitchFileKind.ROMFS)
        val rootChildren = buildList {
            addAll(readRomFsFiles(reader, fileTable, dataOffset, rootOffset, rootEntry.firstFileOffset, ""))
            addAll(readRomFsDirectories(reader, directoryTable, fileTable, dataOffset, rootOffset, rootEntry.childOffset, ""))
        }

        return InspectionResult(
            displayName = displayName,
            size = reader.size,
            detection = DetectionResult(SwitchFileKind.ROMFS),
            supportStatus = SupportStatus.SUPPORTED,
            metadata = listOf(
                MetadataField("Detected type", SwitchFileKind.ROMFS.name),
                MetadataField("Header size", headerSize.toString()),
                MetadataField("Directory hash table size", dirHashSize.toString()),
                MetadataField("Directory table size", dirMetaSize.toString()),
                MetadataField("File hash table size", fileHashSize.toString()),
                MetadataField("File table size", fileMetaSize.toString()),
                MetadataField("Root directory offset", "0x${rootOffset.toString(16)}"),
                MetadataField("Data offset", "0x${dataOffset.toString(16)}"),
            ),
            entries = rootChildren.sortedBy { it.name.lowercase() },
        )
    }

    private suspend fun inspectCnmt(displayName: String, reader: RandomAccessReader): InspectionResult {
        val header = reader.readExactAt(0, min(reader.size, 0x40).toInt())
        if (header.size < 0x20) return invalid(displayName, reader.size, SwitchFileKind.CNMT)
        return InspectionResult(
            displayName = displayName,
            size = reader.size,
            detection = DetectionResult(SwitchFileKind.CNMT),
            supportStatus = SupportStatus.SUPPORTED,
            metadata = listOf(
                MetadataField("Title ID", "%016X".format(header.leLong(0))),
                MetadataField("Version", header.leInt(8).toUInt().toString()),
                MetadataField("Meta type", header[0xC].toUByte().toString()),
                MetadataField("Extended header size", header.leUShort(0xE).toString()),
                MetadataField("Content count", header.leUShort(0x10).toString()),
                MetadataField("Meta count", header.leUShort(0x12).toString()),
                MetadataField("Attributes", "0x${header[0x14].toUByte().toString(16)}"),
                MetadataField("Required system version", header.leInt(0x18).toUInt().toString()),
            ),
        )
    }

    private suspend fun inspectNacp(displayName: String, reader: RandomAccessReader): InspectionResult {
        val header = reader.readExactAt(0, min(reader.size, 0x3210).toInt())
        if (header.size < 0x3070) return invalid(displayName, reader.size, SwitchFileKind.NACP)
        val languageNames = listOf("American English", "British English", "Japanese", "French", "German", "Latin American Spanish", "Spanish", "Italian", "Dutch", "Canadian French", "Portuguese", "Russian", "Korean", "Traditional Chinese", "Simplified Chinese", "Brazilian Portuguese")
        val firstFilled = languageNames.indices.firstOrNull { index ->
            header.ascii(index * 0x300, 0x200).isNotBlank()
        } ?: 0
        return InspectionResult(
            displayName = displayName,
            size = reader.size,
            detection = DetectionResult(SwitchFileKind.NACP),
            supportStatus = SupportStatus.SUPPORTED,
            metadata = listOf(
                MetadataField("Preferred language slot", languageNames.getOrElse(firstFilled) { firstFilled.toString() }),
                MetadataField("Title", header.ascii(firstFilled * 0x300, 0x200)),
                MetadataField("Publisher", header.ascii(firstFilled * 0x300 + 0x200, 0x100)),
                MetadataField("Display version", header.ascii(0x3060, 0x10)),
                MetadataField("Application ID", header.hex(0x3200, 8)),
            ),
        )
    }

    private suspend fun inspectNpdm(displayName: String, reader: RandomAccessReader): InspectionResult {
        val header = reader.readExactAt(0, min(reader.size, 0x80).toInt())
        if (header.size < 0x80) return invalid(displayName, reader.size, SwitchFileKind.NPDM)
        return InspectionResult(
            displayName = displayName,
            size = reader.size,
            detection = DetectionResult(SwitchFileKind.NPDM),
            supportStatus = SupportStatus.SUPPORTED,
            metadata = listOf(
                MetadataField("Magic", header.ascii(0, 4)),
                MetadataField("Version", header.leInt(0x18).toUInt().toString()),
                MetadataField("Main thread priority", header[0xE].toUByte().toString()),
                MetadataField("Main thread core", header[0xF].toUByte().toString()),
                MetadataField("Process name", header.ascii(0x20, 0x10)),
                MetadataField("Product code", header.ascii(0x30, 0x10)),
                MetadataField("ACI offset", "0x${header.leInt(0x70).toUInt().toString(16)}"),
                MetadataField("ACID offset", "0x${header.leInt(0x78).toUInt().toString(16)}"),
            ),
        )
    }

    private suspend fun inspectNro(displayName: String, reader: RandomAccessReader): InspectionResult {
        val header = reader.readExactAt(0, min(reader.size, 0xC0).toInt())
        if (header.size < 0x40) return invalid(displayName, reader.size, SwitchFileKind.NRO)
        val magicOffset = if (header.size >= 0x14 && header.ascii(0x10, 4) == "NRO0") 0x10 else 0
        return InspectionResult(
            displayName = displayName,
            size = reader.size,
            detection = DetectionResult(SwitchFileKind.NRO),
            supportStatus = SupportStatus.SUPPORTED,
            metadata = listOf(
                MetadataField("Header magic", header.ascii(magicOffset, 4)),
                MetadataField("MOD0 offset", if (header.size >= 8) "0x${header.leInt(4).toUInt().toString(16)}" else "unknown"),
                MetadataField("Image size", if (header.size >= magicOffset + 12) header.leInt(magicOffset + 8).toUInt().toString() else "unknown"),
                MetadataField("Build ID", if (header.size >= magicOffset + 0x60) header.hex(magicOffset + 0x40, 0x20) else "unavailable"),
                MetadataField("Status", "Header recognized; asset block and embedded RomFS remain placeholder-only in this build."),
            ),
        )
    }

    private suspend fun inspectNso(displayName: String, reader: RandomAccessReader): InspectionResult {
        val header = reader.readExactAt(0, min(reader.size, 0x100).toInt())
        if (header.size < 0x40) return invalid(displayName, reader.size, SwitchFileKind.NSO)
        return InspectionResult(
            displayName = displayName,
            size = reader.size,
            detection = DetectionResult(SwitchFileKind.NSO),
            supportStatus = SupportStatus.SUPPORTED,
            metadata = listOf(
                MetadataField("Magic", header.ascii(0, 4)),
                MetadataField("Flags", "0x${header.leInt(0xC).toUInt().toString(16)}"),
                MetadataField("Text offset", "0x${header.leInt(0x10).toUInt().toString(16)}"),
                MetadataField("Rodata offset", "0x${header.leInt(0x20).toUInt().toString(16)}"),
                MetadataField("Data offset", "0x${header.leInt(0x30).toUInt().toString(16)}"),
                MetadataField("Module ID", if (header.size >= 0x60) header.hex(0x40, 0x20) else "unavailable"),
            ),
        )
    }

    private suspend fun inspectKip(displayName: String, reader: RandomAccessReader): InspectionResult {
        val header = reader.readExactAt(0, min(reader.size, 0x40).toInt())
        if (header.size < 0x10) return invalid(displayName, reader.size, SwitchFileKind.KIP)
        return InspectionResult(
            displayName = displayName,
            size = reader.size,
            detection = DetectionResult(SwitchFileKind.KIP),
            supportStatus = SupportStatus.SUPPORTED,
            metadata = listOf(
                MetadataField("Magic", header.ascii(0, 4)),
                MetadataField("Header bytes", header.hex(0, min(header.size, 0x20))),
                MetadataField("Status", "KIP header identification is implemented; deeper segment decoding remains a placeholder."),
            ),
        )
    }

    private fun invalid(displayName: String, size: Long, kind: SwitchFileKind): InspectionResult = InspectionResult(
        displayName = displayName,
        size = size,
        detection = DetectionResult(kind),
        supportStatus = SupportStatus.INVALID,
        metadata = listOf(MetadataField("Status", "File is too small or malformed for this parser.")),
    )

    private fun unsupported(displayName: String, size: Long, detection: DetectionResult, message: String): InspectionResult = InspectionResult(
        displayName = displayName,
        size = size,
        detection = detection,
        supportStatus = SupportStatus.UNSUPPORTED,
        metadata = listOf(
            MetadataField("Detected type", detection.kind.name),
            MetadataField("Status", message),
        ),
    )

    private fun encryptedOrDeferred(displayName: String, size: Long, detection: DetectionResult): InspectionResult = InspectionResult(
        displayName = displayName,
        size = size,
        detection = detection,
        supportStatus = SupportStatus.ENCRYPTED_OR_KEYS_REQUIRED,
        metadata = listOf(
            MetadataField("Detected type", detection.kind.name),
            MetadataField("Status", "Encrypted content is not decoded in this build."),
            MetadataField("Lawful key provider", if (keyProvider == null) "Not configured" else "Configured but not yet used"),
        ),
        warnings = listOf("A future revision may query user-supplied lawful key material through an abstraction boundary without bundling keys."),
    )

    fun detectByName(displayName: String): DetectionResult {
        val lower = displayName.lowercase()
        return when {
            lower.endsWith(".nsp") -> DetectionResult(SwitchFileKind.NSP)
            lower.endsWith(".xci") -> DetectionResult(SwitchFileKind.XCI)
            lower.endsWith(".nca") -> DetectionResult(SwitchFileKind.NCA)
            lower.endsWith(".ncz") -> DetectionResult(SwitchFileKind.NCZ)
            lower.endsWith(".romfs") -> DetectionResult(SwitchFileKind.ROMFS)
            lower.endsWith(".exefs") -> DetectionResult(SwitchFileKind.EXEFS)
            lower.endsWith(".cnmt") || lower.contains(".cnmt.") -> DetectionResult(SwitchFileKind.CNMT)
            lower.endsWith(".nacp") -> DetectionResult(SwitchFileKind.NACP)
            lower.endsWith(".npdm") -> DetectionResult(SwitchFileKind.NPDM)
            lower.endsWith(".nro") -> DetectionResult(SwitchFileKind.NRO)
            lower.endsWith(".nso") -> DetectionResult(SwitchFileKind.NSO)
            lower.endsWith(".kip") || lower.endsWith(".kip1") -> DetectionResult(SwitchFileKind.KIP)
            lower.endsWith(".png") -> DetectionResult(SwitchFileKind.IMAGE, "image/png")
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> DetectionResult(SwitchFileKind.IMAGE, "image/jpeg")
            lower.endsWith(".webp") -> DetectionResult(SwitchFileKind.IMAGE, "image/webp")
            lower.endsWith(".mp3") -> DetectionResult(SwitchFileKind.AUDIO, "audio/mpeg")
            lower.endsWith(".wav") -> DetectionResult(SwitchFileKind.AUDIO, "audio/wav")
            lower.endsWith(".ogg") -> DetectionResult(SwitchFileKind.AUDIO, "audio/ogg")
            lower.endsWith(".flac") -> DetectionResult(SwitchFileKind.AUDIO, "audio/flac")
            lower.endsWith(".mp4") -> DetectionResult(SwitchFileKind.VIDEO, "video/mp4")
            lower.endsWith(".webm") -> DetectionResult(SwitchFileKind.VIDEO, "video/webm")
            else -> DetectionResult(SwitchFileKind.UNKNOWN)
        }
    }

    suspend fun detect(displayName: String, reader: RandomAccessReader): DetectionResult {
        val byName = detectByName(displayName)
        val lower = displayName.lowercase()
        val magic0 = reader.readExactAt(0, min(reader.size, 0x110).toInt())
        val magicAt0 = if (magic0.size >= 4) magic0.ascii(0, 4) else ""
        val magicAt10 = if (magic0.size >= 0x14) magic0.ascii(0x10, 4) else ""
        val magicAt100 = if (magic0.size >= 0x104) magic0.ascii(0x100, 4) else ""
        return when {
            lower.endsWith(".nsp") -> DetectionResult(SwitchFileKind.NSP)
            magicAt0 == "PFS0" -> DetectionResult(SwitchFileKind.PFS0)
            magicAt0 == "HFS0" -> DetectionResult(SwitchFileKind.HFS0)
            lower.endsWith(".xci") || magicAt100 == "HEAD" -> DetectionResult(SwitchFileKind.XCI)
            lower.endsWith(".nca") -> DetectionResult(SwitchFileKind.NCA)
            lower.endsWith(".ncz") -> DetectionResult(SwitchFileKind.NCZ)
            looksLikeRomFs(magic0) -> DetectionResult(SwitchFileKind.ROMFS)
            lower.endsWith(".exefs") || looksLikeExeFs(magic0) -> DetectionResult(SwitchFileKind.EXEFS)
            lower.endsWith(".cnmt") || lower.contains(".cnmt.") -> DetectionResult(SwitchFileKind.CNMT)
            lower.endsWith(".nacp") -> DetectionResult(SwitchFileKind.NACP)
            lower.endsWith(".npdm") || magicAt0 == "META" -> DetectionResult(SwitchFileKind.NPDM)
            lower.endsWith(".nro") || magicAt10 == "NRO0" -> DetectionResult(SwitchFileKind.NRO)
            lower.endsWith(".nso") || magicAt0 == "NSO0" -> DetectionResult(SwitchFileKind.NSO)
            lower.endsWith(".kip") || lower.endsWith(".kip1") || magicAt0 == "KIP1" -> DetectionResult(SwitchFileKind.KIP)
            isPng(magic0) -> DetectionResult(SwitchFileKind.IMAGE, "image/png")
            isJpeg(magic0) -> DetectionResult(SwitchFileKind.IMAGE, "image/jpeg")
            isWebp(magic0) -> DetectionResult(SwitchFileKind.IMAGE, "image/webp")
            isMp3(magic0, lower) -> DetectionResult(SwitchFileKind.AUDIO, "audio/mpeg")
            isWav(magic0) -> DetectionResult(SwitchFileKind.AUDIO, "audio/wav")
            isOgg(magic0) -> DetectionResult(SwitchFileKind.AUDIO, "audio/ogg")
            isFlac(magic0) -> DetectionResult(SwitchFileKind.AUDIO, "audio/flac")
            isMp4(magic0, lower) -> DetectionResult(SwitchFileKind.VIDEO, "video/mp4")
            isWebm(magic0, lower) -> DetectionResult(SwitchFileKind.VIDEO, "video/webm")
            else -> byName
        }
    }

    private fun isPng(bytes: ByteArray) = bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
    private fun isJpeg(bytes: ByteArray) = bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
    private fun isWebp(bytes: ByteArray) = bytes.size >= 12 && bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WEBP"
    private fun isMp3(bytes: ByteArray, lower: String) = lower.endsWith(".mp3") || (bytes.size >= 3 && bytes.ascii(0, 3) == "ID3")
    private fun isWav(bytes: ByteArray) = bytes.size >= 12 && bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WAVE"
    private fun isOgg(bytes: ByteArray) = bytes.size >= 4 && bytes.ascii(0, 4) == "OggS"
    private fun isFlac(bytes: ByteArray) = bytes.size >= 4 && bytes.ascii(0, 4) == "fLaC"
    private fun isMp4(bytes: ByteArray, lower: String) = lower.endsWith(".mp4") || (bytes.size >= 12 && bytes.ascii(4, 4) == "ftyp")
    private fun isWebm(bytes: ByteArray, lower: String) = lower.endsWith(".webm") || (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())))
    private fun looksLikeRomFs(bytes: ByteArray): Boolean {
        if (bytes.size < RomFsHeaderSize) return false
        val headerSize = bytes.leLong(0)
        val dirMetaOffset = bytes.leLong(0x18)
        val fileMetaOffset = bytes.leLong(0x38)
        val dataOffset = bytes.leLong(0x48)
        return headerSize >= RomFsHeaderSize &&
            headerSize <= 0x1000 &&
            dirMetaOffset >= headerSize &&
            fileMetaOffset >= dirMetaOffset &&
            dataOffset >= fileMetaOffset
    }
    private fun looksLikeExeFs(bytes: ByteArray): Boolean {
        if (bytes.size < 0xC0) return false
        var nonEmpty = 0
        repeat(10) { index ->
            val entryOffset = index * 0x10
            val nameBytes = bytes.copyOfRange(entryOffset, entryOffset + 0x8)
            val size = bytes.leInt(entryOffset + 0xC).toUInt().toLong()
            val asciiish = nameBytes.all { byte ->
                byte == 0.toByte() || byte.toInt() in 0x20..0x7E
            }
            if (!asciiish) return false
            if (nameBytes.any { it != 0.toByte() } && size > 0L) {
                nonEmpty += 1
            }
        }
        return nonEmpty > 0
    }

    private suspend fun readNullTerminated(reader: RandomAccessReader, position: Long, maxLength: Int): ByteArray {
        val raw = reader.readExactAt(position, maxLength)
        val end = raw.indexOf(0).takeIf { it >= 0 } ?: raw.size
        return raw.copyOf(end)
    }

    private fun buildTree(entries: List<FlatEntry>): List<VirtualNode> {
        val root = mutableMapOf<String, MutableTreeNode>()
        entries.forEach { entry ->
            var current = root
            val segments = entry.path.split('/').filter { it.isNotBlank() }
            segments.forEachIndexed { index, segment ->
                val isLeaf = index == segments.lastIndex
                val node = current.getOrPut(segment) {
                    MutableTreeNode(
                        name = segment,
                        path = segments.take(index + 1).joinToString("/"),
                        isDirectory = !isLeaf,
                        size = if (isLeaf) entry.size else 0L,
                        offset = if (isLeaf) entry.offset else 0L,
                        detection = if (isLeaf) entry.detection else DetectionResult(SwitchFileKind.UNKNOWN),
                    )
                }
                if (isLeaf) {
                    node.isDirectory = false
                    node.size = entry.size
                    node.offset = entry.offset
                    node.detection = entry.detection
                }
                current = node.children
            }
        }
        return root.values.map { it.toImmutable() }.sortedBy { it.name.lowercase() }
    }

    private fun sanitizeNodeName(value: String): String =
        value.replace('\\', '/').trim().trimStart('/').ifBlank { "unnamed" }

    private fun sanitizeRelativePath(value: String): String =
        value.split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/") { segment -> segment.replace(Regex("[^A-Za-z0-9._ -]"), "_") }
            .ifBlank { "unnamed" }

    private fun normalizeExeFsName(name: String): String = when (name) {
        "rtld" -> "rtld.nso"
        "main" -> "main.nso"
        "subsdk0", "subsdk1", "subsdk2", "subsdk3", "subsdk4", "subsdk5", "subsdk6", "subsdk7", "sdk" -> "$name.nso"
        "main.npdm" -> name
        else -> name
    }

    private suspend fun readRomFsDirectories(
        reader: RandomAccessReader,
        directoryTable: ByteArray,
        fileTable: ByteArray,
        dataOffset: Long,
        tableBaseOffset: Int,
        firstOffset: Int,
        parentPath: String,
        visited: MutableSet<Int> = mutableSetOf(),
    ): List<VirtualNode> {
        val nodes = mutableListOf<VirtualNode>()
        var offset = resolveRomFsOffset(tableBaseOffset, firstOffset)
        while (offset != RomFsEntryEmpty && visited.add(offset)) {
            val entry = parseRomFsDirectory(directoryTable, offset) ?: break
            val path = sanitizeRelativePath(listOf(parentPath, entry.name).filter { it.isNotBlank() }.joinToString("/"))
            val children = buildList {
                addAll(readRomFsFiles(reader, fileTable, dataOffset, tableBaseOffset, entry.firstFileOffset, path))
                addAll(readRomFsDirectories(reader, directoryTable, fileTable, dataOffset, tableBaseOffset, entry.childOffset, path))
            }.sortedBy { it.name.lowercase() }
            nodes += VirtualNode(
                name = entry.name.ifBlank { "root" },
                path = path,
                isDirectory = true,
                size = 0L,
                offset = 0L,
                detection = DetectionResult(SwitchFileKind.ROMFS),
                children = children,
            )
            offset = resolveRomFsOffset(tableBaseOffset, entry.siblingOffset)
        }
        return nodes
    }

    private suspend fun readRomFsFiles(
        reader: RandomAccessReader,
        fileTable: ByteArray,
        dataOffset: Long,
        tableBaseOffset: Int,
        firstOffset: Int,
        parentPath: String,
        visited: MutableSet<Int> = mutableSetOf(),
    ): List<VirtualNode> {
        val nodes = mutableListOf<VirtualNode>()
        var offset = resolveRomFsOffset(tableBaseOffset, firstOffset)
        while (offset != RomFsEntryEmpty && visited.add(offset)) {
            val entry = parseRomFsFile(fileTable, offset) ?: break
            if (entry.dataOffset >= 0 && entry.size >= 0 && dataOffset + entry.dataOffset <= reader.size && entry.size <= reader.size - dataOffset - entry.dataOffset) {
                val path = sanitizeRelativePath(listOf(parentPath, entry.name).filter { it.isNotBlank() }.joinToString("/"))
                nodes += VirtualNode(
                    name = entry.name,
                    path = path,
                    isDirectory = false,
                    size = entry.size,
                    offset = dataOffset + entry.dataOffset,
                    detection = detect(path, SliceReadSource(reader, dataOffset + entry.dataOffset, entry.size)),
                )
            }
            offset = resolveRomFsOffset(tableBaseOffset, entry.siblingOffset)
        }
        return nodes
    }

    private fun parseRomFsDirectory(table: ByteArray, offset: Int): RomFsDirectoryEntry? {
        if (offset < 0 || offset + 0x18 > table.size) return null
        val nameSize = table.leInt(offset + 0x14)
        val nameStart = offset + 0x18
        val nameEnd = nameStart + nameSize
        if (nameSize < 0 || nameEnd > table.size) return null
        return RomFsDirectoryEntry(
            siblingOffset = table.leInt(offset + 0x4),
            childOffset = table.leInt(offset + 0x8),
            firstFileOffset = table.leInt(offset + 0xC),
            name = sanitizeNodeName(table.copyOfRange(nameStart, nameEnd).decodeToString()),
        )
    }

    private fun parseRomFsFile(table: ByteArray, offset: Int): RomFsFileEntry? {
        if (offset < 0 || offset + 0x20 > table.size) return null
        val nameSize = table.leInt(offset + 0x1C)
        val nameStart = offset + 0x20
        val nameEnd = nameStart + nameSize
        if (nameSize < 0 || nameEnd > table.size) return null
        return RomFsFileEntry(
            siblingOffset = table.leInt(offset + 0x4),
            dataOffset = table.leLong(offset + 0x8),
            size = table.leLong(offset + 0x10),
            name = sanitizeNodeName(table.copyOfRange(nameStart, nameEnd).decodeToString()),
        )
    }

    private fun findRomFsRootDirectoryOffset(directoryTable: ByteArray): Int {
        if (directoryTable.size < 0x18) return 0
        var offset = 0
        while (offset + 0x18 <= directoryTable.size) {
            val parent = directoryTable.leInt(offset)
            val nameSize = directoryTable.leInt(offset + 0x14)
            if (nameSize == 0 && (parent == RomFsEntryEmpty || parent == 0)) {
                return offset
            }
            val nextOffset = align4(offset + 0x18 + nameSize)
            if (nextOffset <= offset) break
            offset = nextOffset
        }
        return 0
    }

    private fun resolveRomFsOffset(tableBaseOffset: Int, entryOffset: Int): Int {
        if (entryOffset == RomFsEntryEmpty) return RomFsEntryEmpty
        return tableBaseOffset + entryOffset
    }

    private fun align4(value: Int): Int = (value + 3) and 3.inv()

    private data class RomFsDirectoryEntry(
        val siblingOffset: Int,
        val childOffset: Int,
        val firstFileOffset: Int,
        val name: String,
    )

    private data class RomFsFileEntry(
        val siblingOffset: Int,
        val dataOffset: Long,
        val size: Long,
        val name: String,
    )

    private data class FlatEntry(
        val name: String,
        val path: String,
        val offset: Long,
        val size: Long,
        val detection: DetectionResult,
    )

    private data class MutableTreeNode(
        val name: String,
        val path: String,
        var isDirectory: Boolean,
        var size: Long,
        var offset: Long,
        var detection: DetectionResult,
        val children: MutableMap<String, MutableTreeNode> = linkedMapOf(),
    ) {
        fun toImmutable(): VirtualNode = VirtualNode(
            name = name,
            path = path,
            isDirectory = isDirectory,
            size = size,
            offset = offset,
            detection = detection,
            children = children.values.map { it.toImmutable() }.sortedBy { it.name.lowercase() },
        )
    }
}
