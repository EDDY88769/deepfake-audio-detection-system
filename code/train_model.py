import os
import glob
import numpy as np
import librosa
from tensorflow.keras.optimizers import Adam
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import Dense, Dropout, BatchNormalization
from tensorflow.keras.callbacks import EarlyStopping, ModelCheckpoint

# 引用 pipeline 的東西
from pipeline_deepfake import extract_logmel_feature, split_into_segments, SR, SEGMENT_SECONDS

# 輸入所有音檔的特徵矩陣(X)以及對應的標籤(Y)fake : 0 or real : 1
def load_and_extract_features(base_folder, label_map={"fake": 0, "real": 1}):
    X, y = [], []
    segment_length = int(SEGMENT_SECONDS * SR)


    for label_name, label_value in label_map.items():
        folder_path = os.path.join(base_folder, label_name)
        if not os.path.exists(folder_path):
            print(f"找不到資料夾: {folder_path}") 
            continue

        # 讀取資料夾內的 .wav 檔案以及 .mp3 檔案
        audio_files = []
        for ext in ("*.wav", "*.mp3", "*.WAV", "*.MP3"):
            audio_files.extend(glob.glob(os.path.join(folder_path, ext)))

        print(f"讀取 {label_name} 資料夾，共 {len(audio_files)} 個檔案...")
        
        for audio_file in audio_files: 
            try:
                audio, sr = librosa.load(audio_file, sr=SR) # 讀取音檔
                segments = split_into_segments(audio, sr)   # 切成 2 秒一段
                
                for seg in segments:
                    if len(seg) < segment_length:           # 略過長度不足的片段
                        continue
                    feature = extract_logmel_feature(seg, sr) # 提取特徵
                    X.append(feature.flatten())               # 壓平並放入 X 簡單來說，就是把2維的矩陣轉變成1維的
                    y.append(label_value)                     # 放入 Y
            except Exception as e:
                print(f"處理 {audio_file} 失敗: {e}")

    return np.array(X), np.array(y)

if __name__ == "__main__":
    BASE_DIR = r"E:\專"
    TRAIN_DIR = os.path.join(BASE_DIR, "sound", "training")
    VAL_DIR = os.path.join(BASE_DIR, "sound", "testing")
    MODEL_SAVE_PATH = os.path.join(BASE_DIR, "deepfake_ann.keras")

    X_train, y_train = load_and_extract_features(TRAIN_DIR)
    
    X_val, y_val = load_and_extract_features(VAL_DIR)
    
    if len(X_train) == 0 or len(X_val) == 0:
        print("特徵提取失敗，請檢查資料夾路徑。")
        exit()

    print(f"\n資料準備完成！訓練集: {X_train.shape}, 驗證集: {X_val.shape}")
    
    # 建立模型
    input_dim = X_train.shape[1]    # 取一段聲音特徵的長度
    model = Sequential([
        # Dense 這邊的 1024 代表這層有 1024 個神經元
        # activation='relu' 這個是讓神經網路學習非線性的問題
        # BatchNormalization() 這是讓他做正規劃，讓模型更穩定
        # Dropout(0.5) 這是在訓練時把隨機 50% 的神經元屏蔽，以此來防止 overfitting
        # Dense(1, activation='sigmoid') 這是把數字壓到 0 ~ 1 之間，這就是預測的機率
        Dense(1024, activation='relu', input_shape=(input_dim,)),
        BatchNormalization(),
        Dropout(0.5),
        Dense(512, activation='relu'),
        BatchNormalization(),
        Dropout(0.5),
        Dense(256, activation='relu'),
        BatchNormalization(),
        Dropout(0.5),
        Dense(128, activation='relu'),
        BatchNormalization(),
        Dropout(0.5),
        Dense(64, activation='relu'),
        BatchNormalization(),
        Dropout(0.5),
        Dense(1, activation='sigmoid')
    ])

    # optimizer=Adam 這是一個優化器
    # learning_rate=0.0001 這是他的學習率太低會跑很久，太高會沒啥用
    # metrics=['accuracy'] 這是來看準確率的
    model.compile(optimizer=Adam(learning_rate=0.00001), loss='binary_crossentropy', metrics=['accuracy'])

    # epochs=50 跑 50 次
    model.fit(
        X_train, y_train,
        validation_data=(X_val, y_val),
        epochs=300,
        batch_size=32,
    )
    
    model.save(MODEL_SAVE_PATH)
    print(f"\n訓練完成！模型已儲存至: {MODEL_SAVE_PATH}")