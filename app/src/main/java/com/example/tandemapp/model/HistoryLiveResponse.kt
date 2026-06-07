package com.example.tandemapp.model

import kotlinx.serialization.Serializable

@Serializable
data class HistoryLiveResponse(
	val status: String,
	val data: LiveDayDataset
)