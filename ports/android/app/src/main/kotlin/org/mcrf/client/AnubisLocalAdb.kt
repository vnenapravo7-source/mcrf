package org.mcrf.client

import android.content.Context
import android.os.Build
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** A single local Wireless Debugging stream for the fixed Anubis helper. */
internal object AnubisLocalAdb {
    private const val A_CNXN = 0x4e584e43
    private const val A_STLS = 0x534c5453
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val ADB_VERSION = 0x01000001
    private const val TLS_VERSION = 0x01000000
    private const val MAX_PAYLOAD = 4096
    private const val LOCAL_ID = 1
    private const val STARTUP_MS = 12_000L
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val deadlines = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "anubis-adb-deadline").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    /** The caller supplies the TLS context for the identity approved by Android pairing. */
    fun launch(context: Context, connectPort: Int, pairedTls: SSLContext, attempt: AnubisPairingNative.Attempt): Closeable {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ManagedAppFreezeAdb.supports(context))
        require(connectPort in 1..65535)
        val socket = attempt.newSocket()
        val deadline = deadlines.schedule({ runCatching { socket.close() } }, STARTUP_MS, TimeUnit.MILLISECONDS)
        var ticket: ManagedAppFreezeAdb.LocalActivation? = null
        var secureSocket: SSLSocket? = null
        try {
            val tls = connectTls(socket, connectPort, pairedTls)
            secureSocket = tls
            val input = DataInputStream(tls.inputStream)
            val output = tls.outputStream

            val activation = ManagedAppFreezeAdb.beginLocalActivation(context).also { ticket = it }
            val launch = "shell:${activation.shellCommand}\u0000".encodeToByteArray()
            write(output, A_OPEN, LOCAL_ID, 0, launch)
            val opened = read(input, 0)
            if (opened.command != A_OKAY || opened.first == 0 || opened.second != LOCAL_ID) {
                throw IOException("ADB helper stream rejected")
            }
            // The helper owns an authenticated Binder lifetime. The ADB session only starts it.
            while (ManagedAppFreezeAdb.localActivationBinder(activation) == null) {
                if (socket.isClosed) throw IOException("ADB helper did not attach")
                Thread.sleep(50)
            }
            if (!deadline.cancel(false) || socket.isClosed) throw IOException("ADB helper startup expired")
            runCatching { tls.close() }
            return Closeable { cancelActivation(activation) }
        } catch (failure: Throwable) {
            ticket?.let(::cancelActivation)
            runCatching { socket.close() }
            runCatching { secureSocket?.close() }
            throw failure
        } finally {
            deadline.cancel(false)
        }
    }

    private fun connectTls(socket: Socket, connectPort: Int, pairedTls: SSLContext): SSLSocket {
        require(connectPort in 1..65535)
        socket.connect(InetSocketAddress(loopback, connectPort), 3000)
        socket.soTimeout = STARTUP_MS.toInt()
        val rawOutput = socket.getOutputStream()
        val rawInput = DataInputStream(socket.getInputStream())
        write(rawOutput, A_CNXN, ADB_VERSION, MAX_PAYLOAD, "host::\u0000".encodeToByteArray())
        requireTlsUpgrade(rawInput)
        write(rawOutput, A_STLS, TLS_VERSION, 0)
        val tls = pairedTls.socketFactory.createSocket(socket, loopback.hostAddress, connectPort, true) as SSLSocket
        try {
            tls.useClientMode = true
            tls.enabledProtocols = arrayOf("TLSv1.3")
            tls.startHandshake()
            val connected = read(DataInputStream(tls.inputStream), MAX_PAYLOAD)
            if (connected.command != A_CNXN || connected.first < ADB_VERSION || connected.second < MAX_PAYLOAD) {
                throw IOException("ADB TLS connection rejected")
            }
            return tls
        } catch (failure: Throwable) {
            runCatching { socket.close() }
            runCatching { tls.close() }
            throw failure
        }
    }

    private fun cancelActivation(ticket: ManagedAppFreezeAdb.LocalActivation) {
        ManagedAppFreezeAdb.cancelLocalActivation(ticket)?.let { binder ->
            runCatching { ManagedAppFreezeAdb.stop(binder) }
        }
    }

    private data class Packet(val command: Int, val first: Int, val second: Int)

    internal fun requireTlsUpgrade(input: DataInputStream) {
        if (read(input, 0) != Packet(A_STLS, TLS_VERSION, 0)) throw IOException("ADB TLS upgrade required")
    }

    private fun read(input: DataInputStream, maxPayload: Int): Packet {
        val header = ByteArray(24)
        input.readFully(header)
        val data = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = data.int
        val first = data.int
        val second = data.int
        val size = data.int
        data.int // ADB 0x01000001 omits payload checksums.
        val magic = data.int
        if (magic != command.inv() || size !in 0..maxPayload) throw IOException("Invalid ADB packet")
        input.readFully(ByteArray(size))
        return Packet(command, first, second)
    }

    private fun write(output: OutputStream, command: Int, first: Int, second: Int, payload: ByteArray = byteArrayOf()) {
        check(payload.size <= MAX_PAYLOAD)
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        val checksum = if (command == A_CNXN) payload.sumOf { it.toInt() and 0xff } else 0
        header.putInt(command).putInt(first).putInt(second).putInt(payload.size).putInt(checksum).putInt(command.inv())
        output.write(header.array())
        output.write(payload)
        output.flush()
    }
}
