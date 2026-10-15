package com.kudo.android.data

data class SiteEmoji(val name: String, val url: String, val group: String = "")
data class SiteEmojiGroup(val name: String, val items: List<SiteEmoji>)
