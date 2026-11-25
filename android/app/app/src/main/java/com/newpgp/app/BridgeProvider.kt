// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Vaibhav - https://github.com/thisisforlearn
package com.newpgp.app

/** Selects the bridge used by the UI. Tests construct bridges directly. */
object BridgeProvider {

    /**
     * Defaults to the failing [NativePgpBridge] stub. The demo flag is false in
     * normal builds and exists only to walk through the UI locally with fake
     * armor; it must never ship in a release.
     */
    @Volatile
    var current: PgpBridge = if (BuildConfig.ENABLE_DEMO_PGP_BRIDGE) MockPgpBridge() else NativePgpBridge()
}
