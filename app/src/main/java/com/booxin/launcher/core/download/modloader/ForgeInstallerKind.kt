package com.booxin.launcher.core.download.modloader

enum class ForgeInstallerKind {
  /** install_profile.json with "spec" — ForgeNewInstallTask processors */
  NEW_SPEC,
  /** install_profile.json with install + versionInfo — zip extract */
  OLD_LEGACY,
  /** No usable install_profile — BangBang93 bootstrapper */
  BOOTSTRAP_INJECTOR
}
