package com.joctaeng.jarvis.mind.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Quando uma tarefa programada roda (v0.28, pedido 65d). */
enum class Repeat { ONCE, DAILY, WEEKDAYS, DAYS }

/**
 * Uma tarefa programada: no horário, o Euno faz [instruction] como se o usuário tivesse pedido, e avisa o resultado
 * (notificação, e fala se [speak]). Ações de risco continuam pedindo confirmação: nada é enviado/pago/apagado sozinho.
 */
data class ScheduledTask(
    val id: String,
    val name: String,
    val instruction: String,
    val hour: Int,
    val minute: Int,
    val repeat: Repeat,
    /** Para [Repeat.DAYS]: 1 = segunda … 7 = domingo (ISO). */
    val days: Set<Int> = emptySet(),
    /** Para [Repeat.ONCE]: o dia. */
    val date: LocalDate? = null,
    val speak: Boolean = false,
    val enabled: Boolean = true,
    val lastRunMillis: Long = 0L,
)

/** Uma execução registrada (histórico curto). */
data class TaskRun(val taskId: String, val name: String, val atMillis: Long, val ok: Boolean, val summary: String)

object TaskSchedule {
    /** Próximo horário de [task] estritamente depois de [now], ou null (uma vez só e já passou / desligada). */
    fun next(task: ScheduledTask, now: Instant, zone: ZoneId): Instant? {
        if (!task.enabled) return null
        val time = LocalTime.of(task.hour.coerceIn(0, 23), task.minute.coerceIn(0, 59))
        val nowZ = now.atZone(zone)
        if (task.repeat == Repeat.ONCE) {
            val day = task.date ?: nowZ.toLocalDate().let { if (ZonedDateTime.of(it, time, zone).toInstant().isAfter(now)) it else it.plusDays(1) }
            val at = ZonedDateTime.of(day, time, zone).toInstant()
            return at.takeIf { it.isAfter(now) && task.lastRunMillis < at.toEpochMilli() }
        }
        for (offset in 0..7L) {
            val day = nowZ.toLocalDate().plusDays(offset)
            if (!runsOn(task, day.dayOfWeek)) continue
            val at = ZonedDateTime.of(day, time, zone).toInstant()
            if (at.isAfter(now)) return at
        }
        return null
    }

    fun runsOn(task: ScheduledTask, dow: DayOfWeek): Boolean = when (task.repeat) {
        Repeat.ONCE, Repeat.DAILY -> true
        Repeat.WEEKDAYS -> dow.value <= 5
        Repeat.DAYS -> dow.value in task.days
    }

    /** "todo dia às 07:00", "dias úteis às 18:30", "seg, qua às 06:00", "uma vez, 15/10 às 17:00". */
    fun describe(task: ScheduledTask): String {
        val hm = "%02d:%02d".format(task.hour, task.minute)
        val names = listOf("seg", "ter", "qua", "qui", "sex", "sáb", "dom")
        return when (task.repeat) {
            Repeat.DAILY -> "todo dia às $hm"
            Repeat.WEEKDAYS -> "dias úteis às $hm"
            Repeat.DAYS -> task.days.sorted().joinToString(", ") { names[(it - 1).coerceIn(0, 6)] } + " às $hm"
            Repeat.ONCE -> "uma vez" + (task.date?.let { ", %02d/%02d".format(it.dayOfMonth, it.monthValue) } ?: "") + " às $hm"
        }
    }

    /** "seg", "segunda", "terça-feira", "sab" → 1..7 (ISO). Desconhecido → null. */
    fun dayNumber(word: String): Int? {
        val w = SkillBook.normalize(word).take(3)
        return listOf("seg", "ter", "qua", "qui", "sex", "sab", "dom").indexOf(w).takeIf { it >= 0 }?.plus(1)
    }
}

/** As tarefas programadas do usuário + o histórico das últimas execuções. JSON privado do app. */
class TaskBook(private val file: File?, private val clock: () -> Long = System::currentTimeMillis, private val maxHistory: Int = 30) {
    private val json = Json { prettyPrint = true }
    private val tasks = mutableListOf<ScheduledTask>()
    private val history = mutableListOf<TaskRun>()

    init {
        load()
    }

    @Synchronized fun all(): List<ScheduledTask> = tasks.toList()
    @Synchronized fun history(): List<TaskRun> = history.toList()
    @Synchronized fun get(id: String): ScheduledTask? = tasks.firstOrNull { it.id == id }

    @Synchronized
    fun add(task: ScheduledTask): ScheduledTask {
        val t = if (task.id.isBlank()) task.copy(id = "tarefa-${clock()}") else task
        tasks.removeAll { it.id == t.id }
        tasks += t
        save()
        return t
    }

    /** Pelo id ou pelo nome (sem acento/maiúscula). */
    @Synchronized
    fun find(idOrName: String): ScheduledTask? {
        val key = SkillBook.normalize(idOrName)
        return tasks.firstOrNull { it.id == idOrName } ?: tasks.firstOrNull { SkillBook.normalize(it.name) == key }
            ?: tasks.firstOrNull { key.isNotBlank() && key in SkillBook.normalize(it.name) }
    }

    @Synchronized
    fun remove(idOrName: String): ScheduledTask? {
        val t = find(idOrName) ?: return null
        tasks.remove(t)
        save()
        return t
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        val i = tasks.indexOfFirst { it.id == id }
        if (i < 0) return
        tasks[i] = tasks[i].copy(enabled = enabled)
        save()
    }

    /** "Parar todas": desliga todas de uma vez. */
    @Synchronized
    fun disableAll() {
        for (i in tasks.indices) tasks[i] = tasks[i].copy(enabled = false)
        save()
    }

    @Synchronized
    fun recordRun(taskId: String, ok: Boolean, summary: String) {
        val now = clock()
        val i = tasks.indexOfFirst { it.id == taskId }
        val name = tasks.getOrNull(i)?.name ?: taskId
        if (i >= 0) {
            val t = tasks[i]
            // Tarefa de uma vez só: depois de rodar, fica desligada (some da agenda de alarmes, mas o histórico fica).
            tasks[i] = t.copy(lastRunMillis = now, enabled = t.enabled && t.repeat != Repeat.ONCE)
        }
        history += TaskRun(taskId, name, now, ok, summary.take(300))
        while (history.size > maxHistory) history.removeAt(0)
        save()
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val root = json.parseToJsonElement(f.readText()).jsonObject
            root["tasks"]?.jsonArray?.forEach { el ->
                val o = el.jsonObject
                tasks += ScheduledTask(
                    id = o["id"]!!.jsonPrimitive.content,
                    name = o["name"]!!.jsonPrimitive.content,
                    instruction = o["instruction"]!!.jsonPrimitive.content,
                    hour = o["hour"]!!.jsonPrimitive.int,
                    minute = o["minute"]!!.jsonPrimitive.int,
                    repeat = runCatching { Repeat.valueOf(o["repeat"]!!.jsonPrimitive.content) }.getOrDefault(Repeat.DAILY),
                    days = (o["days"] as? JsonArray)?.map { it.jsonPrimitive.int }?.toSet().orEmpty(),
                    date = o["date"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { LocalDate.parse(it) },
                    speak = o["speak"]?.jsonPrimitive?.boolean ?: false,
                    enabled = o["enabled"]?.jsonPrimitive?.boolean ?: true,
                    lastRunMillis = o["lastRun"]?.jsonPrimitive?.long ?: 0L,
                )
            }
            root["history"]?.jsonArray?.forEach { el ->
                val o = el.jsonObject
                history += TaskRun(
                    o["taskId"]!!.jsonPrimitive.content, o["name"]!!.jsonPrimitive.content, o["at"]!!.jsonPrimitive.long,
                    o["ok"]!!.jsonPrimitive.boolean, o["summary"]!!.jsonPrimitive.content,
                )
            }
        }
    }

    private fun save() {
        val f = file ?: return
        val root = buildJsonObject {
            put(
                "tasks",
                buildJsonArray {
                    tasks.forEach { t ->
                        add(
                            buildJsonObject {
                                put("id", t.id); put("name", t.name); put("instruction", t.instruction)
                                put("hour", t.hour); put("minute", t.minute); put("repeat", t.repeat.name)
                                put("days", buildJsonArray { t.days.sorted().forEach { add(JsonPrimitive(it)) } })
                                put("date", t.date?.toString() ?: "")
                                put("speak", t.speak); put("enabled", t.enabled); put("lastRun", t.lastRunMillis)
                            },
                        )
                    }
                },
            )
            put(
                "history",
                buildJsonArray {
                    history.forEach { r ->
                        add(buildJsonObject { put("taskId", r.taskId); put("name", r.name); put("at", r.atMillis); put("ok", r.ok); put("summary", r.summary) })
                    }
                },
            )
        }
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString(JsonObject.serializer(), root))
            tmp.renameTo(f)
        }
    }
}
