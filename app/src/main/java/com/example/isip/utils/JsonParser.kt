package com.example.isip.utils

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * JSON解析工具类
 */
object JsonParser {

    private val gson = Gson()

    /**
     * 对象转JSON字符串
     */
    fun <T> toJson(obj: T): String {
        return gson.toJson(obj)
    }

    /**
     * JSON字符串转对象
     */
    fun <T> fromJson(json: String, clazz: Class<T>): T? {
        return try {
            gson.fromJson(json, clazz)
        } catch (e: JsonSyntaxException) {
            null
        }
    }

    /**
     * 美化JSON输出
     */
    fun <T> toJsonPretty(obj: T): String {
        return com.google.gson.GsonBuilder()
            .setPrettyPrinting()
            .create()
            .toJson(obj)
    }
}
