import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
}

val releaseStoreFile = providers.gradleProperty("RELEASE_STORE_FILE").orNull?.let(::file)
val releaseStorePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning =
    releaseStoreFile?.isFile == true &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null

android {
    namespace = "ka.tile.scrnoff"
    compileSdk = 37

    defaultConfig {
        applicationId = "ka.tile.scrnoff"
        minSdk = 23
        targetSdk = 37
        versionCode = 4
        versionName = "1.2.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = requireNotNull(releaseStoreFile)
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let {
                signingConfig = it
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        viewBinding = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/**",
                "kotlin/**",
            )
        }
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

fun findZipalign(): File? {
    val sdkDir = sequenceOf(
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
    ).filterNotNull()
        .map(::file)
        .firstOrNull { it.isDirectory }
        ?: file("local.properties")
            .takeIf { it.isFile }
            ?.readLines()
            ?.firstOrNull { it.startsWith("sdk.dir=") }
            ?.substringAfter('=')
            ?.replace("\\:", ":")
            ?.replace("\\\\", "\\")
            ?.let(::file)
            ?.takeIf { it.isDirectory }

    val executableName =
        if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
            "zipalign.exe"
        } else {
            "zipalign"
        }

    return sdkDir
        ?.resolve("build-tools")
        ?.listFiles()
        ?.filter { it.isDirectory }
        ?.sortedByDescending { it.name }
        ?.map { it.resolve(executableName) }
        ?.firstOrNull { it.isFile }
}

private object ReleaseApkProcessor {
    private val generatedMetaInfEntries = setOf(
        "META-INF/com/android/build/gradle/app-metadata.properties",
        "META-INF/version-control-info.textproto",
    )

    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_TYPE = 0x0003
    private const val RES_XML_FIRST_NODE_TYPE = 0x0100
    private const val RES_XML_LAST_NODE_TYPE = 0x0104
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val XML_NODE_HEADER_SIZE = 16
    private const val UTF8_FLAG = 0x00000100

    fun ByteArray.u4(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
                ((this[offset + 1].toInt() and 0xff) shl 8) or
                ((this[offset + 2].toInt() and 0xff) shl 16) or
                ((this[offset + 3].toInt() and 0xff) shl 24)

    fun ByteArray.u2(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    fun ByteArray.putU2(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    fun ByteArray.putU4(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }

    fun ByteArray.readUleb128(cursor: IntArray): Int {
        var result = 0
        var count = 0
        var byte: Int
        do {
            byte = this[cursor[0]++].toInt() and 0xff
            result = result or ((byte and 0x7f) shl (count * 7))
            count += 1
        } while ((byte and 0x80) != 0)
        return result
    }

    fun ByteArray.stripDexDebugInfo(): Boolean {
        if (size < 112 || this[0] != 'd'.code.toByte() || this[1] != 'e'.code.toByte() || this[2] != 'x'.code.toByte()) {
            return false
        }

        var changed = false
        val classDefsSize = u4(96)
        val classDefsOff = u4(100)
        repeat(classDefsSize) { classIndex ->
            val classDef = classDefsOff + classIndex * 32
            val sourceFileOffset = classDef + 16
            if (u4(sourceFileOffset) != -1) {
                putU4(sourceFileOffset, -1)
                changed = true
            }

            val classDataOff = u4(classDef + 24)
            if (classDataOff == 0) return@repeat

            val cursor = intArrayOf(classDataOff)
            val staticFieldsSize = readUleb128(cursor)
            val instanceFieldsSize = readUleb128(cursor)
            val directMethodsSize = readUleb128(cursor)
            val virtualMethodsSize = readUleb128(cursor)

            repeat(staticFieldsSize + instanceFieldsSize) {
                readUleb128(cursor)
                readUleb128(cursor)
            }

            repeat(directMethodsSize + virtualMethodsSize) {
                readUleb128(cursor)
                readUleb128(cursor)
                val codeOff = readUleb128(cursor)
                if (codeOff != 0) {
                    val debugInfoOffset = codeOff + 8
                    if (u4(debugInfoOffset) != 0) {
                        putU4(debugInfoOffset, 0)
                        changed = true
                    }
                }
            }
        }

        if (changed) {
            val sha1 = MessageDigest.getInstance("SHA-1").digest(copyOfRange(32, size))
            sha1.copyInto(this, destinationOffset = 12)
            val adler32 = Adler32()
            adler32.update(this, 12, size - 12)
            putU4(8, adler32.value.toInt())
        }
        return changed
    }

    fun ByteArray.xmlStringPool(): List<String> {
        val poolOffset = u2(2)
        val type = u2(poolOffset)
        if (type != RES_STRING_POOL_TYPE) return emptyList()

        val stringCount = u4(poolOffset + 8)
        val flags = u4(poolOffset + 16)
        val stringsStart = poolOffset + u4(poolOffset + 20)
        val offsetsStart = poolOffset + u2(poolOffset + 2)
        val isUtf8 = (flags and UTF8_FLAG) != 0

        return List(stringCount) { index ->
            val stringOffset = stringsStart + u4(offsetsStart + index * 4)
            if (isUtf8) readUtf8XmlString(stringOffset) else readUtf16XmlString(stringOffset)
        }
    }

    fun ByteArray.readUtf8XmlString(offset: Int): String {
        var cursor = offset
        cursor += xmlUtf8LengthSize(cursor)
        val byteLengthSize = xmlUtf8LengthSize(cursor)
        val byteLength = xmlUtf8Length(cursor)
        cursor += byteLengthSize
        return String(this, cursor, byteLength, Charsets.UTF_8)
    }

    fun ByteArray.xmlUtf8LengthSize(offset: Int): Int =
        if ((this[offset].toInt() and 0x80) == 0) 1 else 2

    fun ByteArray.xmlUtf8Length(offset: Int): Int =
        if ((this[offset].toInt() and 0x80) == 0) {
            this[offset].toInt() and 0x7f
        } else {
            ((this[offset].toInt() and 0x7f) shl 8) or (this[offset + 1].toInt() and 0xff)
        }

    fun ByteArray.readUtf16XmlString(offset: Int): String {
        val charLength = xmlUtf16Length(offset)
        val cursor = offset + if ((u2(offset) and 0x8000) == 0) 2 else 4
        return String(this, cursor, charLength * 2, Charsets.UTF_16LE)
    }

    fun ByteArray.xmlUtf16Length(offset: Int): Int {
        val first = u2(offset)
        return if ((first and 0x8000) == 0) {
            first
        } else {
            ((first and 0x7fff) shl 16) or u2(offset + 2)
        }
    }

    fun ByteArray.stripManifestMetadata(): ByteArray =
        withoutElementAttributes(
            "manifest",
            setOf("platformBuildVersionCode", "platformBuildVersionName"),
        ).withoutElementAttributes(
            "application",
            setOf("extractNativeLibs"),
        )

    fun ByteArray.withoutElementAttributes(
        elementName: String,
        removedAttributes: Set<String>,
    ): ByteArray {
        if (size < 8 || u2(0) != RES_XML_TYPE) return this

        val strings = xmlStringPool()
        if (strings.isEmpty()) return this

        var offset = u2(2)
        while (offset in 8 until size) {
            val type = u2(offset)
            val chunkSize = u4(offset + 4)
            if (chunkSize <= 0 || offset + chunkSize > size) return this

            if (type == RES_XML_START_ELEMENT_TYPE) {
                val currentElementName = strings.getOrNull(u4(offset + 20))
                if (currentElementName == elementName) {
                    return withoutAttributes(offset, strings, removedAttributes)
                }
            }
            offset += chunkSize
        }
        return this
    }

    fun ByteArray.withoutAttributes(
        elementOffset: Int,
        strings: List<String>,
        removedAttributes: Set<String>,
    ): ByteArray {
        val attributeStart = u2(elementOffset + 24)
        val attributeSize = u2(elementOffset + 26)
        val attributeCount = u2(elementOffset + 28)
        if (attributeSize <= 0 || attributeCount <= 0) return this

        val attributesOffset = elementOffset + XML_NODE_HEADER_SIZE + attributeStart
        val keep = (0 until attributeCount).filter { index ->
            val attributeOffset = attributesOffset + index * attributeSize
            strings.getOrNull(u4(attributeOffset + 4)) !in removedAttributes
        }
        if (keep.size == attributeCount) return this

        val removed = attributeCount - keep.size
        val bytesRemoved = removed * attributeSize
        val newSize = size - bytesRemoved
        val result = ByteArray(newSize)

        val attributesEnd = attributesOffset + attributeCount * attributeSize
        this.copyInto(result, endIndex = attributesOffset)

        var writeOffset = attributesOffset
        keep.forEach { index ->
            val attributeOffset = attributesOffset + index * attributeSize
            copyInto(result, writeOffset, attributeOffset, attributeOffset + attributeSize)
            writeOffset += attributeSize
        }
        copyInto(result, writeOffset, attributesEnd, size)

        result.putU4(4, u4(4) - bytesRemoved)
        result.putU4(elementOffset + 4, u4(elementOffset + 4) - bytesRemoved)
        result.putU2(elementOffset + 28, keep.size)

        return result
    }

    fun ByteArray.stripXmlDebugInfo(): ByteArray {
        if (size < 8 || u2(0) != RES_XML_TYPE) return this

        var offset = u2(2)
        while (offset in 8 until size) {
            val type = u2(offset)
            val headerSize = u2(offset + 2)
            val chunkSize = u4(offset + 4)
            if (chunkSize <= 0 || offset + chunkSize > size) return this

            if (type in RES_XML_FIRST_NODE_TYPE..RES_XML_LAST_NODE_TYPE && headerSize >= 16) {
                putU4(offset + 8, 0)
                putU4(offset + 12, -1)
            }
            offset += chunkSize
        }
        return this
    }

    fun process(apk: File, zipalign: File) {
        val tmp = apk.resolveSibling("${apk.name}.tmp")
        val aligned = apk.resolveSibling("${apk.name}.aligned")
        ZipInputStream(apk.inputStream().buffered()).use { input ->
            ZipOutputStream(tmp.outputStream().buffered()).use { output ->
                generateSequence { input.nextEntry }.forEach { entry ->
                    if (entry.name !in generatedMetaInfEntries) {
                        var content = input.readBytes()
                        if (entry.name.endsWith(".dex")) {
                            content.stripDexDebugInfo()
                        } else if (entry.name.endsWith(".xml")) {
                            if (entry.name == "AndroidManifest.xml") {
                                content = content.stripManifestMetadata()
                            }
                            content.stripXmlDebugInfo()
                        }
                        output.putNextEntry(
                            ZipEntry(entry.name).apply {
                                time = entry.time
                                comment = entry.comment
                                extra = entry.extra
                                if (entry.method == ZipEntry.STORED || entry.name == "resources.arsc") {
                                    val crc32 = CRC32()
                                    crc32.update(content)
                                    method = ZipEntry.STORED
                                    size = content.size.toLong()
                                    compressedSize = content.size.toLong()
                                    crc = crc32.value
                                }
                            },
                        )
                        output.write(content)
                        output.closeEntry()
                    }
                    input.closeEntry()
                }
            }
        }

        val zipalignProcess = ProcessBuilder(
            zipalign.absolutePath,
            "-f",
            "-p",
            "4",
            tmp.absolutePath,
            aligned.absolutePath,
        )
            .redirectErrorStream(true)
            .start()
        val zipalignOutput = zipalignProcess.inputStream.bufferedReader().readText()
        val zipalignExitCode = zipalignProcess.waitFor()
        if (zipalignExitCode != 0) {
            error("zipalign failed ($zipalignExitCode): $zipalignOutput")
        }
        if (!tmp.delete()) error("Unable to delete ${tmp.absolutePath}")
        if (!apk.delete()) error("Unable to delete ${apk.absolutePath}")
        if (!aligned.renameTo(apk)) error("Unable to replace ${apk.absolutePath}")
    }
}

abstract class PostProcessReleaseApk : DefaultTask() {
    @get:Internal
    abstract val apkFile: RegularFileProperty

    @get:Internal
    abstract val zipalignFile: RegularFileProperty

    @TaskAction
    fun process() {
        val apk = apkFile.get().asFile
        if (!apk.exists()) return
        val zipalign = zipalignFile.get().asFile
        check(zipalign.isFile) { "Unable to find zipalign in Android SDK build-tools" }
        ReleaseApkProcessor.process(apk, zipalign)
    }
}

val stripGeneratedMetaInfFromReleaseApk by tasks.registering(PostProcessReleaseApk::class) {
    apkFile.set(layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk"))
    zipalignFile.fileValue(
        findZipalign() ?: layout.projectDirectory.file(".missing-zipalign").asFile,
    )
}

tasks.configureEach {
    if (name == "assembleRelease") {
        finalizedBy(stripGeneratedMetaInfFromReleaseApk)
    }
}
