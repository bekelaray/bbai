package dev.bekelaray.bbai.core.model

enum class SwitchFileKind {
    NSP,
    PFS0,
    XCI,
    HFS0,
    NCA,
    NCZ,
    ROMFS,
    EXEFS,
    CNMT,
    NACP,
    NPDM,
    NRO,
    NSO,
    KIP,
    IMAGE,
    AUDIO,
    VIDEO,
    UNKNOWN,
}

enum class SupportStatus {
    SUPPORTED,
    UNSUPPORTED,
    ENCRYPTED_OR_KEYS_REQUIRED,
    INVALID,
}

data class DetectionResult(
    val kind: SwitchFileKind,
    val mimeType: String? = null,
    val notes: List<String> = emptyList(),
)

data class MetadataField(
    val label: String,
    val value: String,
)

data class VirtualNode(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val offset: Long,
    val detection: DetectionResult = DetectionResult(SwitchFileKind.UNKNOWN),
    val exportable: Boolean = true,
    val children: List<VirtualNode> = emptyList(),
)

data class InspectionResult(
    val displayName: String,
    val size: Long,
    val detection: DetectionResult,
    val supportStatus: SupportStatus,
    val metadata: List<MetadataField>,
    val entries: List<VirtualNode> = emptyList(),
    val warnings: List<String> = emptyList(),
)

enum class SortMode {
    NAME,
    SIZE,
    OFFSET,
}
