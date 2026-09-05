package com.booxin.launcher.ui.multiplayer

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.booxin.launcher.AppContainer
import com.booxin.launcher.R
import com.booxin.launcher.core.multiplayer.BooxinUserDetail
import com.booxin.launcher.core.multiplayer.MultiplayerSocialConstants
import com.booxin.launcher.core.multiplayer.RewardProfileHelper
import com.booxin.launcher.databinding.DialogUserDetailBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * Landscape user detail: left profile (avatar/name/title/gold), right status + actions.
 * Friend → 2×2 buttons; stranger → single block/unblock.
 */
object UserDetailDialog {

    fun show(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        userId: String,
        fallbackPresenceUpdatedAtUtc: String? = null,
        onChat: (peerId: String, peerName: String) -> Unit,
        onChanged: () -> Unit = {}
    ) {
        if (userId.isBlank()) return
        val binding = DialogUserDetailBinding.inflate(LayoutInflater.from(context))
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.multiplayer_user_detail_title)
            .setView(binding.root)
            .setNegativeButton(R.string.multiplayer_user_detail_close, null)
            .create()

        binding.textTitle.text = context.getString(R.string.multiplayer_user_detail_rank_loading)
        binding.textGold.text = ""
        binding.textUsername.text = "…"
        binding.textStatus.text = ""
        binding.textSignature.text =
            context.getString(R.string.multiplayer_user_detail_signature_empty)
        binding.gridFriendActions.isVisible = false
        binding.buttonStrangerAction.isVisible = false

        dialog.show()

        val dm = context.resources.displayMetrics
        val width = (dm.widthPixels * 0.88f).toInt()
            .coerceIn(dp(context, 360), (dm.widthPixels * 0.94f).toInt())
        val chrome = dp(context, 112)
        val maxContentH = ((dm.heightPixels * 0.82f).toInt() - chrome)
            .coerceAtLeast(dp(context, 160))

        // Mutate existing LayoutParams — never replace with raw ViewGroup.LayoutParams
        // (AlertDialog wraps the view in FrameLayout; wrong LP type → ClassCast crash).
        applyContentHeight(binding, maxContentH)
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)

        lifecycleOwner.lifecycleScope.launch {
            val detailResult = runCatching {
                AppContainer.multiplayerAuth.loadUserDetail(userId).getOrThrow()
            }
            val detail = detailResult.getOrNull()
            if (detail == null) {
                Toast.makeText(
                    context,
                    detailResult.exceptionOrNull()?.message
                        ?: context.getString(R.string.multiplayer_user_detail_load_failed),
                    Toast.LENGTH_SHORT
                ).show()
                if (dialog.isShowing) dialog.dismiss()
                return@launch
            }

            val enriched = if (detail.presenceUpdatedAtUtc.isNullOrBlank() &&
                !fallbackPresenceUpdatedAtUtc.isNullOrBlank()
            ) {
                detail.copy(presenceUpdatedAtUtc = fallbackPresenceUpdatedAtUtc)
            } else {
                detail
            }

            if (!dialog.isShowing) return@launch
            runCatching {
                bindDetail(
                    context = context,
                    lifecycleOwner = lifecycleOwner,
                    dialog = dialog,
                    binding = binding,
                    detail = enriched,
                    onChat = onChat,
                    onChanged = onChanged
                )
                applyContentHeight(binding, maxContentH)
                dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            }.onFailure { err ->
                Toast.makeText(
                    context,
                    err.message ?: context.getString(R.string.multiplayer_user_detail_load_failed),
                    Toast.LENGTH_SHORT
                ).show()
                if (dialog.isShowing) dialog.dismiss()
                return@launch
            }

            val reward = AppContainer.multiplayerAuth.loadPublicRewardProfile(userId).getOrNull()
            if (!dialog.isShowing) return@launch
            if (reward == null) {
                binding.textTitle.text =
                    context.getString(R.string.multiplayer_user_detail_rank_unavailable)
                binding.textGold.text = ""
                binding.imageFrame.applyBooxinFrame(null)
            } else {
                binding.textTitle.text = RewardProfileHelper.rankLabel(reward)
                binding.textGold.text = context.getString(
                    R.string.multiplayer_user_detail_gold_format,
                    reward.gold
                )
                binding.imageFrame.applyBooxinFrame(reward.selectedFrameId)
            }
            applyContentHeight(binding, maxContentH)
        }
    }

    private fun applyContentHeight(binding: DialogUserDetailBinding, maxContentH: Int) {
        val lp = binding.rootUserDetail.layoutParams
        if (lp != null) {
            binding.rootUserDetail.updateLayoutParams {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = maxContentH
            }
        } else {
            binding.rootUserDetail.layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxContentH
            )
        }
        binding.scrollUserDetail.isVerticalScrollBarEnabled = true
        binding.scrollUserDetail.isScrollbarFadingEnabled = false
        binding.rootUserDetail.requestLayout()
    }

    private fun bindDetail(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        dialog: androidx.appcompat.app.AlertDialog,
        binding: DialogUserDetailBinding,
        detail: BooxinUserDetail,
        onChat: (peerId: String, peerName: String) -> Unit,
        onChanged: () -> Unit
    ) {
        val selfId = AppContainer.multiplayerAuth.current()?.user?.id
        val isSelf = !selfId.isNullOrBlank() && selfId == detail.id

        binding.imageAvatar.loadBooxinAvatar(detail.avatarUrl)
        binding.textUsername.text = detail.username.ifBlank { detail.id }
        binding.textStatus.text = detail.statusDisplayText
        binding.textSignature.text = detail.signature?.trim()?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.multiplayer_user_detail_signature_empty)

        val relationship = detail.relationship?.trim().orEmpty()
        binding.badgeRelationship.isVisible = detail.isFriend && relationship.isNotEmpty()
        binding.badgeRelationship.text = relationship

        binding.textBlockedHint.isVisible = detail.isBlockedBy
        binding.textBlockedHint.text = if (detail.isBlockedBy) {
            context.getString(R.string.multiplayer_user_detail_blocked_by)
        } else {
            ""
        }

        val showFriendGrid = detail.isFriend && !isSelf
        binding.gridFriendActions.isVisible = showFriendGrid
        binding.buttonChat.isVisible = showFriendGrid
        binding.buttonRelationship.isVisible = showFriendGrid

        if (showFriendGrid) {
            binding.buttonBlockFriend.isVisible = !detail.isBlocked
            binding.buttonUnblockFriend.isVisible = detail.isBlocked
            binding.buttonRemoveFriend.isVisible = true
            binding.buttonStrangerAction.isVisible = false
        } else if (!isSelf) {
            binding.buttonBlockFriend.isVisible = false
            binding.buttonUnblockFriend.isVisible = false
            binding.buttonRemoveFriend.isVisible = false
            binding.buttonStrangerAction.isVisible = true
            if (detail.isBlocked) {
                binding.buttonStrangerAction.text =
                    context.getString(R.string.multiplayer_unblock)
                binding.buttonStrangerAction.setTextColor(
                    context.getColor(R.color.booxin_blue)
                )
            } else {
                binding.buttonStrangerAction.text =
                    context.getString(R.string.multiplayer_user_detail_block)
                binding.buttonStrangerAction.setTextColor(0xFFD94A4A.toInt())
            }
        } else {
            binding.buttonBlockFriend.isVisible = false
            binding.buttonUnblockFriend.isVisible = false
            binding.buttonRemoveFriend.isVisible = false
            binding.buttonStrangerAction.isVisible = false
        }

        binding.buttonChat.setOnClickListener {
            onChat(detail.id, detail.username)
            dialog.dismiss()
        }

        binding.buttonRelationship.setOnClickListener {
            pickRelationship(context, lifecycleOwner, binding, detail, onChanged)
        }

        val blockClick = {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.multiplayer_user_detail_block)
                .setMessage(
                    context.getString(
                        R.string.multiplayer_user_detail_block_confirm,
                        detail.username
                    )
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.multiplayer_user_detail_block) { _, _ ->
                    lifecycleOwner.lifecycleScope.launch {
                        val result = AppContainer.multiplayerAuth.blockUser(detail.id)
                        if (result.isSuccess) {
                            Toast.makeText(
                                context,
                                R.string.multiplayer_user_detail_blocked_ok,
                                Toast.LENGTH_SHORT
                            ).show()
                            onChanged()
                            dialog.dismiss()
                            show(
                                context = context,
                                lifecycleOwner = lifecycleOwner,
                                userId = detail.id,
                                fallbackPresenceUpdatedAtUtc = detail.presenceUpdatedAtUtc,
                                onChat = onChat,
                                onChanged = onChanged
                            )
                        } else {
                            Toast.makeText(
                                context,
                                result.exceptionOrNull()?.message ?: "failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                .show()
        }

        val unblockClick = {
            lifecycleOwner.lifecycleScope.launch {
                val result = AppContainer.multiplayerAuth.unblockUser(detail.id)
                if (result.isSuccess) {
                    onChanged()
                    dialog.dismiss()
                    show(
                        context = context,
                        lifecycleOwner = lifecycleOwner,
                        userId = detail.id,
                        fallbackPresenceUpdatedAtUtc = detail.presenceUpdatedAtUtc,
                        onChat = onChat,
                        onChanged = onChanged
                    )
                } else {
                    Toast.makeText(
                        context,
                        result.exceptionOrNull()?.message ?: "failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        binding.buttonBlockFriend.setOnClickListener { blockClick() }
        binding.buttonUnblockFriend.setOnClickListener { unblockClick() }
        binding.buttonRemoveFriend.setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.multiplayer_remove_friend)
                .setMessage(
                    context.getString(R.string.multiplayer_remove_confirm, detail.username)
                )
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.multiplayer_remove_friend) { _, _ ->
                    lifecycleOwner.lifecycleScope.launch {
                        val result = AppContainer.multiplayerAuth.removeFriend(detail.id)
                        if (result.isSuccess) {
                            Toast.makeText(
                                context,
                                result.getOrNull() ?: "OK",
                                Toast.LENGTH_SHORT
                            ).show()
                            onChanged()
                            dialog.dismiss()
                        } else {
                            Toast.makeText(
                                context,
                                result.exceptionOrNull()?.message ?: "failed",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                .show()
        }
        binding.buttonStrangerAction.setOnClickListener {
            if (detail.isBlocked) unblockClick() else blockClick()
        }
    }

    private fun pickRelationship(
        context: Context,
        lifecycleOwner: LifecycleOwner,
        binding: DialogUserDetailBinding,
        detail: BooxinUserDetail,
        onChanged: () -> Unit
    ) {
        val presets = MultiplayerSocialConstants.RELATIONSHIP_PRESETS.toTypedArray()
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.multiplayer_user_detail_relationship)
            .setItems(presets) { _, which ->
                val label = presets[which]
                lifecycleOwner.lifecycleScope.launch {
                    val result =
                        AppContainer.multiplayerAuth.updateRelationship(detail.id, label)
                    if (result.isSuccess) {
                        binding.badgeRelationship.isVisible = true
                        binding.badgeRelationship.text = label
                        Toast.makeText(
                            context,
                            R.string.multiplayer_user_detail_relationship_ok,
                            Toast.LENGTH_SHORT
                        ).show()
                        onChanged()
                    } else {
                        Toast.makeText(
                            context,
                            result.exceptionOrNull()?.message ?: "failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
