package org.deadnet.sim

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap

/**
 * TCP-server harness for the DeadNet mesh simulation. Creates N virtual nodes,
 * wires them into a configurable localhost mesh topology, and exposes an
 * interactive REPL so routing/transport can be exercised over real sockets.
 *
 * REPL commands:
 *   nodes                — list all nodes with their id/port/peers
 *   link <n1> <n2>       — connect node n1 to node n2 over a real localhost socket
 *   send <from> <to> <text> — inject a Spray-and-Wait message from node to node
 *   state [n]            — print each node's buffer/message state (or one node)
 *   quit                 — stop all nodes and exit
 */
class SimServer(private val nodeCount: Int) {

    private val nodes = ArrayList<SimNode>(nodeCount)
    private val listenerThreads = mutableListOf<Thread>()

    /** Static registry: nodeIndex -> SimNode, used to resolve routing targets. */
    private val byIndex = ConcurrentHashMap<Int, SimNode>()

    /** Registry: nodeId (8-hex) -> key, used to resolve packet recipients. */
    companion object {
        val nodeKeys = ConcurrentHashMap<String, ByteArray>()

        /**
         * Resolve a route target to a 32-byte key. The REPL uses 1-based node
         * indices ("3" = nodes[2]), so a numeric target is converted to the
         * 0-based registry key before lookup. An 8-char hex node id is also
         * accepted.
         */
        fun resolvePeerKey(target: String): ByteArray? {
            val asIndex = target.toIntOrNull()
            if (asIndex != null) {
                return SimServerRegistry.byIndex(asIndex - 1)?.key
            }
            return nodeKeys[target]
        }
    }

    /** Start all nodes and wire the default topology. */
    fun start() {
        println("DeadNet Mesh Simulator (${nodeCount} virtual nodes)")
        val basePort = 18000

        for (i in 0 until nodeCount) {
            val port = basePort + i
            val node = SimNode(
                name = "N${i + 1}",
                listenPort = port,
                logger = { msg -> println("  $msg") },
                onDelivered = { pkt, idx ->
                    val text = try { pkt.payloadText() } catch (_: Exception) { "<binary>" }
                    println("*** NODE ${idx + 1} DELIVERED message: \"$text\" from ${pkt.senderNodeId()} ***")
                }
            )
            node.index = i
            nodes.add(node)
            byIndex[i] = node
            SimServerRegistry.register(i, node)
            nodeKeys[node.nodeId] = node.key
            node.start()
        }
        println("Created ${nodeCount} nodes:")
        nodes.forEach { println("   [${it.index + 1}] ${it.nodeId} @ 127.0.0.1:${it.listenPort} (${it.name})") }

        // Default topology: chain (0-1, 1-2, ...).
        wireChain()
        println("Default topology wired as a chain: ${nodes.joinToString(" <-> ") { "N${it.index + 1}" }}")
        println("Type 'help' for commands.")
    }

    private fun wireChain() {
        for (i in 0 until nodes.size - 1) {
            link(nodes[i].index + 1, nodes[i + 1].index + 1)
        }
    }

    /** Connect two nodes over real localhost TCP sockets (bidirectional handshake). */
    fun link(aIndex: Int, bIndex: Int): Boolean {
        val a = byIndex[aIndex - 1] ?: run { println("Unknown node: $aIndex"); return false }
        val b = byIndex[bIndex - 1] ?: run { println("Unknown node: $bIndex"); return false }
        if (a == b) { println("Cannot link a node to itself"); return false }

        // b already listening; a dials b. Both exchange handshakes on their own sockets.
        a.connectTo("127.0.0.1", b.listenPort, b.nodeId)
        println("Linked N${a.index + 1} (${a.nodeId}) <-> N${b.index + 1} (${b.nodeId})")
        return true
    }

    /** Parse and run one console command. Returns true to keep looping, false to quit. */
    fun runCommand(line: String): Boolean {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.isEmpty() || parts[0].isEmpty()) return true

        when (parts[0].lowercase()) {
            "help" -> {
                println("Commands: nodes | link <n1> <n2> | send <from> <to> <text> | state [n] | quit")
            }
            "nodes" -> {
                nodes.forEach { n ->
                    println("[${n.index + 1}] ${n.nodeId} @ 127.0.0.1:${n.listenPort} (${n.name})")
                    println("      ${n.state().lines()[1].trim()}")
                }
            }
            "link" -> {
                if (parts.size < 3) { println("Usage: link <n1> <n2>"); return true }
                val a = parts[1].toIntOrNull()
                val b = parts[2].toIntOrNull()
                if (a == null || b == null) { println("Invalid node indices"); return true }
                link(a, b)
            }
            "send" -> {
                if (parts.size < 4) { println("Usage: send <from> <to> <text...>"); return true }
                val fromIdx = parts[1].toIntOrNull()
                if (fromIdx == null) { println("Invalid from node"); return true }
                val to = parts[2]
                val text = parts.drop(3).joinToString(" ")
                val fromNode = byIndex[fromIdx - 1] ?: run { println("Unknown from node: $fromIdx"); return true }
                if (resolvePeerKey(to) == null) {
                    println("Unknown to node: $to")
                    return true
                }
                println("Injecting message from N${fromNode.index + 1} -> $to: \"$text\"")
                fromNode.sendMessage(to, text)
            }
            "state" -> {
                if (parts.size >= 2) {
                    val idx = parts[1].toIntOrNull()
                    if (idx != null) {
                        val n = byIndex[idx - 1]
                        if (n == null) println("Unknown node: $idx") else println(n.state())
                    } else {
                        println("Unknown node: ${parts[1]}")
                    }
                } else {
                    nodes.forEach { println(it.state()) }
                }
            }
            "quit", "exit" -> {
                stop()
                return false
            }
            else -> println("Unknown command: ${parts[0]} (type 'help')")
        }
        return true
    }

    /** Interactive REPL loop on stdin. */
    fun repl() {
        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            print("sim> ")
            System.out.flush()
            val line = reader.readLine() ?: break
            val cont = runCommand(line)
            if (!cont) break
        }
    }

    fun stop() {
        println("Shutting down...")
        nodes.forEach { it.stop() }
        listenerThreads.forEach { try { it.interrupt() } catch (_: Exception) {} }
    }
}

/**
 * Bridge so SimServer.resolvePeerKey can look up nodes by index without
 * circular initialization. Holds the canonical node table built at startup.
 */
object SimServerRegistry {
    private val nodes = ConcurrentHashMap<Int, SimNode>()
    fun register(index: Int, node: SimNode) { nodes[index] = node }
    fun byIndex(index: Int): SimNode? = nodes[index]
    fun clear() { nodes.clear() }
}
