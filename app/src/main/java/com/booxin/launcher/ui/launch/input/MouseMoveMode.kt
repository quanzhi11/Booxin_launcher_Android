package com.booxin.launcher.ui.launch.input

/**
 * FCL-compatible GUI cursor move mode.
 * CLICK: touch position = cursor; press/release left button
 * SLIDE: drag moves cursor relatively; short tap = left click
 */
enum class MouseMoveMode {
    CLICK,
    SLIDE
}
