package ru.tgmaksim.activium.ui.pages.schedule

import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toKotlinMonth

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

import ru.tgmaksim.activium.R
import ru.tgmaksim.activium.api.json
import ru.tgmaksim.activium.api.School
import ru.tgmaksim.activium.api.Dnevnik
import ru.tgmaksim.activium.ui.core.toUi
import ru.tgmaksim.activium.api.NoteResult
import ru.tgmaksim.activium.api.ScheduleDay
import ru.tgmaksim.activium.ui.LoginActivity
import ru.tgmaksim.activium.api.DnevnikTools
import ru.tgmaksim.activium.ui.core.LoadState
import ru.tgmaksim.activium.ui.core.UiViewModel
import ru.tgmaksim.activium.utilities.Utilities
import ru.tgmaksim.activium.ui.core.setShownError
import ru.tgmaksim.activium.ui.core.setCacheLoading
import ru.tgmaksim.activium.ui.core.setCacheSuccess
import ru.tgmaksim.activium.api.MarkSchoolPostResult
import ru.tgmaksim.activium.ui.core.CacheDataLoadState
import ru.tgmaksim.activium.utilities.datastore.CacheManager
import ru.tgmaksim.activium.utilities.datastore.SettingsManager

class ScheduleViewModel : UiViewModel() {
    enum class StateType { Schedule }
    enum class MapStateType { Praises, Notes }

    private val _scheduleData = MutableStateFlow<UiScheduleResult?>(null)
    val scheduleData = _scheduleData.asStateFlow()

    private val _scheduleState = MutableStateFlow<CacheDataLoadState>(CacheDataLoadState.Empty)
    val scheduleState = _scheduleState.asStateFlow()

    private val _praiseStates = MutableStateFlow<Map<String, LoadState<Unit>>>(emptyMap())
    val praiseStates = _praiseStates.asStateFlow()

    private val _noteStates = MutableStateFlow<Map<String, LoadState<NoteResult>>>(emptyMap())
    val noteStates = _noteStates.asStateFlow()

    private val _seePostStates = MutableStateFlow<Map<Long, LoadState<MarkSchoolPostResult>>>(emptyMap())
    val seePostStates = _seePostStates.asStateFlow()

    private val _clickPostStates = MutableStateFlow<Map<Long, LoadState<MarkSchoolPostResult>>>(emptyMap())
    val clickPostStates = _clickPostStates.asStateFlow()

    private var loadCacheScheduleJob: Job? = null
    private var loadCloudCacheScheduleJob: Job? = null

    companion object {
        const val CACHE_SCHEDULE_NAME = "schedule"
        const val CACHE_TIMEZONE_NAME = "timezone"
    }

    fun resetSchedule() {
        _scheduleState.value = CacheDataLoadState.Empty
        _scheduleData.value = null
    }

    fun resetError(stateType: StateType) {
        when (stateType) {
            StateType.Schedule -> _scheduleState.setShownError()
        }
    }

    fun resetError(stateType: MapStateType, lessonKey: String) {
        when (stateType) {
            MapStateType.Praises -> _praiseStates.setShownError(lessonKey)
            MapStateType.Notes -> _noteStates.setShownError(lessonKey)
        }
    }

    fun reset(stateType: MapStateType, lessonKey: String) {
        when (stateType) {
            MapStateType.Praises -> _praiseStates.value = _praiseStates.value.toMutableMap().apply { remove(lessonKey) }
            MapStateType.Notes -> _noteStates.value = _noteStates.value.toMutableMap().apply { remove(lessonKey) }
        }
    }

    fun resetSeePost(postId: Long) {
        _seePostStates.value = _seePostStates.value.toMutableMap().apply { remove(postId) }
    }

    fun resetClickPost(postId: Long) {
        _clickPostStates.value = _clickPostStates.value.toMutableMap().apply { remove(postId) }
    }

    fun logout() {
        viewModelScope.launch {
            LoginActivity.logout()
        }
    }

    fun emptyStates() {
        _praiseStates.value = emptyMap()
        _noteStates.value = emptyMap()
    }

    fun doneStudyLessonMenu() {
        viewModelScope.launch {
            SettingsManager.setStudyLessonMenu(true)
        }
    }

    fun loadCacheSchedule() {
        val job = loadCacheScheduleJob
        if (job?.isActive == true)
            job.cancel()

        loadCacheScheduleJob = viewModelScope.launch {
            _scheduleState.setCacheLoading()

            val before = SettingsManager.getBeforeSchedule()
            val after = SettingsManager.getAfterSchedule()
            val totalDays = before + 1 + after

            val childId = SettingsManager.getActiveChildId()

            try {
                val timezone = CacheManager.read(childId, CACHE_TIMEZONE_NAME)?.value?.toInt()
                    ?: throw CacheNullException()

                val entity = CacheManager.read(childId, CACHE_SCHEDULE_NAME)
                    ?: throw CacheNullException()
                val schedule = json.decodeFromString<List<ScheduleDay>>(entity.value)

                val hasAbilityPraise = false
                _scheduleData.value = UiScheduleResult(
                    schedule = normalizeSchedule(schedule, before, after, timezone, hasAbilityPraise),
                    timezone = timezone,
                    hasAbilityPraise = hasAbilityPraise
                )
                _scheduleState.setCacheSuccess()
            } catch (_: CancellationException) {
                // Запущена другая задача
            } catch (e: Exception) {
                if (e !is CacheNullException) {
                    Utilities.log(e, "Error at loadCacheSchedule")
                    CacheManager.writeDnevnikCache(childId, CACHE_SCHEDULE_NAME, value = "")
                }
                _scheduleData.value = UiScheduleResult(
                    schedule = List(totalDays) { null },
                    timezone = 0,
                    hasAbilityPraise = false
                )
                _scheduleState.setCacheSuccess()
            }
        }
    }

    private fun normalizeSchedule(
        schedule: List<ScheduleDay>,
        before: Int,
        after: Int,
        timezone: Int,
        hasAbilityPraise: Boolean
    ): List<UiScheduleDay?> {
        val currentDate = todayInTimezone(timezone)
        val firstDate = currentDate.minusDays(before.toLong())
        val byDate = schedule.associateBy { it.date }

        val days = ArrayList<UiScheduleDay?>(before + 1 + after)
        for (i in 0..<before + 1 + after) {
            val date = firstDate.plusDays(i.toLong())
            val praiseState = if (hasAbilityPraise) LoadState.Empty else null
            days += byDate[LocalDate(date.year, date.month.toKotlinMonth(), date.dayOfMonth)]?.toUi(praiseState)
        }

        return days
    }

    private fun todayInTimezone(offsetSeconds: Int): ZonedDateTime {
        val utcNow = Instant.now()
        val zone = ZoneOffset.ofTotalSeconds(offsetSeconds)
        return utcNow.atZone(zone)
    }

    fun loadCloudSchedule() {
        val job = loadCloudCacheScheduleJob
        if (job?.isActive == true)
            job.cancel()

        loadCloudCacheScheduleJob = viewModelScope.launch {
            val childId = SettingsManager.getActiveChildId()
            val before = SettingsManager.getBeforeSchedule()
            val after = SettingsManager.getAfterSchedule()

            executeRequest(
                _scheduleState,
                _scheduleData,
                "schedule",
                R.string.error_schedule,
                { Dnevnik.getSchedule(before, after) },
                { it.answer?.let { answer ->
                    UiScheduleResult(
                        schedule = normalizeSchedule(answer.schedule, before, after, answer.timezone, answer.hasAbilityPraise),
                        timezone = answer.timezone,
                        hasAbilityPraise = answer.hasAbilityPraise
                    )
                } }
            ) {
                it.answer ?: return@executeRequest
                CacheManager.writeDnevnikCache(
                    childId,
                    CACHE_TIMEZONE_NAME,
                    value = it.answer.timezone.toString()
                )
                CacheManager.writeDnevnikCache(
                    childId,
                    CACHE_SCHEDULE_NAME,
                    value = json.encodeToString(it.answer.schedule)
                )
            }
        }
    }

    fun sendPraise(lessonKey: String, text: String?) {
        viewModelScope.launch {
            executeRequest(
                _praiseStates,
                lessonKey,
                "praise",
                R.string.error_praise,
                { DnevnikTools.sendPraise(lessonKey, null, text) },
                {}
            )
        }
    }

    fun createLessonNote(lessonKey: String, text: String, public: Boolean, remindTime: Instant?) {
        viewModelScope.launch {
            executeRequest(
                _noteStates,
                lessonKey,
                "note",
                R.string.error_note,
                { DnevnikTools.createNote(lessonKey, text, public, remindTime) },
                { it.answer },
                { noteResult -> onSuccessEditLessonNote(lessonKey, noteResult) }
            )
        }
    }

    fun deleteLessonNote(lessonKey: String) {
        viewModelScope.launch {
            executeRequest(
                _noteStates,
                lessonKey,
                "deleteNote",
                R.string.error_delete_note,
                { DnevnikTools.deleteNote(lessonKey) },
                { NoteResult(note = null) },
                { noteResult -> onSuccessEditLessonNote(lessonKey, noteResult) }
            )
        }
    }

    private suspend fun onSuccessEditLessonNote(lessonKey: String, noteResult: NoteResult) {
        val data = _scheduleData.value
        _scheduleData.value = data?.copy(
            schedule = data.schedule.map {day ->
                if (day?.lessons?.find { it.lessonKey == lessonKey } != null) {
                    day.copy(lessons = day.lessons.map { lesson ->
                        if (lesson.lessonKey == lessonKey) lesson.copy(note = noteResult.note) else lesson
                    })
                } else {
                    day
                }
            }
        )

        try {
            val childId = SettingsManager.getActiveChildId()
            val entity = CacheManager.read(childId, CACHE_SCHEDULE_NAME)
                ?: throw CacheNullException()
            val schedule = json.decodeFromString<List<ScheduleDay>>(entity.value)
            val newSchedule = json.encodeToString(
                schedule.map { day ->
                    if (day.lessons.find { it.lessonKey == lessonKey } != null) {
                        day.copy(lessons = day.lessons.map { lesson ->
                            if (lesson.lessonKey == lessonKey) lesson.copy(note = noteResult.note) else lesson
                        })
                    } else {
                        day
                    }
                }
            )

            CacheManager.writeDnevnikCache(childId, CACHE_SCHEDULE_NAME, value = newSchedule)
        } catch (_: CacheNullException) {
        } catch (_: CancellationException) {
        } catch (e: Exception) {
            Utilities.log(e, "Error at onSuccessEditLessonNote")
        }
    }

    fun seePost(postId: Long) {
        viewModelScope.launch {
            executeRequest(
                _seePostStates,
                postId,
                "seePost",
                R.string.error_mark_school_post,
                { School.seePost(postId) },
                { it.answer }
            )
        }
    }

    fun clickPost(postId: Long) {
        viewModelScope.launch {
            executeRequest(
                _clickPostStates,
                postId,
                "clickPost",
                R.string.error_mark_school_post,
                { School.clickPost(postId) },
                { it.answer }
            ) { postResult -> onSuccessUpdatePost(postId, postResult) }
        }
    }

    fun updatePost(postId: Long, postResult: MarkSchoolPostResult) {
        val data = _scheduleData.value
        _scheduleData.value = data?.copy(
            schedule = data.schedule.map { day ->
                if (day?.schoolPosts?.find { it.postId == postId } != null) {
                    day.copy(schoolPosts = day.schoolPosts.map { post ->
                        if (post.postId == postId) postResult.post else post
                    })
                } else {
                    day
                }
            }
        )
    }

    private suspend fun onSuccessUpdatePost(postId: Long, postResult: MarkSchoolPostResult) {
        updatePost(postId, postResult)

        try {
            val childId = SettingsManager.getActiveChildId()
            val entity = CacheManager.read(childId, CACHE_SCHEDULE_NAME)
                ?: throw CacheNullException()
            val schedule = json.decodeFromString<List<ScheduleDay>>(entity.value)
            val newSchedule = json.encodeToString(
                schedule.map { day ->
                    if (day.schoolPosts.find { it.postId == postId } != null) {
                        day.copy(schoolPosts = day.schoolPosts.map { post ->
                            if (post.postId == postId) postResult.post else post
                        })
                    } else {
                        day
                    }
                }
            )

            CacheManager.writeDnevnikCache(childId, CACHE_SCHEDULE_NAME, value = newSchedule)
        } catch (_: CacheNullException) {
        } catch (_: CancellationException) {
        } catch (e: Exception) {
            Utilities.log(e, "Error at onSuccessUpdatePost")
        }
    }
}