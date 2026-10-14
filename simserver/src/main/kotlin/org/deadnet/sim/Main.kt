package org.deadnet.sim

/**
 * Entry point for the DeadNet mesh simulator.
 *
 * Usage:
 *   ./gradlew run --args="3"        # 3 virtual nodes (default topology: chain)
 *
 * Optional positional args:
 *   <nodeCount>  number of virtual nodes (default 3)
 */
fun main(args: Array<String>) {
    val nodeCount = args.firstOrNull()?.toIntOrNull() ?: 3

    if (nodeCount < 2) {
        println("Need at least 2 nodes to form a mesh; got $nodeCount")
        return
    }

    val server = SimServer(nodeCount)
    server.start()
    server.repl()
    server.stop()
}
