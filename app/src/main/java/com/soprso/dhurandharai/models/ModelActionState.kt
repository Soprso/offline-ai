package com.soprso.dhurandharai.models

data class ModelActionState(
    val busy: Boolean = false,
    val label: String? = null,
    /** 0f..1f when the total size is known; null for indeterminate progress. */
    val progress: Float? = null,
    val message: String? = null,
    val isError: Boolean = false,
)
