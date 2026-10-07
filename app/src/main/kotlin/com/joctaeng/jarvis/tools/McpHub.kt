package com.joctaeng.jarvis.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.joctaeng.euno.mcp.IEunoMcpCallback
import com.joctaeng.euno.mcp.IEunoMcpServer
import com.joctaeng.jarvis.action.mcp.HttpMcpTransport
import com.joctaeng.jarvis.action.mcp.McpCallResult
import com.joctaeng.jarvis.action.mcp.McpClient
import com.joctaeng.jarvis.action.mcp.McpException
import com.joctaeng.jarvis.action.mcp.McpNames
import com.joctaeng.jarvis.action.mcp.McpServerHandle
import com.joctaeng.jarvis.action.mcp.McpTool
import com.joctaeng.jarvis.action.mcp.McpToolInfo
import com.joctaeng.jarvis.action.mcp.McpTransport
import com.joctaeng.jarvis.core.contracts.Tool
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Um app do celular ou servidor na rede que oferece ferramentas MCP. */
data class McpServerInfo(
    val id: String,
    val name: String,
    val kind: Kind,
    /** Pacote/serviço (celular) ou URL (rede). */
    val address: String,
) {
    enum class Kind { ON_DEVICE, NETWORK }
}

/**
 * Conexões MCP do Euno (ADR 0011): apps do celular que exportam o serviço com.joctaeng.euno.action.MCP_SERVER
 * (canal Binder, offline) e servidores MCP por HTTP cadastrados pelo usuário (PC, rede, Tailscale).
 */
class McpHub(private val context: Context, private val networkServers: () -> List<McpServerInfo>) {
    private val lock = Mutex()
    private val connections = mutableMapOf<String, Connection>()
    private val toolsCache = mutableMapOf<String, List<McpToolInfo>>()

    fun discover(): List<McpServerInfo> {
        val pm = context.packageManager
        val onDevice = pm.queryIntentServices(Intent(ACTION), PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            .filter { it.serviceInfo.exported }
            .map {
                val label = it.loadLabel(pm).toString().ifBlank { it.serviceInfo.packageName }
                McpServerInfo(
                    id = it.serviceInfo.metaData?.getString("com.joctaeng.euno.mcp.ID") ?: label,
                    name = label,
                    kind = McpServerInfo.Kind.ON_DEVICE,
                    address = ComponentName(it.serviceInfo.packageName, it.serviceInfo.name).flattenToString(),
                )
            }
        return onDevice + networkServers()
    }

    /** Ferramentas de todos os servidores habilitados; servidor que não responde fica de fora (e é tentado de novo depois). */
    suspend fun tools(enabled: (McpServerInfo) -> Boolean): List<Tool> = discover().filter(enabled).flatMap { server ->
        val infos = runCatching { listTools(server) }.getOrElse { emptyList() }
        val handle = Handle(server)
        infos.map { McpTool(handle, it, McpNames.qualified(server.id, it.name)) }
    }

    suspend fun listTools(server: McpServerInfo, refresh: Boolean = false): List<McpToolInfo> {
        if (!refresh) toolsCache[server.address]?.let { return it }
        val tools = withConnection(server) { it.listTools() }
        toolsCache[server.address] = tools
        return tools
    }

    fun closeAll() {
        connections.values.forEach { it.close() }
        connections.clear()
    }

    private inner class Handle(private val server: McpServerInfo) : McpServerHandle {
        override val id = server.id
        override val displayName = server.name
        override suspend fun call(toolName: String, argumentsJson: String): McpCallResult = try {
            withConnection(server) { it.callTool(toolName, argumentsJson) }
        } catch (e: McpException) {
            if (e.code != McpClient.APP_NOT_READY || server.kind != McpServerInfo.Kind.ON_DEVICE) throw e
            // A parte do app que atende o Euno só roda com o app aberto: abre e tenta de novo.
            openApp(server)
            retryUntilReady { withConnection(server) { it.callTool(toolName, argumentsJson) } }
        }
    }

    private suspend fun <T> retryUntilReady(block: suspend () -> T): T {
        var last: McpException? = null
        repeat(12) {
            delay(1_500)
            try {
                return block()
            } catch (e: McpException) {
                if (e.code != McpClient.APP_NOT_READY) throw e
                last = e
            }
        }
        throw last ?: McpException(McpClient.APP_NOT_READY, "o app não ficou pronto a tempo")
    }

    private fun openApp(server: McpServerInfo) {
        val pkg = ComponentName.unflattenFromString(server.address)?.packageName ?: return
        context.packageManager.getLaunchIntentForPackage(pkg)?.let {
            runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private suspend fun <T> withConnection(server: McpServerInfo, block: suspend (McpClient) -> T): T {
        val connection = lock.withLock {
            connections[server.address]?.takeIf { it.alive } ?: Connection(server).also {
                it.client.initialize()
                connections[server.address] = it
            }
        }
        return try {
            block(connection.client)
        } catch (e: McpException) {
            throw e
        } catch (e: Exception) {
            // Canal caiu (app fechado pelo sistema, rede): descarta para reconectar na próxima.
            lock.withLock { connections.remove(server.address)?.close() }
            throw e
        }
    }

    private inner class Connection(server: McpServerInfo) {
        private val transport: McpTransport = when (server.kind) {
            McpServerInfo.Kind.ON_DEVICE -> BinderMcpTransport(context, ComponentName.unflattenFromString(server.address)!!)
            McpServerInfo.Kind.NETWORK -> HttpMcpTransport(server.address)
        }
        val client = McpClient(transport, clientName = "Euno")
        val alive: Boolean get() = (transport as? BinderMcpTransport)?.alive ?: true
        fun close() = client.close()
    }

    companion object {
        const val ACTION = "com.joctaeng.euno.action.MCP_SERVER"
    }
}

/** MCP pelo Binder: o Euno se liga ao serviço do outro app; funciona offline e sem portas abertas. */
class BinderMcpTransport(private val context: Context, private val component: ComponentName) : McpTransport {
    @Volatile private var server: IEunoMcpServer? = null
    @Volatile var alive = false
        private set
    private var connection: ServiceConnection? = null
    private var callback: IEunoMcpCallback? = null

    override suspend fun open(onMessage: (String) -> Unit) {
        val deliver = onMessage
        callback = object : IEunoMcpCallback.Stub() {
            override fun onMessage(jsonRpcMessage: String) = deliver(jsonRpcMessage)
        }
        val ready = CompletableDeferred<IEunoMcpServer>()
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val s = IEunoMcpServer.Stub.asInterface(binder)
                server = s
                alive = true
                ready.complete(s)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                alive = false
                server = null
            }

            override fun onBindingDied(name: ComponentName) {
                alive = false
                server = null
            }
        }
        connection = conn
        val bound = context.bindService(Intent(McpHub.ACTION).setComponent(component), conn, Context.BIND_AUTO_CREATE)
        if (!bound) throw IllegalStateException("o app ${component.packageName} não aceitou a conexão")
        withTimeout(10_000) { ready.await() }
    }

    override suspend fun send(message: String) {
        val s = server ?: throw IllegalStateException("conexão com ${component.packageName} perdida")
        s.send(message, callback)
    }

    override fun close() {
        alive = false
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        server = null
    }
}
