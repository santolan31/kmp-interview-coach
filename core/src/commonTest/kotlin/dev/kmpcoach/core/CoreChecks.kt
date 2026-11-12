package dev.kmpcoach.core

/** Comprobaciones del núcleo, sin IntelliJ. Las usa CoreTest (JUnit) y sirven de guía para validar paquetes de retos propios. */
object CoreChecks {

    fun run(packJson: String): List<String> {
        val fails = ArrayList<String>()
        fun expect(ok: Boolean, msg: String) { if (!ok) fails += msg }

        val warnings = ArrayList<String>()
        val challenges = ChallengeParser.parsePack(packJson, "challenges.json", warnings)
        expect(warnings.isEmpty(), "Advertencias al leer el paquete: $warnings")
        expect(challenges.size >= 13, "Se esperaban 13 retos y hay ${challenges.size}")

        for (c in challenges) {
            val sol = Analyzer.analyze(c.solution, c)
            expect(sol.objectives.all { it.pass }, "${c.id}: la solución no cumple ${sol.objectives.filter { !it.pass }.map { it.label }}")
            expect(sol.diagnostics.isEmpty(), "${c.id}: la solución tiene avisos ${sol.diagnostics.map { "L${it.line + 1} ${it.message}" }}")
            val start = Analyzer.analyze(c.header + "\n" + c.starter, c)
            expect(!start.objectives.all { it.pass }, "${c.id}: el código inicial ya cumple todos los objetivos")
            expect(start.count(Severity.ERROR) == 0, "${c.id}: el código inicial tiene errores ${start.diagnostics}")
            expect(ChallengeParser.markerId(c.header + "\n" + c.starter) == c.id, "${c.id}: no se detecta el marcador")
        }

        val j1 = challenges.first { it.id == "j1" }
        val bug = """
            fun activeAdultNames(users: List<User>): List<String> {
                val out = mutableListOf<String>()
                for (u in users) {
                    if (u.active && u.age > 18) out.add(u.name!!)
                }
                return out.sortedDescending(
            }
        """.trimIndent()
        val a = Analyzer.analyze(bug, j1)
        val msgs = a.diagnostics.map { "L${it.line + 1} ${it.severity}: ${it.message}" }
        expect(msgs.any { it.startsWith("L3 WARN") && "for" in it }, "j1: no detecta el for -> $msgs")
        expect(msgs.any { it.startsWith("L4 WARN") && "> 18" in it }, "j1: no detecta > 18 -> $msgs")
        expect(msgs.any { it.startsWith("L4 WARN") && "!!" in it }, "j1: no detecta !! -> $msgs")
        expect(msgs.any { it.startsWith("L6 ERROR") && "nunca se cierra" in it }, "j1: no detecta el paréntesis sin cerrar -> $msgs")
        expect(Analyzer.analyze(bug, j1, lexical = false).diagnostics.none { it.severity == Severity.ERROR }, "lexical=false debería omitir llaves")

        val strings = "val x = \"hola \${user.name(\"a\")} y }\" // comentario }\nval y = \"\"\"raw { \"\"\"\nval c = '}'"
        expect(Analyzer.analyze(strings, null).diagnostics.isEmpty(), "Textos y comentarios no deberían generar avisos: ${Analyzer.analyze(strings, null).diagnostics}")

        val compose = "@Composable\nfun CounterScreen() {\n    val count by remember { mutableStateOf(0) }\n    val other = mutableStateOf(1)\n}"
        val j2 = challenges.first { it.id == "j2" }
        val cm = Analyzer.analyze(compose, j2).diagnostics.map { "L${it.line + 1} ${it.severity}" }
        expect("L3 ERROR" in cm && "L4 ERROR" in cm, "j2: reglas de Compose -> $cm")

        val vm = "class VM : ViewModel() {\n    val state = MutableStateFlow(0)\n    fun load() { GlobalScope.launch { } }\n}"
        val vmMsgs = Analyzer.analyze(vm, null).diagnostics.map { it.message }
        expect(vmMsgs.any { "MutableStateFlow" in it } && vmMsgs.any { "GlobalScope" in it }, "Reglas generales de ViewModel -> $vmMsgs")

        val review = ReviewParser.parse(
            "Claro, aquí va:\n```json\n{\"estado\":\"a_medias\",\"resumen\":\"Vas bien\",\"comentarios\":[{\"linea\":99,\"tipo\":\"pregunta\",\"mensaje\":\"¿Por qué `for`?\"},{\"linea\":2,\"tipo\":\"raro\",\"mensaje\":\"x\"}],\"siguientePaso\":\"Usa filter\"}\n```",
            lineCount = 10,
        )
        expect(review.status == "a_medias" && review.comments.size == 2, "ReviewParser: $review")
        expect(review.comments.last().line == 10 && review.comments.first().type == CommentType.MEJORA, "ReviewParser normaliza líneas y tipos: $review")

        val obj = mapOf("a" to listOf(1, "dos\n\"tres\"", null, true), "b" to mapOf("c" to 1.5))
        expect(Json.parse(Json.write(obj)) == mapOf("a" to listOf(1L, "dos\n\"tres\"", null, true), "b" to mapOf("c" to 1.5)), "Json ida y vuelta")
        expect(Prompts.reviewSystem(PromptOptions(Level.SEMI, "es", 5)).contains("Semi Senior"), "Prompt de revisión")
        return fails
    }
}
