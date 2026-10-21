package com.yeknomusic.ftwbridge

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Mass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** Settings shared by the screen and the background job. */
object Bridge {
    private const val PREFS = "ftwbridge"
    const val BACKGROUND_READ = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getWritePermission(WeightRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Database URL + pairing code, received from the phone app's "Connect Samsung Health" link. */
    fun config(ctx: Context): Pair<String, String>? {
        val p = prefs(ctx)
        val url = p.getString("url", null) ?: return null
        val code = p.getString("code", null) ?: return null
        return url to code
    }

    fun saveConfig(ctx: Context, url: String, code: String) {
        prefs(ctx).edit().putString("url", url).putString("code", code).apply()
    }
}

data class SyncSummary(
    val workouts: Int,
    val weightsToHealth: Int,
    val weightsFromHealth: Int,
    val stepDays: Int,
    val errors: List<String>,
) {
    fun text(): String {
        val parts = mutableListOf<String>()
        parts += if (workouts > 0) "$workouts workout${if (workouts == 1) "" else "s"} saved to Samsung Health" else "Workouts up to date"
        if (weightsToHealth > 0) parts += "$weightsToHealth weight${if (weightsToHealth == 1) "" else "s"} sent to Samsung Health"
        if (weightsFromHealth > 0) parts += "$weightsFromHealth weight${if (weightsFromHealth == 1) "" else "s"} sent to your app"
        if (stepDays > 0) parts += "steps & heart rate updated"
        return parts.joinToString("\n") + if (errors.isNotEmpty()) "\n\n⚠ " + errors.joinToString("\n⚠ ") else ""
    }
}

/**
 * One sync pass between the FitTracker sync database (Firebase REST API) and Health Connect,
 * which Samsung Health reads from and writes to.
 */
class SyncEngine(private val ctx: Context, url: String, code: String) {
    private val base = url.trimEnd('/') + "/sync/" + code
    private val zone: ZoneId = ZoneId.systemDefault()
    private val prefs = Bridge.prefs(ctx)

    private data class SetRow(val exId: String, val idx: Int, val lb: Double?, val reps: Int?, val t: Long)

    suspend fun run(foreground: Boolean): SyncSummary = withContext(Dispatchers.IO) {
        val hc = HealthConnectClient.getOrCreate(ctx)
        val granted = hc.permissionController.getGrantedPermissions()
        val canRead = foreground || Bridge.BACKGROUND_READ in granted
        val errors = mutableListOf<String>()
        val since = LocalDate.now(zone).minusDays(14)

        var workouts = 0
        var wOut = 0
        var wIn = 0
        var stepDays = 0

        if (HealthPermission.getWritePermission(ExerciseSessionRecord::class) in granted) {
            try { workouts = syncWorkouts(hc, since) } catch (e: Exception) { errors += "Workouts: ${e.message}" }
        }
        val fbWeights = try { readJson("/wt.json") } catch (e: Exception) { errors += "Weight: ${e.message}"; null }
        if (fbWeights != null && HealthPermission.getWritePermission(WeightRecord::class) in granted) {
            try { wOut = sendPhoneWeights(hc, fbWeights) } catch (e: Exception) { errors += "Weight to Samsung Health: ${e.message}" }
        }
        if (fbWeights != null && canRead && HealthPermission.getReadPermission(WeightRecord::class) in granted) {
            try { wIn = pullHealthWeights(hc, fbWeights, since) } catch (e: Exception) { errors += "Weight from Samsung Health: ${e.message}" }
        }
        if (canRead) {
            try { stepDays = pullActivity(hc, granted) } catch (e: Exception) { errors += "Steps/heart rate: ${e.message}" }
        }

        // status for the phone app's Watch card
        val status = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("n", workouts)
            .put("err", if (errors.isEmpty()) JSONObject.NULL else errors.joinToString("; "))
        try { patch(JSONObject().put("hc", status)) } catch (_: Exception) {}

        SyncSummary(workouts, wOut, wIn, stepDays, errors)
    }

    /* ---------------- workouts → Samsung Health ---------------- */

    private suspend fun syncWorkouts(hc: HealthConnectClient, since: LocalDate): Int {
        val days = readDays(since) ?: return 0
        val records = mutableListOf<Record>()
        val versions = mutableMapOf<String, Long>()

        for (date in days.keys()) {
            val day = days.optJSONObject(date) ?: continue
            val local = try { LocalDate.parse(date) } catch (_: Exception) { continue }
            if (local.isBefore(since)) continue

            // group the day's finished sets by plan (Push Day A, ...)
            val byPlan = mutableMapOf<String, MutableList<SetRow>>()
            for (exId in day.keys()) {
                val plan = PLAN_EXERCISES[exId] ?: continue
                for ((idx, cell) in children(day.opt(exId))) {
                    if (!cell.optBoolean("d")) continue
                    val lb = if (cell.isNull("l")) null else cell.optDouble("l").takeIf { !it.isNaN() }
                    val reps = if (cell.isNull("r")) null else cell.optInt("r")
                    byPlan.getOrPut(plan.planKey) { mutableListOf() } += SetRow(exId, idx, lb, reps, cell.optLong("t"))
                }
            }

            for ((planKey, rows) in byPlan) {
                val id = "ftw-$date-$planKey"
                val version = rows.maxOf { it.t }.coerceAtLeast(1L)
                if (prefs.getLong("v:$id", 0L) >= version) continue          // already saved this version
                records += sessionFor(local, rows, id, version) ?: continue
                versions[id] = version
            }
        }
        if (records.isEmpty()) return 0
        hc.insertRecords(records)              // same clientRecordId + higher version = update, not duplicate
        prefs.edit().apply { versions.forEach { (k, v) -> putLong("v:$k", v) } }.apply()
        return records.size
    }

    private fun sessionFor(date: LocalDate, rows: List<SetRow>, id: String, version: Long): ExerciseSessionRecord? {
        val dayStart = date.atStartOfDay(zone).toInstant()
        val dayEnd = dayStart.plus(Duration.ofDays(1))
        val now = Instant.now()
        // set times are when each set was ticked; sets imported later carry the import time, so ignore those
        val times = rows.map { Instant.ofEpochMilli(it.t) }.filter { !it.isBefore(dayStart) && it.isBefore(dayEnd) }
        var start: Instant
        var end: Instant
        if (times.isNotEmpty()) {
            start = times.min().minus(Duration.ofMinutes(3))
            end = times.max().plus(Duration.ofMinutes(1))
        } else {
            start = dayStart.plus(Duration.ofHours(18))
            end = start.plus(Duration.ofMinutes(3L * rows.size))
        }
        if (start.isBefore(dayStart)) start = dayStart
        if (end.isAfter(now)) end = now
        if (!end.isAfter(start)) return null

        val plan = PLAN_EXERCISES.getValue(rows.first().exId)
        val order = PLAN_EXERCISES.keys.toList()
        val notes = rows.groupBy { it.exId }.toSortedMap(compareBy<String> { order.indexOf(it) }).map { (exId, sets) ->
            val name = PLAN_EXERCISES[exId]?.name ?: exId
            name + ": " + sets.sortedBy { it.idx }.joinToString(", ") { s ->
                val r = s.reps?.toString() ?: "?"
                if (s.lb != null && s.lb > 0) "${fmtLb(s.lb)}×$r" else "$r reps"
            }
        }.joinToString("\n")

        return ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            title = plan.planName,
            notes = notes.take(1000),
            metadata = Metadata.manualEntry(clientRecordId = id, clientRecordVersion = version),
        )
    }

    /* ---------------- weight, both ways ---------------- */

    /** Weights logged in the phone app → Samsung Health. */
    private suspend fun sendPhoneWeights(hc: HealthConnectClient, fb: JSONObject): Int {
        val records = mutableListOf<Record>()
        val versions = mutableMapOf<String, Long>()
        for (date in fb.keys()) {
            val w = fb.optJSONObject(date) ?: continue
            if (w.optString("b") != "p") continue
            val lb = w.optDouble("lb")
            val t = w.optLong("t")
            if (lb.isNaN() || lb < 50 || lb > 700) continue
            val id = "ftw-weight-$date"
            if (prefs.getLong("v:$id", 0L) >= t) continue
            val local = try { LocalDate.parse(date) } catch (_: Exception) { continue }
            val dayStart = local.atStartOfDay(zone).toInstant()
            val tInstant = Instant.ofEpochMilli(t)
            val time = if (!tInstant.isBefore(dayStart) && tInstant.isBefore(dayStart.plus(Duration.ofDays(1)))) tInstant
                       else dayStart.plus(Duration.ofHours(8))
            records += WeightRecord(
                time = time,
                zoneOffset = zone.rules.getOffset(time),
                weight = Mass.pounds(lb),
                metadata = Metadata.manualEntry(clientRecordId = id, clientRecordVersion = t),
            )
            versions[id] = t
        }
        if (records.isEmpty()) return 0
        hc.insertRecords(records)
        prefs.edit().apply { versions.forEach { (k, v) -> putLong("v:$k", v) } }.apply()
        return records.size
    }

    /** Weights from Samsung Health (scale, manual entry...) → the phone app. Skips ones this app wrote. */
    private suspend fun pullHealthWeights(hc: HealthConnectClient, fb: JSONObject, since: LocalDate): Int {
        val res = hc.readRecords(
            ReadRecordsRequest(
                WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.between(since.atStartOfDay(zone).toInstant(), Instant.now()),
            )
        )
        val latest = mutableMapOf<String, WeightRecord>()
        for (r in res.records) {
            if (r.metadata.dataOrigin.packageName == ctx.packageName) continue
            val date = r.time.atZone(zone).toLocalDate().toString()
            val cur = latest[date]
            if (cur == null || r.time.isAfter(cur.time)) latest[date] = r
        }
        val body = JSONObject()
        for ((date, r) in latest) {
            val lb = (r.weight.inPounds * 10).roundToInt() / 10.0
            val modified = r.metadata.lastModifiedTime.toEpochMilli()
            val have = fb.optJSONObject(date)
            val send = when {
                have == null -> true
                have.optString("b") == "h" -> abs(have.optDouble("lb") - lb) > 0.05
                else -> modified > have.optLong("t")              // newer than the phone's entry
            }
            if (send) body.put("wt/$date", JSONObject().put("lb", lb).put("t", modified).put("b", "h"))
        }
        if (body.length() > 0) patch(body)
        return body.length()
    }

    /* ---------------- steps & heart rate → the phone app ---------------- */

    private suspend fun pullActivity(hc: HealthConnectClient, granted: Set<String>): Int {
        val out = mutableMapOf<String, JSONObject>()
        val now = System.currentTimeMillis()
        if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
            val start = LocalDate.now(zone).minusDays(6).atStartOfDay()
            val buckets = hc.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(StepsRecord.COUNT_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, LocalDateTime.now(zone)),
                    timeRangeSlicer = Period.ofDays(1),
                )
            )
            for (b in buckets) {
                val steps = b.result[StepsRecord.COUNT_TOTAL] ?: continue
                val date = b.startTime.toLocalDate().toString()
                out.getOrPut(date) { JSONObject().put("t", now) }.put("s", steps)
            }
        }
        if (HealthPermission.getReadPermission(HeartRateRecord::class) in granted) {
            val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val res = hc.readRecords(
                ReadRecordsRequest(HeartRateRecord::class, timeRangeFilter = TimeRangeFilter.between(todayStart, Instant.now()))
            )
            val samples = res.records.flatMap { it.samples }.filter { it.beatsPerMinute in 26L..229L }
            if (samples.isNotEmpty()) {
                val date = LocalDate.now(zone).toString()
                out.getOrPut(date) { JSONObject().put("t", now) }
                    .put("hr", samples.maxBy { it.time }.beatsPerMinute)
                    .put("hrMin", samples.minOf { it.beatsPerMinute })
            }
        }
        if (out.isEmpty()) return 0
        // merge onto whatever's already saved for each day: a sync that only has fresh steps
        // (no new heart-rate samples yet, or vice versa) must not erase the other field.
        val existing = try { readJson("/stats.json") } catch (_: Exception) { null }
        val body = JSONObject()
        for ((date, v) in out) {
            val prevHc = existing?.optJSONObject(date)?.optJSONObject("hc")
            val merged = if (prevHc != null) JSONObject(prevHc.toString()) else JSONObject()
            v.keys().forEach { k -> merged.put(k, v.get(k)) }
            body.put("stats/$date/hc", merged)
        }
        patch(body)
        return out.size
    }

    /* ---------------- database (Firebase REST) ---------------- */

    private fun readDays(since: LocalDate): JSONObject? {
        val q = "?orderBy=" + enc("\"\$key\"") + "&startAt=" + enc("\"$since\"")
        return try { readJson("/d.json$q") } catch (e: IOException) {
            if (e.message?.contains("HTTP 400") == true) readJson("/d.json") else throw e   // filter not allowed: read all
        }
    }

    private fun readJson(path: String): JSONObject? {
        val text = http("GET", path, null).trim()
        return if (text == "null" || text.isEmpty()) JSONObject() else JSONObject(text)
    }

    private fun patch(body: JSONObject) {
        http("PATCH", ".json", body.toString())
    }

    private fun http(method: String, path: String, body: String?): String {
        // HttpURLConnection can't send PATCH; Firebase accepts POST with a method override
        val override = method == "PATCH"
        val sep = if (path.contains('?')) "&" else "?"
        val url = URL(base + path + if (override) "${sep}x-http-method-override=PATCH" else "")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = if (override) "POST" else method
            conn.connectTimeout = 15000
            conn.readTimeout = 20000
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = conn.responseCode
            if (status !in 200..299) throw IOException("HTTP $status")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** Firebase returns set lists as arrays (with null gaps) or as objects keyed "0", "1"... */
    private fun children(v: Any?): List<Pair<Int, JSONObject>> = when (v) {
        is JSONArray -> (0 until v.length()).mapNotNull { i -> v.optJSONObject(i)?.let { i to it } }
        is JSONObject -> v.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { i -> v.optJSONObject(k)?.let { i to it } } }.toList()
        else -> emptyList()
    }

    private fun fmtLb(lb: Double) = if (lb % 1.0 == 0.0) lb.toInt().toString() else lb.toString()
}
