package com.routineai.interpret

import android.content.Context

/**
 * API 키 보관.
 *
 * 개인용 사이드로드 기준이라 앱 전용 저장소의 SharedPreferences 를 쓴다.
 * 배포용으로 만들 거라면 EncryptedSharedPreferences 나 Keystore 로 바꿔야 한다.
 */
class Settings(ctx: Context) {
    private val sp = ctx.getSharedPreferences("routine", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "").orEmpty()
        set(v) = sp.edit().putString("api_key", v).apply()

    var lastInterpretation: String
        get() = sp.getString("last_interp", "").orEmpty()
        set(v) = sp.edit().putString("last_interp", v).apply()

    var interpretEnabled: Boolean
        get() = sp.getBoolean("interpret_enabled", false)
        set(v) = sp.edit().putBoolean("interpret_enabled", v).apply()
}
