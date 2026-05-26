# -*- coding: utf-8 -*-
"""
Deepfake Detection Audio Pipeline
專為 deepfake detection 優化：保留高頻細節，移除環境低頻噪音
"""

import os
import glob
import numpy as np
import librosa
from scipy import signal

# =============================
# 參數設定
# =============================

SR = 16000
N_MELS = 128
FMAX = 8000
SEGMENT_SECONDS = 1          # 每段固定3秒
HOP_SECONDS = 1              # 不重疊切段（可改成1.5做overlap）
HOP_LENGTH = 1024             # mel-spectrogram hop_length（固定時間維度）
PRE_EMPHASIS = 0.97          # 強化高頻（保留偽造痕跡）
POOL_SIZE = 4                # 池化大小（降維用）
HIGHPASS_CUTOFF = 100        # 高通濾波截止頻率（Hz）


# =============================
# 前處理函數
# =============================

def highpass_filter(signal_data, sr=SR, cutoff=HIGHPASS_CUTOFF):
    """高通濾波：移除低頻環境噪音，保留所有高頻細節"""
    sos = signal.butter(5, cutoff, 'high', fs=sr, output='sos')
    return signal.sosfilt(sos, signal_data)


def pre_emphasis(signal_data, alpha=0.97):
    """Pre-emphasis：強化高頻成分（一階高通濾波）"""
    return np.append(signal_data[0], signal_data[1:] - alpha * signal_data[:-1])


def extract_logmel_feature(y, sr=SR):
    """
    提取 Log-Mel Spectrogram + Delta 特徵
    針對 deepfake detection 優化
    """

    # 1️⃣ 高通濾波（只移除低頻環境噪音）
    y = highpass_filter(y, sr, HIGHPASS_CUTOFF)

    # 2️⃣ pre-emphasis（強化高頻）
    y = pre_emphasis(y, PRE_EMPHASIS)

    # 3️⃣ Mel spectrogram
    mel = librosa.feature.melspectrogram(
        y=y,
        sr=sr,
        n_mels=N_MELS,
        fmax=FMAX,
        hop_length=HOP_LENGTH
    )

    # 4️⃣ log
    log_mel = librosa.power_to_db(mel, ref=np.max)

    # 5️⃣ 加 delta 特徵
    delta = librosa.feature.delta(log_mel)
    delta2 = librosa.feature.delta(log_mel, order=2)

    feature = np.concatenate([log_mel, delta, delta2], axis=0)

    # 6️⃣ CMVN（對整張特徵做）
    feature = (feature - np.mean(feature)) / (np.std(feature) + 1e-8)

    # 7️⃣ 均值池化降維（分類用，避免過擬合）
    # 原始: (384, ~93) → 池化後: (384, ~23)
    feature_pooled = []
    for i in range(feature.shape[0]):
        pooled = np.mean(
            feature[i, :feature.shape[1] // POOL_SIZE * POOL_SIZE].reshape(-1, POOL_SIZE),
            axis=1
        )
        feature_pooled.append(pooled)
    
    feature = np.array(feature_pooled)

    return feature


def split_into_segments(y, sr=SR):
    """將音頻分割成固定長度的片段"""
    segment_length = int(SEGMENT_SECONDS * sr)
    hop_length = int(HOP_SECONDS * sr)

    segments = []

    for start in range(0, len(y) - segment_length + 1, hop_length):
        segment = y[start:start + segment_length]
        segments.append(segment)

    return segments


# =============================
# 主流程
# =============================

def process_dataset(audio_folder, output_folder):
    """
    處理音檔資料集
    輸入：音頻資料夾
    輸出：特徵 .npy 檔案
    """

    os.makedirs(output_folder, exist_ok=True)

    wav_files = sorted(glob.glob(os.path.join(audio_folder, "*.wav")))

    if not wav_files:
        print(f"在 {audio_folder} 中找不到 .wav 檔案")
        return

    print(f"找到 {len(wav_files)} 個音檔\n")
    for wav_file in wav_files:
        print(f"  - {os.path.basename(wav_file)}")
    print()

    all_features = []

    for audio_path in wav_files:

        print(f"處理中: {os.path.basename(audio_path)}")

        try:
            y, sr = librosa.load(audio_path, sr=SR)

            # 切段
            segments = split_into_segments(y, sr)

            for seg in segments:

                feature = extract_logmel_feature(seg, sr)

                # 攤平成1D給ANN
                feature = feature.flatten()

                all_features.append(feature)

        except Exception as e:
            print(f"處理失敗: {e}")
            continue

    all_features = np.array(all_features)

    save_path = os.path.join(output_folder, "features.npy")
    np.save(save_path, all_features)

    print(f"\n✅ 完成")
    print(f"特徵儲存於: {save_path}")
    print(f"資料形狀: {all_features.shape} (樣本數, 特徵維度)")
    print(f"建議 ANN 輸入層: Dense({all_features.shape[1]}, activation='relu')")


# =============================
# main
# =============================

if __name__ == "__main__":

    audio_folder = r"E:\專\sound"
    output_folder = r"E:\專\feature"

    process_dataset(audio_folder, output_folder)

