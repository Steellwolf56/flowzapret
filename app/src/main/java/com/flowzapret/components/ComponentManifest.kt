package com.flowzapret.components

import org.json.JSONObject

data class ComponentArtifact(
    val url: String,
    val sha256: String,
    val size: Long,
)

data class ComponentEntry(
    val version: String,
    val nativeApi: Int,
    val minAppVersionCode: Int,
    val abis: Map<String, ComponentArtifact>,
    val artifact: ComponentArtifact?,
    val changelog: String?,
) {
    fun artifactFor(abi: String): ComponentArtifact? = abis[abi] ?: artifact
}

data class ComponentManifest(
    val schema: Int,
    val generatedAt: String,
    val components: Map<String, ComponentEntry>,
) {
    companion object {
        fun parse(json: String): ComponentManifest {
            val root = JSONObject(json)
            val schema = root.optInt("schema", 1)
            val generatedAt = root.optString("generatedAt")
            val componentsJson = root.optJSONObject("components")
                ?: throw IllegalArgumentException("manifest has no components")

            val components = LinkedHashMap<String, ComponentEntry>()
            val keys = componentsJson.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val obj = componentsJson.optJSONObject(name) ?: continue
                components[name] = parseEntry(obj)
            }
            if (components.isEmpty()) throw IllegalArgumentException("manifest is empty")
            return ComponentManifest(schema, generatedAt, components)
        }

        private fun parseEntry(obj: JSONObject): ComponentEntry {
            val version = obj.optString("version").trim()
            require(version.isNotEmpty()) { "component version is empty" }

            val abis = LinkedHashMap<String, ComponentArtifact>()
            obj.optJSONObject("abis")?.let { abisJson ->
                val keys = abisJson.keys()
                while (keys.hasNext()) {
                    val abi = keys.next()
                    abisJson.optJSONObject(abi)?.let { abis[abi] = parseArtifact(it) }
                }
            }

            val artifact = obj.optJSONObject("artifact")?.let { parseArtifact(it) }

            return ComponentEntry(
                version = version,
                nativeApi = obj.optInt("nativeApi", 1),
                minAppVersionCode = obj.optInt("minAppVersionCode", 0),
                abis = abis,
                artifact = artifact,
                changelog = obj.optString("changelog").ifBlank { null },
            )
        }

        private fun parseArtifact(obj: JSONObject): ComponentArtifact {
            val url = obj.optString("url").trim()
            val sha = obj.optString("sha256").trim().lowercase()
            require(url.isNotEmpty()) { "artifact url is empty" }
            require(sha.length == 64 && sha.all { it.isDigit() || it in 'a'..'f' }) {
                "artifact sha256 is invalid"
            }
            return ComponentArtifact(url, sha, obj.optLong("size", 0))
        }
    }
}
