package com.mb.mbplugin.karate.actions

import org.json.JSONArray
import org.json.JSONObject

class CurlToKarateConverter {

    fun convert(curl: String): String {
        val urlRegex = Regex("""(https?://[^\s'"]+)""")
        val normalizedCurl = curl.replace("\\\r?\n".toRegex(), " ")
        val headerRegex = Regex("""--?header\s+['"]([^:'"]+):\s*([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        val methodRegex = Regex("""-X\s+(\w+)""")
        val dataRegex = Regex("""(--data(?:-raw|-binary)?|-d|--data-urlencode)\s+(?:(["'])(.*?)\2|(\S+))""")

        val bodyMatch = dataRegex.find(curl)
        val body = bodyMatch?.groups?.get(3)?.value ?: bodyMatch?.groups?.get(4)?.value

        val url = urlRegex.find(curl)?.groupValues?.get(1) ?: ""
        val method = methodRegex.find(curl)?.groupValues?.get(1)?.lowercase()
            ?: if (curl.contains("-d") || curl.contains("--data")) "post" else "get"
        val headers = headerRegex.findAll(curl).map { it.groupValues[1] to it.groupValues[2] }.toList()

        val indent1 = "  "
        val indent2 = "    "

        val sb = StringBuilder()
        sb.appendLine("Feature: Generated from cURL")
        sb.appendLine()
        sb.appendLine("${indent1}Background:")
        sb.appendLine("${indent2}* url '$url'")

        headers.forEach { (key, value) ->
            sb.appendLine("${indent2}* header $key = '${truncate(value)}'")
        }

        sb.appendLine()
        sb.appendLine("${indent1}Scenario: Imported from curl")

        if (!body.isNullOrBlank()) {
            val isForm = curl.contains("--data-urlencode") ||
                headers.any { it.first.equals("Content-Type", true) && it.second.contains("x-www-form-urlencoded", true) }

            if (isForm) {
                sb.appendLine("${indent2}* def requestBody = {}")
                val formPairs = body.split("&").flatMap {
                    // handle case grant_type=client_credentials or name="Duy"
                    it.split("=").takeIf { it.size == 2 }?.let { listOf(it[0] to it[1]) } ?: emptyList()
                }
                formPairs.forEach { (key, value) ->
                    sb.appendLine("${indent2}* set requestBody.${key.trim()} = '${escapeValue(value.trim())}'")
                }
                sb.appendLine("${indent2}* request requestBody")
            } else {
                val cleanBody = body.trim().trim('"', '\'')
                if (cleanBody.trim().startsWith("{")) {
                    try {
                        val jsonBody = JSONObject(cleanBody)
                        sb.appendLine("${indent2}* def requestBody = {}")
                        val bodyBuilder = StringBuilder()
                        buildKarateJsonAssignments(bodyBuilder, "requestBody", jsonBody)
                        bodyBuilder.lines().forEach { line ->
                            sb.appendLine("$indent2$line")
                        }
                        sb.appendLine("${indent2}* request requestBody")
                    } catch (_: Exception) {
                        sb.appendLine("${indent2}* def requestBody = '''${truncate(cleanBody)}'''")
                        sb.appendLine("${indent2}* request requestBody")
                    }
                } else {
                    sb.appendLine("${indent2}* def requestBody = '''${truncate(cleanBody)}'''")
                    sb.appendLine("${indent2}* request requestBody")
                }
            }
        }

        sb.appendLine("${indent2}* method ${method.lowercase()}")
        sb.appendLine("${indent2}* status 200")
        return sb.toString()
    }

    private fun buildKarateJsonAssignments(sb: StringBuilder, prefix: String, json: JSONObject) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.get(key)
            val path = "$prefix.$key"
            when (value) {
                is JSONObject -> {
                    sb.append("* set $path = {}\n")
                    buildKarateJsonAssignments(sb, path, value)
                }
                is JSONArray -> {
                    sb.append("* set $path = []\n")
                    for (i in 0 until value.length()) {
                        val item = value.get(i)
                        when (item) {
                            is JSONObject -> {
                                sb.append("* set $path[$i] = {}\n")
                                buildKarateJsonAssignments(sb, "$path[$i]", item)
                            }
                            else -> {
                                sb.append("* set $path[$i] = ${formatValue(item.toString())}\n")
                            }
                        }
                    }
                }
                else -> sb.append("* set $path = ${formatValue(value.toString())}\n")
            }
        }
    }

    private fun formatValue(value: String): String {
        val trimmed = truncate(value)
        return when {
            trimmed.equals("true", true) || trimmed.equals("false", true) -> trimmed.lowercase()
            trimmed.matches(Regex("-?\\d+(\\.\\d+)?")) -> trimmed
            else -> "'${escapeValue(trimmed)}'"
        }
    }

    private fun escapeValue(value: String): String = value.replace("'", "\\'")

    private fun truncate(value: String): String =
        if (value.length > 30) value.take(30) + "..." else value
}
