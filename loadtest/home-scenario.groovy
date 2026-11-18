import static net.grinder.script.Grinder.grinder
import static org.hamcrest.Matchers.is
import static org.junit.Assert.assertThat

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import net.grinder.script.GTest
import net.grinder.scriptengine.groovy.junit.GrinderRunner
import net.grinder.scriptengine.groovy.junit.annotation.BeforeProcess
import net.grinder.scriptengine.groovy.junit.annotation.BeforeThread
import org.junit.Test
import org.junit.runner.RunWith
import org.ngrinder.http.HTTPRequest
import org.ngrinder.http.HTTPRequestControl
import org.ngrinder.http.HTTPResponse

import java.time.LocalDate

/**
 * 학생이 앱을 열었을 때 부르는 조회 API (LLM 을 부르는 API 는 넣지 않는다).
 * 가상 사용자(스레드)마다 시드 계정 하나로 한 번 로그인하고, 매 반복마다 아래 6개를 차례로 부른다.
 *   내 정보 → 플랜보드 목록 → 오늘 할일 → 주간 할일 → 이번 달 캘린더 → 안 읽은 알림 수
 * 시드: loadtest/seed.sql (loadtest1@rooter.test ~ loadtest300@rooter.test)
 * 대상 주소는 스크립트 파라미터로 바꿀 수 있다 (기본 http://host.docker.internal:8080)
 */
@RunWith(GrinderRunner)
class TestRunner {
    static final int SEED_USERS = 300
    static final String PASSWORD = "loadtest1234!"

    public static String baseUrl
    public static GTest me, boards, today, week, calendar, unread

    HTTPRequest request
    Map<String, String> authHeaders
    int userId

    @BeforeProcess
    static void beforeProcess() {
        HTTPRequestControl.setConnectionTimeout(30000)
        // HTTP 플러그인은 워커 스레드에서 처음 만들면 초기화에 실패한다 — 프로세스 단계에서 한 번 만들어 둔다
        new HTTPRequest()
        String param = System.getProperty("param")
        baseUrl = (param == null || param.isEmpty()) ? "http://host.docker.internal:8080" : param
        me = new GTest(1, "GET /api/users/{id}")
        boards = new GTest(2, "GET /api/plan-boards")
        today = new GTest(3, "GET /api/plan-tasks?date")
        week = new GTest(4, "GET /api/plan-tasks/week")
        calendar = new GTest(5, "GET /api/calendar")
        unread = new GTest(6, "GET /api/notifications/unread-count")
    }

    @BeforeThread
    void beforeThread() {
        me.record(this, "getMe")
        boards.record(this, "getBoards")
        today.record(this, "getToday")
        week.record(this, "getWeek")
        calendar.record(this, "getCalendar")
        unread.record(this, "getUnread")
        grinder.statistics.delayReports = true

        // 스레드마다 다른 계정: (agent, process, thread) 순번을 시드 계정 수로 나눈 나머지
        int threads = grinder.properties.getInt("grinder.threads", 1)
        int processes = grinder.properties.getInt("grinder.processes", 1)
        int index = (grinder.agentNumber * processes + grinder.processNumber) * threads + grinder.threadNumber
        String email = "loadtest${index % SEED_USERS + 1}@rooter.test"

        request = new HTTPRequest()
        request.setHeaders(["Content-Type": "application/json"])
        HTTPResponse login = request.POST("${baseUrl}/api/auth/login",
            JsonOutput.toJson([email: email, password: PASSWORD]).getBytes("UTF-8"))
        assertThat(login.statusCode, is(200))
        def body = new JsonSlurper().parseText(login.bodyText)
        userId = body.userId as int
        authHeaders = ["Authorization": "Bearer ${body.token}".toString()]
    }

    private void get(String path) {
        request.setHeaders(authHeaders)
        HTTPResponse response = request.GET("${baseUrl}${path}")
        if (response.statusCode != 200) {
            grinder.logger.warn("{} -> {}", path, response.statusCode)
        }
        assertThat(response.statusCode, is(200))
    }

    @Test
    void getMe() { get("/api/users/${userId}") }

    @Test
    void getBoards() { get("/api/plan-boards") }

    @Test
    void getToday() { get("/api/plan-tasks?date=${LocalDate.now()}") }

    @Test
    void getWeek() { get("/api/plan-tasks/week?date=${LocalDate.now()}") }

    @Test
    void getCalendar() {
        LocalDate first = LocalDate.now().withDayOfMonth(1)
        get("/api/calendar?start=${first}&end=${first.plusMonths(1).minusDays(1)}")
    }

    @Test
    void getUnread() { get("/api/notifications/unread-count") }
}
