package uk.veritaslabs.kits.sync

import uk.veritaslabs.kits.spaces.SpacesConfig

/** The kit's tests use their own schemes and group names; the kit has no brand of its own to default to. */
internal val TestSpacesConfig = SpacesConfig(inviteScheme = "app://invite", groupName = "Test Space")
internal val TestDeviceSyncConfig = DeviceSyncConfig(pairingScheme = "app://pair", groupName = "TestDevices")

/** The send order the engine tests pin: the feature ids of [TestSources], in the order their documents are sent (the roster is always last). */
internal val TestSendOrder = listOf("pantry", "shopping-list", "cookbook", "merchants", "mealplan", "calendar", "private-calendar", "tasks", "private-tasks")
