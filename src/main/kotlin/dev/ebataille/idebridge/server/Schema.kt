package dev.ebataille.idebridge.server

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** Builds the JSON Schema for tool inputs, with no external dependency. */
object Schema {

    fun string(description: String): JsonObject {
        return leaf("string", description)
    }

    fun integer(description: String): JsonObject {
        return leaf("integer", description)
    }

    fun boolean(description: String): JsonObject {
        return leaf("boolean", description)
    }

    fun enumOf(description: String, vararg values: String): JsonObject {
        val node = leaf("string", description)
        val allowed = JsonArray()
        values.forEach { allowed.add(it) }
        node.add("enum", allowed)
        return node
    }

    fun arrayOf(description: String, items: JsonObject): JsonObject {
        val node = leaf("array", description)
        node.add("items", items)
        return node
    }

    fun obj(vararg properties: Pair<String, JsonObject>, required: List<String> = emptyList()): JsonObject {
        val node = JsonObject()
        node.addProperty("type", "object")
        val props = JsonObject()
        properties.forEach { props.add(it.first, it.second) }
        node.add("properties", props)
        val requiredArray = JsonArray()
        required.forEach { requiredArray.add(it) }
        node.add("required", requiredArray)
        node.add("additionalProperties", JsonPrimitive(false))
        return node
    }

    private fun leaf(type: String, description: String): JsonObject {
        val node = JsonObject()
        node.addProperty("type", type)
        node.addProperty("description", description)
        return node
    }
}

/** Forgiving argument access: models happily omit optional parameters. */
object Args {

    fun string(args: JsonObject, key: String): String? {
        val value = args.get(key) ?: return null
        if (value.isJsonNull) {
            return null
        }
        return value.asString
    }

    fun requiredString(args: JsonObject, key: String): String {
        return string(args, key) ?: throw IllegalArgumentException("Missing required parameter: $key")
    }

    fun int(args: JsonObject, key: String, fallback: Int): Int {
        val value = args.get(key) ?: return fallback
        if (value.isJsonNull) {
            return fallback
        }
        return value.asInt
    }

    fun boolean(args: JsonObject, key: String, fallback: Boolean): Boolean {
        val value = args.get(key) ?: return fallback
        if (value.isJsonNull) {
            return fallback
        }
        return value.asBoolean
    }

    /** Batch tools take a list of records, so that N operations cost one round trip. */
    fun objectList(args: JsonObject, key: String): List<JsonObject> {
        val value = args.get(key) ?: return emptyList()
        if (!value.isJsonArray) {
            return emptyList()
        }
        return value.asJsonArray.mapNotNull { if (it.isJsonObject) it.asJsonObject else null }
    }

    fun stringList(args: JsonObject, key: String): List<String> {
        val value = args.get(key) ?: return emptyList()
        if (!value.isJsonArray) {
            return emptyList()
        }
        return value.asJsonArray.mapNotNull { if (it.isJsonNull) null else it.asString }
    }
}
