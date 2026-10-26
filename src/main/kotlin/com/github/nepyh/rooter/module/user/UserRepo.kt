package com.github.nepyh.rooter.module.user

import com.github.nepyh.rooter.module.user.model.AgreementCode
import com.github.nepyh.rooter.module.user.model.AgreementRecord
import com.github.nepyh.rooter.module.user.model.AgreementState
import com.github.nepyh.rooter.module.user.model.DayOfWeek
import com.github.nepyh.rooter.module.user.model.StudentProfileRow
import com.github.nepyh.rooter.module.user.model.UnavailableKind
import com.github.nepyh.rooter.module.user.model.UnavailableTimeRow
import com.github.nepyh.rooter.module.user.model.UserRow
import org.jetbrains.exposed.v1.core.ResultRow
import java.time.LocalDate
import java.time.LocalTime


interface UserRepo {

    fun insertUser(
        email: String,
        username: String,
        password: String,
        avatarImageKey: String? = null,
        bio: String? = null
    ): UserRow

    /**
     * 유저 생성과 동의 이력 저장을 **한 트랜잭션**으로 처리한다.
     * 가입만 되고 동의가 빠지는 상태를 만들지 않기 위해서다.
     */
    fun insertUserWithAgreements(
        email: String,
        username: String,
        password: String,
        agreements: List<AgreementRecord>
    ): UserRow

    fun insertAgreements(userId: Int, agreements: List<AgreementRecord>)

    /** code 별 가장 최근 1건. append-only 라 "최신 행" 이 곧 현재 유효한 동의다. */
    fun findLatestAgreements(userId: Int): Map<AgreementCode, AgreementState>

    fun findUserByEmail(email: String): UserRow?

    fun findUserById(id: Int): UserRow?

    fun updateAvatarImageKey(userId: Int, avatarImageKey: String): UserRow

    fun updateProfile(userId: Int, username: String?, bio: String?): UserRow

    /** 비밀번호 변경과 함께 tokenVersion 을 올려, 기존에 발급된 토큰이 더 이상 통과하지 못하게 한다. */
    fun updatePassword(userId: Int, hashedPassword: String): UserRow

    fun incrementTokenVersion(userId: Int): UserRow

    fun updateSleepTime(userId: Int, sleepTime: LocalTime, wakeTime: LocalTime): UserRow

    fun updateCommuteMinutes(userId: Int, commuteMinutes: Int): UserRow

    fun findStudentProfileByUserId(userId: Int): StudentProfileRow?

    fun findUnavailableTimesByUserId(userId: Int): List<UnavailableTimeRow>

    fun insertStudentProfile(
        userId: Int,
        schoolId: String,
        grade: Int,
        classNumber: Int
    ): StudentProfileRow

    fun findTaskRowsByDateRange(userId: Int, start: LocalDate, end: LocalDate): Map<LocalDate, List<ResultRow>>

    fun insertUnavailableTime(
        userId: Int,
        dayOfWeek: DayOfWeek,
        startTime: LocalTime,
        endTime: LocalTime,
        kind: UnavailableKind,
        label: String?
    ): UnavailableTimeRow

    /** 삭제된 row 수를 반환한다. 0이면 존재하지 않거나 본인 소유가 아님. */
    fun deleteUnavailableTime(userId: Int, timeId: Int): Int

    /**
     * 회원 탈퇴. users 행을 지우면 이 유저를 참조하는 테이블(플랜보드·퀴즈·복습·캘린더 등)은
     * DDL 의 on delete cascade 로 함께 지워진다 (rooter-ddl). 삭제된 row 수를 반환한다.
     */
    fun deleteUser(userId: Int): Int
}
