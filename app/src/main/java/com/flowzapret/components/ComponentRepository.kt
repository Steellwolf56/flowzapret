package com.flowzapret.components

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.flowzapret.BuildConfig
import com.flowzapret.utility.FlowsealPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.zip.ZipInputStream

/**
 * Загрузка, проверка и установка обновляемых компонентов.
 *
 * Манифест (`components.json`) скачивается по HTTPS и проверяется
 * ECDSA-подписью (`components.json.sig`). Каждый артефакт проверяется по
 * SHA-256, нативные библиотеки — ещё и по ELF-заголовку/ABI.
 */
object ComponentRepository {

    private const val TAG = "ComponentRepository"

    const val BYEDPI = "byedpi"
    const val TGWS = "tgwsproxy"
    const val PRESETS = "presets"

    val LIB_COMPONENTS = listOf(BYEDPI, TGWS)
    val ALL_COMPONENTS = listOf(BYEDPI, TGWS, PRESETS)

    private const val PREFS = "component_settings"
    private const val KEY_MANIFEST_URL = "manifest_url"

    private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024
    private const val MAX_SIGNATURE_BYTES = 16 * 1024
    private const val MAX_ARTIFACT_BYTES = 64L * 1024 * 1024

    data class UpdateResult(
        val updated: List<String> = emptyList(),
        val skipped: List<String> = emptyList(),
        val errors: Map<String, String> = emptyMap(),
    ) {
        val hasChanges: Boolean get() = updated.isNotEmpty()
    }

    fun manifestUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_MANIFEST_URL, BuildConfig.COMPONENTS_MANIFEST_URL)
            ?.trim()
            .orEmpty()
    }

    fun setManifestUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MANIFEST_URL, url.trim())
            .apply()
    }

    /** Скачивает и проверяет манифест. */
    suspend fun fetchManifest(context: Context, url: String = manifestUrl(context)): ComponentManifest =
        withContext(Dispatchers.IO) {
            require(url.startsWith("https://")) { "Только HTTPS" }
            val bytes = httpGet(url, MAX_MANIFEST_BYTES)
            val signature = runCatching {
                httpGet("$url.sig", MAX_SIGNATURE_BYTES)
            }.getOrNull() ?: throw SecurityException("Нет подписи манифеста (${url}.sig)")

            verifySignature(context, bytes, signature)
            ComponentManifest.parse(bytes.toString(Charsets.UTF_8))
        }

    /**
     * Устанавливает перечисленные компоненты из манифеста.
     * Возвращает список реально обновлённых и ошибки по каждому компоненту.
     */
    suspend fun install(
        context: Context,
        manifest: ComponentManifest,
        baseUrl: String,
        names: Collection<String> = ALL_COMPONENTS,
        onProgress: (String) -> Unit = {},
    ): UpdateResult = withContext(Dispatchers.IO) {
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val errors = LinkedHashMap<String, String>()

        for (name in names) {
            val entry = manifest.components[name] ?: run {
                skipped.add(name)
                continue
            }

            try {
                if (entry.minAppVersionCode > BuildConfig.VERSION_CODE) {
                    throw IllegalStateException(
                        "Требуется приложение версии ${entry.minAppVersionCode}+"
                    )
                }
                if (entry.nativeApi > BuildConfig.COMPONENT_API) {
                    throw IllegalStateException(
                        "Требуется nativeApi ${entry.nativeApi}, поддерживается ${BuildConfig.COMPONENT_API}"
                    )
                }
                if (ComponentStore.installedVersion(name, context) == entry.version) {
                    skipped.add(name)
                    continue
                }

                onProgress(name)
                val artifact = entry.artifactFor(ComponentStore.abi())
                    ?: throw IllegalStateException("Нет артефакта для ABI ${ComponentStore.abi()}")

                val tmp = File(context.cacheDir, "component-$name-${System.nanoTime()}.tmp")
                try {
                    val resolved = resolveUrl(baseUrl, artifact.url)
                    downloadToFile(resolved, tmp, artifact, MAX_ARTIFACT_BYTES)
                    installDownloaded(context, name, entry, artifact, tmp)
                    updated.add(name)
                } finally {
                    tmp.delete()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to install $name", t)
                errors[name] = t.message ?: t.javaClass.simpleName
            }
        }

        if (updated.contains(PRESETS)) FlowsealPresets.invalidate()

        UpdateResult(updated, skipped, errors)
    }

    /**
     * Ручной импорт zip: внутри должны лежать `components.json`, опционально
     * `components.json.sig` и сами артефакты. URL в манифесте может быть
     * абсолютным — в этом случае берётся имя файла из zip.
     */
    suspend fun installFromZip(context: Context, uri: Uri): UpdateResult =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "component-import-${System.nanoTime()}")
            try {
                dir.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input ->
                    unzip(input, dir)
                } ?: throw IllegalArgumentException("Не удалось открыть файл")

                val manifestFile = File(dir, "components.json")
                require(manifestFile.isFile) { "В архиве нет components.json" }
                val bytes = manifestFile.readBytes()

                val sigFile = File(dir, "components.json.sig")
                if (sigFile.isFile) {
                    verifySignature(context, bytes, sigFile.readBytes())
                }

                val manifest = ComponentManifest.parse(bytes.toString(Charsets.UTF_8))
                installFromLocalDir(context, manifest, dir)
            } finally {
                dir.deleteRecursively()
            }
        }

    private fun installFromLocalDir(
        context: Context,
        manifest: ComponentManifest,
        dir: File,
    ): UpdateResult {
        val updated = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val errors = LinkedHashMap<String, String>()

        for ((name, entry) in manifest.components) {
            try {
                if (entry.minAppVersionCode > BuildConfig.VERSION_CODE) {
                    throw IllegalStateException("Требуется приложение версии ${entry.minAppVersionCode}+")
                }
                if (entry.nativeApi > BuildConfig.COMPONENT_API) {
                    throw IllegalStateException("Требуется nativeApi ${entry.nativeApi}")
                }
                if (ComponentStore.installedVersion(name, context) == entry.version) {
                    skipped.add(name)
                    continue
                }

                val artifact = entry.artifactFor(ComponentStore.abi())
                    ?: throw IllegalStateException("Нет артефакта для ABI ${ComponentStore.abi()}")
                val fileName = artifact.url.substringAfterLast('/')
                val source = File(dir, fileName)
                if (!source.isFile) throw IllegalStateException("В архиве нет $fileName")

                verifyFile(source, artifact)
                installFile(context, name, entry, artifact, source)
                updated.add(name)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to import $name", t)
                errors[name] = t.message ?: t.javaClass.simpleName
            }
        }

        if (updated.contains(PRESETS)) FlowsealPresets.invalidate()
        return UpdateResult(updated, skipped, errors)
    }

    private fun installDownloaded(
        context: Context,
        name: String,
        entry: ComponentEntry,
        artifact: ComponentArtifact,
        file: File,
    ) {
        verifyFile(file, artifact)
        installFile(context, name, entry, artifact, file)
    }

    private fun installFile(
        context: Context,
        name: String,
        entry: ComponentEntry,
        artifact: ComponentArtifact,
        file: File,
    ) {
        when (name) {
            PRESETS -> {
                validatePresets(file)
                ComponentStore.install(
                    name = PRESETS,
                    version = entry.version,
                    files = listOf("flowseal_presets.json" to file),
                    context = context,
                )
            }

            else -> {
                val abi = ComponentStore.abi()
                require(checkElf(file, abi)) { "Файл не является ELF для $abi" }
                ComponentStore.install(
                    name = name,
                    version = entry.version,
                    files = listOf("$abi/lib$name.so" to file),
                    context = context,
                )
            }
        }
    }

    private fun verifyFile(file: File, artifact: ComponentArtifact) {
        require(file.isFile && file.length() > 0) { "Пустой файл" }
        if (artifact.size > 0) {
            require(file.length() == artifact.size) {
                "Размер не совпадает: ${file.length()} != ${artifact.size}"
            }
        }
        val digest = sha256(file)
        require(digest.equals(artifact.sha256, ignoreCase = true)) {
            "SHA-256 не совпадает"
        }
    }

    private fun validatePresets(file: File) {
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val presets = root.optJSONArray("presets")
            ?: throw IllegalArgumentException("Нет массива presets")
        require(presets.length() > 0) { "Пустой список пресетов" }
    }

    /** Проверяет ELF-заголовок и целевую архитектуру. */
    fun checkElf(file: File, abi: String): Boolean {
        val header = ByteArray(20)
        val read = FileInputStream(file).use { it.read(header) }
        if (read < 20) return false
        if (header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
        ) {
            return false
        }
        val is64 = header[4].toInt() == 2
        val machine = (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
        return when (abi) {
            "arm64-v8a" -> is64 && machine == 0xB7
            "armeabi-v7a" -> !is64 && machine == 0x28
            else -> false
        }
    }

    private fun verifySignature(context: Context, data: ByteArray, signatureBytes: ByteArray) {
        val pem = context.resources.openRawResource(com.flowzapret.R.raw.components_pubkey)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        val encoded = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = Base64.decode(encoded, Base64.DEFAULT)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))

        val signature = Base64.decode(
            signatureBytes.toString(Charsets.UTF_8).trim(),
            Base64.DEFAULT,
        )
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(data)
        if (!verifier.verify(signature)) {
            throw SecurityException("Подпись манифеста неверна")
        }
    }

    private fun resolveUrl(baseUrl: String, artifactUrl: String): String {
        if (artifactUrl.startsWith("https://")) return artifactUrl
        require(baseUrl.startsWith("https://")) { "Только HTTPS" }
        return URL(URL(baseUrl), artifactUrl).toString()
    }

    private fun downloadToFile(
        url: String,
        dest: File,
        artifact: ComponentArtifact,
        maxBytes: Long,
    ) {
        require(url.startsWith("https://")) { "Только HTTPS" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "FlowZapret/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val declared = connection.contentLengthLong
            if (declared > maxBytes) throw IllegalStateException("Файл слишком большой")

            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IllegalStateException("Файл слишком большой")
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
            }
            if (artifact.size > 0 && total != artifact.size) {
                throw IllegalStateException("Размер не совпадает: $total != ${artifact.size}")
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(artifact.sha256, ignoreCase = true)) {
                throw SecurityException("SHA-256 не совпадает")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpGet(url: String, maxBytes: Int): ByteArray {
        require(url.startsWith("https://")) { "Только HTTPS" }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "FlowZapret/${BuildConfig.VERSION_NAME}")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IllegalStateException("Ответ слишком большой")
                    out.write(buffer, 0, n)
                }
            }
            return out.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun unzip(input: java.io.InputStream, targetDir: File) {
        val canonicalTarget = targetDir.canonicalFile
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val file = File(canonicalTarget, entry.name)
                val canonicalFile = file.canonicalFile
                if (!canonicalFile.path.startsWith(canonicalTarget.path + File.separator)) {
                    throw SecurityException("Небезопасный путь в архиве: ${entry.name}")
                }
                if (entry.isDirectory) {
                    canonicalFile.mkdirs()
                } else {
                    canonicalFile.parentFile?.mkdirs()
                    canonicalFile.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
