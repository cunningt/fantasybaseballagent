#!/bin/bash
# Batch generate summaries for all podcast transcripts using Ollama

TRANSCRIPT_DIR="/Users/tcunning/src/podcasttranscribe/transcripts"
SUMMARY_DIR="$TRANSCRIPT_DIR/summaries"
MODEL="qwen3.5:4b"

# Create summary directory if needed
mkdir -p "$SUMMARY_DIR"

# Count files
total=$(ls -1 "$TRANSCRIPT_DIR"/*.txt 2>/dev/null | wc -l | tr -d ' ')
current=0
skipped=0

echo "=== Generating Transcript Summaries ==="
echo "Found $total transcripts"
echo ""

for file in "$TRANSCRIPT_DIR"/*.txt; do
    filename=$(basename "$file")
    summary_file="$SUMMARY_DIR/${filename}.summary"

    current=$((current + 1))

    # Skip if summary already exists
    if [ -f "$summary_file" ]; then
        echo "[$current/$total] SKIP (cached): $filename"
        skipped=$((skipped + 1))
        continue
    fi

    echo "[$current/$total] Processing: $filename"

    # Read transcript content (limit to first 50000 chars to avoid overwhelming the model)
    content=$(head -c 50000 "$file")

    # Create the prompt
    prompt="Please summarize this transcript.   
Extract fantasy baseball insights from this podcast transcript. Focus on:
- Player news (injuries, performance, roster moves)
- Prospect updates (call-ups, rankings, breakouts)
- Sleepers and add/drop recommendations
- Pitching updates (rotations, closers, streaming)
- List key points

TRANSCRIPT:
$content

FANTASY INSIGHTS:"

    # Call Ollama and save summary
    echo "$prompt" | ollama run "$MODEL" --nowordwrap 2>/dev/null > "$summary_file"

    if [ -s "$summary_file" ]; then
        echo "    Saved summary ($(wc -c < "$summary_file" | tr -d ' ') bytes)"
    else
        echo "    WARNING: Empty summary generated"
        rm -f "$summary_file"
    fi

    # Small delay to not overwhelm Ollama
    sleep 1
done

echo ""
echo "=== Complete ==="
echo "Processed: $((current - skipped)) new summaries"
echo "Skipped: $skipped (already cached)"
echo "Total: $current"
