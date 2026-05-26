import os
import glob
import numpy as np
import librosa
from tensorflow.keras.models import load_model



# 引入特徵提取 pipeline
from pipeline_deepfake import extract_logmel_feature, split_into_segments, SR, SEGMENT_SECONDS

def predict_folder(test_folder, model_path):
    """
    掃描資料夾內的所有音檔並進行預測
    """
    if not os.path.exists(model_path):
        print(f"找不到模型: {model_path}，請先執行 train_model.py！")
        return
        
    if not os.path.exists(test_folder):
        print(f"找不到測試資料夾: {test_folder}")
        return

    extensions = ("*.wav", "*.mp3", "*.mov")
    audio_files = []
    for ext in extensions:
        audio_files.extend(glob.glob(os.path.join(test_folder, ext)))
    audio_files.sort()

    print(f"載入模型中: {model_path}...")
    model = load_model(model_path)
    segment_length = int(SEGMENT_SECONDS * SR)

    print("\n" + "="*40)
    print("開始進行 Deepfake 語音檢測")
    print("="*40)

    for file_path in audio_files:
        filename = os.path.basename(file_path)
        try:
            audio, sr = librosa.load(file_path, sr=SR)
            segments = split_into_segments(audio, sr)
            
            predictions = []
            for seg in segments:
                if len(seg) < segment_length:
                    continue
                
                feature = extract_logmel_feature(seg, sr)
                pred = model.predict(np.expand_dims(feature.flatten(), axis=0), verbose=0)
                predictions.append(pred[0][0])
            
            if not predictions:
                print(f"[{filename}]音檔太短，無法分析。")
                continue
                
            # 計算平均機率
            avg_score = np.mean(predictions)
            result = "Real" if avg_score >= 0.5 else "Fake"
            
            # 輸出結果
            path = 'output.txt'
            f = open(path, 'w')
            print(f"[{filename}] 預測結果:{result} | 真實機率:{avg_score:.4f}", file=f)
            
        except Exception as e:
            print(f"[{filename}] 分析失敗:{e}")


if __name__ == "__main__":
    # 設定路徑 (根據你的環境修改)
    BASE_DIR = r"E:\專"
    MODEL_PATH = os.path.join(BASE_DIR, "deepfake_ann.keras")
    
    # 這是你新建立的獨立 Test 資料夾
    TEST_FOLDER = os.path.join(BASE_DIR, "Test")
    
    predict_folder(TEST_FOLDER, MODEL_PATH)