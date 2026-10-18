package org.relay.sim

import org.relay.core.payload.Lang
import kotlin.system.exitProcess

private const val USAGE = """Relay mesh simulator

  scenario chain --nodes 10            10-node line, origin -> far-end gateway (needs inventory resync beyond TTL 7)
  scenario grid --nodes 50             grid, corner to corner
  scenario partition                   two clusters bridged only by a walker node
  scenario loss --nodes 10 --loss 0.2  chain with random frame loss; must reach 100% after resync rounds
  scenario all                         every scenario above with its defaults
  e2e --backend http://localhost:8080 [--approve] [--text "..."] [--lang or] [--timeout 30] [--poll-ms 1000]
  e2e --fake [--approve]               same, against an in-process fake backend
  locate --backend http://localhost:8080 [--no-help]   mesh-assisted location: a no-fix NEED relayed by two phones with
                                       a fix; passes iff the backend's ticket gets a "mesh" geo estimate near the truth
                                       (--no-help = "Help locate neighbours" off: passes iff no hint is sent)

Common options: --seed N (default 42). Exit code 0 iff delivery is 100%."""

private fun parseOpts(args: List<String>): Map<String, String> {
    val m = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (!a.startsWith("--")) { System.err.println("unexpected argument: $a"); exitProcess(2) }
        val key = a.removePrefix("--")
        val next = args.getOrNull(i + 1)
        if (next != null && !next.startsWith("--")) { m[key] = next; i += 2 } else { m[key] = "true"; i += 1 }
    }
    return m
}

internal fun runScenarios(name: String, o: Map<String, String>, print: (String) -> Unit = ::println): Boolean {
    val seed = o["seed"]?.toLongOrNull() ?: 42L
    val outcomes = ArrayList<Outcome>()
    fun nodes(default: Int) = o["nodes"]?.toIntOrNull() ?: default
    when (name) {
        "chain" -> outcomes += Scenarios.chain(nodes(10), seed)
        "grid" -> outcomes += Scenarios.grid(nodes(50), seed)
        "partition" -> outcomes += Scenarios.partition(seed)
        "loss" -> outcomes += Scenarios.loss(nodes(10), o["loss"]?.toDoubleOrNull() ?: 0.2, seed)
        "all" -> {
            outcomes += Scenarios.chain(10, seed)
            outcomes += Scenarios.grid(50, seed)
            outcomes += Scenarios.partition(seed)
            outcomes += Scenarios.loss(10, 0.2, seed)
        }
        else -> { System.err.println("unknown scenario '$name'\n$USAGE"); return false }
    }
    for (r in outcomes) print(r.render())
    val ok = outcomes.all { it.pass }
    if (outcomes.size > 1) print("\nSUMMARY: ${outcomes.count { it.pass }}/${outcomes.size} scenarios at 100% delivery -> ${if (ok) "PASS" else "FAIL"}")
    return ok
}

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) { println(USAGE); exitProcess(if (args.isEmpty()) 2 else 0) }
    when (args[0]) {
        "scenario" -> {
            val name = args.getOrNull(1) ?: run { println(USAGE); exitProcess(2) }
            val ok = runScenarios(name, parseOpts(args.drop(2)))
            exitProcess(if (ok) 0 else 1)
        }
        "e2e" -> {
            val o = parseOpts(args.drop(1))
            val lang = o["lang"]?.let { Lang.fromTag(it) ?: run { System.err.println("unknown --lang '$it' (en hi or tl war ceb)"); exitProcess(2) } }
            val timeoutMs = ((o["timeout"]?.toDoubleOrNull() ?: 30.0) * 1000).toLong()
            val fake = if (o["fake"] == "true") FakeBackend() else null
            val backend = fake?.baseUrl ?: o["backend"] ?: "http://localhost:8080"
            val cfg = E2eConfig(
                backend = backend, approve = o["approve"] == "true",
                text = o["text"] ?: "my mother needs insulin, near the school",
                lang = lang, timeoutMs = timeoutMs, pollMs = o["poll-ms"]?.toLongOrNull() ?: 1000,
            )
            println("e2e: backend=$backend approve=${cfg.approve} lang=${lang?.tag ?: "auto"}")
            val result = try { E2e.run(cfg) } finally { fake?.close() }
            println(if (result.success) "E2E PASS (ticket #${result.ticketNo})" else "E2E FAIL: ${result.reason}")
            exitProcess(if (result.success) 0 else 1)
        }
        "locate" -> {
            val o = parseOpts(args.drop(1))
            val help = o["no-help"] != "true"
            val cfg = LocateConfig(backend = o["backend"] ?: "http://localhost:8080", helpLocate = help,
                timeoutMs = ((o["timeout"]?.toDoubleOrNull() ?: 30.0) * 1000).toLong())
            println("locate: backend=${cfg.backend} help-locate-neighbours=${if (help) "on" else "off"}")
            val r = LocateE2e.run(cfg)
            println(if (r.success) "LOCATE PASS" else "LOCATE FAIL: ${r.reason}")
            exitProcess(if (r.success) 0 else 1)
        }
        else -> { System.err.println("unknown command '${args[0]}'\n$USAGE"); exitProcess(2) }
    }
}
