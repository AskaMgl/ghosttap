package com.aska.ghosttap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * JSON 工具类
 * 使用 Gson 进行序列化和反序列化
 */
public class JsonUtils {
    
    private static final Gson gson = new GsonBuilder()
        .serializeNulls()
        .create();
    
    /**
     * 将对象转换为 JSON 字符串
     */
    public static String toJson(Object obj) {
        return gson.toJson(obj);
    }
    
    /**
     * 将 JSON 字符串转换为对象
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        return gson.fromJson(json, clazz);
    }
}
