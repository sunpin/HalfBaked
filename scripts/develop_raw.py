#!/usr/bin/env python3
"""
NOAICAM Standalone RAW Developer Script
AIノイズリダクション・水彩画化処理を排除した、スタンドアロンPC用RAW(DNG)自動現像スクリプト

使用例:
    python develop_raw.py input.dng -o output.jpg --exposure 0.5 --temperature 5500 --contrast 1.1
    python develop_raw.py ./dng_folder/ -o ./output_folder/ --batch
"""

import os
import sys
import argparse
import numpy as np

try:
    import rawpy
    from PIL import Image, ImageEnhance
except ImportError:
    print("[!] 必要なライブラリが見つかりません。以下を実行してインストールしてください:")
    print("    pip install rawpy pillow numpy")
    sys.exit(1)


def develop_dng(
    dng_path: str,
    output_path: str,
    exposure_ev: float = 0.0,
    temperature_k: float = 5500.0,
    contrast: float = 1.0,
    black_level: float = 0.0,
    white_level: float = 0.0,
    saturation: float = 1.0,
    sharpness: float = 1.0,
    jpeg_quality: int = 96
) -> bool:
    """
    DNG (RAW) ファイルを非AI・素のセンサーディテール重視で現像しJPEG出力します。
    """
    if not os.path.exists(dng_path):
        print(f"[ERROR] 入力ファイルが存在しません: {dng_path}")
        return False

    print(f"[*] 現像開始: {os.path.basename(dng_path)}")

    try:
        with rawpy.imread(dng_path) as raw:
            # AIノイズ低減 (fbdd/chromatic) をすべてOFFにし、純粋なデモザイク現像を実行
            rgb = raw.postprocess(
                use_camera_wb=True,             # カメラWB使用
                half_size=False,                # フル解像度
                no_auto_bright=True,            # 勝手な自動増感を無効化
                output_bps=16,                  # 16-bit 階調処理
                user_black=None,
                user_sat=None,
                demosaicing_algorithm=rawpy.DemosaicAlgorithm.AHD
            )

        # 16-bit float 変換
        img_float = rgb.astype(np.float32) / 65535.0

        # 1. 露出補正 (EV)
        if exposure_ev != 0.0:
            exp_mult = 2.0 ** exposure_ev
            img_float = img_float * exp_mult

        # 2. 色温度 (WB) 補正
        if temperature_k != 5500.0:
            temp_factor = (temperature_k - 5500.0) / 4500.0
            red_gain = 1.0 + temp_factor * 0.3
            blue_gain = 1.0 - temp_factor * 0.3
            img_float[:, :, 0] *= red_gain
            img_float[:, :, 2] *= blue_gain

        # 3. 黒レベル / 白レベル調整
        if black_level != 0.0 or white_level != 0.0:
            bl = black_level * 0.05
            wl = 1.0 + white_level * 0.2
            img_float = (img_float - bl) / (wl - bl)

        # クリッピング (0.0 〜 1.0)
        img_float = np.clip(img_float, 0.0, 1.0)

        # 8-bit PIL Image へ変換
        img_8bit = (img_float * 255.0).astype(np.uint8)
        pil_img = Image.fromarray(img_8bit, mode='RGB')

        # 4. コントラスト
        if contrast != 1.0:
            enhancer = ImageEnhance.Contrast(pil_img)
            pil_img = enhancer.enhance(contrast)

        # 5. 彩度
        if saturation != 1.0:
            enhancer = ImageEnhance.Color(pil_img)
            pil_img = enhancer.enhance(saturation)

        # 6. シャープネス
        if sharpness != 1.0:
            enhancer = ImageEnhance.Sharpness(pil_img)
            pil_img = enhancer.enhance(sharpness)

        # 出力ディレクトリ作成 & 保存
        out_dir = os.path.dirname(output_path)
        if out_dir and not os.path.exists(out_dir):
            os.makedirs(out_dir, exist_ok=True)

        pil_img.save(output_path, 'JPEG', quality=jpeg_quality)
        print(f"[✓] 現像完了保存: {output_path} (解像度: {pil_img.width}x{pil_img.height})")
        return True

    except Exception as e:
        print(f"[!] 現像処理エラー: {e}")
        return False


def main():
    parser = argparse.ArgumentParser(description="NOAICAM RAW (DNG) Standalone Developer")
    parser.add_argument("input", help="入力DNGファイルまたはフォルダのパス")
    parser.add_argument("-o", "--output", help="出力ファイルまたはフォルダのパス", default=None)
    parser.add_argument("--exposure", type=float, default=0.0, help="露出補正 (EV) [-3.0 〜 +3.0]")
    parser.add_argument("--temperature", type=float, default=5500.0, help="色温度 (K) [2000 〜 10000]")
    parser.add_argument("--contrast", type=float, default=1.0, help="コントラスト [0.5 〜 2.0]")
    parser.add_argument("--black-level", type=float, default=0.0, help="黒レベル [-1.0 〜 1.0]")
    parser.add_argument("--white-level", type=float, default=0.0, help="白レベル [-1.0 〜 1.0]")
    parser.add_argument("--saturation", type=float, default=1.0, help="彩度 [0.0 〜 2.0]")
    parser.add_argument("--sharpness", type=float, default=1.0, help="シャープネス [0.0 〜 2.0]")
    parser.add_argument("--batch", action="store_true", help="フォルダ内のDNGを一括バッチ処理")

    args = parser.parse_args()

    if args.batch or os.path.isdir(args.input):
        in_dir = args.input
        out_dir = args.output if args.output else os.path.join(in_dir, "developed_jpg")

        if not os.path.exists(in_dir):
            print(f"[!] フォルダが見つかりません: {in_dir}")
            sys.exit(1)

        dng_files = [f for f in os.listdir(in_dir) if f.lower().endswith(('.dng', '.raw'))]
        print(f"[*] バッチ現像対象: {len(dng_files)} 件のファイル")

        success_count = 0
        for f in dng_files:
            in_file = os.path.join(in_dir, f)
            out_file = os.path.join(out_dir, os.path.splitext(f)[0] + ".jpg")
            if develop_dng(
                in_file, out_file,
                exposure_ev=args.exposure,
                temperature_k=args.temperature,
                contrast=args.contrast,
                black_level=args.black_level,
                white_level=args.white_level,
                saturation=args.saturation,
                sharpness=args.sharpness
            ):
                success_count += 1

        print(f"[*] バッチ現像完了: {success_count} / {len(dng_files)} 件成功")

    else:
        in_file = args.input
        out_file = args.output if args.output else os.path.splitext(in_file)[0] + ".jpg"
        develop_dng(
            in_file, out_file,
            exposure_ev=args.exposure,
            temperature_k=args.temperature,
            contrast=args.contrast,
            black_level=args.black_level,
            white_level=args.white_level,
            saturation=args.saturation,
            sharpness=args.sharpness
        )


if __name__ == "__main__":
    main()
