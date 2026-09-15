package dev.bekelaray.bbai.core.parser

enum class KeyMaterialKind {
    PROD_KEYS,
    TITLE_KEYS,
    CONSOLE_KEYS,
}

data class LawfulKeyRequest(
    val kind: KeyMaterialKind,
    val subject: String,
)

interface LawfulKeyProvider {
    suspend fun hasKeyMaterial(request: LawfulKeyRequest): Boolean
}
