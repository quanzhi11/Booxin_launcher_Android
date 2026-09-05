package com.booxin.launcher.core.skin

import android.util.Base64
import android.util.Log
import com.booxin.launcher.core.LauncherPaths
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal authlib-injector Yggdrasil API for a single offline player skin.
 * Binds to 127.0.0.1 so the game JVM can fetch profile textures without mods.
 *
 * Intentionally does **not** advertise `feature.enable_profile_key` and answers
 * `/minecraftservices/player/certificates` with 404 so the client never obtains
 * (or forges) a profile public-key signature. That avoids vanilla LAN hosts kicking
 * with "无效的玩家档案公钥签名" while still serving skin textures.
 */
class OfflineYggdrasilServer(
    private val username: String,
    private val uuidNoDash: String,
    private val skinBytes: ByteArray
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    private val textureHash: String =
        MessageDigest.getInstance("SHA-256").digest(skinBytes)
            .joinToString("") { "%02x".format(it) }

    private val keys: SkinKeys = SkinKeys.loadOrCreate()

    @Volatile
    var apiRoot: String = ""
        private set

    fun start(): String {
        stop()
        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        serverSocket = ss
        val port = ss.localPort
        apiRoot = "http://127.0.0.1:$port/"
        running.set(true)
        acceptThread = Thread({
            while (running.get()) {
                val socket = try {
                    ss.accept()
                } catch (_: SocketException) {
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "accept failed", t)
                    break
                }
                Thread({
                    runCatching { handleClient(socket) }
                        .onFailure { Log.w(TAG, "request failed", it) }
                }, "booxin-ygg-req").apply { isDaemon = true }.start()
            }
        }, "booxin-ygg-accept").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "offline yggdrasil at $apiRoot for $username")
        return apiRoot
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        apiRoot = ""
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 10_000
        socket.use { s ->
            val input = BufferedInputStream(s.getInputStream())
            val output = BufferedOutputStream(s.getOutputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                writeResponse(output, 400, "text/plain", "Bad Request")
                return
            }
            val method = parts[0].uppercase(Locale.US)
            val rawPath = parts[1].substringBefore('?')
            val query = parts[1].substringAfter('?', "")
            // Drain headers.
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }

            when {
                method == "GET" && rawPath == "/status" -> {
                    writeJson(
                        output,
                        200,
                        JSONObject()
                            .put("user.count", 1)
                            .put("token.count", 0)
                            .put("pendingAuthentication.count", 0)
                    )
                }
                method == "GET" && (rawPath == "/" || rawPath.isEmpty()) -> {
                    writeJson(output, 200, rootMeta())
                }
                method == "GET" && rawPath.startsWith("/sessionserver/session/minecraft/profile/") -> {
                    val id = rawPath.removePrefix("/sessionserver/session/minecraft/profile/")
                        .substringBefore('/')
                        .replace("-", "")
                        .lowercase(Locale.US)
                    if (id == uuidNoDash.lowercase(Locale.US)) {
                        writeJson(output, 200, profileJson(unsigned = queryContains(query, "unsigned", "true")))
                    } else {
                        writeResponse(output, 204, null, null)
                    }
                }
                method == "GET" && rawPath == "/sessionserver/session/minecraft/hasJoined" -> {
                    val name = queryParam(query, "username")
                    if (!name.isNullOrBlank() && name.equals(username, ignoreCase = true)) {
                        writeJson(output, 200, profileJson(unsigned = false))
                    } else {
                        writeResponse(output, 204, null, null)
                    }
                }
                method == "POST" && rawPath == "/sessionserver/session/minecraft/join" -> {
                    // Offline client join stub — always accept.
                    drainBody(input)
                    writeResponse(output, 204, null, null)
                }
                method == "POST" && rawPath == "/authserver/authenticate" -> {
                    drainBody(input)
                    writeJson(output, 200, authenticateJson())
                }
                method == "POST" && (
                    rawPath == "/authserver/refresh" ||
                        rawPath == "/authserver/validate" ||
                        rawPath == "/authserver/invalidate" ||
                        rawPath == "/authserver/signout"
                    ) -> {
                    drainBody(input)
                    if (rawPath == "/authserver/validate") {
                        writeResponse(output, 204, null, null)
                    } else if (rawPath == "/authserver/refresh") {
                        writeJson(output, 200, authenticateJson())
                    } else {
                        writeResponse(output, 204, null, null)
                    }
                }
                method == "GET" && rawPath == "/textures/$textureHash" -> {
                    writeResponse(output, 200, "image/png", skinBytes)
                }
                method == "POST" && rawPath == "/api/profiles/minecraft" -> {
                    drainBody(input)
                    writeJson(
                        output,
                        200,
                        JSONArray().put(
                            JSONObject()
                                .put("id", uuidNoDash)
                                .put("name", username)
                        )
                    )
                }
                // authlib-injector redirects api.minecraftservices.com → {apiRoot}minecraftservices/...
                // With -Dauthlibinjector.profileKey=enabled the dummy AA== ProfileKeyFilter is off,
                // so this reaches us. Refuse certificates: client must not submit a profile key.
                method == "POST" && (
                    rawPath == "/minecraftservices/player/certificates" ||
                        rawPath == "/player/certificates"
                    ) -> {
                    drainBody(input)
                    Log.i(TAG, "refuse player certificates (no profile key for LAN/vanilla hosts)")
                    writeResponse(output, 404, "application/json", """{"error":"Not Found","errorMessage":"Profile keys disabled for offline skin"}""")
                }
                else -> writeResponse(output, 404, "application/json", """{"error":"Not Found"}""")
            }
        }
    }

    private fun rootMeta(): JSONObject =
        JSONObject()
            .put(
                "meta",
                JSONObject()
                    .put("serverName", "Booxin Offline Skin")
                    .put("implementationName", "booxin-offline-skin")
                    .put("implementationVersion", "1.0.0")
                    .put("feature.non_email_login", true)
                // Do NOT set feature.enable_profile_key — skins only; no chat/profile certs.
            )
            .put(
                "skinDomains",
                JSONArray().put("127.0.0.1").put("localhost")
            )
            // Used only to sign texture properties, not player profile keys.
            .put("signaturePublickey", keys.publicPem)

    private fun profileJson(unsigned: Boolean): JSONObject {
        val texturesPayload = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("profileId", uuidNoDash)
            .put("profileName", username)
            .put(
                "textures",
                JSONObject().put(
                    "SKIN",
                    JSONObject().put("url", "${apiRoot}textures/$textureHash")
                )
            )
        val value = Base64.encodeToString(
            texturesPayload.toString().toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP
        )
        val prop = JSONObject().put("name", "textures").put("value", value)
        if (!unsigned) {
            prop.put("signature", keys.sign(value))
        }
        return JSONObject()
            .put("id", uuidNoDash)
            .put("name", username)
            .put("properties", JSONArray().put(prop))
    }

    private fun authenticateJson(): JSONObject =
        JSONObject()
            .put("accessToken", "0")
            .put("clientToken", "booxin-offline")
            .put(
                "selectedProfile",
                JSONObject().put("id", uuidNoDash).put("name", username)
            )
            .put(
                "availableProfiles",
                JSONArray().put(
                    JSONObject().put("id", uuidNoDash).put("name", username)
                )
            )

    private fun writeJson(output: BufferedOutputStream, code: Int, body: Any) {
        writeResponse(
            output,
            code,
            "application/json; charset=utf-8",
            body.toString().toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun writeResponse(
        output: BufferedOutputStream,
        code: Int,
        contentType: String?,
        body: Any?
    ) {
        val bytes = when (body) {
            null -> ByteArray(0)
            is ByteArray -> body
            is String -> body.toByteArray(StandardCharsets.UTF_8)
            else -> body.toString().toByteArray(StandardCharsets.UTF_8)
        }
        val reason = when (code) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "OK"
        }
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Connection: close\r\n")
            append("X-Authlib-Injector-API-Location: /\r\n")
            if (contentType != null && code != 204) {
                append("Content-Type: $contentType\r\n")
                append("Content-Length: ${bytes.size}\r\n")
            } else {
                append("Content-Length: 0\r\n")
            }
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(header)
        if (code != 204 && bytes.isNotEmpty()) {
            output.write(bytes)
        }
        output.flush()
    }

    private companion object {
        private const val TAG = "OfflineYgg"

        private fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) {
                    return if (buf.size() == 0) null else buf.toString(StandardCharsets.US_ASCII.name())
                }
                if (b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
            }
            return buf.toString(StandardCharsets.US_ASCII.name())
        }

        private fun drainBody(input: InputStream) {
            // Best-effort: most of our POST handlers ignore body.
            runCatching {
                if (input.available() > 0) {
                    val skip = ByteArray(minOf(input.available(), 64 * 1024))
                    input.read(skip)
                }
            }
        }

        private fun queryParam(query: String, key: String): String? {
            if (query.isBlank()) return null
            return query.split('&').asSequence()
                .map { it.split('=', limit = 2) }
                .firstOrNull { it[0] == key }
                ?.getOrNull(1)
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        }

        private fun queryContains(query: String, key: String, value: String): Boolean =
            queryParam(query, key)?.equals(value, ignoreCase = true) == true
    }
}

private class SkinKeys(
    val privateKey: PrivateKey,
    val publicPem: String
) {
    fun sign(value: String): String {
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initSign(privateKey)
        sig.update(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
    }

    companion object {
        fun loadOrCreate(): SkinKeys {
            val dir = File(LauncherPaths.runtimeDir, "offline-skin").also { it.mkdirs() }
            val privFile = File(dir, "private.pk8")
            val pubFile = File(dir, "public.pem")
            if (privFile.isFile && pubFile.isFile) {
                return runCatching {
                    val kf = KeyFactory.getInstance("RSA")
                    val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                    SkinKeys(priv, pubFile.readText())
                }.getOrElse {
                    privFile.delete()
                    pubFile.delete()
                    create(dir, privFile, pubFile)
                }
            }
            return create(dir, privFile, pubFile)
        }

        private fun create(dir: File, privFile: File, pubFile: File): SkinKeys {
            dir.mkdirs()
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048)
            val pair = gen.generateKeyPair()
            privFile.writeBytes(pair.private.encoded)
            val pub = pair.public as RSAPublicKey
            val b64 = Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
            .chunked(64)
            .joinToString("\n")
            val pem = "-----BEGIN PUBLIC KEY-----\n$b64\n-----END PUBLIC KEY-----\n"
            pubFile.writeText(pem)
            return SkinKeys(pair.private, pem)
        }
    }
}
