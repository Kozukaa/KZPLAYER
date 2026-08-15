package com.kzplayer.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// v149 : resolveur DNS-over-HTTPS (RFC 8484, format binaire "wire").
//
// Implemente okhttp3.Dns pour etre branche directement sur les OkHttpClient de
// l'app (Api.kt + lecteur ExoPlayer via KzHttpDataSource). En mode SYSTEM
// (defaut) il delegue au resolveur DNS Android natif -> zero changement de
// comportement pour les utilisateurs qui n'ont rien configure.
//
// Quand un DNS est choisi (Cloudflare/Google/Quad9/AdGuard/personnalise) :
// - envoie une requete DNS binaire au serveur DoH via HTTPS POST
// - parse la reponse et retourne les IP
// - cache 5 minutes pour eviter de spammer le serveur DoH
// - fallback sur DNS systeme si la requete DoH echoue (ne casse jamais le reseau)
//
// N'utilise JAMAIS DoH pour resoudre le hostname du serveur DoH lui-meme
// (sinon boucle infinie).
object DohDns : okhttp3.Dns {
    @Volatile private var appContext: Context? = null

    private val DNS_MSG_MEDIA = "application/dns-message".toMediaType()
    private const val CACHE_TTL_MS = 5L * 60L * 1000L
    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val CLASS_IN = 1

    // hostname -> (expireAt, ips)
    private val cache = ConcurrentHashMap<String, Pair<Long, List<InetAddress>>>()

    // Client OkHttp dedie aux requetes DoH. Il utilise le DNS SYSTEME pour
    // resoudre le hostname du serveur DoH (dns.quad9.net, cloudflare-dns.com, ...).
    // Sans cela, on aurait une recursion infinie.
    private val dohClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    fun init(ctx: Context) { appContext = ctx.applicationContext }

    fun clearCache() { cache.clear() }

    override fun lookup(hostname: String): List<InetAddress> {
        val ctx = appContext ?: return okhttp3.Dns.SYSTEM.lookup(hostname)
        val provider = DnsPref.current(ctx)
        if (provider == DnsPref.SYSTEM) return okhttp3.Dns.SYSTEM.lookup(hostname)

        val endpoint = DnsPref.endpointFor(ctx, provider)
        if (endpoint.isBlank() || !endpoint.startsWith("http")) {
            return okhttp3.Dns.SYSTEM.lookup(hostname)
        }

        // Evite la recursion : le hostname du serveur DoH est resolu par le systeme.
        val dohHost = try { URI(endpoint).host?.lowercase() } catch (_: Exception) { null }
        if (dohHost != null && hostname.lowercase() == dohHost) {
            return okhttp3.Dns.SYSTEM.lookup(hostname)
        }

        val now = System.currentTimeMillis()
        cache[hostname]?.let { if (it.first > now) return it.second }

        val ips = mutableListOf<InetAddress>()
        try {
            for (qtype in intArrayOf(TYPE_A, TYPE_AAAA)) {
                try { ips.addAll(query(hostname, qtype, endpoint)) } catch (_: Exception) {}
            }
        } catch (_: Exception) { /* on tombe dans le fallback ci-dessous */ }

        if (ips.isNotEmpty()) {
            cache[hostname] = (now + CACHE_TTL_MS) to ips
            return ips
        }
        // Fallback : DNS systeme si DoH echoue -> l'app continue de fonctionner.
        return try { okhttp3.Dns.SYSTEM.lookup(hostname) } catch (e: Exception) { throw e }
    }

    private fun query(hostname: String, qtype: Int, endpoint: String): List<InetAddress> {
        val packet = buildQueryPacket(hostname, qtype)
        val req = Request.Builder()
            .url(endpoint)
            .post(packet.toRequestBody(DNS_MSG_MEDIA))
            .header("Accept", "application/dns-message")
            .header("Content-Type", "application/dns-message")
            .build()
        return dohClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList<InetAddress>()
            val body = resp.body ?: return@use emptyList<InetAddress>()
            parseResponse(body.bytes(), qtype)
        }
    }

    private fun buildQueryPacket(hostname: String, qtype: Int): ByteArray {
        val buf = ByteArrayOutputStream()
        val dos = DataOutputStream(buf)
        dos.writeShort(0)          // ID
        dos.writeShort(0x0100)     // Flags: standard query, RD=1
        dos.writeShort(1)          // QDCOUNT = 1
        dos.writeShort(0)          // ANCOUNT
        dos.writeShort(0)          // NSCOUNT
        dos.writeShort(0)          // ARCOUNT
        for (label in hostname.split('.')) {
            if (label.isEmpty()) continue
            val bytes = label.toByteArray(Charsets.UTF_8)
            if (bytes.size > 63) throw IllegalArgumentException("label > 63")
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0)           // root label
        dos.writeShort(qtype)
        dos.writeShort(CLASS_IN)
        return buf.toByteArray()
    }

    private fun parseResponse(data: ByteArray, qtype: Int): List<InetAddress> {
        val dis = DataInputStream(ByteArrayInputStream(data))
        dis.readShort() // ID
        dis.readShort() // Flags
        dis.readShort() // QDCOUNT
        val ancount = dis.readUnsignedShort()
        dis.readShort() // NSCOUNT
        dis.readShort() // ARCOUNT

        // Section Question : on saute le nom + QTYPE (2) + QCLASS (2).
        skipName(dis)
        dis.readShort()
        dis.readShort()

        val ips = mutableListOf<InetAddress>()
        repeat(ancount) {
            skipName(dis)
            val type = dis.readUnsignedShort()
            dis.readShort() // class
            dis.readInt()   // TTL
            val rdlength = dis.readUnsignedShort()
            if (type == qtype && (type == TYPE_A || type == TYPE_AAAA)) {
                val rdata = ByteArray(rdlength)
                dis.readFully(rdata)
                try { ips.add(InetAddress.getByAddress(rdata)) } catch (_: Exception) {}
            } else {
                dis.skipBytes(rdlength)
            }
        }
        return ips
    }

    private fun skipName(dis: DataInputStream) {
        while (true) {
            val len = dis.readUnsignedByte()
            when {
                len == 0 -> return
                len >= 0xC0 -> { dis.readUnsignedByte(); return }  // pointeur (2 octets total)
                else -> dis.skipBytes(len)
            }
        }
    }
}
