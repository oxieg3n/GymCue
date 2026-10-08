package com.gymguide.app.data

/** Generated from app/schemas/.../2.json (Room export) — CREATE statements for tables added in db v2. */
val SCHEMA_V2_CREATE = listOf(
  "CREATE TABLE IF NOT EXISTS `workouts` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, `split` TEXT NOT NULL, `ownerId` TEXT NOT NULL, `visibility` TEXT NOT NULL, `sourceTemplateId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `syncState` TEXT NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
  "CREATE TABLE IF NOT EXISTS `workout_items` (`id` TEXT NOT NULL, `workoutId` TEXT NOT NULL, `position` INTEGER NOT NULL, `exerciseId` TEXT NOT NULL, `sets` INTEGER NOT NULL, `reps` TEXT NOT NULL, `restSec` INTEGER NOT NULL, PRIMARY KEY(`id`))",
  "CREATE INDEX IF NOT EXISTS `index_workout_items_workoutId` ON `workout_items` (`workoutId`)",
  "CREATE TABLE IF NOT EXISTS `gyms` (`id` TEXT NOT NULL, `provider` TEXT NOT NULL, `providerPlaceId` TEXT NOT NULL, `name` TEXT NOT NULL, `address` TEXT NOT NULL, `lat` REAL NOT NULL, `lng` REAL NOT NULL, `status` TEXT NOT NULL, `createdBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `syncState` TEXT NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
  "CREATE UNIQUE INDEX IF NOT EXISTS `index_gyms_provider_providerPlaceId` ON `gyms` (`provider`, `providerPlaceId`)",
  "CREATE TABLE IF NOT EXISTS `gym_equipment` (`id` TEXT NOT NULL, `gymId` TEXT NOT NULL, `catalogEquipmentId` TEXT NOT NULL, `name` TEXT NOT NULL, `manufacturer` TEXT NOT NULL, `model` TEXT NOT NULL, `category` TEXT NOT NULL, `alternateNames` TEXT NOT NULL, `primaryMuscles` TEXT NOT NULL, `howToUse` TEXT NOT NULL, `qrCode` TEXT NOT NULL, `videoUrl` TEXT NOT NULL, `notes` TEXT NOT NULL, `photoUris` TEXT NOT NULL, `fieldSources` TEXT NOT NULL, `status` TEXT NOT NULL, `reviewNote` TEXT NOT NULL, `submittedBy` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `syncState` TEXT NOT NULL, `deleted` INTEGER NOT NULL, PRIMARY KEY(`id`))",
  "CREATE INDEX IF NOT EXISTS `index_gym_equipment_gymId` ON `gym_equipment` (`gymId`)",
  "CREATE TABLE IF NOT EXISTS `users` (`id` TEXT NOT NULL, `displayName` TEXT NOT NULL, `email` TEXT NOT NULL, `role` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, `syncState` TEXT NOT NULL, PRIMARY KEY(`id`))",
  "CREATE TABLE IF NOT EXISTS `audit_log` (`id` TEXT NOT NULL, `actorId` TEXT NOT NULL, `action` TEXT NOT NULL, `targetType` TEXT NOT NULL, `targetId` TEXT NOT NULL, `detail` TEXT NOT NULL, `at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
  "CREATE TABLE IF NOT EXISTS `outbox` (`id` TEXT NOT NULL, `entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, `op` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, PRIMARY KEY(`id`))"
)
