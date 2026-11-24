import os
import glob
import json
import time
import subprocess
import numpy as np
import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
COLLECT_DIR = os.path.join(SCRIPT_DIR, "CollectData")
OUTPUT_DIR = os.path.join(SCRIPT_DIR, "ML")

SEQUENCE_LENGTH = 16
INPUT_SIZE = 20
HIDDEN_SIZE = 48
OUTPUT_SIZE = 2

NORM_DELTA_YAW = 180.0
NORM_DELTA_PITCH = 90.0
NORM_MOVE = 45.0
NORM_VEL = 45.0
NORM_SPEED = 10.0

EPOCHS = 200
BATCH_SIZE = 256
LEARNING_RATE = 1e-3
PITCH_LOSS_WEIGHT = 2.0
YAW_LOSS_WEIGHT = 1.5


def build_base_features(data):
    return np.column_stack([
        data[:, 0] / NORM_DELTA_YAW,
        data[:, 1] / NORM_DELTA_PITCH,
        data[:, 2],
        data[:, 3],
        data[:, 4],
        data[:, 5],
        data[:, 6],
        data[:, 7],
        data[:, 8],
        data[:, 9] / NORM_VEL,
        data[:, 10] / NORM_VEL,
        data[:, 11],
        data[:, 12] / NORM_SPEED,
        data[:, 13],
        data[:, 14] / NORM_DELTA_YAW,
        data[:, 15] / NORM_DELTA_PITCH,
        data[:, 16] / NORM_SPEED,
        data[:, 17],
        data[:, 18] / NORM_SPEED,
        data[:, 19] / NORM_SPEED,
    ]).astype(np.float32)


def split_segments(data):
    if len(data) <= 1:
        return [data]
    resets = np.where(np.diff(data[:, 4]) < 0)[0] + 1
    return np.split(data, resets)


class AimDataset(Dataset):
    def __init__(self, csv_files):
        xs = []
        ys = []
        for f in csv_files:
            data = np.loadtxt(f, delimiter=",", skiprows=1, dtype=np.float32)
            if data.ndim == 1:
                data = data.reshape(1, -1)
            if data.shape[1] < 22:
                continue
            for segment in split_segments(data):
                if len(segment) < SEQUENCE_LENGTH:
                    continue
                features = build_base_features(segment)
                targets = segment[:, -2:] / NORM_MOVE
                for i in range(SEQUENCE_LENGTH - 1, len(segment)):
                    xs.append(features[i - SEQUENCE_LENGTH + 1:i + 1])
                    ys.append(targets[i])
        if xs:
            self.X = np.stack(xs).astype(np.float32)
            self.Y = np.stack(ys).astype(np.float32)
        else:
            self.X = np.zeros((0, SEQUENCE_LENGTH, INPUT_SIZE), dtype=np.float32)
            self.Y = np.zeros((0, OUTPUT_SIZE), dtype=np.float32)

    def __len__(self):
        return len(self.X)

    def __getitem__(self, idx):
        return torch.from_numpy(self.X[idx]), torch.from_numpy(self.Y[idx])


class AimGRU(nn.Module):
    def __init__(self):
        super().__init__()
        self.gru = nn.GRU(INPUT_SIZE, HIDDEN_SIZE, batch_first=True)
        self.fc = nn.Linear(HIDDEN_SIZE, OUTPUT_SIZE)

    def forward(self, x):
        out, _ = self.gru(x)
        return self.fc(out[:, -1, :])


def export_model(model, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    gru = model.gru
    fc = model.fc
    with open(path, "w") as f:
        json.dump({
            "type": "gru",
            "说明": {
                "type": "模型类型。这里固定为 gru，Java 运行时只加载 GRU 模型。",
                "input_size": "每个 tick 的输入特征数量，目前是 20。",
                "sequence_length": "训练时使用的连续 tick 数，目前是 16。",
                "hidden_size": "GRU 隐藏状态大小，越大表达力越强但推理越重。",
                "gru.weight_ih": "输入到 GRU 三个门的权重，顺序是 reset / update / new。",
                "gru.weight_hh": "上一 hidden state 到 GRU 三个门的权重，顺序是 reset / update / new。",
                "gru.bias_ih": "输入侧偏置，对应 reset / update / new。",
                "gru.bias_hh": "隐藏状态侧偏置，对应 reset / update / new。",
                "output.weights": "hidden state 到输出 moveYaw / movePitch 的线性层权重。",
                "output.biases": "hidden state 到输出 moveYaw / movePitch 的线性层偏置。"
            },
            "input_size": INPUT_SIZE,
            "sequence_length": SEQUENCE_LENGTH,
            "hidden_size": HIDDEN_SIZE,
            "output_size": OUTPUT_SIZE,
            "gru": {
                "weight_ih": gru.weight_ih_l0.detach().cpu().numpy().tolist(),
                "weight_hh": gru.weight_hh_l0.detach().cpu().numpy().tolist(),
                "bias_ih": gru.bias_ih_l0.detach().cpu().numpy().tolist(),
                "bias_hh": gru.bias_hh_l0.detach().cpu().numpy().tolist(),
            },
            "output": {
                "weights": fc.weight.detach().cpu().numpy().tolist(),
                "biases": fc.bias.detach().cpu().numpy().tolist(),
            },
        }, f)
    print(f"GRU model exported to {path}", flush=True)


def weighted_mse(pred, target):
    yaw_err = (pred[:, 0] - target[:, 0]) ** 2
    pitch_err = (pred[:, 1] - target[:, 1]) ** 2
    return (yaw_err * YAW_LOSS_WEIGHT + pitch_err * PITCH_LOSS_WEIGHT).mean()


def print_offline_metrics(model, dataset):
    model.eval()
    with torch.no_grad():
        pred = model(torch.from_numpy(dataset.X)).cpu().numpy()
    target = dataset.Y

    pred_deg = pred * NORM_MOVE
    target_deg = target * NORM_MOVE
    yaw_rmse = np.sqrt(np.mean((pred_deg[:, 0] - target_deg[:, 0]) ** 2))
    pitch_rmse = np.sqrt(np.mean((pred_deg[:, 1] - target_deg[:, 1]) ** 2))

    last_features = dataset.X[:, -1, :]
    delta_yaw = last_features[:, 0] * NORM_DELTA_YAW
    delta_pitch = last_features[:, 1] * NORM_DELTA_PITCH
    desired_yaw = -np.sign(delta_yaw)
    desired_pitch = -np.sign(delta_pitch)
    pred_yaw_sign = np.sign(pred_deg[:, 0])
    pred_pitch_sign = np.sign(pred_deg[:, 1])

    valid_yaw = desired_yaw != 0
    valid_pitch = desired_pitch != 0
    away_yaw = valid_yaw & (pred_yaw_sign != 0) & (pred_yaw_sign != desired_yaw)
    away_pitch = valid_pitch & (pred_pitch_sign != 0) & (pred_pitch_sign != desired_pitch)
    direction_total = max(1, int(valid_yaw.sum() + valid_pitch.sum()))
    direction_hit_rate = 1.0 - (away_yaw.sum() + away_pitch.sum()) / direction_total

    correct_yaw = valid_yaw & (pred_yaw_sign == desired_yaw)
    correct_pitch = valid_pitch & (pred_pitch_sign == desired_pitch)
    overshoot_yaw = correct_yaw & (np.abs(pred_deg[:, 0]) > np.maximum(0.5, np.abs(delta_yaw) * 0.9))
    overshoot_pitch = correct_pitch & (np.abs(pred_deg[:, 1]) > np.maximum(0.5, np.abs(delta_pitch) * 0.9))
    overshoot_rate = (overshoot_yaw.sum() + overshoot_pitch.sum()) / direction_total

    angular_velocity = np.sqrt(pred_deg[:, 0] ** 2 + pred_deg[:, 1] ** 2)
    avg_angular_velocity = np.mean(angular_velocity)
    if len(pred_deg) > 1:
        diff = np.diff(pred_deg, axis=0)
        jitter = np.mean(np.sqrt(diff[:, 0] ** 2 + diff[:, 1] ** 2))
    else:
        jitter = 0.0

    print("Offline train-set metrics:", flush=True)
    print(f"  yaw RMSE: {yaw_rmse:.2f} deg | pitch RMSE: {pitch_rmse:.2f} deg", flush=True)
    print(f"  direction hit rate: {direction_hit_rate * 100.0:.2f}%", flush=True)
    print(f"  avg angular velocity: {avg_angular_velocity:.2f} deg/tick", flush=True)
    print(f"  jitter: {jitter:.2f} deg/tick^2", flush=True)
    print(f"  overshoot rate: {overshoot_rate * 100.0:.2f}%", flush=True)
    model.train()


def main():
    csv_files = sorted(glob.glob(os.path.join(COLLECT_DIR, "*.csv")))
    if not csv_files:
        print(f"No CSV files found in {COLLECT_DIR}", flush=True)
        return

    print(f"Found {len(csv_files)} CSV files", flush=True)
    dataset = AimDataset(csv_files)
    print(f"Total sequences: {len(dataset)}", flush=True)
    if len(dataset) == 0:
        print("No valid sequences", flush=True)
        return

    dataloader = DataLoader(dataset, batch_size=BATCH_SIZE, shuffle=True, drop_last=False)

    model = AimGRU()
    optimizer = torch.optim.Adam(model.parameters(), lr=LEARNING_RATE)

    for epoch in range(EPOCHS):
        epoch_start = time.time()
        total_loss = 0.0
        total_yaw_loss = 0.0
        total_pitch_loss = 0.0
        count = 0
        for batch_x, batch_y in dataloader:
            optimizer.zero_grad()
            pred = model(batch_x)
            loss = weighted_mse(pred, batch_y)
            loss.backward()
            optimizer.step()
            total_loss += loss.item() * len(batch_x)
            with torch.no_grad():
                total_yaw_loss += ((pred[:, 0] - batch_y[:, 0]) ** 2).sum().item()
                total_pitch_loss += ((pred[:, 1] - batch_y[:, 1]) ** 2).sum().item()
            count += len(batch_x)
        avg_loss = total_loss / max(1, count)
        avg_yaw = (total_yaw_loss / max(1, count)) ** 0.5 * NORM_MOVE
        avg_pitch = (total_pitch_loss / max(1, count)) ** 0.5 * NORM_MOVE
        epoch_time = time.time() - epoch_start
        print(f"Epoch {epoch+1}/{EPOCHS} - Loss: {avg_loss:.6f} | yaw RMSE: {avg_yaw:.2f} deg | pitch RMSE: {avg_pitch:.2f} deg | {epoch_time:.1f}s", flush=True)

    timestamp = time.strftime("%Y-%m-%d_%H.%M", time.localtime())
    output_file = os.path.join(OUTPUT_DIR, f"Model_{timestamp}.json")
    print_offline_metrics(model, dataset)
    export_model(model, output_file)


def wait_before_exit():
    # set by the client's .ml train (ZAK0_NO_PAUSE kept for scripts exported by Zak0)
    if os.environ.get("MYAU_NO_PAUSE") == "1" or os.environ.get("ZAK0_NO_PAUSE") == "1":
        return
    if os.name == "nt":
        subprocess.call("pause", shell=True)
    else:
        input("Press Enter to exit...")


if __name__ == "__main__":
    try:
        main()
    finally:
        wait_before_exit()
