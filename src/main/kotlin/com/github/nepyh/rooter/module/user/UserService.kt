package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.module.planboard.SleepTime
import com.github.nepyh.rooter.module.planboard.UserRoutine
import com.github.nepyh.rooter.module.planboard.model.PlanTaskStatus
import com.github.nepyh.rooter.module.storage.FileStorage
import com.github.nepyh.rooter.module.storage.toUploadableFile
import com.github.nepyh.rooter.module.user.dto.AgreementCatalogResponse
import com.github.nepyh.rooter.module.user.dto.AgreementDecision
import com.github.nepyh.rooter.module.user.dto.AgreementDocumentResponse
import com.github.nepyh.rooter.module.user.dto.AgreementsUpdateRequest
import com.github.nepyh.rooter.module.user.dto.AgreementsUpdateResponse
import com.github.nepyh.rooter.module.user.dto.AvatarUpdateResponse
import com.github.nepyh.rooter.module.user.dto.CommuteTimeRequest
import com.github.nepyh.rooter.module.user.dto.CommuteTimeResponse
import com.github.nepyh.rooter.module.user.dto.SleepTimeRequest
import com.github.nepyh.rooter.module.user.dto.SleepTimeResponse
import com.github.nepyh.rooter.module.user.dto.StudentProfileRequest
import com.github.nepyh.rooter.module.user.dto.StudentProfileResponse
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeRequest
import com.github.nepyh.rooter.module.user.dto.UnavailableTimeResponse
import com.github.nepyh.rooter.module.planboard.model.PlanTaskTable
import com.github.nepyh.rooter.module.user.dto.StreakDayResponse
import com.github.nepyh.rooter.module.user.dto.StreakResponse
import com.github.nepyh.rooter.module.user.dto.UpdateProfileRequest
import com.github.nepyh.rooter.module.user.dto.UserInfoResponse
import com.github.nepyh.rooter.module.user.dto.UserProfileUpdateResponse
import com.github.nepyh.rooter.module.user.dto.UserRegisterRequest
import com.github.nepyh.rooter.module.user.dto.UserAgreementResponse
import com.github.nepyh.rooter.module.user.dto.UserAgreementsResponse
import com.github.nepyh.rooter.module.user.dto.UserRegisterResponse
import com.github.nepyh.rooter.module.user.exception.AgreementValidationException
import com.github.nepyh.rooter.module.user.exception.UnavailableTimeNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserNotFoundException
import com.github.nepyh.rooter.module.user.exception.UserValidationException
import com.github.nepyh.rooter.module.user.model.AgreementCatalog
import com.github.nepyh.rooter.module.user.model.AgreementCode
import com.github.nepyh.rooter.module.user.model.AgreementRecord
import com.github.nepyh.rooter.module.user.model.DayOfWeek
import com.github.nepyh.rooter.module.user.model.UnavailableTimeRow
import io.ktor.http.content.PartData
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val AVATAR_CONTENT_TYPES = mapOf(
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "png" to "image/png",
    "webp" to "image/webp",
)
private const val MAX_AVATAR_FILE_SIZE_BYTES = 5 * 1024 * 1024L // 5MB
private const val MAX_UNAVAILABLE_LABEL_LENGTH = 50
private const val MAX_COMMUTE_MINUTES = 180 // users.commute_minutes check (0~180)

// STRICT: 기본(SMART)이면 "24:00" 이 00:00 으로 통과한다
private val HOUR_MINUTE_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT)

class UserService(
    private val userRepo: UserRepo,
    private val fileStorage: FileStorage,
    private val agreements: AgreementCatalog
) {
    private val logger = LoggerFactory.getLogger(UserService::class.java)

    fun registerUser(request: UserRegisterRequest): UserRegisterResponse {

        // 사용자 이름 (12자 이하)
        val lengthUsername = request.username
        if (lengthUsername.length > 12) {
            throw UserValidationException.WrongUsernameException()
        }

        // 이메일 길이 체크 (users.email varchar(320))
        if (request.email.length > 320) {
            throw UserValidationException.WrongEmailLengthException()
        }

        // 이메일 중복 체크
        val existingUser = userRepo.findUserByEmail(request.email)
        if (existingUser != null) {
            throw UserValidationException.DuplicatedEmailException()
        }

        // 비밀번호 형식 체크 (8자 이상, 숫자 + 영문 포함)
        val passwordRegex = Regex("^(?=.*[A-Za-z])(?=.*\\d).{8,}$")
        if (!passwordRegex.matches(request.password)) {
            throw UserValidationException.WrongPasswordFormatException()
        }

        // bcrypt 암호화
        val hashedPassword = BCrypt.hashpw(
            request.password,
            BCrypt.gensalt()
        )

        // 구버전 앱은 agreements 를 안 보낸다 — 그때도 가입은 되게 두고, 필수 미동의는 pendingAgreements 로 드러난다
        val agreementRecords = request.agreements
            ?.let { resolveDecisions(it, requireAllRequired = true) }
            .orEmpty()

        // DB 저장 (유저 + 동의 이력을 한 트랜잭션으로)
        userRepo.insertUserWithAgreements(
            email = request.email,
            username = request.username,
            password = hashedPassword,
            agreements = agreementRecords
        )

        return UserRegisterResponse(
            email = request.email,
            username = request.username
        )
    }

    suspend fun getUserInfo(id: Int): UserInfoResponse {
        val user = userRepo.findUserById(id) ?: throw UserNotFoundException()
        val profile = userRepo.findStudentProfileByUserId(id)

        return UserInfoResponse(
            id = user.id.value,
            username = user.username,
            email = user.email,
            schoolId = profile?.schoolId,
            grade = profile?.grade,
            classNumber = profile?.classNumber,
            createdAt = user.createdAt.toString(),
            avatarUrl = user.avatarImageKey?.let { fileStorage.getUrl(it) },
            bio = user.bio,
            pendingAgreements = pendingAgreements(id)
        )
    }

    fun updateProfile(userId: Int, request: UpdateProfileRequest): UserProfileUpdateResponse {
        request.username?.let {
            if (it.isBlank() || it.length > 12) {
                throw UserValidationException.WrongUsernameException()
            }
        }
        request.bio?.let {
            if (it.length > 500) {
                throw UserValidationException.WrongBioLengthException()
            }
        }

        val row = userRepo.updateProfile(
            userId = userId,
            username = request.username,
            bio = request.bio
        )

        return UserProfileUpdateResponse(
            id = row.id.value,
            username = row.username,
            bio = row.bio
        )
    }

    fun getStreak(userId: Int, start: LocalDate, end: LocalDate): StreakResponse {
        if (end.isBefore(start)) {
            throw UserValidationException.WrongDateRangeException()
        }
        userRepo.findUserById(userId) ?: throw UserNotFoundException()

        val tasksByDate = userRepo.findTaskRowsByDateRange(userId, start, end)

        val days = generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .map { date ->
                val rows = tasksByDate[date].orEmpty()
                val totalTasks = rows.size
                val completedTasks = rows.count { it[PlanTaskTable.status] == PlanTaskStatus.DONE }
                val completionRate = if (totalTasks == 0) 0.0 else (completedTasks.toDouble() / totalTasks) * 100

                StreakDayResponse(date = date.toString(), completionRate = completionRate)
            }
            .toList()

        return StreakResponse(days = days)
    }

    /** 가입 화면이 그릴 현재 조항 목록. 문서 본문은 url 이 가리키는 정적 페이지가 정본이다. */
    fun getAgreementCatalog(): AgreementCatalogResponse = AgreementCatalogResponse(
        agreements = agreements.all().map { document ->
            AgreementDocumentResponse(
                code = document.code.name,
                required = document.code.required,
                title = document.title,
                version = document.version,
                url = document.url
            )
        }
    )

    /** code 별 최근 동의 1건. 한 번도 동의하지 않은 code 는 응답에서 빠진다. */
    fun getAgreements(userId: Int): UserAgreementsResponse {
        userRepo.findUserById(userId) ?: throw UserNotFoundException()
        val latest = userRepo.findLatestAgreements(userId)

        return UserAgreementsResponse(
            agreements = agreements.all().mapNotNull { document ->
                latest[document.code]?.let { row ->
                    UserAgreementResponse(
                        code = document.code.name,
                        required = document.code.required,
                        version = row.version,
                        agreed = row.agreed,
                        createdAt = row.createdAt.toString()
                    )
                }
            }
        )
    }

    /**
     * 필수 항목 중 아직 유효한 동의가 없는 code 목록.
     * 행이 없거나(한 번도 안 물어봄), 최신 행이 거부이거나, 버전이 현재와 다르면 미동의다.
     */
    fun pendingAgreements(userId: Int): List<String> {
        val latest = userRepo.findLatestAgreements(userId)

        return agreements.requiredCodes()
            .filter { code ->
                val row = latest[code]
                row == null || !row.agreed || row.version != agreements.current(code).version
            }
            .map { it.name }
    }

    /** 재동의·철회. 보낸 항목만 검증해 새 행으로 append 하고, 남은 필수 항목을 돌려준다. */
    fun updateAgreements(userId: Int, request: AgreementsUpdateRequest): AgreementsUpdateResponse {
        userRepo.findUserById(userId) ?: throw UserNotFoundException()

        val records = resolveDecisions(request.agreements, requireAllRequired = false)
        userRepo.insertAgreements(userId, records)

        return AgreementsUpdateResponse(pendingAgreements = pendingAgreements(userId))
    }

    /**
     * 요청의 조항 결정을 검증해 저장용 레코드로 바꾼다.
     * 가입은 [requireAllRequired] 로 필수 항목이 전부 agreed=true 인지까지 본다.
     * 재동의는 일부 항목만 보낼 수 있지만, 필수 항목을 agreed=false 로 보내는 것은 어느 쪽이든 거부한다.
     */
    private fun resolveDecisions(
        decisions: List<AgreementDecision>,
        requireAllRequired: Boolean
    ): List<AgreementRecord> {
        if (decisions.isEmpty()) throw AgreementValidationException.EmptyAgreementsException()

        val seen = mutableSetOf<AgreementCode>()
        val records = decisions.map { decision ->
            val code = AgreementCode.fromCode(decision.code)
                ?: throw AgreementValidationException.UnknownCodeException(decision.code)
            if (!seen.add(code)) {
                throw AgreementValidationException.DuplicatedCodeException(decision.code)
            }

            val current = agreements.current(code)
            if (current.version != decision.version) {
                throw AgreementValidationException.VersionMismatchException(
                    decision.code, current.version, decision.version
                )
            }
            if (code.required && !decision.agreed) {
                throw AgreementValidationException.RequiredMissingException(listOf(decision.code))
            }

            AgreementRecord(
                code = code,
                version = current.version,
                required = code.required,
                agreed = decision.agreed
            )
        }

        if (requireAllRequired) {
            val missing = agreements.requiredCodes().filter { code -> records.none { it.code == code } }
            if (missing.isNotEmpty()) {
                throw AgreementValidationException.RequiredMissingException(missing.map { it.name })
            }
        }

        return records
    }

    fun getUnavailableTimes(id: Int): List<UnavailableTimeResponse> {
        userRepo.findUserById(id) ?: throw UserNotFoundException()

        return userRepo.findUnavailableTimesByUserId(id).map { it.toResponse() }
    }

    fun createStudentProfile(userId: Int, request: StudentProfileRequest): StudentProfileResponse {
        userRepo.findUserById(userId) ?: throw UserNotFoundException()

        // 학교 코드 길이 체크 (student_profiles.school_id char(10))
        if (request.schoolId.length > 10) {
            throw UserValidationException.WrongSchoolIdException()
        }

        val row = userRepo.insertStudentProfile(
            userId = userId,
            schoolId = request.schoolId,
            grade = request.grade,
            classNumber = request.classNumber
        )

        return StudentProfileResponse(
            id = row.id.value,
            userId = userId,
            schoolId = row.schoolId,
            grade = row.grade,
            classNumber = row.classNumber
        )
    }

    suspend fun updateAvatar(userId: Int, file: PartData.FileItem): AvatarUpdateResponse {
        val user = userRepo.findUserById(userId) ?: throw UserNotFoundException()
        val previousKey = user.avatarImageKey

        val extension = file.originalFileName?.substringAfterLast('.', "")?.lowercase()
        if (extension.isNullOrBlank() || extension !in AVATAR_CONTENT_TYPES) {
            throw UserValidationException.UnsupportedAvatarFileTypeException()
        }

        // Content-Length 헤더는 실제 멀티파트 업로드에서 안 실릴 수 있어 신뢰할 수 없음 —
        // 읽으면서 직접 바이트 수를 세어 상한을 넘는 순간 중단한다.
        val bytes = readWithSizeLimit(file.provider(), MAX_AVATAR_FILE_SIZE_BYTES)
            ?: throw UserValidationException.AvatarFileTooLargeException()

        // Content-Type 은 파트 헤더를 믿지 않고 확장자로 정한다 — 헤더가 비어 있으면 S3 오브젝트가
        // octet-stream 으로 저장돼 브라우저가 이미지를 바로 표시하지 못한다.
        val boundedFile = file.toUploadableFile(
            content = ByteReadChannel(bytes),
            contentLength = bytes.size.toLong(),
            contentType = AVATAR_CONTENT_TYPES.getValue(extension)
        )

        val avatarImageKey = fileStorage.upload(boundedFile, "avatars")
        userRepo.updateAvatarImageKey(userId, avatarImageKey)

        // 이전 아바타 오브젝트는 지우지 않으면 아무도 참조하지 않는 채로 스토리지에 계속 쌓인다.
        // 정리 실패가 아바타 교체 자체를 실패로 만들면 안 되므로 로그만 남기고 넘어간다.
        if (previousKey != null && previousKey != avatarImageKey) {
            runCatching { fileStorage.delete(previousKey) }
                .onFailure { logger.warn("이전 아바타 오브젝트 삭제 실패: key={}", previousKey, it) }
        }

        return AvatarUpdateResponse(
            userId = userId,
            avatarUrl = fileStorage.getUrl(avatarImageKey)
        )
    }

    /**
     * 회원 탈퇴. 유저와 그 유저의 모든 기록을 지운다 (DDL cascade). 이후 이 유저의 토큰은
     * UserJwtValidator 가 유저를 못 찾아 401 로 막는다.
     */
    suspend fun deleteUser(userId: Int) {
        val user = userRepo.findUserById(userId) ?: throw UserNotFoundException()
        val avatarKey = user.avatarImageKey

        if (userRepo.deleteUser(userId) == 0) throw UserNotFoundException()

        // 아바타 오브젝트는 DB cascade 대상이 아니라 직접 지운다. 탈퇴는 이미 끝났으므로 실패해도 로그만 남긴다.
        if (avatarKey != null) {
            runCatching { fileStorage.delete(avatarKey) }
                .onFailure { logger.warn("탈퇴 유저 아바타 오브젝트 삭제 실패: key={}", avatarKey, it) }
        }
    }

    fun addUnavailableTime(userId: Int, request: UnavailableTimeRequest): UnavailableTimeResponse {
        userRepo.findUserById(userId) ?: throw UserNotFoundException()

        val dayOfWeek = try {
            DayOfWeek.fromCode(request.dayOfWeek)
        } catch (_: IllegalArgumentException) {
            throw UserValidationException.WrongDayOfWeekException()
        }
        // 이름은 앞뒤 공백을 떼고, 비어 있으면 저장하지 않는다 (user_unavailable_times.label varchar(50))
        val label = request.label?.trim()?.takeIf { it.isNotEmpty() }
        if (label != null && label.length > MAX_UNAVAILABLE_LABEL_LENGTH) {
            throw UserValidationException.WrongUnavailableLabelException()
        }

        val row = userRepo.insertUnavailableTime(
            userId = userId,
            dayOfWeek = dayOfWeek,
            startTime = LocalTime.parse(request.startTime),
            endTime = LocalTime.parse(request.endTime),
            kind = request.kind,
            label = label
        )

        return row.toResponse()
    }

    private fun UnavailableTimeRow.toResponse() = UnavailableTimeResponse(
        id = id.value,
        dayOfWeek = dayOfWeek,
        startTime = startTime.toString(),
        endTime = endTime.toString(),
        kind = kind,
        label = label
    )

    /** 저장한 수면시간. 저장하지 않았으면 기본값(23:00 / 06:30)과 isDefault=true */
    fun getSleepTime(userId: Int): SleepTimeResponse {
        val user = userRepo.findUserById(userId) ?: throw UserNotFoundException()
        return sleepTimeResponseOf(user.sleepTime, user.wakeTime)
    }

    /** 수면시간 저장. 취침·기상은 HH:mm, 같은 시각은 안 됨. 자정 넘어 자는 경우(01:00 취침)도 그대로 저장한다 */
    fun updateSleepTime(userId: Int, request: SleepTimeRequest): SleepTimeResponse {
        userRepo.findUserById(userId) ?: throw UserNotFoundException()

        val sleepTime = parseHourMinute(request.sleepTime)
        val wakeTime = parseHourMinute(request.wakeTime)
        if (sleepTime == wakeTime) throw UserValidationException.WrongSleepTimeException()

        val user = userRepo.updateSleepTime(userId, sleepTime, wakeTime)
        return sleepTimeResponseOf(user.sleepTime, user.wakeTime)
    }

    /** 저장한 하교 후 귀가 시간. 저장하지 않았으면 기본값(40분)과 isDefault=true */
    fun getCommuteTime(userId: Int): CommuteTimeResponse {
        val user = userRepo.findUserById(userId) ?: throw UserNotFoundException()
        return commuteTimeResponseOf(user.commuteMinutes?.toInt())
    }

    /** 하교 후 귀가 시간 저장 (0~180분). 0 이면 하교하자마자 할일을 잡을 수 있다 */
    fun updateCommuteTime(userId: Int, request: CommuteTimeRequest): CommuteTimeResponse {
        userRepo.findUserById(userId) ?: throw UserNotFoundException()
        if (request.commuteMinutes !in 0..MAX_COMMUTE_MINUTES) throw UserValidationException.WrongCommuteMinutesException()

        val user = userRepo.updateCommuteMinutes(userId, request.commuteMinutes)
        return commuteTimeResponseOf(user.commuteMinutes?.toInt())
    }

    private fun commuteTimeResponseOf(commuteMinutes: Int?): CommuteTimeResponse = CommuteTimeResponse(
        commuteMinutes = commuteMinutes ?: UserRoutine.DEFAULT_COMMUTE_MINUTES,
        isDefault = commuteMinutes == null
    )

    private fun parseHourMinute(value: String): LocalTime =
        runCatching { LocalTime.parse(value, HOUR_MINUTE_FORMAT) }.getOrElse { throw UserValidationException.WrongTimeFormatException() }

    private fun sleepTimeResponseOf(sleepTime: LocalTime?, wakeTime: LocalTime?): SleepTimeResponse {
        val saved = if (sleepTime != null && wakeTime != null) SleepTime(sleepTime, wakeTime) else null
        val effective = saved ?: SleepTime.DEFAULT
        return SleepTimeResponse(
            sleepTime = effective.sleepAt.format(HOUR_MINUTE_FORMAT),
            wakeTime = effective.wakeAt.format(HOUR_MINUTE_FORMAT),
            isDefault = saved == null
        )
    }

    fun deleteUnavailableTime(userId: Int, timeId: Int) {
        userRepo.findUserById(userId) ?: throw UserNotFoundException()

        val deleted = userRepo.deleteUnavailableTime(userId, timeId)
        if (deleted == 0) throw UnavailableTimeNotFoundException()
    }

    /** maxBytes 를 넘으면 다 읽지 않고 null 을 반환한다 (업로드된 파일 전체를 메모리에 올리기 전에 상한을 강제). */
    private suspend fun readWithSizeLimit(channel: ByteReadChannel, maxBytes: Long): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = channel.readAvailable(chunk)
            if (read == -1) break
            total += read
            if (total > maxBytes) return null
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }
}