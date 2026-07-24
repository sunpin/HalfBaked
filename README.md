# HalfBaked (低AIノイズ RAWカメラ)

NOAICAM は、近年のスマートフォンカメラに標準搭載されている**過剰なAI処理（ノイズ低減による塗りつぶし・水彩画化・過度なエッジ強調）を排除**し、イメージセンサー本来の質感と精細度を備えたRAW（DNG）撮影およびリアルタイム現像を行えるAndroidカメラアプリです。

---

## 🌟 主な特徴

- 📷 **メーカーAIポスト処理回避 (純粋RAW撮影)**
  - Android `Camera2 API` の `RAW_SENSOR` ストリームを直接取得し、DNG形式で保存。
  - スマートフォンの過剰な自動修正を受けない、カメラセンサー素のディテールを保持。
    ※機種によりAI処理の回避ができない可能性がある

- 🎛️ **マニュアル露出制御 (Manual AE / AUTO)**

- 🎨 **リアルタイム自動現像 & パラメータカスタマイズ**
  - RAW撮影と同時に自動現像を行い、高画質JPEGをギャラリーへ自動出力。

  - **現像パラメーター調整機能**:
    - 露出補正 (EV) `-3.0EV` 〜 `+3.0EV`
    - 色温度 / ホワイトバランス `2000K` 〜 `10000K`
    - コントラスト / 彩度 / シャープネス
    - 黒レベル (Black Point) / 白レベル (White Point)

- 🖼️ **インアプリ・ギャラリー & 再現像**
  - 撮影したRAW/JPEG写真をアプリ内で閲覧。
  - パラメータを変更してのRAW再現像・別名保存に対応。

- 📐 **撮影補助機能**
  - 構図グリッド表示（3×3）
  - タップ・トゥ・フォーカス（リングインジケーター表示）
  - フラッシュモード切り替え (OFF / FLASH / TORCH)


---

## 📱 動作環境

- **OS**: Android 9.0 (API Level 28) 以上
- **必須機能**: Camera2 API (`RAW_SENSOR` 機能をサポートするカメラモジュール)
- **動作確認済み端末**: Google Pixel 9a

---

## 🛠️ ビルド方法

Android Studio または Gradle コマンドラインからビルド可能です。

### デバッグ版ビルド
```bash
./gradlew assembleDebug
```

### リリース版ビルド (Release APK)
```bash
./gradlew assembleRelease
```
生成されるAPKファイル: `app/build/outputs/apk/release/app-release.apk`

---

## 📄 ライセンス

MIT License
