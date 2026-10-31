/*
 * SPDX-FileCopyrightText: 2026 NewPipe e.V. <https://newpipe-ev.de>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

const val NEWPIPE_VERSION_SDK_COMPILE_MAJOR = 37
const val NEWPIPE_VERSION_SDK_COMPILE_MINOR = 0
const val NEWPIPE_VERSION_SDK_MIN = 23
const val NEWPIPE_VERSION_SDK_TARGET = 35

const val NEWPIPE_VERSION_CODE = 1015
const val NEWPIPE_VERSION_NAME = "0.29.1"

const val NEWPIPE_APPLICATION_ID_OLD = "org.schabi.newpipe"
const val NEWPIPE_APPLICATION_ID_NEW = "net.newpipe.app"

// applicationId is separate from namespace on purpose: namespace identifies
// generated R/BuildConfig classes and must stay unique per module, while the
// application id only decides which installed app this build replaces.
const val JM21_APPLICATION_ID = "com.jm21.player"
