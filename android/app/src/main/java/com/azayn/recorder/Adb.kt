package com.azayn.recorder

import android.app.Application
import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import io.github.muntashirakon.adb.android.AdbMdns
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/** Lets libadb use the platform's Conscrypt (hidden API) for the TLS that Wireless debugging needs. */
class App : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        HiddenApiBypass.addHiddenApiExemptions("L")
    }
}

/**
 * ADB to this same phone over Wireless debugging (libadb-android). The app pairs once like a computer
 * would, then connects to the phone's own adbd to start the recorder daemon as the shell user, which is
 * what lets it capture call audio. Our key and certificate stay in the app's private storage.
 */
object Adb {
    private var manager: Manager? = null

    @Synchronized
    fun manager(ctx: Context): AbsAdbConnectionManager =
        manager ?: Manager(ctx.applicationContext).also { manager = it }

    /** Finds the pairing service shown while "Pair device with pairing code" is open. */
    fun discoverPairing(ctx: Context, onFound: (host: String, port: Int) -> Unit): AdbMdns =
        AdbMdns(ctx, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            if (host != null && port > 0) onFound(host.hostAddress ?: "127.0.0.1", port)
        }.also { it.start() }

    fun pair(ctx: Context, host: String, port: Int, code: String): Boolean =
        manager(ctx).pair(host, port, code.trim())

    /** Connects (discovering the Wireless debugging port) unless already connected. */
    fun connect(ctx: Context, timeoutMs: Long = 15_000): Boolean {
        val m = manager(ctx)
        return m.isConnected || m.connectTls(ctx, timeoutMs)
    }

    /**
     * Runs a shell command as the adb shell user and returns its stdout and stderr.
     *
     * Uses adbd's "shell v2" protocol, like `adb shell` from a computer. With the plain v1 service,
     * adbd kills every process the command started (its whole process group, which `setsid` can't
     * leave) as soon as the command returns, so the recorder daemon would die at once.
     */
    fun shell(ctx: Context, command: String): String {
        manager(ctx).openStream("shell,v2,raw:$command").use { stream ->
            val raw = java.io.ByteArrayOutputStream()
            val buf = ByteArray(4096)
            val input = stream.openInputStream()
            try {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    raw.write(buf, 0, n)
                    if (exited(raw.toByteArray())) break
                }
            } catch (e: java.io.IOException) {
                // libadb reports the stream ending as "Stream closed" rather than end-of-stream.
                if (!stream.isClosed) throw e
            }
            return unframe(raw.toByteArray())
        }
    }

    // Shell v2 packets: 1-byte id (1 stdout, 2 stderr, 3 exit code), 4-byte little-endian length, data.
    private fun packets(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        val out = mutableListOf<Pair<Int, ByteArray>>()
        var i = 0
        while (i + 5 <= bytes.size) {
            val len = (bytes[i + 1].toInt() and 0xff) or ((bytes[i + 2].toInt() and 0xff) shl 8) or
                ((bytes[i + 3].toInt() and 0xff) shl 16) or ((bytes[i + 4].toInt() and 0xff) shl 24)
            if (i + 5 + len > bytes.size) break
            out += bytes[i].toInt() to bytes.copyOfRange(i + 5, i + 5 + len)
            i += 5 + len
        }
        return out
    }

    private fun exited(bytes: ByteArray) = packets(bytes).any { it.first == 3 }

    private fun unframe(bytes: ByteArray): String =
        packets(bytes).filter { it.first == 1 || it.first == 2 }.joinToString("") { it.second.toString(Charsets.UTF_8) }

    fun disconnect(ctx: Context) {
        runCatching { manager(ctx).disconnect() }
    }

    private class Manager(ctx: Context) : AbsAdbConnectionManager() {
        private val privateKey: PrivateKey
        private val certificate: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            setTimeout(15, TimeUnit.SECONDS)
            val keyFile = File(ctx.filesDir, "adb/private.key")
            val certFile = File(ctx.filesDir, "adb/cert.der")
            if (keyFile.exists() && certFile.exists()) {
                privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                certificate = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
            } else {
                val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
                privateKey = pair.private
                certificate = selfSigned(pair.public, pair.private)
                keyFile.parentFile!!.mkdirs()
                keyFile.writeBytes(privateKey.encoded)
                certFile.writeBytes(certificate.encoded)
            }
        }

        override fun getPrivateKey() = privateKey
        override fun getCertificate() = certificate
        override fun getDeviceName() = "Azayn Recorder"

        /** A long-lived self-signed certificate: adbd remembers it after pairing, so it must not expire soon. */
        private fun selfSigned(publicKey: java.security.PublicKey, key: PrivateKey): Certificate {
            val name = X500Name("CN=Azayn Recorder")
            val notBefore = Date()
            val notAfter = Date(notBefore.time + 3650L * 24 * 3600 * 1000)
            val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(SecureRandom().nextLong() and Long.MAX_VALUE), notBefore, notAfter, name, publicKey)
                .build(JcaContentSignerBuilder("SHA256withRSA").build(key))
            return JcaX509CertificateConverter().getCertificate(holder)
        }
    }
}
