package com.qingjiexi.app

/**
 * Rust 解析核心的 JNI 桥接。
 *
 * 对应 Rust 侧 `src/jni.rs` 中导出的
 * `Java_com_media_parser_NativeLib_parseText`。
 */
object NativeLib {
    init {
        System.loadLibrary("mediaparse")
    }

    /**
     * 解析粘贴文本。
     * 返回 JSON：`{"ok":true,"data":{...}}` 或 `{"ok":false,"error":"..."}`
     */
    external fun parseText(input: String): String

    /**
     * 请求取消正在进行的解析（停止按钮）。
     * 注意：每次新的 parseText 会自动复位取消状态，停止后可直接重新解析。
     */
    external fun stopParse()
}