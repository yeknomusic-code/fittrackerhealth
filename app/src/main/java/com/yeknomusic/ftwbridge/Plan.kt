package com.yeknomusic.ftwbridge

/** Generated from the watch app's js/data.js (same exercise ids as the phone app). */
data class PlanExercise(val planKey: String, val planName: String, val name: String)

val PLAN_EXERCISES: Map<String, PlanExercise> = mapOf(
    "bench" to PlanExercise("push-day-a", "Push Day A", "Barbell Bench Press"),
    "inc_db" to PlanExercise("push-day-a", "Push Day A", "Incline DB Press"),
    "ohp" to PlanExercise("push-day-a", "Push Day A", "Overhead Press (DB)"),
    "lat_raise" to PlanExercise("push-day-a", "Push Day A", "Lateral Raises"),
    "cable_fly" to PlanExercise("push-day-a", "Push Day A", "Cable Crossover"),
    "skulls" to PlanExercise("push-day-a", "Push Day A", "Skull Crushers"),
    "pushdown" to PlanExercise("push-day-a", "Push Day A", "Cable Pushdown"),
    "deadlift" to PlanExercise("pull-day-a", "Pull Day A", "Conventional Deadlift"),
    "bb_row" to PlanExercise("pull-day-a", "Pull Day A", "Barbell Row (Overhand)"),
    "pullups" to PlanExercise("pull-day-a", "Pull Day A", "Pull-Ups (Weighted)"),
    "cab_row" to PlanExercise("pull-day-a", "Pull Day A", "Seated Cable Row"),
    "face_pull" to PlanExercise("pull-day-a", "Pull Day A", "Face Pull"),
    "inc_curl" to PlanExercise("pull-day-a", "Pull Day A", "Incline DB Curl"),
    "hammer" to PlanExercise("pull-day-a", "Pull Day A", "Hammer Curl"),
    "squat" to PlanExercise("leg-day-a", "Leg Day A", "Barbell Back Squat"),
    "rdl" to PlanExercise("leg-day-a", "Leg Day A", "Romanian Deadlift"),
    "bss" to PlanExercise("leg-day-a", "Leg Day A", "Bulgarian Split Squat"),
    "leg_press" to PlanExercise("leg-day-a", "Leg Day A", "Leg Press"),
    "leg_curl" to PlanExercise("leg-day-a", "Leg Day A", "Leg Curl (Seated)"),
    "hip_thrust" to PlanExercise("leg-day-a", "Leg Day A", "Hip Thrust"),
    "calf" to PlanExercise("leg-day-a", "Leg Day A", "Standing Calf Raise"),
    "inc_db2" to PlanExercise("push-day-b", "Push Day B", "Incline DB Bench Press"),
    "db_press2" to PlanExercise("push-day-b", "Push Day B", "DB Shoulder Press"),
    "cab_fly2" to PlanExercise("push-day-b", "Push Day B", "Cable Fly (Mid)"),
    "mach_chest" to PlanExercise("push-day-b", "Push Day B", "Machine Chest Press"),
    "lat_cab" to PlanExercise("push-day-b", "Push Day B", "Lateral Raises (Cable)"),
    "ot_ext" to PlanExercise("push-day-b", "Push Day B", "Overhead Tricep Ext."),
    "dips" to PlanExercise("push-day-b", "Push Day B", "Dips (Bodyweight)"),
    "lat_pull" to PlanExercise("pull-day-b", "Pull Day B", "Lat Pulldown (Wide)"),
    "db_row2" to PlanExercise("pull-day-b", "Pull Day B", "DB Row (Single Arm)"),
    "cab_row2" to PlanExercise("pull-day-b", "Pull Day B", "Cable Row (Close Grip)"),
    "rev_deck" to PlanExercise("pull-day-b", "Pull Day B", "Reverse Pec Deck"),
    "ez_curl" to PlanExercise("pull-day-b", "Pull Day B", "EZ-Bar Curl"),
    "cab_curl" to PlanExercise("pull-day-b", "Pull Day B", "Cable Curl"),
    "rev_curl" to PlanExercise("pull-day-b", "Pull Day B", "Reverse Curl")
)
