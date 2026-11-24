# Training environment for the AI advisor, kept entirely on E: (C: has no room).
#   powershell -ExecutionPolicy Bypass -File tools\ai\setup_venv.ps1
# Creates tools\ai\.venv with CUDA PyTorch + Unsloth. The global Python (CPU torch, used by .ml train)
# is not touched.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$cache = 'E:\AI\cache'
$env:PIP_CACHE_DIR = "$cache\pip"
$env:HF_HOME = "$cache\hf"
$env:TORCH_HOME = "$cache\torch"
$env:TMP = "$cache\tmp"; $env:TEMP = "$cache\tmp"
New-Item -ItemType Directory -Force $env:PIP_CACHE_DIR, $env:HF_HOME, $env:TORCH_HOME, $env:TMP | Out-Null

$venv = Join-Path $here '.venv'
if (-not (Test-Path "$venv\Scripts\python.exe")) {
    python -m venv $venv
}
$py = "$venv\Scripts\python.exe"
& $py -m pip install --upgrade pip
# CUDA 12.8 wheels run on the RTX 40 series with any current driver
& $py -m pip install torch==2.11.0 --index-url https://download.pytorch.org/whl/cu128
# A plain `pip install unsloth` pulls a newer CPU-only torch from PyPI over the CUDA one (Windows):
# pin torch to the CUDA build and let torchvision come from the same CUDA index.
& $py -m pip install unsloth -c (Join-Path $here 'constraints.txt') --extra-index-url https://download.pytorch.org/whl/cu128
& $py -c "import torch; print('torch', torch.__version__, 'cuda', torch.cuda.is_available(), torch.cuda.get_device_name(0) if torch.cuda.is_available() else '')"
& $py -c "import unsloth; print('unsloth', unsloth.__version__)"
