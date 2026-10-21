package com.github.nepyh.rooter.module.school

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.nepyh.rooter.common.todayInAppZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * NICE 조회 결과를 **날짜 하나 단위**로 들고 있는 서버 메모리 캐시 (+ 백그라운드 갱신).
 *
 * NICE 는 기간(from~to)으로 묻지만 홈(1일)·주간(7일)·계획 생성(몇 주)이 서로 다른 기간을 부르므로,
 * 기간째 저장하면 같은 날짜인데도 캐시가 맞지 않는다. 그래서 받은 결과를 날짜별로 나눠 저장하고,
 * 요청이 오면 캐시에 없는 날짜만 모아 그 범위로 NICE 를 한 번 부른다.
 *
 * - 캐시에 없는 날짜: 그 자리에서 받아 저장 (결과가 없는 날도 [emptyValue] 로 저장해 매번 다시 묻지 않는다)
 * - [refreshAfter] 가 지난 날짜: 저장된 값을 바로 돌려주고, 뒤에서 그 범위를 한 번에 다시 받아 바꾼다.
 *   같은 날짜를 동시에 여러 번 갱신하지 않고, 갱신이 실패하면 옛 값을 그대로 둔다
 * - 실패는 저장하지 않는다 — [get] 의 fetch 가 예외를 던지면 저장 전에 그대로 올라간다
 * - [expireAfterAccess] 동안 아무도 조회하지 않은 날짜는 지운다
 *
 * @param G 날짜 말고 나머지 키 (학교·학년·반 등)
 */
internal class DailyCache<G : Any, V : Any>(
    private val name: String,
    private val emptyValue: V,
    private val refreshAfter: (date: LocalDate, value: V, today: LocalDate) -> Duration,
    private val clock: Clock,
    private val refreshScope: CoroutineScope,
    maximumSize: Long = DEFAULT_MAXIMUM_SIZE,
    expireAfterAccess: Duration = DEFAULT_EXPIRE_AFTER_ACCESS
) {
    private data class Key<G>(val group: G, val date: LocalDate)
    private class Entry<V>(val value: V, val fetchedAt: Instant)

    private val cache: Cache<Key<G>, Entry<V>> = Caffeine.newBuilder()
        .maximumSize(maximumSize)
        .expireAfterAccess(expireAfterAccess)
        .build()
    private val refreshing: MutableSet<Key<G>> = ConcurrentHashMap.newKeySet()
    private val refreshJobs: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    /**
     * [group] 의 [from]~[to] 날짜별 값. [fetch] 는 받은 범위의 날짜별 결과를 돌려준다 (결과가 없는 날짜는 빠져도 된다).
     */
    suspend fun get(
        group: G,
        from: LocalDate,
        to: LocalDate,
        fetch: suspend (from: LocalDate, to: LocalDate) -> Map<LocalDate, V>
    ): Map<LocalDate, V> {
        val dates = datesBetween(from, to)
        val cached = dates.associateWith { cache.getIfPresent(Key(group, it)) }

        val missing = dates.filter { cached[it] == null }
        val fetched = if (missing.isEmpty()) {
            emptyMap()
        } else {
            logger.info("NICE 캐시 {} 미스 {}일 ({}~{})", name, missing.size, missing.first(), missing.last())
            fetch(missing.first(), missing.last()).also { store(group, missing.first(), missing.last(), it) }
        }

        val now = clock.instant()
        val today = todayInAppZone(clock)
        val stale = dates.filter { date ->
            val entry = cached[date] ?: return@filter false
            Duration.between(entry.fetchedAt, now) >= refreshAfter(date, entry.value, today)
        }
        if (stale.isNotEmpty()) refreshInBackground(group, stale, fetch)

        return dates.associateWith { date -> cached[date]?.value ?: fetched[date] ?: emptyValue }
    }

    /** 테스트용 — 지금 돌고 있는 백그라운드 갱신이 끝날 때까지 기다린다 */
    internal suspend fun awaitRefreshes() = refreshJobs.toList().joinAll()

    private fun store(group: G, from: LocalDate, to: LocalDate, values: Map<LocalDate, V>) {
        val fetchedAt = clock.instant()
        datesBetween(from, to).forEach { date -> cache.put(Key(group, date), Entry(values[date] ?: emptyValue, fetchedAt)) }
    }

    private fun refreshInBackground(group: G, staleDates: List<LocalDate>, fetch: suspend (LocalDate, LocalDate) -> Map<LocalDate, V>) {
        // 이미 다른 요청이 갱신 중인 날짜는 빼고, 남은 날짜를 감싸는 범위로 한 번만 다시 받는다
        val claimed = staleDates.filter { refreshing.add(Key(group, it)) }
        if (claimed.isEmpty()) return
        val from = claimed.first()
        val to = claimed.last()

        // 바로 끝나는 갱신이 목록에 남지 않게, 등록을 먼저 하고 시작한다
        val job = refreshScope.launch(start = CoroutineStart.LAZY) {
            try {
                store(group, from, to, fetch(from, to))
                logger.info("NICE 캐시 {} 백그라운드 갱신 {}~{}", name, from, to)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("NICE 캐시 {} 백그라운드 갱신 실패 {}~{} — 옛 값 유지: {}", name, from, to, e.message)
            } finally {
                claimed.forEach { refreshing.remove(Key(group, it)) }
            }
        }
        refreshJobs += job
        job.invokeOnCompletion { refreshJobs -= job }
        job.start()
    }

    private fun datesBetween(from: LocalDate, to: LocalDate): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }.toList()

    companion object {
        private val logger = LoggerFactory.getLogger(DailyCache::class.java)
        const val DEFAULT_MAXIMUM_SIZE = 20_000L
        val DEFAULT_EXPIRE_AFTER_ACCESS: Duration = Duration.ofDays(7)
    }
}
