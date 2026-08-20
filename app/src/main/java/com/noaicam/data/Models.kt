package com.noaicam.data

enum class ZoomCropMode(val label: String) {
    CROP_UPSCALE("拡大 (ズーム画角切出 + 高画質リサイズ)"),
    CROP_ONLY("トリミング (ズーム画角切り出しのみ)"),
    OFF("OFF (フルフレーム全域保存)")
}

enum class DevelopEffect(val label: String) {
    NONE("なし (標準現像)"),
    GRAVURE("グラビア調 (点描・網点印刷)"),
    SUPER_PORTRAIT("スーパーポートレート (美肌/ソフトグロウ)"),
    OIL_PAINT("油絵調 (オイルペイント)"),
    PEN_SKETCH("ペン画調 (インクスケッチ)"),
    ANIME("アニメ調 (セル画風)"),
    RETRO_FILM("レトロフィルム (トイカメラ)"),
    NOIR("モノクロノワール (高コントラスト白黒)")
}

data class RawImageData(
    val dngFilePath: String,
    val captureTimeMillis: Long = System.currentTimeMillis(),
    val iso: Int? = null,
    val exposureTimeNanos: Long? = null,
    val focalLength: Float? = null,
    val isRawHardwareSupported: Boolean = true
)

data class DevelopParams(
    val isWbAuto: Boolean = true,              // ホワイトバランス AUTOモード
    val isNoiseReductionEnabled: Boolean = false, // ノイズ除去 (NR) ON/OFF
    val exposure: Float = 0f,                  // -3.0f to +3.0f EV
    val temperature: Float = 5500f,            // 2000K to 10000K
    val tint: Float = 0f,                      // -100f to +100f
    val contrast: Float = 1.0f,                // 0.5f to 2.0f
    val blackLevel: Float = 0f,                // -1.0f to +1.0f (黒レベル)
    val whiteLevel: Float = 0f,                // -1.0f to +1.0f (白レベル)
    val saturation: Float = 1.0f,              // 0.0f to 2.0f
    val sharpness: Float = 1.0f,               // 0.0f to 2.0f (シャープネス)
    val cropScale: Float = 1.0f,               // 1.0f to 4.0f (ズーム倍率)
    val cropPanX: Float = 0f,                  // -1.0f to +1.0f (左右クロップ位置)
    val cropPanY: Float = 0f,                  // -1.0f to +1.0f (上下クロップ位置)
    val effect: DevelopEffect = DevelopEffect.NONE, // アートエフェクト
    val zoomCropMode: ZoomCropMode = ZoomCropMode.CROP_UPSCALE // ズーム現像モード
)
