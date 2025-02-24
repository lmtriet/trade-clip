## Introduction

This script automates the creation of trade review videos by processing OBS screen recordings and TraceCraft trading data. It ingests OBS recordings named in `YYYY-MM-DD HH-mm-ss.mp4` format alongside a `dataExport.json` file containing trade metadata, then generates timestamped video clips centered around each trade's entry/exit points with configurable pre/post buffers. The clips include contextual information like symbol, direction (long/short), profit/loss, setup tags from TraceCraft's labeling system, and performance ratings formatted directly into filenames for easy identification.

The script produces both individual trade clips and a merged compilation video with YouTube-compatible chapter markers, enabling traders to quickly navigate between trades during review sessions. This automated workflow replaces manual video editing by precisely aligning trading journal data with screen recordings, creating a structured highlight reel that captures key decision points and outcomes from the trading session. The output preserves original video quality through FFmpeg stream copying while maintaining platform compatibility across Windows, macOS, and Linux systems.

## Installation Requirements

**1. Install Babashka**
```bash
# Linux/macOS using brew
brew install borkdude/brew/babashka

# Windows (via scoop)
scoop install babashka

# Manual install (all platforms)
curl -s https://raw.githubusercontent.com/babashka/babashka/master/install | bash
```

**2. Install FFmpeg**
```bash
# macOS
brew install ffmpeg

# Ubuntu/Debian
sudo apt-get install ffmpeg

# Windows
scoop install ffmpeg
```

## Configuration Setup
Edit these values in the script's `config` map:
```clojure
(def config
  {:extracting-clips? true
   :pre-buffer 30   ; Seconds before trade entry
   :post-buffer 60  ; Seconds after trade exit
   :ffmpeg-path "ffmpeg" ; Verify matches installed binary name
   :upload-youtube? false ; Enable after setting OAuth credentials
   :client-id ""     ; YouTube API credentials
   :client-secret "" 
   :access-token-file "./access_token"
   :refresh-token-file "./refresh_token"})
```

## Execution Steps

**1. Prepare Input Files**
- Place OBS recordings in format: `YYYY-MM-DD HH-mm-ss.mp4`
- Download `dataExport.json` from TraceCraft and place it in the same folder as the script

**2. Run the Script**
```bash
bb clips.clj "/path/to/your/2025-02-21 13-25-30.mp4"
```

**3. Expected Outputs**
- Individual trade clips with formatted filenames:
  ```2025-02-21_13-25-30_ES_Failure+Break_+200_R5.mp4```
- Merged compilation file: ```20250221_AllTrades.mp4```
- Chapter markers file: ```chapters.txt```

## Post-Processing
The script generates:
1. Trimmed clips for each trade with configurable buffers
2. Merged video of all daily trades
3. YouTube-compatible chapter markers with:
    - Trade timestamps
    - Symbols
    - P/L amounts
    - Setup tags
    - Performance ratings

```text
Example chapters.txt content:
00:00:00 2025-02-21_13-25-30_ES_Failure+Break_+200_R5 - 00:01:30
00:01:30 2025-02-21_14-10-15_NQ_Pullback_+150_R4 - 00:03:00
```

## Troubleshooting
- Ensure FFmpeg is in PATH if seeing `executable not found` errors
- Validate JSON data structure matches script expectations
- Check file naming conventions match `YYYY-MM-DD HH-mm-ss.mp4` format
- Disable YouTube uploads in config until OAuth configured

For advanced use, modify buffer times in config to capture more/less pre-trade context.
