package com.gerard.momosms

data class SmsModel(
    val id: Long,
    val sender: String,
    val body: String,
    val timestamp: Long,
    val operator: String
)
