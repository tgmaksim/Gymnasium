package ru.tgmaksim.activium.ui.pages.marks

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

import ru.tgmaksim.activium.R
import ru.tgmaksim.activium.api.json
import ru.tgmaksim.activium.api.Dnevnik
import ru.tgmaksim.activium.api.MarkLast
import ru.tgmaksim.activium.ui.core.UiText
import ru.tgmaksim.activium.ui.LoginActivity
import ru.tgmaksim.activium.utilities.Utilities
import ru.tgmaksim.activium.ui.core.UiViewModel
import ru.tgmaksim.activium.api.MarksSubjectFinal
import ru.tgmaksim.activium.ui.core.setShownError
import ru.tgmaksim.activium.ui.core.setCacheError
import ru.tgmaksim.activium.api.MarksSubjectPeriod
import ru.tgmaksim.activium.ui.core.setCacheLoading
import ru.tgmaksim.activium.ui.core.setCacheSuccess
import ru.tgmaksim.activium.ui.core.CacheDataLoadState
import ru.tgmaksim.activium.utilities.datastore.CacheManager
import ru.tgmaksim.activium.utilities.datastore.SettingsManager

class MarksViewModel : UiViewModel() {
    enum class StateType { Marks, FinalMarks }

    private val _marksData = MutableStateFlow<UiMarksResult?>(null)
    val marksData = _marksData.asStateFlow()

    private val _marksState = MutableStateFlow<CacheDataLoadState>(CacheDataLoadState.Empty)
    val marksState = _marksState.asStateFlow()

    private val _finalMarksState = MutableStateFlow<CacheDataLoadState>(CacheDataLoadState.Empty)
    val finalMarksState = _finalMarksState.asStateFlow()

    private var loadCacheMarksJob: Job? = null
    private var loadCloudMarksJob: Job? = null

    companion object {
        const val CACHE_LAST_MARKS_NAME = "marks"
        const val CACHE_SUBJECT_MARKS_PERIOD = "subject_marks_period"
        const val CACHE_SUBJECT_MARKS_YEAR = "subject_marks_year"
    }

    fun resetError(stateType: StateType) {
        when (stateType) {
            StateType.Marks -> _marksState.setShownError()
            StateType.FinalMarks -> _finalMarksState.setShownError()
        }

    }

    fun resetMarks() {
        _marksState.value = CacheDataLoadState.Empty
        _finalMarksState.value = CacheDataLoadState.Empty
        _marksData.value = null
    }

    fun logout() {
        viewModelScope.launch {
            LoginActivity.logout()
        }
    }

    fun doneStudyMarkRating() {
        viewModelScope.launch {
            SettingsManager.setStudyMarkRating(true)
        }
    }

    fun doneStudySubjectRating() {
        viewModelScope.launch {
            SettingsManager.setStudySubjectRating(true)
        }
    }

    fun loadCacheMarks() {
        val job = loadCacheMarksJob
        if (job?.isActive == true)
            job.cancel()

        loadCacheMarksJob = viewModelScope.launch {
            _marksState.setCacheLoading()

            val childId = SettingsManager.getActiveChildId()

            try {
                val entityLastMarks = CacheManager.read(childId, CACHE_LAST_MARKS_NAME)
                    ?: throw CacheNullException()
                val lastMarks = json.decodeFromString<List<MarkLast>>(entityLastMarks.value)

                val entitySubjectMarksPeriod = CacheManager.read(childId, CACHE_SUBJECT_MARKS_PERIOD)
                    ?: throw CacheNullException()
                val subjectMarksPeriod = json.decodeFromString<List<MarksSubjectPeriod>>(entitySubjectMarksPeriod.value)

                _marksData.value = UiMarksResult(
                    recentMarks = lastMarks,
                    periodMarks = subjectMarksPeriod,
                    ratingKey = null,
                    finalMarks = emptyList()
                )
                _marksState.setCacheSuccess()
            } catch (_: CancellationException) {
                // Запущена другая задача
            } catch (e: Exception) {
                if (e !is CacheNullException) {
                    Utilities.log(e, "Error at loadCacheMarks")
                    CacheManager.writeDnevnikCache(childId, CACHE_LAST_MARKS_NAME, value = "")
                    CacheManager.writeDnevnikCache(childId, CACHE_SUBJECT_MARKS_PERIOD, value = "")
                }
                _marksState.setCacheSuccess()
            }
        }
    }

    fun loadCloudMarks() {
        val job = loadCloudMarksJob
        if (job?.isActive == true)
            job.cancel()

        loadCloudMarksJob = viewModelScope.launch {
            val childId = SettingsManager.getActiveChildId()
            val period = SettingsManager.getLastMarksPeriod()

            executeRequest(
                _marksState,
                _marksData,
                "marks",
                R.string.error_marks,
                { Dnevnik.getMarks(period) },
                { it.answer?.let { answer ->
                    UiMarksResult(
                        recentMarks = answer.recentMarks,
                        periodMarks = answer.periodMarks,
                        ratingKey = answer.ratingKey,
                        finalMarks = emptyList()
                    )
                } }
            ) {
                it.answer ?: return@executeRequest
                CacheManager.writeDnevnikCache(
                    childId,
                    CACHE_LAST_MARKS_NAME,
                    value = json.encodeToString(it.answer.recentMarks)
                )
                CacheManager.writeDnevnikCache(
                    childId,
                    CACHE_SUBJECT_MARKS_PERIOD,
                    value = json.encodeToString(it.answer.periodMarks)
                )
            }
        }
    }

    fun loadCacheFinalMarks() {
        viewModelScope.launch {
            _finalMarksState.setCacheLoading()

            try {
                val childId = SettingsManager.getActiveChildId()

                try {
                    val entity = CacheManager.read(childId, CACHE_SUBJECT_MARKS_YEAR)
                        ?: throw CacheNullException()
                    val finalMarks = json.decodeFromString<List<MarksSubjectFinal>>(entity.value)

                    _marksData.value = _marksData.value?.copy(finalMarks = finalMarks)
                    _finalMarksState.setCacheSuccess()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e !is CacheNullException) {
                        Utilities.log(e, "Error at loadCacheFinalMarks")
                        CacheManager.writeDnevnikCache(childId, CACHE_SUBJECT_MARKS_YEAR, value = "")
                    }
                    _finalMarksState.setCacheSuccess()
                }
            } catch (_: CancellationException) {
                _finalMarksState.setCacheError(UiText.StringResource(R.string.error_schedule))
            }
        }
    }

    fun loadCloudFinalMarks() {
        viewModelScope.launch {
            val childId = SettingsManager.getActiveChildId()

            executeRequest(
                _finalMarksState,
                _marksData,
                "finalMarks",
                R.string.error_marks,
                { Dnevnik.getFinalMarks() },
                { it.answer?.finalMarks?.let {
                    finalMarks -> _marksData.value?.copy(finalMarks = finalMarks)
                } }
            ) {
                it.answer ?: return@executeRequest
                CacheManager.writeDnevnikCache(
                    childId,
                    CACHE_SUBJECT_MARKS_YEAR,
                    value = json.encodeToString(it.answer.finalMarks)
                )
            }
        }
    }
}