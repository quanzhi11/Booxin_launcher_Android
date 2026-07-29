package com.booxin.launcher.ui.multiplayer

import android.widget.ImageView
import coil.load
import coil.transform.CircleCropTransformation
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.multiplayer.MultiplayerAvatarUrlHelper

fun ImageView.loadBooxinAvatar(avatarUrl: String?) {
    val root = AppContainer.multiplayerAuth.current()?.apiRoot
    val resolved = MultiplayerAvatarUrlHelper.resolveDisplayUrl(avatarUrl, root)
    load(resolved) {
        crossfade(true)
        transformations(CircleCropTransformation())
        placeholder(R.drawable.ic_avatar_placeholder)
        error(R.drawable.ic_avatar_placeholder)
        fallback(R.drawable.ic_avatar_placeholder)
    }
}
