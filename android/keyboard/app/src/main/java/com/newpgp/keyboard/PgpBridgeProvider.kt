// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.keyboard

/** Selects the bridge used by the service. Tests construct bridges directly. */
object PgpBridgeProvider {

    /**
     * Defaults to the failing [NativePgpBridge] stub. The demo flag is false in normal
     * builds and exists only to exercise the full UI flow locally with fake armor.
     */
    @Volatile
    var current: PgpBridge = if (BuildConfig.ENABLE_DEMO_PGP_BRIDGE) MockPgpBridge() else NativePgpBridge()
}
