package com.flexsentlabs.koncerto.core.config

import kotlinx.serialization.Serializable

@Serializable
data class CrossProjectFollowUpConfig(
    val targetProjectSlug: String,
    val titleTemplate: String,
    val descriptionTemplate: String? = null,
    val linkType: String? = null
)
