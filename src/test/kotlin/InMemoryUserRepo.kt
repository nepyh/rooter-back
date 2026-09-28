import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.user.UserRepo
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.model.DayOfWeek
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.StudentProfileTable
import com.github.nepyh.rooter.module.user.model.UnavailableTimeRow
import com.github.nepyh.rooter.module.user.model.UnavailableTimeTable
import com.github.nepyh.rooter.module.user.model.UserRow
import com.github.nepyh.rooter.module.user.model.UserTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime


/**
 * DB 없이 도는 UserRepo 인메모리 구현체 (테스트 전용).
 *
 * Exposed DAO 엔티티는 트랜잭션 없이는 만들 수 없다 — EntityClass.wrap/wrapRow 가 TransactionManager.current() 를
 * 요구하기 때문. 대신 Entity._readValues 는 공개 프로퍼티라, 저장해 둔 값을 ResultRow 로 채워 넣으면
 * DB 에서 읽어온 것과 같은 상태의 엔티티를 조립할 수 있다. 그래서 여기서는 컬럼을 직접 채운다.
 * 따라서 아래 toRow() 에서 채우지 않은 컬럼을 서비스 코드가 읽으면 실패한다 — 필요해지면 여기 매핑도 늘릴 것.
 */
class InMemoryUserRepo : UserRepo {

    private data class StoredUser(
        val id: Int,
        var email: String,
        var username: String,
        var password: String,
        var avatarImageKey: String?,
        var bio: String?,
        val createdAt: OffsetDateTime,
        var tokenVersion: Int,
    )

    private data class StoredProfile(
        val id: Int,
        val userId: Int,
        val schoolId: String,
        val grade: Int,
        val classNumber: Int,
    )

    private data class StoredUnavailableTime(
        val id: Int,
        val userId: Int,
        val dayOfWeek: DayOfWeek,
        val startTime: LocalTime,
        val endTime: LocalTime,
    )

    private data class StoredTask(val userId: Int, val planDate: LocalDate, val isCompleted: Boolean)

    private val users = LinkedHashMap<Int, StoredUser>()
    private val profiles = mutableListOf<StoredProfile>()
    private val unavailableTimes = mutableListOf<StoredUnavailableTime>()
    private val tasks = mutableListOf<StoredTask>()
    private var nextUserId = 1
    private var nextProfileId = 1
    private var nextUnavailableTimeId = 1

    /** planboard 쪽 데이터가 필요한 테스트는 직접 심는다 (getStreak 등). */
    fun seedTask(userId: Int, planDate: LocalDate, isCompleted: Boolean) {
        tasks += StoredTask(userId, planDate, isCompleted)
    }

    override fun insertUser(
        email: String,
        username: String,
        password: String,
        avatarImageKey: String?,
        bio: String?
    ): UserRow {
        val stored = StoredUser(
            id = nextUserId++,
            email = email,
            username = username,
            password = password,
            avatarImageKey = avatarImageKey,
            bio = bio,
            createdAt = OffsetDateTime.now(),
            tokenVersion = 0,
        )
        users[stored.id] = stored
        return stored.toRow()
    }

    override fun findUserByEmail(email: String): UserRow? =
        users.values.firstOrNull { it.email == email }?.toRow()

    override fun findUserById(id: Int): UserRow? = users[id]?.toRow()

    override fun updateAvatarImageKey(userId: Int, avatarImageKey: String): UserRow {
        val stored = users[userId] ?: throw UserNotFoundException()
        stored.avatarImageKey = avatarImageKey
        return stored.toRow()
    }

    override fun updateProfile(userId: Int, username: String?, bio: String?): UserRow {
        val stored = users[userId] ?: throw UserNotFoundException()
        username?.let { stored.username = it }
        bio?.let { stored.bio = it }
        return stored.toRow()
    }

    override fun updatePassword(userId: Int, hashedPassword: String): UserRow {
        val stored = users[userId] ?: throw UserNotFoundException()
        stored.password = hashedPassword
        stored.tokenVersion += 1
        return stored.toRow()
    }

    override fun incrementTokenVersion(userId: Int): UserRow {
        val stored = users[userId] ?: throw UserNotFoundException()
        stored.tokenVersion += 1
        return stored.toRow()
    }

    override fun findStudentProfileByUserId(userId: Int): StudentProfileRow? =
        profiles.firstOrNull { it.userId == userId }?.toRow()

    override fun findUnavailableTimesByUserId(userId: Int): List<UnavailableTimeRow> =
        unavailableTimes.filter { it.userId == userId }.map { it.toRow() }

    override fun insertStudentProfile(
        userId: Int,
        schoolId: String,
        grade: Int,
        classNumber: Int
    ): StudentProfileRow {
        users[userId] ?: throw UserNotFoundException()
        val stored = StoredProfile(
            id = nextProfileId++,
            userId = userId,
            schoolId = schoolId,
            grade = grade,
            classNumber = classNumber,
        )
        profiles += stored
        return stored.toRow()
    }

    override fun findTaskRowsByDateRange(userId: Int, start: LocalDate, end: LocalDate): Map<LocalDate, List<ResultRow>> =
        tasks.filter { it.userId == userId && it.planDate in start..end }
            .groupBy { it.planDate }
            .mapValues { (_, rows) -> rows.map { it.toRow() } }

    override fun insertUnavailableTime(
        userId: Int,
        dayOfWeek: DayOfWeek,
        startTime: LocalTime,
        endTime: LocalTime
    ): UnavailableTimeRow {
        users[userId] ?: throw UserNotFoundException()
        val stored = StoredUnavailableTime(
            id = nextUnavailableTimeId++,
            userId = userId,
            dayOfWeek = dayOfWeek,
            startTime = startTime,
            endTime = endTime,
        )
        unavailableTimes += stored
        return stored.toRow()
    }

    /** 삭제된 row 수를 반환한다. 0이면 존재하지 않거나 본인 소유가 아님. */
    override fun deleteUnavailableTime(userId: Int, timeId: Int): Int {
        val targets = unavailableTimes.filter { it.userId == userId && it.id == timeId }
        unavailableTimes.removeAll(targets)
        return targets.size
    }

    private fun StoredUser.toRow(): UserRow = UserRow(EntityID(id, UserTable)).also { row ->
        row._readValues = ResultRow.createAndFillValues(
            mapOf(
                UserTable.id to EntityID(id, UserTable),
                UserTable.email to email,
                UserTable.username to username,
                UserTable.password to password,
                UserTable.avatarImageKey to avatarImageKey,
                UserTable.bio to bio,
                UserTable.createdAt to createdAt,
                UserTable.tokenVersion to tokenVersion,
            )
        )
    }

    private fun StoredProfile.toRow(): StudentProfileRow = StudentProfileRow(EntityID(id, StudentProfileTable)).also { row ->
        row._readValues = ResultRow.createAndFillValues(
            mapOf(
                StudentProfileTable.id to EntityID(id, StudentProfileTable),
                StudentProfileTable.schoolId to schoolId,
                StudentProfileTable.grade to grade,
                StudentProfileTable.classNumber to classNumber,
            )
        )
    }

    private fun StoredUnavailableTime.toRow(): UnavailableTimeRow =
        UnavailableTimeRow(EntityID(id, UnavailableTimeTable)).also { row ->
            row._readValues = ResultRow.createAndFillValues(
                mapOf(
                    UnavailableTimeTable.id to EntityID(id, UnavailableTimeTable),
                    UnavailableTimeTable.dayOfWeek to dayOfWeek,
                    UnavailableTimeTable.startTime to startTime,
                    UnavailableTimeTable.endTime to endTime,
                )
            )
        }

    private fun StoredTask.toRow(): ResultRow = ResultRow.createAndFillValues(
        mapOf(PlanTaskTable.isCompleted to isCompleted)
    )
}
