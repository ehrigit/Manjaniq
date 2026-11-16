package com.manjaniq.core

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Locale
import java.util.zip.InflaterInputStream

/**
 * The server list Psiphon's client starts with.
 */
object PsiphonServerList {

    const val PSIPHON_SERVERS_DAT = "psiphon_servers.dat"

    internal const val SIGNING_KEY =
        "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH" +
            "5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5" +
            "OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42Kcot" +
            "LFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6" +
            "/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7G" +
            "stZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1O" +
            "geF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8" +
            "u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz" +
            "31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xal" +
            "KxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="

    private const val ENTRIES_FILE = "psiphon-servers.txt"
    private val leavesFrom = Regex("""psiphon can leave from: ([A-Z]{2}(?: [A-Z]{2})*)\s*$""")
    private const val SOURCE_MARK = "psiphon-servers.source"
    private const val MAX_COMPRESSED = 16 * 1024 * 1024
    private const val MAX_UNCOMPRESSED = 64 * 1024 * 1024
    private val isoCountries = Locale.getISOCountries().toSet()

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            if (size > limit - output.size()) throw IOException("the Psiphon list is too large")
            output.write(buffer, 0, size)
        }
        return output.toByteArray()
    }

    /**
     * Prefer a previously supplied list in filesDir. Otherwise stage the signed
     * APK asset in the private work directory. Never overwrite a user's list,
     * and never replace the last usable bundled copy with an invalid update.
     */
    @Synchronized
    fun entriesFile(context: Context, workDir: File, onProblem: (IOException) -> Unit = {}): File? {
        if (!workDir.isDirectory && !workDir.mkdirs()) {
            onProblem(IOException("could not create the Psiphon work directory"))
            return null
        }
        if (File(context.filesDir, PSIPHON_SERVERS_DAT).isFile) {
            entriesFile(context.filesDir, workDir, onProblem = onProblem)?.let { return it }
            // Leave an unusable user-supplied file untouched, but allow a clean
            // install to fall back to the authenticated bundled list.
        }
        val bundledDir = File(workDir, "psiphon-bundled")
        val cached = File(bundledDir, PSIPHON_SERVERS_DAT)
        try {
            val packed = context.assets.open(PSIPHON_SERVERS_DAT).use { readBounded(it, MAX_COMPRESSED) }
            val cachedBytes = if (cached.isFile && cached.length() <= MAX_COMPRESSED) cached.readBytes() else null
            if (cachedBytes == null || !cachedBytes.contentEquals(packed)) {
                // Check before replacing a known-good copy. entriesFile also
                // verifies the signature before exposing the plain entries.
                unpack(packed, SIGNING_KEY)
                if (!bundledDir.isDirectory && !bundledDir.mkdirs()) throw IOException("could not create bundled-list directory")
                val fresh = File.createTempFile("psiphon-", ".new", bundledDir)
                try {
                    fresh.writeBytes(packed)
                    if (!fresh.renameTo(cached)) throw IOException("could not stage the bundled Psiphon list")
                } finally {
                    fresh.delete()
                }
                Log.i("PsiphonServerList", "Verified and staged the bundled Psiphon server list")
            }
        } catch (e: IOException) {
            onProblem(e)
        }
        return entriesFile(bundledDir, workDir, onProblem = onProblem)
    }

    @Synchronized
    fun entriesFile(assetDir: File, workDir: File, signingKey: String = SIGNING_KEY, onProblem: (IOException) -> Unit = {}): File? {
        val source = File(assetDir, PSIPHON_SERVERS_DAT)
        if (!source.isFile) return null
        val entries = File(workDir, ENTRIES_FILE)
        val mark = File(workDir, SOURCE_MARK)
        return try {
            if (!workDir.isDirectory && !workDir.mkdirs()) throw IOException("could not create the Psiphon work directory")
            val packed = source.inputStream().use { readBounded(it, MAX_COMPRESSED) }
            // A length/mtime pair can collide across an app update or replacement.
            val stamp = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(packed + signingKey.toByteArray(Charsets.US_ASCII))
            )
            if (entries.isFile && mark.isFile && mark.readText() == stamp) return entries
            val text = unpack(packed, signingKey)
            val fresh = File(workDir, "$ENTRIES_FILE.${System.nanoTime()}.new")
            fresh.writeText(text)
            try {
                if (!fresh.renameTo(entries)) throw IOException("could not put ${entries.name} in place")
            } finally {
                fresh.delete()
            }
            mark.writeText(stamp)
            entries
        } catch (e: IOException) {
            onProblem(e)
            entries.takeIf { it.isFile }
        }
    }

    fun publishedAt(stamp: String?): Long = stamp?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.times(1000) ?: 0L

    fun bundledListGoesOver(copy: File, publishedAt: Long, keptByUser: Boolean): Boolean =
        !copy.exists() || (!keptByUser && publishedAt > copy.lastModified())

    fun regions(entries: String): Set<String> = entries.lineSequence().mapNotNullTo(sortedSetOf()) { regionOf(it.trim()) }

    private fun regionOf(line: String): String? {
        if (line.isEmpty() || line.length % 2 != 0) return null
        val bytes = ByteArray(line.length / 2)
        for (index in bytes.indices) {
            val high = Character.digit(line[2 * index], 16)
            val low = Character.digit(line[2 * index + 1], 16)
            if (high < 0 || low < 0) return null
            bytes[index] = ((high shl 4) or low).toByte()
        }
        val json = bytes.toString(Charsets.UTF_8).split(' ', limit = 5).getOrNull(4) ?: return null
        val region = try {
            val obj = JSONObject(json)
            if (obj.has("region") && !obj.isNull("region")) obj.get("region").takeIf { it is String } as? String else null
        } catch (_: Exception) {
            null
        }
        return region?.trim()?.uppercase(Locale.ROOT)?.takeIf { it in isoCountries }
    }

    fun regionsOf(line: String): List<String>? = leavesFrom.find(line)?.groupValues?.get(1)?.split(' ')

    fun remember(regions: Collection<String>) {
        AetherSettingsStorage.putPsiphonRegions(regions.filter { it in isoCountries }.toSortedSet().joinToString(","))
    }

    fun remembered(): Set<String> =
        AetherSettingsStorage.getPsiphonRegions().split(',').filterTo(sortedSetOf()) { it in isoCountries }

    fun forgetRemembered() {
        AetherSettingsStorage.putPsiphonRegions("")
    }

    @Throws(IOException::class)
    internal fun unpack(packed: ByteArray, signingKey: String): String {
        val text = try {
            InflaterInputStream(ByteArrayInputStream(packed)).use { readBounded(it, MAX_UNCOMPRESSED) }.toString(Charsets.UTF_8)
        } catch (e: IOException) {
            throw IOException("the list is not compressed the way Psiphon writes it", e)
        }
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("the list is not the package Psiphon writes", e)
        }
        val data = (if (json.has("data") && !json.isNull("data")) json.get("data") as? String else null)
            ?: throw IOException("the list carries no entries")
        val keyDigest = (if (json.has("signingPublicKeyDigest") && !json.isNull("signingPublicKeyDigest")) json.get("signingPublicKeyDigest") as? String else null)
            ?: throw IOException("the list names no key")
        val signature = (if (json.has("signature") && !json.isNull("signature")) json.get("signature") as? String else null)
            ?: throw IOException("the list is not signed")
        try {
            val decoder = Base64.getDecoder()
            val expected = MessageDigest.getInstance("SHA-256").digest(signingKey.toByteArray(Charsets.US_ASCII))
            if (!decoder.decode(keyDigest).contentEquals(expected)) throw IOException("the list is signed with another key")
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decoder.decode(signingKey)))
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key)
            verifier.update(data.toByteArray(Charsets.UTF_8))
            if (!verifier.verify(decoder.decode(signature))) throw IOException("the signature of the list does not check out")
        } catch (e: GeneralSecurityException) {
            throw IOException("the signature of the list cannot be checked", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("the list is not encoded the way Psiphon writes it", e)
        }
        if (data.lineSequence().none { it.isNotBlank() }) throw IOException("the list is empty")
        return data
    }
}
