package com.example.focaaa.chat.pairing

/** Prevents a delayed PIN operation from reopening private UI after Back or backgrounding. */
internal fun canContinuePinGateAttempt(
    expectedBackgroundCount: Int,
    currentBackgroundCount: Int,
    isExpectedGateVisible: Boolean
): Boolean = expectedBackgroundCount == currentBackgroundCount && isExpectedGateVisible
