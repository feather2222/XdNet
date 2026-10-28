package com.xd.network

/** HTTP methods supported by [NetworkRequest]. */
enum class HttpMethod(val permitsRequestBody: Boolean) {
    GET(false),
    HEAD(false),
    POST(true),
    PUT(true),
    PATCH(true),
    DELETE(true)
}
