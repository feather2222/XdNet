package com.xd.network

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Default JSON behavior suitable for APIs that may add response fields over time. */
object XDJson {
    @JvmField
    val default: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = false
    }
}

/** Serializes [value] into an UTF-8 JSON request body. */
inline fun <reified T> RequestBody.Companion.json(
    value: T,
    json: Json = XDJson.default
): RequestBody = try {
    RequestBody.json(json.encodeToString(value))
} catch (error: SerializationException) {
    throw NetworkException.Serialization(error)
}

/** Deserializes this response body, irrespective of its HTTP status code. */
inline fun <reified T> NetworkResponse.bodyJson(
    json: Json = XDJson.default
): T = try {
    json.decodeFromString(bodyString())
} catch (error: SerializationException) {
    throw NetworkException.Serialization(error)
}

/** Awaits a successful HTTP response and deserializes its JSON body. */
suspend inline fun <reified T> NetworkCall.awaitJson(
    json: Json = XDJson.default
): T {
    val response = await()
    if (!response.isSuccessful) throw NetworkException.HttpStatus(response)
    return response.bodyJson(json)
}

/** Executes a GET and returns a deserialized successful JSON response. */
suspend inline fun <reified T> XDNetworkClient.getJson(
    path: String,
    query: Map<String, String> = emptyMap(),
    json: Json = XDJson.default
): T = get(path, query).awaitJson(json)

/** Serializes [value], executes a POST and deserializes a successful JSON response. */
suspend inline fun <reified Request, reified Response> XDNetworkClient.postJson(
    path: String,
    value: Request,
    json: Json = XDJson.default
): Response = post(path, RequestBody.json(value, json)).awaitJson(json)
