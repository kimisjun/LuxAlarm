/*
 * Copyright (C) 2026 김은준
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.dsalmun.luxalarm.wake

import java.time.DayOfWeek
import java.time.LocalTime
import java.util.Collections

@ConsistentCopyVisibility
data class WakeRoutine private constructor(
    val enabled: Boolean,
    val goal: LocalTime,
    val repeatDays: Set<DayOfWeek>,
    val stages: List<WakeStage>,
    val presetSelection: PresetSelection,
    val presetBase: WakePreset,
) {
    init {
        require(StageKind.entries.all { kind -> stages.count { it.kind == kind } == 1 }) {
            "A routine must contain exactly one stage of every kind"
        }
        require(!enabled || stages.any { it.enabled }) { "An enabled routine must have an enabled stage" }
    }

    val preparationMinutes: Int
        get() = stages.filter { it.enabled }.maxOfOrNull { it.startOffsetBeforeGoalMinutes } ?: 0

    val wakeStart: LocalTime
        get() = goal.minusMinutes(preparationMinutes.toLong())

    fun stage(kind: StageKind): WakeStage = stages.single { it.kind == kind }

    fun withGoal(goal: LocalTime): WakeRoutine = copyValidated(goal = goal)

    fun withPreparationMinutes(minutes: Int): WakeRoutine {
        require(minutes in 0..60) { "Preparation must be between 0 and 60 minutes" }
        val oldPreparation = preparationMinutes
        val baseStages = presetBase.stages().associateBy { it.kind }
        val basePreparation =
            stages
                .filter { it.enabled }
                .maxOfOrNull { baseStages.getValue(it.kind).startOffsetBeforeGoalMinutes }
                ?: 1
        val resized =
            stages.map { stage ->
                if (!stage.enabled) {
                    stage
                } else {
                    val sourceOffset =
                        if (oldPreparation == 0) {
                            baseStages.getValue(stage.kind).startOffsetBeforeGoalMinutes
                        } else {
                            stage.startOffsetBeforeGoalMinutes
                        }
                    val sourcePreparation = if (oldPreparation == 0) basePreparation else oldPreparation
                    stage.copy(
                        startOffsetBeforeGoalMinutes = scaleMinutesHalfUp(sourceOffset, minutes, sourcePreparation),
                    )
                }
            }
        return copyValidated(stages = resized, presetSelection = PresetSelection.CUSTOM)
    }

    fun withStage(stage: WakeStage): WakeRoutine =
        copyValidated(
            stages = stages.map { current -> if (current.kind == stage.kind) stage else current },
            presetSelection = PresetSelection.CUSTOM,
        )

    fun withStageEnabled(kind: StageKind, enabled: Boolean): WakeRoutine =
        withStage(stage(kind).copy(enabled = enabled))

    fun withEnabled(enabled: Boolean): WakeRoutine = copyValidated(enabled = enabled)

    fun resetToPresetBase(): WakeRoutine =
        copyValidated(
            stages = presetBase.stages(),
            presetSelection = PresetSelection.valueOf(presetBase.name),
        )

    private fun copyValidated(
        enabled: Boolean = this.enabled,
        goal: LocalTime = this.goal,
        repeatDays: Set<DayOfWeek> = this.repeatDays,
        stages: List<WakeStage> = this.stages,
        presetSelection: PresetSelection = this.presetSelection,
    ): WakeRoutine =
        WakeRoutine(
            enabled = enabled,
            goal = goal,
            repeatDays = immutableSet(repeatDays),
            stages = immutableList(stages),
            presetSelection = presetSelection,
            presetBase = presetBase,
        )

    companion object {
        /**
         * Scales non-negative minute offsets using nearest-integer rounding, with exact halves
         * rounded upward. The monotone formula preserves the ordering and ties of offsets.
         */
        private fun scaleMinutesHalfUp(offset: Int, target: Int, source: Int): Int =
            ((2 * offset * target + source) / (2 * source))

        private fun <T> immutableList(values: Collection<T>): List<T> =
            Collections.unmodifiableList(ArrayList(values))

        private fun <T> immutableSet(values: Collection<T>): Set<T> =
            Collections.unmodifiableSet(LinkedHashSet(values))

        fun default(
            goal: LocalTime,
            repeatDays: Set<DayOfWeek>,
            enabled: Boolean = true,
        ): WakeRoutine = fromPreset(WakePreset.VERY_GENTLE, goal, repeatDays, enabled)

        fun fromPreset(
            preset: WakePreset,
            goal: LocalTime,
            repeatDays: Set<DayOfWeek>,
            enabled: Boolean = true,
        ): WakeRoutine =
            WakeRoutine(
                enabled = enabled,
                goal = goal,
                repeatDays = immutableSet(repeatDays),
                stages = immutableList(preset.stages()),
                presetSelection = PresetSelection.valueOf(preset.name),
                presetBase = preset,
            )
    }
}
