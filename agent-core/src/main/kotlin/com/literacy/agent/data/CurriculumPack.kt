package com.literacy.agent.data

data class CurriculumPack(
    val id: String,
    val name: String,
    val iconResName: String,
    val chars: List<String>,
    val prerequisitePackIds: List<String> = emptyList(),
    val targetCapabilities: List<String> = listOf("recognize", "write"),
    val exampleSentences: Map<String, List<String>> = emptyMap(),
)
