package ru.tgmaksim.activium.ui.pages.schedule

import android.os.Bundle
import android.view.View
import android.app.Activity
import android.view.ViewGroup
import android.graphics.Color
import android.view.LayoutInflater
import androidx.activity.result.contract.ActivityResultContracts

import android.animation.ValueAnimator
import android.animation.ObjectAnimator
import android.view.animation.LinearInterpolator

import androidx.core.view.doOnLayout
import androidx.core.widget.addTextChangedListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder

import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.timepicker.TimeFormat
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.timepicker.MaterialTimePicker

import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.combine
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged

import java.util.Locale
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import kotlinx.datetime.plus
import kotlinx.datetime.minus
import kotlin.collections.get
import kotlinx.datetime.number
import java.time.LocalDateTime
import kotlinx.datetime.LocalDate
import kotlin.properties.Delegates
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.toKotlinMonth
import java.time.format.DateTimeFormatter

import ru.tgmaksim.activium.R
import ru.tgmaksim.activium.api.json
import ru.tgmaksim.activium.api.NoteResult
import ru.tgmaksim.activium.ui.LoginActivity
import ru.tgmaksim.activium.ui.core.LoadState
import ru.tgmaksim.activium.utilities.Utilities
import ru.tgmaksim.activium.ui.main.MainActivity
import ru.tgmaksim.activium.ui.pages.MainFragment
import ru.tgmaksim.activium.api.MarkSchoolPostResult
import ru.tgmaksim.activium.ui.core.CacheDataLoadState
import ru.tgmaksim.activium.databinding.DialogPraiseBinding
import ru.tgmaksim.activium.databinding.SchedulePageBinding
import ru.tgmaksim.activium.ui.webview.WebSchoolPostActivity
import ru.tgmaksim.activium.utilities.datastore.SettingsManager
import ru.tgmaksim.activium.databinding.DialogLessonNoteEditorBinding
import ru.tgmaksim.activium.ui.pages.schedule.adapters.ScheduleDayAdapter
import ru.tgmaksim.activium.ui.pages.schedule.adapters.ScheduleCalendarDayUi
import ru.tgmaksim.activium.ui.pages.schedule.adapters.ScheduleCalendarAdapter
import ru.tgmaksim.activium.ui.pages.schedule.skeletone.CalendarSkeletonAdapter

/**
 * Страница с расписанием, оценками на уроках
 * @author Максим Дрючин (tgmaksim)
 * @see ru.tgmaksim.activium.ui.main.MainActivity
 * */
class SchedulePage(param: String? = null) : MainFragment(param) {
    private lateinit var ui: SchedulePageBinding
    private val scheduleViewModel
        get() = (requireActivity() as MainActivity).scheduleViewModel

    private var shimmerAnimator: ObjectAnimator? = null
    private var shouldAnimateShimmer = false

    private val calendarSkeletonAdapter = CalendarSkeletonAdapter(SKELETON_CALENDAR_COUNT)
    private val calendarAdapter = ScheduleCalendarAdapter(::onCalendarDayClick)
    private val dayAdapter = ScheduleDayAdapter(
        skeletonLessonsCount = SKELETON_LESSONS_COUNT,
        onPraiseClick = ::onPraiseLesson,
        onMenuLesson = ::onMenuLesson,
        onRating = ::onRating,
        onSeePost = ::onSeePost,
        onClickPost = ::onClickPost
    )

    private val pagerSnapHelper = PagerSnapHelper()

    private val postLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val postId = result.data?.getLongExtra("postId", -1L)?.takeIf {
                    it != -1L
                } ?: return@registerForActivityResult
                val stringPost = result.data?.getStringExtra("postResult")
                val newPostResult = try {
                    stringPost?.let { json.decodeFromString<MarkSchoolPostResult>(it) }
                } catch (_: Exception) {
                    null
                } ?: return@registerForActivityResult

                (activity as MainActivity).updateNewSchoolPosts(newPostResult.countPostsWithoutVision)
                scheduleViewModel.updatePost(postId, newPostResult)
            }

            requireActivity().recreate()
        }

    private var currentData: UiScheduleResult? = null
    private var currentPraiseStates: Map<String, LoadState<Unit>> = emptyMap()
    private var currentNoteStates: Map<String, LoadState<NoteResult>> = emptyMap()
    private var currentBefore by Delegates.notNull<Int>()
    private var currentAfter by Delegates.notNull<Int>()
    private var currentActiveChildId by Delegates.notNull<Long>()
    private var currentSelectedDate: LocalDate? = null
    private var currentDates: List<LocalDate> = emptyList()

    private val praiseAnimations: MutableMap<String, FloatArray> = mutableMapOf()

    companion object {
        private const val PRAISE_TEXT_LIMIT = 64
        private const val NOTE_TEXT_LIMIT = 256
        private const val SKELETON_CALENDAR_COUNT = 7
        private const val SKELETON_DAYS_COUNT = 1
        private const val SKELETON_LESSONS_COUNT = 5
        private const val OPEN_NEXT_DAY_SINCE_HOURS = 15
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        ui = SchedulePageBinding.inflate(inflater, container, false)

        return ui.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val settings = runBlocking { SettingsManager.snapshot() }
        currentBefore = settings.beforeSchedule
        currentAfter = settings.afterSchedule
        currentActiveChildId = settings.activeChildId

        setupRecyclerViews()

        setupCollectors()

        setupSwipeRefresh()

        handleIntent()
    }

    override fun onResume() {
        super.onResume()
        if (shouldAnimateShimmer)
            startShimmer()
    }

    override fun onPause() {
        stopShimmer()
        super.onPause()
    }

    override fun onDestroyView() {
        stopShimmer()
        super.onDestroyView()
    }

    fun onBackPressed(): Boolean {
        val timezone = currentData?.timezone ?: return false

        val defaultDate = getDefaultDate(timezone)
        if (currentSelectedDate != defaultDate) {
            onCalendarDayClick(defaultDate)
            return true
        }

        return false
    }

    override fun newIntent(param: String) {
        super.newIntent(param)
        handleIntent()
    }

    private fun handleIntent() {
        if (param == "today") {
            when (scheduleViewModel.scheduleState.value) {
                CacheDataLoadState.Empty, CacheDataLoadState.CacheLoading -> Unit
                else -> {
                    currentData?.let { data ->
                        if (data.schedule.isNotEmpty()) {
                            val position = getSelectedDateIndex(getToday(data.timezone))
                            submitCalendar(data, position)
                            ui.dayRecycler.scrollToPosition(position)
                            param = null
                        }
                    }
                }
            }
        }
    }

    private fun startShimmer() {
        ui.skeletonShimmer.visibility = View.VISIBLE
        ui.skeletonShimmer.doOnLayout {
            val startX = -ui.skeletonShimmer.width.toFloat()
            val endX = ui.root.width.toFloat()

            ui.skeletonShimmer.translationX = startX

            shimmerAnimator?.cancel()
            shimmerAnimator = ObjectAnimator.ofFloat(
                ui.skeletonShimmer,
                View.TRANSLATION_X,
                startX,
                endX
            ).apply {
                duration = 600L
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.RESTART
                interpolator = LinearInterpolator()
                start()
            }
        }
    }

    private fun stopShimmer() {
        shimmerAnimator?.cancel()
        shimmerAnimator = null
        ui.skeletonShimmer.visibility = View.GONE
    }

    private fun setupRecyclerViews() {
        ui.calendarRecycler.layoutManager = LinearLayoutManager(
            requireContext(),
            LinearLayoutManager.HORIZONTAL,
            false
        )
        ui.calendarRecycler.setHasFixedSize(true)
        ui.calendarRecycler.adapter = calendarSkeletonAdapter

        ui.dayRecycler.layoutManager = LinearLayoutManager(
            requireContext(),
            LinearLayoutManager.HORIZONTAL,
            false
        )
        ui.dayRecycler.setHasFixedSize(true)
        ui.dayRecycler.adapter = dayAdapter
        pagerSnapHelper.attachToRecyclerView(ui.dayRecycler)

        dayAdapter.submitList(List(SKELETON_DAYS_COUNT) { null })

        ui.dayRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            private var position: Int? = null

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)

                val layoutManager = ui.dayRecycler.layoutManager as? LinearLayoutManager ?: return
                val snappedView = pagerSnapHelper.findSnapView(layoutManager) ?: return
                val newPosition = layoutManager.getPosition(snappedView)

                if (position == newPosition) return
                position = newPosition

                currentDates.getOrNull(newPosition)?.let { date ->
                    if (currentSelectedDate != date) {
                        currentSelectedDate = date
                        submitCalendar(currentData)
                    }
                }
            }
        })
    }

    private fun submitCalendar(data: UiScheduleResult?, selectedIndex: Int? = null) {
        if (data == null || currentDates.isEmpty()) return

        val activeSelectedIndex = selectedIndex ?: currentSelectedDate?.let { currentDates.indexOf(it) } ?: 0

        val items = currentDates.mapIndexed { index, date ->
            ScheduleCalendarDayUi(
                date = date,
                weekday = formatWeekday(date),
                dayNumber = date.day.toString(),
                isSelected = index == activeSelectedIndex,
            )
        }

        calendarAdapter.submitList(items)

        centerCalendarItem(activeSelectedIndex)
    }

    private fun currentDateInTimezone(offsetSeconds: Int): LocalDate {
        val utcNow = Instant.now()
        val zone = ZoneOffset.ofTotalSeconds(offsetSeconds)
        val zoned = utcNow.atZone(zone)
        return LocalDate(
            zoned.year,
            zoned.month.toKotlinMonth(),
            zoned.dayOfMonth
        )
    }

    @Suppress("DEPRECATION")
    private fun formatWeekday(date: LocalDate): String {
        val javaDate = java.time.LocalDate.of(date.year, date.month.number, date.day)
        return javaDate.format(DateTimeFormatter.ofPattern("EE", Locale("ru"))).uppercase()
    }

    private fun getLesson(lessonKey: String): UiScheduleLesson? {
        return currentData
            ?.schedule
            ?.filterNotNull()
            ?.flatMap { it.lessons }
            ?.find { it.lessonKey == lessonKey }
    }

    private fun onCalendarDayClick(date: LocalDate) {
        val position = currentDates.indexOf(date)
        if (position < 0) return

        currentSelectedDate = date
        submitCalendar(currentData, position)
        ui.dayRecycler.post {
            ui.dayRecycler.scrollToPosition(position)
        }
    }

    private fun centerCalendarItem(position: Int) {
        ui.calendarRecycler.post {
            val layoutManager = ui.calendarRecycler.layoutManager as? LinearLayoutManager ?: return@post

            val itemWidth = resources.getDimensionPixelSize(R.dimen.schedule_calendar_day_width)
            val recyclerWidth = ui.calendarRecycler.width

            val offset = recyclerWidth / 2 - itemWidth / 2

            layoutManager.scrollToPositionWithOffset(position, offset)
        }
    }

    private fun onPraiseLesson(lessonKey: String, location: FloatArray) {
        val view = DialogPraiseBinding.inflate(layoutInflater, ui.root, false)

        view.textCounter.text = getString(R.string.praise_text_counter, view.text.text?.length ?: 0, PRAISE_TEXT_LIMIT)

        view.text.addTextChangedListener {
            view.textCounter.text = getString(R.string.praise_text_counter, view.text.text?.length ?: 0, PRAISE_TEXT_LIMIT)
            if ((it?.length ?: 0) > PRAISE_TEXT_LIMIT)
                view.textCounter.setTextColor(Color.RED)
            else if ((it?.length ?: 0) == PRAISE_TEXT_LIMIT)
                view.textCounter.setTextColor(requireContext().getColor(R.color.text_secondary))
        }

        val dialog = MaterialAlertDialogBuilder(
            requireContext(),
            R.style.AppDialogTheme
        ).setView(view.root).create()

        view.buttonSendPraise.setOnClickListener {
            val text = view.text.text?.toString()?.trim()?.ifEmpty { null }

            dialog.dismiss()

            praiseAnimations[lessonKey] = location
            scheduleViewModel.sendPraise(lessonKey, text)
        }

        dialog.show()
    }

    private fun onMenuLesson(lessonKey: String, location: FloatArray) {
        val lesson = getLesson(lessonKey).takeIf { it?.isExtra == false } ?: return
        LessonMenuDialog(
            lesson,
            { openLessonNoteEditor(lessonKey) },
            { openDeleteNoteDialog(lessonKey) },
            { onPraiseLesson(lessonKey, location) },
            { Utilities.openUrl(requireContext(), lesson.dnevnikruUrl!!) },
            { onRating(lessonKey) }
        ).show(childFragmentManager, LessonMenuDialog.TAG)

        scheduleViewModel.doneStudyLessonMenu()
    }

    private fun openLessonNoteEditor(lessonKey: String) {
        val lesson = getLesson(lessonKey) ?: return
        val timezone = currentData?.timezone ?: return

        val zone = ZoneOffset.ofTotalSeconds(timezone)

        val view = DialogLessonNoteEditorBinding.inflate(layoutInflater, ui.root, false)

        view.text.setText(lesson.note?.text)
        view.settingsPublic.isChecked = lesson.note?.public != false  // По умолчанию true

        if (lesson.note?.remindTime != null) {
            val seconds = lesson.note.remindTime.epochSeconds
            val instant = Instant.ofEpochSecond(seconds)
            val zoned = instant.atZone(zone)

            val date = zoned.toLocalDate()
            val time = zoned.toLocalTime()

            view.reminderDateTimeText.text = getString(
                R.string.note_remind_datetime_format,
                date.toString(),
                time.toString()
            )
        }

        view.textCounter.text = getString(R.string.praise_text_counter, view.text.text?.length ?: 0, NOTE_TEXT_LIMIT)

        view.text.addTextChangedListener {
            view.textCounter.text = getString(R.string.praise_text_counter, view.text.text?.length ?: 0, NOTE_TEXT_LIMIT)
            if ((it?.length ?: 0) > NOTE_TEXT_LIMIT)
                view.textCounter.setTextColor(Color.RED)
            else if ((it?.length ?: 0) == NOTE_TEXT_LIMIT)
                view.textCounter.setTextColor(requireContext().getColor(R.color.text_secondary))
        }

        val dialog = MaterialAlertDialogBuilder(
            requireContext(),
            R.style.AppDialogTheme
        ).setView(view.root).create()

        view.titleCreate.visibility = if (lesson.note?.text.isNullOrBlank()) View.VISIBLE else View.GONE
        view.titleEdit.visibility = if (!lesson.note?.text.isNullOrBlank()) View.VISIBLE else View.GONE

        var remindDateTime = lesson.note?.remindTime?.let { Instant.ofEpochSecond(it.epochSeconds) }

        view.buttonReminderDateTime.setOnClickListener {
            val picker = MaterialDatePicker.Builder.datePicker()
                .setTitleText(R.string.title_dialog_note_remind_date_editor)
                .build()

            picker.addOnPositiveButtonClickListener { selection ->
                val millis = selection ?: return@addOnPositiveButtonClickListener
                val instant = Instant.ofEpochMilli(millis)
                val date = instant.atZone(ZoneOffset.ofTotalSeconds(timezone)).toLocalDate()

                val picker = MaterialTimePicker.Builder()
                    .setTimeFormat(TimeFormat.CLOCK_24H)
                    .setTitleText(R.string.title_dialog_note_remind_time_editor)
                    .build()

                picker.addOnPositiveButtonClickListener {
                    val time = LocalTime.of(picker.hour, picker.minute)
                    remindDateTime = LocalDateTime.of(date, time).toInstant(ZoneOffset.ofTotalSeconds(timezone))

                    val dateText = date.toString()
                    val timeText = time.toString().take(5)
                    view.reminderDateTimeText.text = getString(
                        R.string.note_remind_datetime_format,
                        dateText,
                        timeText
                    )
                }
                picker.addOnNegativeButtonClickListener {
                    remindDateTime = null
                    view.reminderDateTimeText.text = getString(R.string.note_remind_datetime)
                }

                picker.show(parentFragmentManager, "note_remind_time")
            }
            picker.addOnNegativeButtonClickListener {
                remindDateTime = null
                view.reminderDateTimeText.text = getString(R.string.note_remind_datetime)
            }

            picker.show(parentFragmentManager, "note_remind_date")
        }

        view.buttonCreateNote.setOnClickListener {
            val text = view.text.text?.toString()?.trim()?.ifEmpty { null }

            if (text == null) {
                Utilities.showAlertDialog(
                    requireContext(),
                    getString(R.string.title_dialog_no_note_text),
                    getString(R.string.message_dialog_no_note_text),
                    getString(R.string.button_dialog_no_note_text)
                )
                return@setOnClickListener
            }

            dialog.dismiss()

            scheduleViewModel.createLessonNote(
                lesson.lessonKey!!,
                text,
                view.settingsPublic.isChecked,
                remindDateTime
            )
        }

        dialog.show()
    }

    private fun openDeleteNoteDialog(lessonKey: String) {
        Utilities.showAlertDialog(
            requireContext(),
            getString(R.string.title_dialog_delete_note),
            getString(R.string.message_dialog_delete_note),
            getString(R.string.button_dialog_delete_note)
        ) { _, _ ->
            scheduleViewModel.deleteLessonNote(lessonKey)
        }
    }

    private fun onRating(lessonKey: String) {
        val lesson = getLesson(lessonKey) ?: return

        RatingDialog(
            lesson,
            showNumber = false
        ).show(parentFragmentManager, RatingDialog.TAG)
    }

    private fun onSeePost(postId: Long) {
        val posts = currentData?.schedule?.mapNotNull { it?.schoolPosts }?.flatten() ?: return
        val post = posts.find { it.postId == postId } ?: return

        if (post.isSaw) return

        scheduleViewModel.seePost(postId)
    }

    private fun onClickPost(postId: Long) {
        val posts = currentData?.schedule?.mapNotNull { it?.schoolPosts }?.flatten() ?: return
        val post = posts.find { it.postId == postId } ?: return

        WebSchoolPostActivity.start(postLauncher, requireContext(), post)

        scheduleViewModel.clickPost(postId)
    }

    private fun setupCollectors() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        SettingsManager.activeChildIdFlow(),
                        SettingsManager.beforeScheduleFlow(),
                        SettingsManager.afterScheduleFlow()
                    ) { childId, before, after ->
                        Triple(childId, before, after)
                    }
                        .distinctUntilChanged()
                        .collect { (activeChildId, scheduleBefore, scheduleAfter) ->
                            val childChanged = activeChildId != currentActiveChildId
                            val rangeChanged = scheduleBefore != currentBefore || scheduleAfter != currentAfter

                            currentActiveChildId = activeChildId
                            currentBefore = scheduleBefore
                            currentAfter = scheduleAfter

                            if (childChanged) {
                                restartScheduleFromScratch()
                            } else if (rangeChanged) {
                                val oldData = currentData

                                if (oldData != null) {
                                    val resized = resizeSchedule(oldData, scheduleBefore, scheduleAfter)
                                    currentData = resized
                                    renderSchedule(resized, currentPraiseStates, currentNoteStates)
                                    if (resized.schedule.any { it == null })
                                        scheduleViewModel.loadCacheSchedule()
                                } else {
                                    restartScheduleFromScratch()
                                }
                            }
                        }
                }
                launch {
                    scheduleViewModel.scheduleState.collect { state ->
                        when (state) {
                            CacheDataLoadState.Empty -> {
                                scheduleViewModel.loadCacheSchedule()

                                if (!shouldAnimateShimmer) {
                                    showSkeletonMode()
                                }
                            }
                            CacheDataLoadState.CacheLoading -> {
                                updateCloudLoading(false)
                            }
                            CacheDataLoadState.CacheSuccess -> {
                                shouldAnimateShimmer = false
                                stopShimmer()
                                scheduleViewModel.loadCloudSchedule()
                            }
                            is CacheDataLoadState.CacheError -> {
                                Utilities.showUiMessage(requireContext(), state.message)
                                scheduleViewModel.loadCloudSchedule()
                            }
                            CacheDataLoadState.CloudLoading -> {
                                scheduleViewModel.emptyStates()
                                updateCloudLoading(true)
                            }
                            CacheDataLoadState.CloudSuccess -> {
                                updateCloudLoading(false)
                            }
                            is CacheDataLoadState.CloudError -> {
                                updateCloudLoading(false)
                                Utilities.showUiMessage(requireContext(), state.message)
                                scheduleViewModel.resetError(ScheduleViewModel.StateType.Schedule)
                                if (state.unauthorized)
                                    logout()
                            }
                            CacheDataLoadState.ShownError -> {
                                // Ошибка уже показана
                            }
                        }
                    }
                }
                launch {
                    scheduleViewModel.scheduleData.collect { data ->
                        if (currentData != data) {
                            currentData = data
                            if (data != null)
                                renderSchedule(data, currentPraiseStates, currentNoteStates)
                        }
                    }
                }
                launch {
                    scheduleViewModel.praiseStates.collect { states ->
                        currentPraiseStates = states
                        currentData?.let { renderSchedule(it, currentPraiseStates, currentNoteStates) }

                        for ((lessonKey, state) in states) {
                            if (state is LoadState.Error) {
                                Utilities.showUiMessage(requireContext(), state.message)
                                scheduleViewModel.resetError(ScheduleViewModel.MapStateType.Praises, lessonKey)
                                if (state.unauthorized) {
                                    logout()
                                    break
                                }
                            } else if (state is LoadState.Success) {
                                startPraiseAnimation(praiseAnimations[lessonKey])
                                scheduleViewModel.reset(ScheduleViewModel.MapStateType.Praises, lessonKey)
                            }
                        }
                    }
                }
                launch {
                    scheduleViewModel.noteStates.collect { states ->
                        currentNoteStates = states
                        currentData?.let { renderSchedule(it, currentPraiseStates, currentNoteStates) }

                        for ((lessonKey, state) in states) {
                            if (state is LoadState.Error) {
                                Utilities.showUiMessage(requireContext(), state.message)
                                scheduleViewModel.resetError(ScheduleViewModel.MapStateType.Notes, lessonKey)
                                if (state.unauthorized) {
                                    logout()
                                    break
                                }
                            }
                        }
                    }
                }
                launch {
                    scheduleViewModel.seePostStates.collect { states ->
                        for ((postId, state) in states) {
                            if (state is LoadState.Success) {
                                (activity as MainActivity).updateNewSchoolPosts(state.data.countPostsWithoutVision)
                            }

                            scheduleViewModel.resetSeePost(postId)
                        }
                    }
                }
                launch {
                    scheduleViewModel.clickPostStates.collect { states ->
                        for ((postId, state) in states) {
                            if (state is LoadState.Success) {
                                (activity as MainActivity).updateNewSchoolPosts(state.data.countPostsWithoutVision)
                            }

                            scheduleViewModel.resetClickPost(postId)
                        }
                    }
                }
                launch {
                    val mainActivity = (requireActivity() as MainActivity)
                    mainActivity.activityViewModel.adState.collect { state ->
                        if (state is LoadState.Success && state.data.ad != null) {
                            mainActivity.renderAdBanner(ui.adBanner, state.data.ad)
                            ui.adBanner.root.visibility = View.VISIBLE
                        } else {
                            ui.adBanner.root.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }

    private fun restartScheduleFromScratch() {
        currentDates = emptyList()
        currentSelectedDate = null
        showSkeletonMode()
        scheduleViewModel.resetSchedule()
    }

    private fun resizeSchedule(data: UiScheduleResult, before: Int, after: Int): UiScheduleResult {
        val today = currentDateInTimezone(data.timezone)
        val firstDate = today.minus(DatePeriod(days = before))
        val byDate = data.schedule.filterNotNull().associateBy { it.date }

        val newSchedule = List(before + 1 + after) { index ->
            val date = firstDate.plus(DatePeriod(days = index))
            byDate[date]
        }

        return data.copy(schedule = newSchedule)
    }

    private fun showSkeletonMode() {
        currentData = null
        currentPraiseStates = emptyMap()
        currentNoteStates = emptyMap()
        shouldAnimateShimmer = true

        ui.calendarRecycler.adapter = calendarSkeletonAdapter
        dayAdapter.submitList(List(SKELETON_DAYS_COUNT) { null })

        startShimmer()
    }

    private fun updateCloudLoading(show: Boolean) {
        ui.swipeRefresh.isRefreshing = show
    }

    private fun logout() {
        scheduleViewModel.logout()

        LoginActivity.openLoginActivity(requireActivity())
    }

    private fun startPraiseAnimation(location: FloatArray?) {
        (requireActivity() as MainActivity).startKonfettiAnimation(ui.konfettiView, location)
    }

    private fun renderSchedule(
        rawData: UiScheduleResult,
        praiseStates: Map<String, LoadState<Unit>>,
        noteStates: Map<String, LoadState<NoteResult>>
    ) {
        if (rawData.schedule.isEmpty()) return

        val data = rawData.withPraiseStates(praiseStates).withNoteStates(noteStates)

        if (ui.calendarRecycler.adapter !== calendarAdapter) {
            ui.calendarRecycler.adapter = calendarAdapter
        }
        if (ui.dayRecycler.adapter !== dayAdapter) {
            ui.dayRecycler.adapter = dayAdapter
        }

        currentDates = buildDates(data.timezone)
        val selected = if (param == "today") getToday(data.timezone) else getSelectedDate(data.timezone)

        currentSelectedDate = selected
        val selectedIndex = getSelectedDateIndex(selected)

        dayAdapter.submitList(data.schedule)

        submitCalendar(data, selectedIndex)
        ui.dayRecycler.post {
            ui.dayRecycler.scrollToPosition(selectedIndex)
        }
    }

    private fun UiScheduleResult.withPraiseStates(praiseStates: Map<String, LoadState<Unit>>): UiScheduleResult {
        if (praiseStates.isEmpty()) return this

        return this.copy(
            schedule = schedule.map { day ->
                if (day?.lessons?.find { praiseStates[it.lessonKey] != null } != null) {
                    day.copy(lessons = day.lessons.map { lesson ->
                        val state = praiseStates[lesson.lessonKey]
                        if (state != null) lesson.copy(praiseState = state) else lesson
                    })
                } else {
                    day
                }
            }
        )
    }

    private fun UiScheduleResult.withNoteStates(noteStates: Map<String, LoadState<NoteResult>>): UiScheduleResult {
        if (noteStates.isEmpty()) return this

        return this.copy(
            schedule = schedule.map { day ->
                if (day?.lessons?.find { noteStates[it.lessonKey] != null } != null) {
                    day.copy(lessons = day.lessons.map { lesson ->
                        val state = noteStates[lesson.lessonKey]
                        if (state != null) lesson.copy(noteState = state) else lesson
                    })
                } else {
                    day
                }
            }
        )
    }

    private fun buildDates(timezone: Int): List<LocalDate> {
        val today = currentDateInTimezone(timezone)
        val first = today.minus(DatePeriod(days = currentBefore))
        return List(currentBefore + currentAfter + 1) { index ->
            first.plus(DatePeriod(days = index))
        }
    }

    private fun getDefaultDate(timezone: Int): LocalDate {
        val zoned = Instant.now().atZone(ZoneOffset.ofTotalSeconds(timezone))
        val today = getToday(timezone)

        return if (zoned.hour >= OPEN_NEXT_DAY_SINCE_HOURS) today.plus(DatePeriod(days = 1)) else today
    }

    private fun getToday(timezone: Int): LocalDate {
        val zoned = Instant.now().atZone(ZoneOffset.ofTotalSeconds(timezone))
        return LocalDate(
            zoned.year,
            zoned.month.toKotlinMonth(),
            zoned.dayOfMonth
        )
    }

    private fun getSelectedDate(timezone: Int): LocalDate {
        val selected = currentSelectedDate?.takeIf { it in currentDates }
            ?: getDefaultDate(timezone).takeIf { it in currentDates }
            ?: currentDates.getOrNull(currentBefore.coerceIn(0, currentDates.lastIndex))
            ?: currentDates.first()

        return selected
    }

    private fun getSelectedDateIndex(selected: LocalDate): Int {
        return currentDates.indexOf(selected).coerceAtLeast(0)
    }

    private fun setupSwipeRefresh() {
        ui.swipeRefresh.setColorSchemeColors(requireContext().getColor(R.color.swipe_refresh_scheme))
        ui.swipeRefresh.setProgressBackgroundColorSchemeColor(requireContext().getColor(R.color.main_bg))

        ui.swipeRefresh.setDistanceToTriggerSync((150 * resources.displayMetrics.density).toInt())
        ui.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            val layoutManager = ui.dayRecycler.layoutManager as? LinearLayoutManager
            val selectedIndex = currentData?.let { getSelectedDateIndex(getSelectedDate(it.timezone)) }
            val view = selectedIndex?.let { layoutManager?.findViewByPosition(it) }

            val lessonsRecycler = view?.findViewById<RecyclerView>(R.id.lessonsRecycler)

            lessonsRecycler?.canScrollVertically(-1) ?: true
        }

        ui.swipeRefresh.setOnRefreshListener {
            when (scheduleViewModel.scheduleState.value) {
                is CacheDataLoadState.CloudSuccess, is CacheDataLoadState.CloudError, is CacheDataLoadState.ShownError -> {
                    scheduleViewModel.loadCloudSchedule()
                }
                else -> {
                    updateCloudLoading(false)
                }
            }
        }
    }
}