package uk.veritaslabs.kits.sync

/** The steps [DeviceSyncService.pairWith] runs, in order, to add another device to this device's group. */
public enum class PairingStep { PrepareGroup, CreateInvite, SendInvite, AwaitJoin, GrantAdmin, SaveDevice }

public enum class PairingStepStatus { Pending, Running, Done, Failed }

/** Called as [PairingStep]s start, finish or fail. */
public typealias PairingProgressListener = (PairingStep, PairingStepStatus) -> Unit
