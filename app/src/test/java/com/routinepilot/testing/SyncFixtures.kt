package com.routinepilot.testing

import com.routinepilot.domain.sync.FieldMap
import com.routinepilot.domain.sync.ObjectKey
import com.routinepilot.domain.sync.SyncType
import java.util.UUID

val routineId: UUID = UUID.fromString("00000000-0000-4000-8000-000000000001")
val stepOneId: UUID = UUID.fromString("00000000-0000-4000-8000-000000000011")
val stepTwoId: UUID = UUID.fromString("00000000-0000-4000-8000-000000000012")
val taskId: UUID = UUID.fromString("00000000-0000-4000-8000-000000000021")

val routineKey = ObjectKey(SyncType.ROUTINE, routineId)

fun stepKey(id: UUID) = ObjectKey(SyncType.ROUTINE_STEP, id)

/** A full routine record, with every tracked field present. */
fun routineFields(
    title: String = "Morning",
    description: String? = null,
    position: Int = 1,
): FieldMap = mapOf("title" to title, "description" to description, "position" to position.toString())

/** A full step record. The step belongs to [routineId] unless another routine is given. */
fun stepFields(
    title: String = "Brush",
    description: String? = null,
    durationSeconds: Int? = 120,
    position: Int = 1,
    routine: UUID = routineId,
): FieldMap =
    mapOf(
        "routineId" to routine.toString(),
        "title" to title,
        "description" to description,
        "durationSeconds" to durationSeconds?.toString(),
        "position" to position.toString(),
    )

/** A full task record. */
fun taskFields(
    title: String = "Water plants",
    isCompleted: Boolean = false,
    position: Int = 1,
): FieldMap =
    mapOf(
        "title" to title,
        "isCompleted" to isCompleted.toString(),
        "routineId" to null,
        "position" to position.toString(),
    )
