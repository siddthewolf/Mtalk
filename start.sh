#!/bin/bash
# CoinDCX Agent — one-command launcher
# Run this on YOUR computer, then open the IP shown on your phone

set -e

echo ""
echo "=================================================="
echo "  CoinDCX Portfolio Agent — Setup & Launch"
echo "=================================================="

# Check Python
if ! command -v python3 &>/dev/null; then
  echo "ERROR: Python 3 not found. Install from https://python.org"
  exit 1
fi

echo "→ Python: $(python3 --version)"

# Install dependencies
echo "→ Installing dependencies..."
pip3 install -q requests numpy flask python-dotenv

# Copy .env if not exists
if [ ! -f .env ]; then
  cp .env.example .env
  echo "→ Created .env file (add your API keys inside or use the dashboard Setup tab)"
fi

# Get local IP for phone access
if command -v ipconfig &>/dev/null; then
  # macOS
  LOCAL_IP=$(ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || echo "your-computer-ip")
elif command -v hostname &>/dev/null; then
  LOCAL_IP=$(hostname -I 2>/dev/null | awk '{print $1}' || echo "your-computer-ip")
else
  LOCAL_IP="your-computer-ip"
fi

echo ""
echo "=================================================="
echo "  Dashboard ready! Open on your phone:"
echo ""
echo "  http://${LOCAL_IP}:5000"
echo ""
echo "  (Make sure phone & computer are on same WiFi)"
echo "=================================================="
echo ""

python3 dashboard.py
